package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.payment.window=10m",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.outbound.retry-interval=1h",
        "spring.datasource.url=jdbc:h2:mem:retry-exhaustion;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/retry-exhaustion"
})
@AutoConfigureMockMvc
class PaymentRetryExhaustionIntegrationTest {

    private static final long PRICE_CENTS = 6_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimulatedPaymentChannel channel;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private PaymentRequestRepository requests;
    @Autowired private FaultWorkOrderRepository tickets;
    @Autowired private PaymentRequestRetryJob retryJob;
    @Autowired private PaymentDispatchService dispatch;
    @Autowired private JdbcTemplate jdbc;

    private PaymentScenario scenario;
    private Long reservationId;
    private String orderNo;
    private String refundKey;

    @BeforeEach
    void setUp() throws Exception {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
        Long equipmentId = scenario.createPricedEquipment("RETRY-EXHAUST", PRICE_CENTS);
        reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        orderNo = PaymentService.orderNoFor(reservationId);
        refundKey = PaymentIdempotency.cancellationRefund(orderNo);
        assertThat(scenario.payViaApi(orderNo, "student", "student123")).isEqualTo(200);
        Await.until("the payment dispatch to complete", () -> requests
                .findByIdempotencyKey(PaymentIdempotency.payment(orderNo))
                .filter(request -> request.getStatus() == PaymentRequestStatus.SENT).isPresent());
        channel.deliverPending();
        assertThat(orders.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.PAID);
    }

    @Test
    void eventRetryCreatesATicketWhenTransportFailsAfterSevenChannelRejections() throws Exception {
        channel.rejectNextOutboundFinal(7);
        assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo("REFUNDING");
        for (int attempt = 0; attempt < 7; attempt++) {
            int expectedAttempt = attempt;
            Await.until("refund attempt " + attempt + " to await its rejection callback", () -> {
                PaymentRequest request = refund();
                return request.getStatus() == PaymentRequestStatus.SENT
                        && request.getChannelAttempt() == expectedAttempt;
            });
            assertThat(refund().getAttempts()).isEqualTo(attempt + 1);
            assertThat(tickets.existsByDiscrepancyKey("outbound|" + refundKey)).isFalse();
            if (attempt == 6) {
                channel.failNextOutbound(1);
            }
            channel.deliverPending();
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(this::assertExhaustedWithOneTicket);
        assertThat(refund().getChannelAttempt()).isEqualTo(7);
        channel.redeliverAll();
        dispatch.attempt(refundKey);
        retryJob.retryDueRequests();
        assertExhaustedWithOneTicket();
    }

    @Test
    void scheduledRetryAlsoCreatesOneTicketWhenTransportFailuresExhaustTheBudget() {
        // Persist the first seven failed sends without queuing an immediate event;
        // the scanner must perform the final send itself.
        PaymentRequest request = new PaymentRequest(orderNo, refundKey, PaymentTransactionType.REFUND, PRICE_CENTS);
        for (int attempt = 0; attempt < 7; attempt++) {
            assertThat(request.markFailed("previous transport failure")).isFalse();
        }
        requests.saveAndFlush(request);
        jdbc.update("update payment_requests set next_attempt_at=? where idempotency_key=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), refundKey);
        assertThat(tickets.existsByDiscrepancyKey("outbound|" + refundKey)).isFalse();
        channel.failNextOutbound(1);

        retryJob.retryDueRequests();

        assertExhaustedWithOneTicket();
        assertThat(refund().getChannelAttempt()).isZero();
        retryJob.retryDueRequests();
        dispatch.attempt(refundKey);
        assertExhaustedWithOneTicket();
    }

    private PaymentRequest refund() {
        return requests.findByIdempotencyKey(refundKey).orElseThrow();
    }

    private void assertExhaustedWithOneTicket() {
        PaymentRequest request = refund();
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.ABANDONED);
        assertThat(request.getAttempts()).isEqualTo(8);
        assertThat(request.getLastError()).contains("IllegalStateException");
        assertThat(requests.findDueKeys(Instant.now())).doesNotContain(refundKey);
        assertThat(tickets.findAll())
                .filteredOn(ticket -> ("outbound|" + refundKey).equals(ticket.getDiscrepancyKey()))
                .singleElement().satisfies(ticket -> assertThat(ticket.getDescription())
                        .contains("REFUND", "8", request.getLastError()));
        assertThat(channel.ledger())
                .filteredOn(entry -> entry.orderNo().equals(orderNo) && entry.type() == ChannelEntryType.REFUND)
                .isEmpty();
    }
}
