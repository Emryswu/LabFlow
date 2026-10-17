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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.arthur.labops.reservation.ReservationStatuses;
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
    @Autowired TrendQueryCapture capture;

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
        assertThat(capture.sql("fault_work_orders", "resolved_at"))
                .as("resolved trend SQL captured from the real endpoint").isNotNull();
        Map<String, Object> plan = jdbc.queryForMap("explain " + capture.sql("fault_work_orders", "resolved_at"),
                Timestamp.from(from), Timestamp.from(to));
        assertThat(plan.get("type")).as("selective resolved_at query access path").isEqualTo("range");
        assertThat(plan.get("key")).as("an index chosen by the MySQL optimizer").isNotNull();
        assertThat(((Number) plan.get("rows")).longValue())
                .as("estimated rows must exclude the ten thousand historical tickets").isLessThan(100);

        // Same rows, created at the same instants: the created series must not scan history either.
        assertThat(mapper.readTree(response).get("workOrdersCreated").get(13).asLong()).isEqualTo(20);
        assertRangeAccess("fault_work_orders", "created_at", "idx_work_order_created_at", from, to);
    }

    @Test
    void reservationTrendsUseTheirTimestampIndexes() throws Exception {
        TestAuth.clearCache();
        String admin = bearer(mvc, mapper, "admin", "admin123");
        Long equipmentId = equipment.saveAndFlush(new Equipment(
                "TREND-PLAN-RES", "预约查询计划测试设备", "综合仪器", "T102")).getId();
        Long requesterId = users.findByUsername("admin").orElseThrow().getId();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(13).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant historical = from.minus(365, ChronoUnit.DAYS);
        Instant recent = today.atStartOfDay(zone).plusHours(1).toInstant();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10_020; i++) {
            boolean old = i < 10_000;
            Timestamp at = Timestamp.from(old ? historical : recent);
            Timestamp end = Timestamp.from((old ? historical : recent).plus(1, ChronoUnit.HOURS));
            // History is COMPLETED, recent is APPROVED: both are on the calendar, so the
            // status filter of the scheduled series cannot narrow the scan by itself.
            rows.add(new Object[]{equipmentId, requesterId, at, end, old ? "COMPLETED" : "APPROVED", at, at});
        }
        jdbc.batchUpdate("""
                insert into equipment_reservations
                    (equipment_id, requester_id, requester_name, purpose,
                     start_time, end_time, status, created_at, expires_at)
                values (?, ?, '管理员', '查询计划回归', ?, ?, ?, ?, ?)
                """, rows);
        jdbc.execute("analyze table equipment_reservations");

        byte[] response = mvc.perform(get("/api/dashboard/trends")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode body = mapper.readTree(response);
        assertThat(body.get("reservationsCreated").get(13).asLong()).isEqualTo(20);
        assertThat(body.get("reservationsScheduled").get(13).asLong()).isEqualTo(20);

        assertRangeAccess("equipment_reservations", "created_at", "idx_reservation_created_at", from, to);
        assertRangeAccess("equipment_reservations", "start_time", "idx_reservation_start_time", from, to,
                ReservationStatuses.SCHEDULED.stream().map(Enum::name).toArray());
    }

    /**
     * EXPLAINs the captured endpoint SQL. {@code possible_keys} is checked rather than
     * {@code key}: which of several usable indexes the optimizer picks is a cost call,
     * but whether the expected index is usable at all is what a dropped migration breaks.
     */
    private void assertRangeAccess(String table, String column, String expectedIndex,
                                   Instant from, Instant to, Object... extraParams) {
        String sql = capture.sql(table, column);
        assertThat(sql).as(table + "." + column + " trend SQL captured from the real endpoint").isNotNull();
        List<Object> params = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        params.addAll(List.of(extraParams));
        assertThat(sql.chars().filter(c -> c == '?').count()).as("bind parameters of " + sql).isEqualTo(params.size());
        Map<String, Object> plan = jdbc.queryForMap("explain " + sql, params.toArray());
        assertThat(plan.get("type")).as(table + "." + column + " access path").isEqualTo("range");
        assertThat(String.valueOf(plan.get("possible_keys"))).as(table + "." + column + " usable indexes")
                .contains(expectedIndex);
        assertThat(((Number) plan.get("rows")).longValue())
                .as("estimated rows must exclude the ten thousand historical rows").isLessThan(100);
    }

    /**
     * Keeps the single-column trend queries the endpoint runs, keyed by "table.column".
     * Admins run two on fault_work_orders.created_at (all tickets, then discrepancies
     * only); the one with fewer bind parameters is the unfiltered series.
     */
    static class TrendQueryCapture implements StatementInspector {
        private static final Pattern TREND_QUERY = Pattern.compile("^select \\w+\\.(\\w+) from (\\w+) \\w+ where ");
        private final Map<String, String> byColumn = new ConcurrentHashMap<>();

        @Override
        public String inspect(String statement) {
            Matcher matcher = TREND_QUERY.matcher(statement);
            if (matcher.find()) {
                byColumn.merge(matcher.group(2) + "." + matcher.group(1), statement,
                        (kept, next) -> parameters(next) < parameters(kept) ? next : kept);
            }
            return statement;
        }

        String sql(String table, String column) {
            return byColumn.get(table + "." + column);
        }

        private static long parameters(String sql) {
            return sql.chars().filter(c -> c == '?').count();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {
        @Bean
        TrendQueryCapture trendQueryCapture() {
            return new TrendQueryCapture();
        }

        @Bean
        HibernatePropertiesCustomizer captureTrendSql(TrendQueryCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }
}
