package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionType;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/refund-before-cancel"
})
@AutoConfigureMockMvc
class ChannelRefundBeforeCancellationIntegrationTest {

    private static final long PRICE_CENTS = 5_000L;

    enum CancellationRoute { OWNER, FAULT_REPORT }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimulatedPaymentChannel channel;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentOrderRepository orderRepository;
    @Autowired private PaymentRequestRepository requestRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @ParameterizedTest
    @EnumSource(CancellationRoute.class)
    void fullyRefundedOrderClosesWithoutAnotherRefund(CancellationRoute route) throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REFUNDED-CANCEL", PRICE_CENTS);
        Long reservationId = payAndRefund(equipmentId, PRICE_CENTS);
        String orderNo = PaymentService.orderNoFor(reservationId);

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.REFUNDED);
        cancel(route, equipmentId, reservationId, "CANCELLED");

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow()).satisfies(order -> {
            assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
            assertThat(order.getRefundedCents()).isEqualTo(PRICE_CENTS);
            assertThat(order.netCents()).isZero();
        });
        assertThat(requestRepository.findByOrderNoOrderByIdAsc(orderNo)).isEmpty();
        assertThat(channel.deliverPending()).isZero();
        assertThat(channel.ledger()).filteredOn(entry -> entry.orderNo().equals(orderNo)
                && entry.type() == ChannelEntryType.REFUND).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(CancellationRoute.class)
    void partiallyRefundedOrderStillWaitsForTheRemainingRefund(CancellationRoute route) throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PARTIAL-CANCEL", PRICE_CENTS);
        Long reservationId = payAndRefund(equipmentId, 2_000L);
        String orderNo = PaymentService.orderNoFor(reservationId);

        cancel(route, equipmentId, reservationId, "REFUNDING");

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.REFUNDING);
        assertThat(requestRepository.findByOrderNoOrderByIdAsc(orderNo)).singleElement()
                .satisfies(request -> {
                    assertThat(request.getType()).isEqualTo(PaymentTransactionType.REFUND);
                    assertThat(request.getAmountCents()).isEqualTo(3_000L);
                });
        Await.until("the remaining refund to reach the channel", () -> channel.ledger().stream()
                .filter(entry -> entry.orderNo().equals(orderNo) && entry.type() == ChannelEntryType.REFUND)
                .count() == 2);
        assertThat(channel.deliverPending()).isEqualTo(1);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.REFUNDED);
    }

    private Long payAndRefund(Long equipmentId, long refundCents) throws Exception {
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        channel.charge(orderNo, PRICE_CENTS);
        assertThat(channel.deliverPending()).isEqualTo(1);
        channel.refund(orderNo, refundCents);
        assertThat(channel.deliverPending()).isEqualTo(1);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
        return reservationId;
    }

    private void cancel(CancellationRoute route, Long equipmentId, Long reservationId,
                        String expectedStatus) throws Exception {
        if (route == CancellationRoute.OWNER) {
            assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo(expectedStatus);
        } else {
            mockMvc.perform(post("/api/work-orders")
                            .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "equipmentId", equipmentId,
                                    "title", "设备故障",
                                    "description", "设备无法使用，需要停机检修",
                                    "priority", "HIGH"))))
                    .andExpect(status().isCreated());
        }
    }
}
