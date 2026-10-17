package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.user.PlatformUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Verify the production query's access path on a selective two-week window. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "labops.demo-data.enabled=false",
        "labops.reservation-expiry.scan-interval=3600000",
        "labops.payment.outbound.retry-interval=1h",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect"
})
@AutoConfigureMockMvc
@Import(DashboardTrendIndexMysqlTest.CaptureConfiguration.class)
@Timeout(90)
class DashboardTrendIndexMysqlTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withPassword("dashboard_index_test_password");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EquipmentRepository equipment;
    @Autowired PlatformUserRepository users;
    @Autowired ResolvedQueryCapture capture;

    @Test
    void resolvedTrendUsesRangeAccessInsteadOfScanningHistoricalTickets() throws Exception {
        TestAuth.clearCache();
        String admin = bearer(mvc, mapper, "admin", "admin123");
        Long equipmentId = equipment.saveAndFlush(new Equipment(
                "TREND-PLAN", "查询计划测试设备", "综合仪器", "T101")).getId();
        Long reporterId = users.findByUsername("admin").orElseThrow().getId();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(13).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant historical = from.minus(365, ChronoUnit.DAYS);
        Instant recent = today.atStartOfDay(zone).plusHours(1).toInstant();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10_020; i++) {
            Timestamp at = Timestamp.from(i < 10_000 ? historical : recent);
            rows.add(new Object[]{equipmentId, reporterId, at, at, at});
        }
        jdbc.batchUpdate("""
                insert into fault_work_orders
                    (equipment_id, reporter_id, reporter_name, title, description,
                     priority, status, created_at, updated_at, resolved_at)
                values (?, ?, '管理员', '查询计划回归', '历史与近期工单', 'MEDIUM', 'CLOSED', ?, ?, ?)
                """, rows);
        jdbc.execute("analyze table fault_work_orders");

        byte[] response = mvc.perform(get("/api/dashboard/trends")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode counts = mapper.readTree(response).get("workOrdersResolved");
        assertThat(counts.size()).isEqualTo(14);
        assertThat(counts.get(13).asLong()).isEqualTo(20);

        // Explain the SQL Hibernate actually executed, without forcing an index.
        assertThat(capture.sql).as("resolved trend SQL captured from the real endpoint").isNotNull();
        Map<String, Object> plan = jdbc.queryForMap("explain " + capture.sql,
                Timestamp.from(from), Timestamp.from(to));
        assertThat(plan.get("type")).as("selective resolved_at query access path").isEqualTo("range");
        assertThat(plan.get("key")).as("an index chosen by the MySQL optimizer").isNotNull();
        assertThat(((Number) plan.get("rows")).longValue())
                .as("estimated rows must exclude the ten thousand historical tickets").isLessThan(100);
    }

    static class ResolvedQueryCapture implements StatementInspector {
        private String sql;

        @Override
        public String inspect(String statement) {
            if (statement.startsWith("select ") && statement.contains(".resolved_at")
                    && statement.contains(" from fault_work_orders ")) {
                sql = statement;
            }
            return statement;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {
        @Bean
        ResolvedQueryCapture resolvedQueryCapture() {
            return new ResolvedQueryCapture();
        }

        @Bean
        HibernatePropertiesCustomizer captureTrendSql(ResolvedQueryCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }
}
