package com.arthur.labops.reservation.expiry;

import java.time.Instant;

/**
 * Wire format for a delay message.
 *
 * <p>Formats:
 * <ul>
 *   <li>Approval with target deadline: {@code 123@1732600000000}</li>
 *   <li>Payment with target deadline: {@code PAYMENT:123@1732600000000}</li>
 *   <li>Legacy untagged / undated payloads ({@code 123}, {@code PAYMENT:123})
 *       decode with {@code deadlineEpochMs == null} and fire immediately.</li>
 * </ul>
 */
public final class ReservationDeadlinePayload {

    private ReservationDeadlinePayload() {
    }

    public static String encode(ReservationDeadlineKind kind, Long reservationId) {
        return kind == ReservationDeadlineKind.APPROVAL
                ? reservationId.toString()
                : kind.name() + ":" + reservationId;
    }

    public static String encode(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        String base = encode(kind, reservationId);
        return deadline == null ? base : base + "@" + deadline.toEpochMilli();
    }

    /** @return the decoded deadline, or null when the payload is unusable */
    public static Decoded decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String trimmed = payload.trim();
        int separator = trimmed.indexOf(':');
        ReservationDeadlineKind kind = ReservationDeadlineKind.APPROVAL;
        String rest = trimmed;
        if (separator >= 0) {
            try {
                kind = ReservationDeadlineKind.valueOf(trimmed.substring(0, separator));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            rest = trimmed.substring(separator + 1);
        }

        int atIndex = rest.indexOf('@');
        String idPart = rest;
        Long deadlineEpochMs = null;
        if (atIndex >= 0) {
            idPart = rest.substring(0, atIndex);
            String timestampPart = rest.substring(atIndex + 1);
            if (timestampPart.isEmpty()) {
                return null;
            }
            try {
                deadlineEpochMs = Long.valueOf(timestampPart);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        try {
            return new Decoded(kind, Long.valueOf(idPart), deadlineEpochMs);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Decoded(ReservationDeadlineKind kind, Long reservationId, Long deadlineEpochMs) {

        public Decoded(ReservationDeadlineKind kind, Long reservationId) {
            this(kind, reservationId, null);
        }

        public Instant deadlineInstant() {
            return deadlineEpochMs == null ? null : Instant.ofEpochMilli(deadlineEpochMs);
        }
    }
}
