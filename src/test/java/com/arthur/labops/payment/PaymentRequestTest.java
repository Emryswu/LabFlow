package com.arthur.labops.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class PaymentRequestTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    @Test
    void constructorRejectsInvalidArguments() {
        assertThatThrownBy(() -> new PaymentRequest(" ", "k1", PaymentTransactionType.PAYMENT, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRequest("LF00000001", "", PaymentTransactionType.PAYMENT, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRequest("LF00000001", "k1", null, 100))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRequest("LF00000001", "k1", PaymentTransactionType.PAYMENT, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRequest("LF00000001", "k1", PaymentTransactionType.REFUND, -50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markFailedTruncatesErrorAppliesExponentialBackoffAndAbandonsAtMaxAttempts() {
        PaymentRequest request = new PaymentRequest(
                "LF00000001", "LF00000001:PAY", PaymentTransactionType.PAYMENT, 5_000L);
        String oversized = "e".repeat(PaymentRequest.MAX_ERROR_LENGTH + 75);

        assertThat(request.markFailed(oversized, NOW)).isFalse();
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getLastError()).hasSize(PaymentRequest.MAX_ERROR_LENGTH);
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));

        assertThat(request.markFailed("err-2", NOW)).isFalse();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));

        for (int attempt = 3; attempt < PaymentRequest.MAX_ATTEMPTS; attempt++) {
            assertThat(request.markFailed("err-" + attempt, NOW)).isFalse();
        }
        assertThat(request.markFailed("final-err", NOW)).isTrue();
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.ABANDONED);
        assertThat(request.getAttempts()).isEqualTo(PaymentRequest.MAX_ATTEMPTS);
    }

    @Test
    void reopenAfterChannelRejectionIncrementsChannelKeyTruncatesAndRespectsBudget() {
        PaymentRequest request = new PaymentRequest(
                "LF00000002", "LF00000002:REF:CANCEL", PaymentTransactionType.REFUND, 2_000L);
        String oversized = "r".repeat(PaymentRequest.MAX_ERROR_LENGTH + 20);

        // From PENDING: consumes 1 attempt and advances channelAttempt to 1.
        assertThat(request.reopenAfterChannelRejection(oversized, NOW)).isTrue();
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getChannelAttempt()).isEqualTo(1);
        assertThat(request.channelKey()).isEqualTo("LF00000002:REF:CANCEL#1");
        assertThat(request.getLastError()).hasSize(PaymentRequest.MAX_ERROR_LENGTH);
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);

        // Burn remaining attempts via markFailed until 1 slot left, then reject one more time.
        for (int i = 2; i < PaymentRequest.MAX_ATTEMPTS; i++) {
            assertThat(request.markFailed("transport-" + i, NOW)).isFalse();
        }
        request.markSent(); // sets attempts = MAX_ATTEMPTS
        assertThat(request.reopenAfterChannelRejection("terminal rejection", NOW)).isFalse();
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.ABANDONED);

        // Terminal states cannot be reopened.
        assertThat(request.reopenAfterChannelRejection("late", NOW)).isFalse();
    }
}
