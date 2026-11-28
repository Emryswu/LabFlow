package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.arthur.labops.MysqlCriticalConcurrencyRegressionTest.Gate;
import com.arthur.labops.MysqlCriticalConcurrencyRegressionTest.QueryControl;

/** Reproduce the empty-index gap collision seen during independent-device A/B warmup. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "labops.demo-users.enabled=true", "labops.demo-data.enabled=false",
        "labops.reservation-lock.mode=local", "labops.reservation-expiry.mode=local",
        "labops.reservation-expiry.scan-interval=3600000",
        "labops.payment.outbound.retry-interval=1h",
        "labops.payment.channel.bill-directory=target/test-channel-bills/reservation-create-mysql",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "management.health.redis.enabled=false", "management.health.rabbit.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=8"
})
@AutoConfigureMockMvc
@Import(MysqlCriticalConcurrencyRegressionTest.ControlledQueries.class)
@Timeout(90)
class MysqlReservationCreateDeadlockTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withPassword("reservation_create_test_password");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired QueryControl control;

    @Test
    void independentCreatesAfterEmptyConflictReadsBothCommitWithoutDeadlock() throws Exception {
        TestAuth.clearCache();
        control.jdbc = jdbc;
        assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
        // A fresh container makes the empty-index condition deterministic.
        assertThat(jdbc.queryForObject("select count(*) from equipment_reservations", Long.class)).isZero();
        assertThat(jdbc.update("""
                insert into platform_users(username,password_hash,display_name,role,enabled,created_at)
                select 'student_gap_two',password_hash,'Gap test student','STUDENT',true,created_at
                from platform_users where username='student'
                """)).isEqualTo(1);
        String admin = bearer(mvc, mapper, "admin", "admin123");
        String firstStudent = bearer(mvc, mapper, "student", "student123");
        String secondStudent = bearer(mvc, mapper, "student_gap_two", "student123");
        Long firstEquipment = createEquipment(admin);
        Long secondEquipment = createEquipment(admin);
        Instant start = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Gate first = new Gate("findConflictsForUpdate", firstEquipment, true);
        Gate second = new Gate("findConflictsForUpdate", secondEquipment, true);
        long deadlocksBefore = deadlocks();
        var pool = Executors.newFixedThreadPool(2);
        MvcResult a;
        MvcResult b;
        try {
            var fa = pool.submit(() -> create(first, firstStudent, firstEquipment, start));
            var fb = pool.submit(() -> create(second, secondStudent, secondEquipment, start));
            // Both current reads have completed while their transactions are open.
            // Under RR their gap locks coexist; neither insert can proceed until
            // the other transaction releases the gap.
            assertThat(first.queried.await(10, TimeUnit.SECONDS)).as("first conflict read").isTrue();
            assertThat(second.queried.await(10, TimeUnit.SECONDS)).as("second conflict read").isTrue();
            first.release.countDown();
            second.release.countDown();
            a = fa.get(30, TimeUnit.SECONDS);
            b = fb.get(30, TimeUnit.SECONDS);
        } finally {
            first.release.countDown();
            second.release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(deadlocks() - deadlocksBefore).as("InnoDB deadlocks, even if retried").isZero();
        assertThat(a.getResponse().getStatus()).as(a.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(b.getResponse().getStatus()).as(b.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(jdbc.queryForList("""
                select equipment_id from equipment_reservations where status='PENDING' order by equipment_id
                """, Long.class)).containsExactlyInAnyOrder(firstEquipment, secondEquipment);
    }

    private Long createEquipment(String admin) throws Exception {
        var response = mvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "code", "GAP-" + UUID.randomUUID(), "name", "Gap regression",
                                "category", "test", "location", "test lab"))))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }

    private MvcResult create(Gate gate, String authorization, Long equipment, Instant start) throws Exception {
        control.lane.set(gate);
        try {
            return mvc.perform(post("/api/reservations")
                            .header(HttpHeaders.AUTHORIZATION, authorization)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "equipmentId", equipment, "purpose", "gap regression",
                                    "startTime", start.toString(),
                                    "endTime", start.plus(1, ChronoUnit.HOURS).toString()))))
                    .andReturn();
        } finally {
            control.lane.remove();
        }
    }

    private long deadlocks() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.prepareStatement(
                     "select `count`, status from information_schema.innodb_metrics where name='lock_deadlocks'");
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("status")).isEqualTo("enabled");
            return rows.getLong("count");
        }
    }
}
