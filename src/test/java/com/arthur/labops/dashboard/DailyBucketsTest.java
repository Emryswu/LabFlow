package com.arthur.labops.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class DailyBucketsTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 4);

    @Test
    void cutsDaysAtLocalMidnightNotUtcMidnight() {
        // 15:59:59Z is 23:59:59 on Oct 10 in Shanghai; one second later is already Oct 11.
        List<Long> counts = DailyBuckets.count(List.of(
                Instant.parse("2026-10-10T15:59:59Z"),
                Instant.parse("2026-10-10T16:00:00Z")), FIRST_DAY, 14, SHANGHAI);

        assertThat(counts.get(6)).isEqualTo(1);
        assertThat(counts.get(7)).isEqualTo(1);
    }

    @Test
    void ignoresTimestampsOutsideTheWindowAndNulls() {
        List<Long> counts = DailyBuckets.count(Arrays.asList(
                Instant.parse("2026-10-03T15:59:59Z"), // Oct 3 local: day before the window
                Instant.parse("2026-10-03T16:00:00Z"), // Oct 4 local: first day
                Instant.parse("2026-10-17T15:59:59Z"), // Oct 17 local: last day
                Instant.parse("2026-10-17T16:00:00Z"), // Oct 18 local: after the window
                null), FIRST_DAY, 14, SHANGHAI);

        assertThat(counts).hasSize(14);
        assertThat(counts.get(0)).isEqualTo(1);
        assertThat(counts.get(13)).isEqualTo(1);
        assertThat(counts.stream().mapToLong(Long::longValue).sum()).isEqualTo(2);
    }

    @Test
    void daysListsEveryDateOldestFirst() {
        assertThat(DailyBuckets.days(FIRST_DAY, 14))
                .hasSize(14)
                .startsWith(FIRST_DAY)
                .endsWith(LocalDate.of(2026, 10, 17));
    }
}
