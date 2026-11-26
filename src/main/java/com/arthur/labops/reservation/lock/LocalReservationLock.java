package com.arthur.labops.reservation.lock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "labops.reservation-lock.mode", havingValue = "local", matchIfMissing = true)
public class LocalReservationLock implements ReservationLock {

    private static final String EQUIPMENT_PREFIX = "equipment:";

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T execute(Long equipmentId, Supplier<T> action) {
        return executeKeys(List.of(EQUIPMENT_PREFIX + equipmentId), action);
    }

    @Override
    public <T> T executeWindow(Long equipmentId, Instant startTime, Instant endTime, Supplier<T> action) {
        return executeKeys(ReservationLockKeys.forWindow(EQUIPMENT_PREFIX, equipmentId, startTime, endTime), action);
    }

    @Override
    public <T> T executeUser(Long userId, Supplier<T> action) {
        return executeKeys(List.of("user:" + userId), action);
    }

    private <T> T executeKeys(List<String> keys, Supplier<T> action) {
        List<ReentrantLock> acquired = new ArrayList<>(keys.size());
        try {
            for (String key : keys) {
                ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock(true));
                lock.lock();
                acquired.add(lock);
            }
            return action.get();
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }
}
