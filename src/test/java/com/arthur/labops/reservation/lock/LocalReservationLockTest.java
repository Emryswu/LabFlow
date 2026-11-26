package com.arthur.labops.reservation.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Default CI lock ({@code labops.reservation-lock.mode=local}). Same-equipment
 * overlapping dates are serialized in ascending UTC-day order, while distinct
 * dates on the same equipment run concurrently without blocking each other.
 */
class LocalReservationLockTest {

    @Test
    void sameEquipmentCriticalSectionsDoNotOverlap() throws Exception {
        LocalReservationLock lock = new LocalReservationLock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> hold(lock, 9L, start, inside, maxInside));
            Future<?> second = executor.submit(() -> hold(lock, 9L, start, inside, maxInside));
            getOrTimeout(first, "same-equipment first");
            getOrTimeout(second, "same-equipment second");
        } finally {
            shutdownRace(executor);
        }

        assertThat(maxInside.get()).isEqualTo(1);
    }

    @Test
    void sameEquipmentDifferentDatesRunInParallelWithoutBlockingEachOther() throws Exception {
        LocalReservationLock lock = new LocalReservationLock();
        CountDownLatch bothEntered = new CountDownLatch(2);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();

        Instant day1Start = Instant.parse("2026-12-01T10:00:00Z");
        Instant day1End = Instant.parse("2026-12-01T12:00:00Z");
        Instant day5Start = Instant.parse("2026-12-05T10:00:00Z");
        Instant day5End = Instant.parse("2026-12-05T12:00:00Z");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> holdWindowConcurrent(
                    lock, 9L, day1Start, day1End, bothEntered, inside, maxInside));
            Future<?> second = executor.submit(() -> holdWindowConcurrent(
                    lock, 9L, day5Start, day5End, bothEntered, inside, maxInside));
            getOrTimeout(first, "different-dates first");
            getOrTimeout(second, "different-dates second");
        } finally {
            shutdownRace(executor);
        }

        assertThat(maxInside.get()).isEqualTo(2);
    }

    @Test
    void sameEquipmentCrossMidnightOverlappingDateSerializesWithoutDeadlock() throws Exception {
        LocalReservationLock lock = new LocalReservationLock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(2);

        Instant crossStart = Instant.parse("2026-12-01T22:00:00Z");
        Instant crossEnd = Instant.parse("2026-12-02T02:00:00Z");
        Instant day2Start = Instant.parse("2026-12-02T01:00:00Z");
        Instant day2End = Instant.parse("2026-12-02T03:00:00Z");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> holdWindowSerialized(
                    lock, 9L, crossStart, crossEnd, start, inside, maxInside));
            Future<?> second = executor.submit(() -> holdWindowSerialized(
                    lock, 9L, day2Start, day2End, start, inside, maxInside));
            getOrTimeout(first, "cross-midnight first");
            getOrTimeout(second, "cross-midnight second");
        } finally {
            shutdownRace(executor);
        }

        assertThat(maxInside.get()).isEqualTo(1);
    }

    @Test
    void halfOpenMidnightBoundaryDoesNotLockNextDay() throws Exception {
        LocalReservationLock lock = new LocalReservationLock();
        CountDownLatch bothEntered = new CountDownLatch(2);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();

        Instant day1EndAtMidnight = Instant.parse("2026-12-02T00:00:00Z");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> holdWindowConcurrent(
                    lock, 9L,
                    Instant.parse("2026-12-01T22:00:00Z"),
                    day1EndAtMidnight,
                    bothEntered, inside, maxInside));
            Future<?> second = executor.submit(() -> holdWindowConcurrent(
                    lock, 9L,
                    day1EndAtMidnight,
                    Instant.parse("2026-12-02T02:00:00Z"),
                    bothEntered, inside, maxInside));
            getOrTimeout(first, "midnight-boundary first");
            getOrTimeout(second, "midnight-boundary second");
        } finally {
            shutdownRace(executor);
        }

        assertThat(maxInside.get()).isEqualTo(2);
    }

    private static Void hold(LocalReservationLock lock, long equipmentId, CyclicBarrier start,
                             AtomicInteger inside, AtomicInteger maxInside) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return lock.execute(equipmentId, () -> {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            inside.decrementAndGet();
            return null;
        });
    }

    private static Void holdWindowSerialized(LocalReservationLock lock, long equipmentId,
                                             Instant startTime, Instant endTime, CyclicBarrier start,
                                             AtomicInteger inside, AtomicInteger maxInside) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return lock.executeWindow(equipmentId, startTime, endTime, () -> {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            inside.decrementAndGet();
            return null;
        });
    }

    private static Void holdWindowConcurrent(LocalReservationLock lock, long equipmentId,
                                             Instant startTime, Instant endTime, CountDownLatch bothEntered,
                                             AtomicInteger inside, AtomicInteger maxInside) {
        return lock.executeWindow(equipmentId, startTime, endTime, () -> {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            bothEntered.countDown();
            try {
                assertThat(bothEntered.await(3, TimeUnit.SECONDS))
                        .as("non-overlapping dates should enter concurrently without blocking")
                        .isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                inside.decrementAndGet();
            }
            return null;
        });
    }

    private static void getOrTimeout(Future<?> future, String race) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("timeout (" + race + ")", timeout);
        }
    }

    private static void shutdownRace(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new AssertionError("worker threads did not terminate");
        }
    }
}
