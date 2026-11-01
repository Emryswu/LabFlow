package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentIdempotency;
import com.arthur.labops.payment.PaymentRequest;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentRequestRetryJob;
import com.arthur.labops.payment.PaymentRequestStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * An ABANDONED request whose ticket write was lost must still end up with exactly
 * one ticket: the status commits before the ticket's own transaction, and nothing
 * else ever looks at an ABANDONED request again.
 */
@SpringBootTest(properties = {
        "labops.payment.window=10m",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.outbound.retry-interval=1h",
        "spring.datasource.url=jdbc:h2:mem:abandoned-ticket-backstop;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/abandoned-ticket-backstop"
})
@AutoConfigureMockMvc
class AbandonedPaymentTicketBackstopIntegrationTest {

    private static final long PRICE_CENTS = 6_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimulatedPaymentChannel channel;
    @Autowired private PaymentRequestRepository requests;
    @Autowired private FaultWorkOrderRepository tickets;
    @Autowired private PaymentRequestRetryJob retryJob;

    private String orderNo;

    @BeforeEach
    void setUp() throws Exception {
        TestAuth.clearCache();
        channel.reset();
        PaymentScenario scenario = new PaymentScenario(mockMvc, objectMapper);
        Long equipmentId = scenario.createPricedEquipment("ABANDON-BACKSTOP", PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        orderNo = PaymentService.orderNoFor(reservationId);
    }

    @Test
    void backstopRaisesTheLostTicketOnceAndOnlyOnce() {
        // The state the dispatch path leaves behind when markFailed committed
        // ABANDONED and the follow-up ticket transaction then threw.
        String refundKey = PaymentIdempotency.cancellationRefund(orderNo);
        PaymentRequest request = abandoned(orderNo, refundKey);
        String ticketKey = "outbound|" + refundKey;
        assertThat(tickets.existsByDiscrepancyKey(ticketKey)).isFalse();
        assertThat(requests.findAbandonedWithoutTicket())
                .extracting(PaymentRequest::getIdempotencyKey).contains(refundKey);

        retryJob.raiseMissingAbandonedTickets();

        assertOneTicket(ticketKey, request);
        assertThat(requests.findAbandonedWithoutTicket())
                .extracting(PaymentRequest::getIdempotencyKey).doesNotContain(refundKey);

        retryJob.raiseMissingAbandonedTickets();
        retryJob.retryDueRequests();

        assertOneTicket(ticketKey, request);
        assertThat(requests.findByIdempotencyKey(refundKey).orElseThrow().getStatus())
                .isEqualTo(PaymentRequestStatus.ABANDONED);
    }

    @Test
    void requestsWithoutAResolvableOrderAreNotRescannedForever() {
        // The ticket service can only audit these; selecting them would write the
        // same audit line on every scan.
        String unknownOrder = "LF-UNKNOWN-" + System.nanoTime();
        abandoned(unknownOrder, PaymentIdempotency.cancellationRefund(unknownOrder));

        assertThat(requests.findAbandonedWithoutTicket())
                .extracting(PaymentRequest::getOrderNo).doesNotContain(unknownOrder);
    }

    private PaymentRequest abandoned(String order, String key) {
        PaymentRequest request = new PaymentRequest(order, key, PaymentTransactionType.REFUND, PRICE_CENTS);
        boolean exhausted = false;
        while (!exhausted) {
            exhausted = request.markFailed("java.lang.IllegalStateException: transport failure");
        }
        return requests.saveAndFlush(request);
    }

    private void assertOneTicket(String ticketKey, PaymentRequest request) {
        assertThat(tickets.findAll())
                .filteredOn(ticket -> ticketKey.equals(ticket.getDiscrepancyKey()))
                .singleElement().satisfies(ticket -> assertThat(ticket.getDescription())
                        .contains("REFUND", String.valueOf(request.getAttempts()), request.getLastError()));
    }
}