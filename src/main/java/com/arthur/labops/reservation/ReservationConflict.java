package com.arthur.labops.reservation;

import java.time.Instant;

/**
 * A slot-holding reservation that overlaps a pending one, shown next to the
 * pending request so the approver sees the clash before pressing approve.
 *
 * @param requesterName {@code null} when the viewer is a student looking at
 *                      someone else's booking
 */
public record ReservationConflict(
        Long id,
        Instant startTime,
        Instant endTime,
        ReservationStatus status,
        String requesterName
) {
}
