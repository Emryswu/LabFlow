package com.arthur.labops.reservation.expiry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;

@Component
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "rabbit")
public class RabbitReservationExpiryListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitReservationExpiryListener.class);

    @FunctionalInterface
    interface ActiveDeadlineCheck {
        boolean isWaitingFor(ReservationDeadlineKind kind, Long reservationId);
    }

    private final ReservationDeadlineHandler deadlineHandler;
    private final RabbitExpiryTopologyProperties topology;
    private final ActiveDeadlineCheck activeCheck;
    private final ReservationExpiryScheduler scheduler;
    private final Clock clock;

    @Autowired
    public RabbitReservationExpiryListener(ReservationDeadlineHandler deadlineHandler,
                                           RabbitExpiryTopologyProperties topology,
                                           ReservationRepository reservationRepository,
                                           ReservationExpiryScheduler scheduler) {
        this(
                deadlineHandler,
                topology,
                (kind, reservationId) -> reservationRepository.findStatusById(reservationId)
                        .map(status -> isWaitingStatus(kind, status))
                        .orElse(false),
                scheduler,
                Clock.systemUTC());
    }

    public RabbitReservationExpiryListener(ReservationDeadlineHandler deadlineHandler,
                                           RabbitExpiryTopologyProperties topology) {
        this(deadlineHandler, topology, (kind, id) -> true, null, Clock.systemUTC());
    }

    RabbitReservationExpiryListener(ReservationDeadlineHandler deadlineHandler,
                                    RabbitExpiryTopologyProperties topology,
                                    ActiveDeadlineCheck activeCheck,
                                    ReservationExpiryScheduler scheduler,
                                    Clock clock) {
        this.deadlineHandler = deadlineHandler;
        this.topology = topology;
        this.activeCheck = activeCheck;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @RabbitListener(queues = "#{@reservationExpiryQueue.name}")
    public void expire(String payload) {
        ReservationDeadlinePayload.Decoded decoded = ReservationDeadlinePayload.decode(payload);
        if (decoded == null) {
            log.warn("Ignoring unparseable RabbitMQ deadline payload queue={}", topology.getExpiryQueue());
            return;
        }

        Instant deadline = decoded.deadlineInstant();
        if (deadline != null && scheduler != null) {
            Instant now = clock.instant();
            // Any positive remaining time (even 1~50ms) must cascade through the
            // smallest bucket rather than firing early: DB expiresAt may equal
            // schedule deadline, so firing even 30ms early would fail now >= deadline
            // without rescheduling.
            if (now.isBefore(deadline)) {
                if (!activeCheck.isWaitingFor(decoded.kind(), decoded.reservationId())) {
                    log.debug("Lazy-disarm dropping settled RabbitMQ deadline kind={} reservationId={} deadline={}",
                            decoded.kind(), decoded.reservationId(), deadline);
                    return;
                }
                long remainingMs = Duration.between(now, deadline).toMillis();
                log.debug("Cascading RabbitMQ deadline to finer bucket kind={} reservationId={} remainingMs={}",
                        decoded.kind(), decoded.reservationId(), remainingMs);
                scheduler.schedule(decoded.kind(), decoded.reservationId(), deadline);
                return;
            }
        }

        log.info("RabbitMQ deadline message consumed kind={} reservationId={} queue={}",
                decoded.kind(), decoded.reservationId(), topology.getExpiryQueue());
        deadlineHandler.fire(decoded.kind(), decoded.reservationId());
    }

    static boolean isWaitingStatus(ReservationDeadlineKind kind, ReservationStatus status) {
        return switch (kind) {
            case APPROVAL -> status == ReservationStatus.PENDING;
            case PAYMENT -> status == ReservationStatus.AWAITING_PAYMENT;
        };
    }
}
