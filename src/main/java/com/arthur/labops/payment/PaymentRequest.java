package com.arthur.labops.payment;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One request the platform makes to the channel, and its delivery state.
 *
 * <p>{@code idempotencyKey} names the <em>intent</em>, not the attempt: "the full
 * payment for order X", "the cancellation refund for order X". Attempts use
 * {@link #channelKey()}, which changes only after the channel gives a definitive
 * rejection. A transport failure keeps the same channel key because the outcome
 * is unknown and a fresh key could turn uncertainty into a duplicate payment.
 */
@Entity
@Table(name = "payment_requests")
public class PaymentRequest {

    /** Cap on attempts before a human is asked to look. */
    static final int MAX_ATTEMPTS = 8;
    static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_BACKOFF_SHIFT = 20;

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentTransactionType type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentRequestStatus status;

    @Column(nullable = false)
    private int attempts;

    /**
     * How many times the channel has given this intent a final rejection. Only
     * this counter changes the key presented to the channel; a local send failure
     * leaves it alone, because we do not know whether the channel received that
     * attempt and must ask again under the same key.
     */
    @Column(name = "channel_attempt", nullable = false)
    private int channelAttempt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentRequest() {
    }

    public PaymentRequest(String orderNo, String idempotencyKey, PaymentTransactionType type, long amountCents) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        this.orderNo = orderNo;
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.amountCents = amountCents;
        this.status = PaymentRequestStatus.PENDING;
        this.nextAttemptAt = Instant.now();
        this.createdAt = this.nextAttemptAt;
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public PaymentTransactionType getType() { return type; }
    public long getAmountCents() { return amountCents; }
    public PaymentRequestStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getChannelAttempt() { return channelAttempt; }

    public String channelKey() {
        return channelAttempt == 0 ? idempotencyKey : idempotencyKey + "#" + channelAttempt;
    }

    public boolean matchesChannelKey(String expectedChannelKey) {
        return expectedChannelKey != null && channelKey().equals(expectedChannelKey);
    }

    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }

    /** Nothing further will be sent for this request, whatever the reason. */
    public boolean isSettled() {
        return status == PaymentRequestStatus.SENT
                || status == PaymentRequestStatus.ABANDONED
                || status == PaymentRequestStatus.OBSOLETE;
    }

    public boolean markObsolete() {
        if (isSettled()) {
            return false;
        }
        this.status = PaymentRequestStatus.OBSOLETE;
        this.updatedAt = Instant.now();
        return true;
    }

    /**
     * A definitive FAILED outcome belongs to one channel attempt. It may arrive
     * after that attempt was marked SENT, synchronously while it is still PENDING,
     * or after a transport exception left it FAILED. All three mean the same
     * thing once the channel answers: that attempt moved no money, so the intent
     * remains owed under a fresh channel key.
     *
     * <p>Only PENDING has not already charged the physical attempt to the retry
     * budget. SENT was counted by markSent and FAILED by markFailed, so counting
     * either of those again here would burn two retry slots for one channel call.
     *
     * @return true when queued for another channel attempt; false when the intent
     *         was already terminal or the retry budget is exhausted
     */
    public boolean reopenAfterChannelRejection(String reason) {
        return reopenAfterChannelRejection(reason, Instant.now());
    }

    boolean reopenAfterChannelRejection(String reason, Instant now) {
        if (status == PaymentRequestStatus.ABANDONED || status == PaymentRequestStatus.OBSOLETE) {
            return false;
        }
        if (status != PaymentRequestStatus.PENDING
                && status != PaymentRequestStatus.FAILED
                && status != PaymentRequestStatus.SENT) {
            return false;
        }
        if (status == PaymentRequestStatus.PENDING) {
            this.attempts += 1;
        }
        this.channelAttempt += 1;
        return !recordFailureState(reason, now, false);
    }

    public void markSent() {
        this.status = PaymentRequestStatus.SENT;
        this.attempts += 1;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    /** @return true when this attempt exhausted the retry budget */
    public boolean markFailed(String error) {
        return markFailed(error, Instant.now());
    }

    boolean markFailed(String error, Instant now) {
        this.attempts += 1;
        return recordFailureState(error, now, true);
    }

    private boolean recordFailureState(String error, Instant now, boolean applyBackoff) {
        this.lastError = truncateError(error);
        this.updatedAt = now;
        if (attempts >= MAX_ATTEMPTS) {
            this.status = PaymentRequestStatus.ABANDONED;
            return true;
        }
        this.status = PaymentRequestStatus.FAILED;
        if (applyBackoff) {
            long backoffMillis = Math.min(
                    MAX_BACKOFF.toMillis(),
                    BASE_BACKOFF.toMillis() * (1L << Math.min(attempts - 1, MAX_BACKOFF_SHIFT)));
            this.nextAttemptAt = now.plusMillis(backoffMillis);
        } else {
            this.nextAttemptAt = now;
        }
        return false;
    }

    private static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
