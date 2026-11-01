package com.arthur.labops.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.arthur.labops.equipment.Equipment;

class ReservationTest {

    private static final Instant START = Instant.parse("2030-01-15T10:00:00Z");

    @Test
    void approvalDeadlineNeverOutlivesRequestedWindow() {
        Instant start = Instant.now().plus(1, ChronoUnit.MINUTES);
        Instant end = start.plus(1, ChronoUnit.MINUTES);
        Reservation reservation = new Reservation(
                new Equipment("DEADLINE-001", "审批截止测试", "测试仪器", "D104"),
                1L,
                "Student",
                "验证审批截止时间",
                start,
                end,
                end.plus(15, ChronoUnit.MINUTES));

        assertThat(reservation.getExpiresAt()).isEqualTo(end);
    }

    @ParameterizedTest
    @CsvSource({"-1, true, CANCELLED", "0, false, PAID", "1, false, PAID"})
    void fullRefundCancelsPaidOnlyStrictlyBeforeStart(long nanosFromStart, boolean expectedChange,
                                                      ReservationStatus expectedStatus) {
        Reservation reservation = paidReservation();

        boolean changed = reservation.cancelOnFullRefundIfNotStarted(START.plusNanos(nanosFromStart));

        assertThat(changed).isEqualTo(expectedChange);
        assertThat(reservation.getStatus()).isEqualTo(expectedStatus);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L, 3_601L})
    void fullRefundClosesRefundingBeforeAtOrAfterStart(long secondsFromStart) {
        Reservation reservation = paidReservation();
        reservation.beginRefund();

        assertThat(reservation.cancelOnFullRefundIfNotStarted(START.plusSeconds(secondsFromStart))).isTrue();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void fullRefundDoesNotCancelAnAlreadyCancelledReservationAgain() {
        Reservation reservation = paidReservation();
        Instant now = START.minusSeconds(1);
        assertThat(reservation.cancelOnFullRefundIfNotStarted(now)).isTrue();

        assertThat(reservation.cancelOnFullRefundIfNotStarted(now)).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    private Reservation paidReservation() {
        Reservation reservation = new Reservation(
                new Equipment("REFUND-001", "退款测试设备", "测试仪器", "D104"),
                1L, "Student", "全额退款", START, START.plusSeconds(3_600), START);
        reservation.markPaid();
        return reservation;
    }
}
