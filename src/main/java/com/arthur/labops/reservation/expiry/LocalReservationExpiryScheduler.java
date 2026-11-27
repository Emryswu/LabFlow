package com.arthur.labops.reservation.expiry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "local", matchIfMissing = true)
public class LocalReservationExpiryScheduler implements ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalReservationExpiryScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ReservationDeadlineHandler deadlineHandler;

    /**
     * Armed deadlines, so a settled reservation can drop its task instead of
     * leaving it parked in the scheduler's queue until the original instant.
     * Keyed by kind + id (not by instant), so rescheduling replaces rather than
     * accumulates.
     */
    private final Map<String, ScheduledFuture<?>> armed = new ConcurrentHashMap<>();

    public LocalReservationExpiryScheduler(TaskScheduler taskScheduler,
                                           ReservationDeadlineHandler deadlineHandler) {
        this.taskScheduler = taskScheduler;
        this.deadlineHandler = deadlineHandler;
    }

    @Override
    public void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        String key = key(kind, reservationId);
        AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            ScheduledFuture<?> own = self.get();
            if (own != null) {
                armed.remove(key, own);
            }
            try {
                deadlineHandler.fire(kind, reservationId);
            } finally {
                finished.set(true);
                own = self.get();
                if (own != null) {
                    armed.remove(key, own);
                }
            }
        }, deadline);
        self.set(future);
        ScheduledFuture<?> previous = armed.put(key, future);
        if (previous != null) {
            previous.cancel(false);
        }
        // Reclaim immediately if the task finished before armed.put completed.
        if (finished.get()) {
            armed.remove(key, future);
        }
    }

    @Override
    public void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        ScheduledFuture<?> future = armed.remove(key(kind, reservationId));
        if (future != null) {
            future.cancel(false);
            log.debug("Cancelled local reservation deadline kind={} reservationId={}", kind, reservationId);
        }
    }

    /** Visible for tests: how many deadlines are still parked in the scheduler. */
    public int armedCount() {
        return armed.size();
    }

    private static String key(ReservationDeadlineKind kind, Long reservationId) {
        return kind.name() + ':' + reservationId;
    }
}
