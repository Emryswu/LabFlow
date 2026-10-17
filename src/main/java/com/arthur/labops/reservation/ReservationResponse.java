package com.arthur.labops.reservation;

import java.time.Instant;
import java.util.List;

public record ReservationResponse(
        Long id,
        Long equipmentId,
        Long requesterId,
        String requesterName,
        String purpose,
        Instant startTime,
        Instant endTime,
        ReservationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant paymentDeadline,
        /**
         * Only for PENDING reservations, and only filled by the list endpoint:
         * slot-holding reservations this request overlaps. Non-empty means
         * approving it now would be rejected with RESERVATION_CONFLICT.
         */
        List<ReservationConflict> conflictsWith
) {
    static ReservationResponse from(Reservation reservation) {
        return from(reservation, List.of());
    }

    static ReservationResponse from(Reservation reservation, List<ReservationConflict> conflictsWith) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEquipment().getId(),
                reservation.getRequesterId(),
                reservation.getRequesterName(),
                reservation.getPurpose(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getExpiresAt(),
                reservation.getPaymentDeadline(),
                conflictsWith);
    }
}
