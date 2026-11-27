package com.arthur.labops.reservation.lock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes deterministic, ascending UTC-day shard keys for an equipment window.
 */
final class ReservationLockKeys {

    static final int MAX_SHARDED_DAYS = 31;

    private ReservationLockKeys() {
    }

    static String equipmentKey(String equipmentPrefix, Long equipmentId) {
        Objects.requireNonNull(equipmentPrefix, "equipmentPrefix must not be null");
        Objects.requireNonNull(equipmentId, "equipmentId must not be null");
        return equipmentPrefix + equipmentId;
    }

    static String userKey(String userPrefix, Long userId) {
        Objects.requireNonNull(userPrefix, "userPrefix must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        return userPrefix + userId;
    }

    static List<String> forWindow(String equipmentPrefix, Long equipmentId, Instant startTime, Instant endTime) {
        String equipmentKey = equipmentKey(equipmentPrefix, equipmentId);
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return List.of(equipmentKey);
        }
        long startDay = LocalDate.ofInstant(startTime, ZoneOffset.UTC).toEpochDay();
        // Half-open [startTime, endTime): an endTime exactly at 00:00:00Z belongs to the preceding day.
        Instant endInclusive = endTime.minusNanos(1L);
        long endDay = LocalDate.ofInstant(endInclusive, ZoneOffset.UTC).toEpochDay();
        long spanDays = endDay - startDay + 1L;
        if (spanDays <= 0L || spanDays > MAX_SHARDED_DAYS) {
            return List.of(equipmentKey);
        }
        List<String> keys = new ArrayList<>((int) spanDays);
        for (long day = startDay; day <= endDay; day++) {
            keys.add(equipmentKey + ":day:" + day);
        }
        return keys;
    }
}
