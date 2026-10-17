package com.arthur.labops.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Turns raw timestamps into one count per calendar day.
 *
 * <p>The day boundary is the server's local midnight, not UTC midnight. Doing
 * this in Java instead of {@code GROUP BY DATE(created_at)} keeps H2 and MySQL
 * on the same answer: both would cut days in the session time zone, which is
 * UTC for the stored instants and would shift every evening reservation in
 * China onto the next day.
 */
final class DailyBuckets {

    private DailyBuckets() {
    }

    /** Days run from {@code firstDay} for {@code days} days; anything outside is ignored. */
    static List<Long> count(Collection<Instant> timestamps, LocalDate firstDay, int days, ZoneId zone) {
        long[] counts = new long[days];
        for (Instant timestamp : timestamps) {
            if (timestamp == null) {
                continue;
            }
            long index = ChronoUnit.DAYS.between(firstDay, timestamp.atZone(zone).toLocalDate());
            if (index >= 0 && index < days) {
                counts[(int) index]++;
            }
        }
        return Arrays.stream(counts).boxed().toList();
    }

    static List<LocalDate> days(LocalDate firstDay, int days) {
        return firstDay.datesUntil(firstDay.plusDays(days)).toList();
    }
}
