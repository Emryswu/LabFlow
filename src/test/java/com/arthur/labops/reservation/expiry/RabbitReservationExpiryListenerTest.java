package com.arthur.labops.reservation.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationStatus;

class RabbitReservationExpiryListenerTest {

    private RecordingDeadlineHandler deadlineHandler;
    private RabbitExpiryTopologyProperties topology;
    private RabbitReservationExpiryListener listener;

    @BeforeEach
    void setUp() {
        deadlineHandler = new RecordingDeadlineHandler();
        topology = new RabbitExpiryTopologyProperties();
        listener = new RabbitReservationExpiryListener(deadlineHandler, topology);
    }

    @Test
    void nonNumericPayloadIsIgnoredWithoutCallingExpire() {
        assertThatCode(() -> listener.expire("not-a-number")).doesNotThrowAnyException();
        assertThat(deadlineHandler.fired).isEmpty();
    }

    @Test
    void numericPayloadStillCallsExpireIfPending() {
        listener.expire("42");
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.APPROVAL, 42L));
    }

    /**
     * The payment window rides the same work queue. An untagged payload must keep
     * meaning "approval deadline" so messages written by the previous version
     * still decode, and a tagged one must not be mistaken for an approval.
     */
    @Test
    void taggedPayloadRoutesToThePaymentDeadline() {
        listener.expire("PAYMENT:9");
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.PAYMENT, 9L));
    }

    @Test
    void unknownKindIsIgnored() {
        assertThatCode(() -> listener.expire("NONSENSE:9")).doesNotThrowAnyException();
        assertThat(deadlineHandler.fired).isEmpty();
    }

    @Test
    void invalidPayloadDoesNotBlockLaterValidMessage() {
        assertThatCode(() -> listener.expire("not-a-number")).doesNotThrowAnyException();
        listener.expire("7");
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.APPROVAL, 7L));
    }

    @Test
    void cascadesToNextBucketWhenDeadlineIsStillInFutureAndWaiting() {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        Instant deadline = now.plusSeconds(10);
        RecordingScheduler scheduler = new RecordingScheduler();
        RabbitReservationExpiryListener cascadingListener = new RabbitReservationExpiryListener(
                deadlineHandler,
                topology,
                (kind, id) -> true,
                scheduler,
                Clock.fixed(now, ZoneOffset.UTC));

        cascadingListener.expire("101@" + deadline.toEpochMilli());

        assertThat(deadlineHandler.fired).isEmpty();
        assertThat(scheduler.scheduled)
                .containsExactly(new ScheduledCall(ReservationDeadlineKind.APPROVAL, 101L, deadline));
    }

    @Test
    void lazyDisarmDropsAlreadySettledReservationWithoutReschedulingOrFiring() {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        Instant deadline = now.plusSeconds(60);
        RecordingScheduler scheduler = new RecordingScheduler();
        RabbitReservationExpiryListener disarmListener = new RabbitReservationExpiryListener(
                deadlineHandler,
                topology,
                (kind, id) -> false,
                scheduler,
                Clock.fixed(now, ZoneOffset.UTC));

        disarmListener.expire("PAYMENT:55@" + deadline.toEpochMilli());

        assertThat(deadlineHandler.fired).isEmpty();
        assertThat(scheduler.scheduled).isEmpty();
    }

    /**
     * Critical boundary: when DB {@code expiresAt == schedule.deadline}, arriving even
     * 30ms early must NOT fire early (which would fail {@code now >= expiresAt} and lose
     * the deadline without rescheduling). Instead it cascades into the 100ms bucket
     * and fires once {@code now >= deadline}.
     */
    @Test
    void thirtyMillisBeforeExactDeadlineCascadesInsteadOfFiringEarlyAndThenExpiresOnTime() {
        Instant deadline = Instant.parse("2026-11-26T10:05:00Z");
        MutableClock clock = new MutableClock(deadline.minusMillis(30));
        RecordingScheduler scheduler = new RecordingScheduler();

        Reservation reservation = new Reservation(
                new Equipment("EQ-1", "Scope", "Optics", "L101"),
                1L,
                "Alice",
                "Exact boundary test",
                deadline.plusSeconds(3600),
                deadline.plusSeconds(7200),
                deadline);

        ReservationDeadlineHandler entityCheckingHandler = new ReservationDeadlineHandler(null, null) {
            @Override
            public boolean fire(ReservationDeadlineKind kind, Long reservationId) {
                deadlineHandler.fired.add(Map.entry(kind, reservationId));
                return reservation.expireIfPending(clock.instant());
            }
        };

        RabbitReservationExpiryListener boundaryListener = new RabbitReservationExpiryListener(
                entityCheckingHandler,
                topology,
                (kind, id) -> RabbitReservationExpiryListener.isWaitingStatus(kind, reservation.getStatus()),
                scheduler,
                clock);

        String payload = ReservationDeadlinePayload.encode(ReservationDeadlineKind.APPROVAL, 88L, deadline);

        // 30ms early: cascades instead of firing early.
        boundaryListener.expire(payload);
        assertThat(deadlineHandler.fired).isEmpty();
        assertThat(scheduler.scheduled)
                .containsExactly(new ScheduledCall(ReservationDeadlineKind.APPROVAL, 88L, deadline));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);

        // After the 100ms smallest bucket hops past deadline: fires and transitions to EXPIRED.
        clock.advanceMillis(100);
        boundaryListener.expire(payload);
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.APPROVAL, 88L));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void exactDeadlineTimestampFiresImmediately() {
        Instant deadline = Instant.parse("2026-11-26T10:05:00Z");
        RecordingScheduler scheduler = new RecordingScheduler();
        RabbitReservationExpiryListener dueListener = new RabbitReservationExpiryListener(
                deadlineHandler,
                topology,
                (kind, id) -> true,
                scheduler,
                Clock.fixed(deadline, ZoneOffset.UTC));

        dueListener.expire("PAYMENT:77@" + deadline.toEpochMilli());

        assertThat(scheduler.scheduled).isEmpty();
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.PAYMENT, 77L));
    }

    private static Map.Entry<ReservationDeadlineKind, Long> entry(ReservationDeadlineKind kind, Long id) {
        return Map.entry(kind, id);
    }

    private record ScheduledCall(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
    }

    private static final class RecordingScheduler implements ReservationExpiryScheduler {
        private final List<ScheduledCall> scheduled = new ArrayList<>();

        @Override
        public void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
            scheduled.add(new ScheduledCall(kind, reservationId, deadline));
        }

        @Override
        public void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advanceMillis(long millis) {
            current = current.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static class RecordingDeadlineHandler extends ReservationDeadlineHandler {
        final List<Map.Entry<ReservationDeadlineKind, Long>> fired = new ArrayList<>();

        private RecordingDeadlineHandler() {
            super(null, null);
        }

        @Override
        public boolean fire(ReservationDeadlineKind kind, Long reservationId) {
            fired.add(Map.entry(kind, reservationId));
            return true;
        }
    }
}
