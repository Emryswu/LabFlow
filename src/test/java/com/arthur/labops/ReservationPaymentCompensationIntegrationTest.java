package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.audit.OperationLogRepository;
import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationPaymentGateway;
import com.arthur.labops.reservation.ReservationPaymentTimeoutService;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.ReservationStatuses;
import com.arthur.labops.reservation.expiry.ReservationDeadlineKind;
import com.arthur.labops.reservation.expiry.ReservationExpiryCompensationJob;
import com.arthur.labops.reservation.expiry.ReservationExpiryScheduler;
import com.arthur.labops.reservation.expiry.ReservationExpirationService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-compensation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "labops.reservation-max-active-per-user=1"
})
@AutoConfigureMockMvc
@Import(ReservationPaymentCompensationIntegrationTest.SchedulerConfiguration.class)
class ReservationPaymentCompensationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentOrderRepository orderRepository;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private PlatformUserRepository userRepository;
    @Autowired private OperationLogRepository operationLogRepository;
    @Autowired private ReservationExpiryCompensationJob compensationJob;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FailingPaymentScheduler scheduler;
    @Autowired private ReservationPaymentTimeoutService paymentTimeoutService;
    @Autowired private ReservationExpirationService expirationService;
    @Autowired private EquipmentStatusService equipmentStatusService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private ReservationPaymentGateway paymentGateway;

    @Test
    void failedPaymentDeadlineDeliveryIsRecoveredAndReleasesSlotAndQuota() throws Exception {
        TestAuth.clearCache();
        PaymentScenario scenario = new PaymentScenario(mockMvc, objectMapper);
        Long equipmentId = scenario.createPricedEquipment("PAY-COMP", 3_000L);
        Long reservationId = scenario.createReservation(equipmentId, 1);

        assertThat(scenario.approve(reservationId).get("status")).isEqualTo("AWAITING_PAYMENT");
        assertThat(scheduler.failedPayments.get()).isPositive();
        Reservation awaiting = reservationRepository.findById(reservationId).orElseThrow();
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        assertThat(reservationRepository.countByRequesterIdAndStatusIn(student.getId(), ReservationStatuses.QUOTA))
                .isEqualTo(1);
        String replacement = objectMapper.writeValueAsString(Map.of(
                "equipmentId", equipmentId,
                "purpose", "补偿后复用原时段",
                "startTime", awaiting.getStartTime().toString(),
                "endTime", awaiting.getEndTime().toString()));
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON).content(replacement))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_QUOTA_EXCEEDED"));

        // Move the persisted deadline instead of sleeping; no timer was delivered.
        jdbc.update("update equipment_reservations set payment_deadline = ? where id = ?",
                Instant.now().minusSeconds(1), reservationId);
        compensationJob.expireOverduePendingReservations();
        compensationJob.expireOverduePendingReservations();

        Reservation expired = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(expired.getPaymentDeadline()).isNull();
        assertThat(orderRepository.findByOrderNo(PaymentService.orderNoFor(reservationId)).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.CLOSED);
        assertThat(reservationRepository.countByRequesterIdAndStatusIn(student.getId(), ReservationStatuses.QUOTA))
                .isZero();
        assertThat(operationLogRepository.findAll().stream()
                .filter(log -> reservationId.equals(log.getTargetId()))
                .filter(log -> "RESERVATION_PAYMENT_EXPIRED".equals(log.getAction())))
                .singleElement();

        String body = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON).content(replacement))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long replacementId = objectMapper.readTree(body).get("id").longValue();
        assertThat(scenario.approve(replacementId).get("status")).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void scanPreservesFuturePaymentAndPaidRowsWhileStillExpiringPending() {
        Reservation future = reservation("future");
        future.awaitPayment(Instant.now().plusSeconds(3600));
        future = reservationRepository.saveAndFlush(future);
        Reservation paid = reservation("paid");
        paid.markPaid();
        paid = reservationRepository.saveAndFlush(paid);
        // Even legacy data with a stale payment deadline must keep its paid status.
        jdbc.update("update equipment_reservations set payment_deadline = ? where id = ?",
                Instant.now().minusSeconds(1), paid.getId());
        Reservation pending = reservationRepository.saveAndFlush(reservation("pending"));

        compensationJob.expireOverduePendingReservations();

        assertThat(reservationRepository.findById(future.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        assertThat(reservationRepository.findById(paid.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
        assertThat(reservationRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    private Reservation reservation(String label) {
        Equipment equipment = equipmentRepository.saveAndFlush(new Equipment(
                "COMP-" + UUID.randomUUID(), label, "test", "lab"));
        PlatformUser teacher = userRepository.findByUsername("teacher").orElseThrow();
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        return new Reservation(equipment, teacher.getId(), teacher.getDisplayName(), label,
                start, start.plusSeconds(3600), Instant.now().minusSeconds(1));
    }

    @Test
    void failedPaymentClosureDoesNotPreventLaterRowsFromBeingRecovered() {
        Reservation first = overduePayment("first");
        Reservation failed = overduePayment("failed");
        Reservation last = overduePayment("last");
        ReservationPaymentTimeoutService failing = new ReservationPaymentTimeoutService(
                reservationRepository, equipmentRepository, equipmentStatusService, auditLogService, paymentGateway) {
            @Override
            public boolean closeIfPaymentWindowElapsed(Long reservationId) {
                if (reservationId.equals(failed.getId())) {
                    throw new IllegalStateException("simulated payment closure failure");
                }
                return paymentTimeoutService.closeIfPaymentWindowElapsed(reservationId);
            }
        };

        new ReservationExpiryCompensationJob(
                reservationRepository, expirationService, failing, equipmentStatusService)
                .expireOverduePendingReservations();

        assertThat(reservationRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        assertThat(reservationRepository.findById(last.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    private Reservation overduePayment(String label) {
        Reservation reservation = reservation(label);
        reservation.awaitPayment(Instant.now().minusSeconds(1));
        return reservationRepository.saveAndFlush(reservation);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulerConfiguration {
        @Bean
        @Primary
        FailingPaymentScheduler failingPaymentScheduler() {
            return new FailingPaymentScheduler();
        }
    }

    static class FailingPaymentScheduler implements ReservationExpiryScheduler {
        final AtomicInteger failedPayments = new AtomicInteger();

        @Override
        public void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
            if (kind == ReservationDeadlineKind.PAYMENT) {
                failedPayments.incrementAndGet();
                throw new IllegalStateException("simulated RabbitMQ delivery failure after commit");
            }
        }

        @Override
        public void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        }
    }
}
