package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentCallbackIngest;
import com.arthur.labops.payment.PaymentCallbackRequest;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.PaymentTransactionType;
import com.arthur.labops.payment.channel.ChannelEntry;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/refund-auto-cancel",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.arthur.labops.SqlCaptureStatementInspector"
})
@AutoConfigureMockMvc
class ChannelRefundAutoCancellationIntegrationTest {

    private static final long PRICE_CENTS = 5_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimulatedPaymentChannel channel;
    @Autowired private PaymentCallbackIngest callbackIngest;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentOrderRepository orderRepository;
    @Autowired private PaymentRequestRepository requestRepository;
    @Autowired private PaymentTransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2_000L})
    void fullRefundCancelsAndReleasesSlotIncludingAfterAPartialRefund(long firstRefund) throws Exception {
        Long equipmentId = scenario.createPricedEquipment("AUTO-CANCEL", PRICE_CENTS);
        Long reservationId = pay(equipmentId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        if (firstRefund > 0) {
            refund(orderNo, firstRefund);
            assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                    .isEqualTo(ReservationStatus.PAID);
        }

        refund(orderNo, PRICE_CENTS - firstRefund);

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow()).satisfies(order -> {
            assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
            assertThat(order.getRefundedCents()).isEqualTo(PRICE_CENTS);
        });
        assertThat(requestRepository.findByOrderNoOrderByIdAsc(orderNo)).isEmpty();
        // A new overlapping reservation can be approved without a manual cancellation.
        Long replacement = scenario.createReservation(equipmentId, 1);
        assertThat(scenario.approve(replacement).get("status")).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void partialRefundKeepsPaidReservationAndVersionUnchanged() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PARTIAL-KEEP", PRICE_CENTS);
        Long reservationId = pay(equipmentId);
        long version = reservationRepository.findById(reservationId).orElseThrow().getVersion();
        String orderNo = PaymentService.orderNoFor(reservationId);

        refund(orderNo, 2_000L);

        assertThat(reservationRepository.findById(reservationId).orElseThrow()).satisfies(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAID);
            assertThat(reservation.getVersion()).isEqualTo(version);
        });
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PARTIALLY_REFUNDED);
    }

    @ParameterizedTest
    @ValueSource(longs = {3_600L, -60L})
    void fullRefundKeepsStartedOrEndedPaidReservationUnchanged(long endOffsetSeconds) throws Exception {
        Long reservationId = pay(scenario.createPricedEquipment("USED-REFUND", PRICE_CENTS));
        Instant now = Instant.now();
        Instant start = now.minusSeconds(7_200);
        setWindow(reservationId, start, now.plusSeconds(endOffsetSeconds));
        Reservation paid = reservationRepository.findById(reservationId).orElseThrow();
        String orderNo = PaymentService.orderNoFor(reservationId);

        // The channel refunded before the session started, but its callback was
        // delayed. Cancellation depends on processing time, not occurredAt.
        var request = new PaymentCallbackRequest(orderNo, "USED-REFUND-" + reservationId,
                PaymentTransactionType.REFUND, PRICE_CENTS, "CH-USED-" + reservationId,
                PaymentCallbackRequest.STATUS_SUCCESS, start.minusSeconds(60));
        assertThat(callbackIngest.ingest(request).accepted()).isTrue();

        assertPaidAndVersionUnchanged(reservationId, paid.getVersion());
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow()).satisfies(order -> {
            assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
            assertThat(order.getRefundedCents()).isEqualTo(PRICE_CENTS);
        });
        assertThat(transactionRepository.countByOrderNo(orderNo)).isEqualTo(2);
    }

    @Test
    void fullRefundExactlyAtStartKeepsPaidReservationAndVersionUnchanged() throws Exception {
        Long reservationId = pay(scenario.createPricedEquipment("START-REFUND", PRICE_CENTS));
        Instant start = Instant.parse("2030-01-15T10:00:00Z");
        setWindow(reservationId, start, start.plusSeconds(3_600));
        Reservation paid = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(paid.getStartTime()).isEqualTo(start);
        String orderNo = PaymentService.orderNoFor(reservationId);

        try (MockedStatic<Instant> time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(start);
            refund(orderNo, PRICE_CENTS);
        }

        assertPaidAndVersionUnchanged(reservationId, paid.getVersion());
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.REFUNDED);
    }

    @ParameterizedTest
    @ValueSource(longs = {3_600L, -60L})
    void fullRefundStillClosesRefundingAfterStartOrEnd(long endOffsetSeconds) throws Exception {
        Long reservationId = pay(scenario.createPricedEquipment("REFUNDING-USED", PRICE_CENTS));
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.beginRefund();
        reservationRepository.saveAndFlush(reservation);
        Instant now = Instant.now();
        setWindow(reservationId, now.minusSeconds(7_200), now.plusSeconds(endOffsetSeconds));

        refund(PaymentService.orderNoFor(reservationId), PRICE_CENTS);

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void replayOfFullRefundDoesNotWriteReservationOrderOrLedgerAgain() throws Exception {
        Long reservationId = pay(scenario.createPricedEquipment("REFUND-REPLAY", PRICE_CENTS));
        String orderNo = PaymentService.orderNoFor(reservationId);
        ChannelEntry entry = refund(orderNo, PRICE_CENTS);
        Reservation cancelled = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        var order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        var request = new PaymentCallbackRequest(orderNo, entry.channelTxnId(), PaymentTransactionType.REFUND,
                entry.amountCents(), entry.channelTxnId(), entry.status(), entry.occurredAt());

        // Call the ingest directly so the simulator cannot swallow a replay error.
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = callbackIngest.ingest(request);
            assertThat(result.accepted()).isFalse();
            assertThat(result.orderStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
        }

        assertThat(reservationRepository.findById(reservationId).orElseThrow()).satisfies(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservation.getVersion()).isEqualTo(cancelled.getVersion());
        });
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow()).satisfies(reloaded -> {
            assertThat(reloaded.getStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
            assertThat(reloaded.getRefundedCents()).isEqualTo(PRICE_CENTS);
            assertThat(reloaded.getVersion()).isEqualTo(order.getVersion());
            assertThat(reloaded.getUpdatedAt()).isEqualTo(order.getUpdatedAt());
        });
        assertThat(transactionRepository.countByOrderNo(orderNo)).isEqualTo(2);
    }

    @Test
    void fullRefundLocksEquipmentThenReservationThenPaymentOrder() throws Exception {
        Long reservationId = pay(scenario.createPricedEquipment("REFUND-LOCK", PRICE_CENTS));
        channel.refund(PaymentService.orderNoFor(reservationId), PRICE_CENTS);

        SqlCaptureStatementInspector.clear();
        assertThat(channel.deliverPending()).isEqualTo(1);

        List<String> locks = SqlCaptureStatementInspector.snapshot().stream()
                .filter(sql -> sql.contains(" for update"))
                .toList();
        assertThat(locks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(locks.get(0)).contains(" from equipment ");
        assertThat(locks.get(1)).contains(" from equipment_reservations ");
        assertThat(locks.get(2)).contains(" from payment_orders ");
    }

    private Long pay(Long equipmentId) throws Exception {
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        channel.charge(PaymentService.orderNoFor(reservationId), PRICE_CENTS);
        assertThat(channel.deliverPending()).isEqualTo(1);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
        return reservationId;
    }

    private ChannelEntry refund(String orderNo, long amount) {
        ChannelEntry entry = channel.refund(orderNo, amount);
        assertThat(channel.deliverPending()).isEqualTo(1);
        return entry;
    }

    private void setWindow(Long reservationId, Instant start, Instant end) {
        jdbcTemplate.update("update equipment_reservations set start_time = ?, end_time = ? where id = ?",
                Timestamp.from(start), Timestamp.from(end), reservationId);
    }

    private void assertPaidAndVersionUnchanged(Long reservationId, long version) {
        assertThat(reservationRepository.findById(reservationId).orElseThrow()).satisfies(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAID);
            assertThat(reservation.getVersion()).isEqualTo(version);
        });
    }
}
