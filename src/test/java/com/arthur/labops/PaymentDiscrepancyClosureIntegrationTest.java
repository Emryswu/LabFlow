package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.audit.OperationLogRepository;
import com.arthur.labops.payment.PaymentCallbackIngest;
import com.arthur.labops.payment.PaymentCallbackRequest;
import com.arthur.labops.payment.PaymentDispatchService;
import com.arthur.labops.payment.PaymentIdempotency;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentRequest;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentRequestRetryJob;
import com.arthur.labops.payment.PaymentRequestStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.ReservationStatuses;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderCategory;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.payment.window=10m",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.outbound.retry-interval=1h",
        "labops.reservation-max-active-per-user=1",
        "labops.demo-data.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:discrepancy-closure;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/discrepancy-closure",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.arthur.labops.SqlCaptureStatementInspector"
})
@AutoConfigureMockMvc
class PaymentDiscrepancyClosureIntegrationTest {

    private static final long PRICE_CENTS = 5_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimulatedPaymentChannel channel;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentOrderRepository orderRepository;
    @Autowired private PaymentRequestRepository requestRepository;
    @Autowired private PaymentRequestRetryJob retryJob;
    @Autowired private PaymentDispatchService dispatchService;
    @Autowired private PaymentCallbackIngest callbackIngest;
    @Autowired private FaultWorkOrderRepository workOrderRepository;
    @Autowired private OperationLogRepository operationLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
        List<Reservation> existing = reservationRepository.findAll();
        for (Reservation reservation : existing) {
            if (ReservationStatuses.QUOTA.contains(reservation.getStatus())) {
                reservation.cancel();
            }
        }
        reservationRepository.saveAllAndFlush(existing);
    }

    @Test
    void closingAbandonedRefundTicketSettlesRefundingReservationReleasesQuotaAndKeepsLateCallbackIdempotent()
            throws Exception {
        Long equipmentId = scenario.createPricedEquipment("DISC-ABANDON", PRICE_CENTS);
        Long reservationId = createPaidReservation(equipmentId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        String refundKey = PaymentIdempotency.cancellationRefund(orderNo);

        // Fail outbound refund attempts until budget is exhausted and ticket is raised.
        channel.failNextOutbound(1);
        assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo("REFUNDING");
        Await.until("initial refund attempt to fail", () -> requestRepository
                .findByIdempotencyKey(refundKey)
                .filter(request -> request.getStatus() == PaymentRequestStatus.FAILED && request.getAttempts() == 1)
                .isPresent());

        for (int attempt = 1; attempt < 8; attempt++) {
            jdbcTemplate.update("update payment_requests set next_attempt_at = ? where idempotency_key = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), refundKey);
            channel.failNextOutbound(1);
            retryJob.retryDueRequests();
        }
        assertThat(requestRepository.findByIdempotencyKey(refundKey).orElseThrow().getStatus())
                .isEqualTo(PaymentRequestStatus.ABANDONED);

        FaultWorkOrder ticket = workOrderRepository.findAll().stream()
                .filter(w -> w.getCategory() == WorkOrderCategory.PAYMENT_DISCREPANCY)
                .filter(w -> ("outbound|" + refundKey).equals(w.getDiscrepancyKey()))
                .findFirst()
                .orElseThrow();

        // While REFUNDING, user quota (max=1) is still held.
        assertQuotaBlocked(equipmentId);

        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        transitionTicket(admin, ticket.getId(), "IN_PROGRESS");
        transitionTicket(admin, ticket.getId(), "RESOLVED");

        SqlCaptureStatementInspector.clear();
        transitionTicket(admin, ticket.getId(), "CLOSED");

        // Assert global lock order: Equipment -> FaultWorkOrder -> Reservation.
        List<String> locks = SqlCaptureStatementInspector.snapshot().stream()
                .filter(sql -> sql.contains(" for update"))
                .toList();
        int eqLock = firstIndex(locks, s -> s.contains(" from equipment "));
        int woLock = firstIndex(locks, s -> s.contains(" from fault_work_orders "));
        int resLock = firstIndex(locks, s -> s.contains(" from equipment_reservations "));
        assertThat(eqLock).isGreaterThanOrEqualTo(0);
        assertThat(woLock).isGreaterThan(eqLock);
        assertThat(resLock).isGreaterThan(woLock);

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(operationLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("RESERVATION_CANCELLED");
                    assertThat(log.getTargetType()).isEqualTo("RESERVATION");
                    assertThat(log.getTargetId()).isEqualTo(reservationId);
                    assertThat(log.getDetails()).contains(String.valueOf(ticket.getId()));
                });

        // Quota is now released; student can place a new reservation.
        Long replacement = scenario.createReservation(equipmentId, 1);
        assertThat(replacement).isNotNull();

        // Late channel refund callback remains idempotent without errors.
        PaymentCallbackRequest lateRefund = new PaymentCallbackRequest(
                orderNo, "LATE-REF-" + reservationId, PaymentTransactionType.REFUND,
                PRICE_CENTS, "CH-LATE-" + reservationId,
                PaymentCallbackRequest.STATUS_SUCCESS, Instant.now());
        assertThat(callbackIngest.ingest(lateRefund).orderStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
        assertThat(callbackIngest.ingest(lateRefund).accepted()).isFalse();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void closingDriftedRefundTicketSettlesRefundingReservationAndAcceptsRemainingLateRefund()
            throws Exception {
        Long equipmentId = scenario.createPricedEquipment("DISC-DRIFT", PRICE_CENTS);
        Long reservationId = createPaidReservation(equipmentId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        String refundKey = PaymentIdempotency.cancellationRefund(orderNo);

        // Hold the reservation in REFUNDING with an uncompleted refund request, then trigger drift.
        channel.failNextOutbound(1);
        assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo("REFUNDING");
        Await.until("initial refund attempt to fail", () -> requestRepository
                .findByIdempotencyKey(refundKey)
                .filter(request -> request.getStatus() == PaymentRequestStatus.FAILED)
                .isPresent());

        // An out-of-band partial refund reduces refundableCents to 3000 while request expects 5000.
        PaymentCallbackRequest partialCallback = new PaymentCallbackRequest(
                orderNo, "OOB-PARTIAL-" + reservationId, PaymentTransactionType.REFUND,
                2_000L, "CH-PARTIAL-" + reservationId,
                PaymentCallbackRequest.STATUS_SUCCESS, Instant.now());
        assertThat(callbackIngest.ingest(partialCallback).accepted()).isTrue();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.REFUNDING);

        // Attempt detects DRIFTED, marks request OBSOLETE, and raises a discrepancy ticket.
        dispatchService.attempt(refundKey);
        assertThat(requestRepository.findByIdempotencyKey(refundKey).orElseThrow().getStatus())
                .isEqualTo(PaymentRequestStatus.OBSOLETE);
        FaultWorkOrder ticket = workOrderRepository.findAll().stream()
                .filter(w -> w.getCategory() == WorkOrderCategory.PAYMENT_DISCREPANCY)
                .filter(w -> ("outbound|" + refundKey).equals(w.getDiscrepancyKey()))
                .findFirst()
                .orElseThrow();

        assertQuotaBlocked(equipmentId);

        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        transitionTicket(admin, ticket.getId(), "IN_PROGRESS");
        transitionTicket(admin, ticket.getId(), "RESOLVED");
        transitionTicket(admin, ticket.getId(), "CLOSED");

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);

        // Quota released after discrepancy closure.
        assertThat(scenario.createReservation(equipmentId, 1)).isNotNull();

        // Remaining refund arrives late from the channel: order settles cleanly and reservation stays CANCELLED.
        PaymentCallbackRequest remainingCallback = new PaymentCallbackRequest(
                orderNo, "OOB-REM-" + reservationId, PaymentTransactionType.REFUND,
                3_000L, "CH-REM-" + reservationId,
                PaymentCallbackRequest.STATUS_SUCCESS, Instant.now());
        var result = callbackIngest.ingest(remainingCallback);
        assertThat(result.accepted()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getRefundedCents())
                .isEqualTo(PRICE_CENTS);
    }

    @Test
    void closingUnrelatedReconciliationTicketDoesNotPrematurelyCancelInFlightRefundingReservation()
            throws Exception {
        Long equipmentId = scenario.createPricedEquipment("DISC-INFLIGHT", PRICE_CENTS);
        Long reservationId = createPaidReservation(equipmentId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        String refundKey = PaymentIdempotency.cancellationRefund(orderNo);

        // Student cancels; channel holds the refund callback in MANUAL mode so refund is genuinely in-flight (SENT).
        assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo("REFUNDING");
        Await.until("initial refund attempt to be sent", () -> requestRepository
                .findByIdempotencyKey(refundKey)
                .filter(request -> request.getStatus() == PaymentRequestStatus.SENT)
                .isPresent());

        var equipment = orderRepository.findByOrderNo(orderNo)
                .map(order -> workOrderRepository.findAll().stream()
                        .map(FaultWorkOrder::getEquipment)
                        .filter(eq -> eq.getId().equals(order.getEquipmentId()))
                        .findFirst()
                        .orElse(null))
                .orElse(null);
        if (equipment == null) {
            equipment = reservationRepository.findById(reservationId).orElseThrow().getEquipment();
        }
        FaultWorkOrder unrelatedBillTicket = workOrderRepository.saveAndFlush(FaultWorkOrder.discrepancy(
                equipment,
                1L,
                "2026-11-26|" + orderNo + "|AMOUNT_MISMATCH",
                "[对账差异] 订单 " + orderNo,
                "历史账单差异测试",
                com.arthur.labops.workorder.WorkOrderPriority.HIGH));

        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        transitionTicket(admin, unrelatedBillTicket.getId(), "IN_PROGRESS");
        transitionTicket(admin, unrelatedBillTicket.getId(), "RESOLVED");
        transitionTicket(admin, unrelatedBillTicket.getId(), "CLOSED");

        // Reservation must remain REFUNDING until the in-flight refund actually settles.
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.REFUNDING);
        assertThat(channel.deliverPending()).isEqualTo(1);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    private Long createPaidReservation(Long equipmentId) throws Exception {
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        channel.charge(orderNo, PRICE_CENTS);
        assertThat(channel.deliverPending()).isEqualTo(1);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
        return reservationId;
    }

    private void assertQuotaBlocked(Long equipmentId) throws Exception {
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", "配额检查",
                                "startTime", start.toString(),
                                "endTime", start.plus(1, ChronoUnit.HOURS).toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_QUOTA_EXCEEDED"));
    }

    private void transitionTicket(String adminToken, Long workOrderId, String targetStatus) throws Exception {
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetStatus", targetStatus))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(targetStatus));
    }

    private static int firstIndex(List<String> items, java.util.function.Predicate<String> predicate) {
        for (int i = 0; i < items.size(); i++) {
            if (predicate.test(items.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
