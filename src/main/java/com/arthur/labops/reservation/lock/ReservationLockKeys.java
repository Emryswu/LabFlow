package com.arthur.labops.reservation.lock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes deterministic, ascending UTC-day shard keys for an equipment window.
 */
final class ReservationLockKeys {

    static final int MAX_SHARDED_DAYS = 31;

    private ReservationLockKeys() {
    }

    static List<String> forWindow(String equipmentPrefix, Long equipmentId, Instant startTime, Instant endTime) {
        if (equipmentId == null || startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return List.of(equipmentPrefix + equipmentId);
        }
        long startDay = LocalDate.ofInstant(startTime, ZoneOffset.UTC).toEpochDay();
        // Half-open [startTime, endTime): an endTime exactly at 00:00:00Z belongs to the preceding day.
        Instant endInclusive = endTime.minusNanos(1L);
        long endDay = LocalDate.ofInstant(endInclusive, ZoneOffset.UTC).toEpochDay();
        long spanDays = endDay - startDay + 1L;
        if (spanDays <= 0L || spanDays > MAX_SHARDED_DAYS) {
            return List.of(equipmentPrefix + equipmentId);
        }
        List<String> keys = new ArrayList<>((int) spanDays);
        for (long day = startDay; day <= endDay; day++) {
            keys.add(equipmentPrefix + equipmentId + ":day:" + day);
        }
        return keys;
    }
}
