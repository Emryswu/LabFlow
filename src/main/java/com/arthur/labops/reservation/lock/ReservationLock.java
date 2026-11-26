package com.arthur.labops.reservation.lock;

import java.time.Instant;
import java.util.function.Supplier;

public interface ReservationLock {

    <T> T execute(Long equipmentId, Supplier<T> action);

    /**
     * Acquires date-sharded equipment locks in ascending UTC epoch-day order for
     * {@code [startTime, endTime)} so reservations on different dates do not
     * serialize behind a coarse equipment-wide lock.
     */
    <T> T executeWindow(Long equipmentId, Instant startTime, Instant endTime, Supplier<T> action);

    /**
     * Per-user lock so quota checks cannot race across different equipment ids.
     * Always acquire user then equipment to keep a single lock order.
     */
    <T> T executeUser(Long userId, Supplier<T> action);
}
