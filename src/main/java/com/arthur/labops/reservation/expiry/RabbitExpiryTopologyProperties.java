package com.arthur.labops.reservation.expiry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ topology names and fixed time-bucket ladder for reservation expiry.
 * Overridable in tests so HOL integration tests do not share the production
 * work queue consumer.
 */
@ConfigurationProperties(prefix = "labops.reservation-expiry.rabbit")
public class RabbitExpiryTopologyProperties {

    public static final String DEFAULT_DELAY_QUEUE_PREFIX = "labops.reservation.expiry.delay.";
    public static final String DEFAULT_PAYMENT_DELAY_QUEUE_PREFIX = "labops.reservation.payment.delay.";
    public static final String DEFAULT_EXPIRY_EXCHANGE = "labops.reservation.expiry.exchange";
    public static final String DEFAULT_EXPIRY_QUEUE = "labops.reservation.expiry.queue";
    public static final String DEFAULT_EXPIRY_ROUTING_KEY = "reservation.expire";
    public static final String LEGACY_SHARED_DELAY_QUEUE = "labops.reservation.expiry.delay.queue";
    public static final String LEGACY_DELAY_EXCHANGE = "labops.reservation.expiry.delay.exchange";

    /**
     * Fixed ladder of delay buckets (in milliseconds), giving O(1) total broker
     * queues while keeping each queue's TTL uniform (no FIFO HOL blocking).
     */
    public static final List<Long> DEFAULT_DELAY_BUCKETS_MS = List.of(
            100L, 500L, 1_000L, 5_000L, 15_000L, 60_000L, 300_000L, 900_000L, 3_600_000L);

    private String delayQueuePrefix = DEFAULT_DELAY_QUEUE_PREFIX;
    private String paymentDelayQueuePrefix = DEFAULT_PAYMENT_DELAY_QUEUE_PREFIX;
    private String expiryExchange = DEFAULT_EXPIRY_EXCHANGE;
    private String expiryQueue = DEFAULT_EXPIRY_QUEUE;
    private String expiryRoutingKey = DEFAULT_EXPIRY_ROUTING_KEY;
    private List<Long> delayBucketsMs = new ArrayList<>(DEFAULT_DELAY_BUCKETS_MS);

    public String getDelayQueuePrefix() {
        return delayQueuePrefix;
    }

    public void setDelayQueuePrefix(String delayQueuePrefix) {
        this.delayQueuePrefix = delayQueuePrefix;
    }

    public String getPaymentDelayQueuePrefix() {
        return paymentDelayQueuePrefix;
    }

    public void setPaymentDelayQueuePrefix(String paymentDelayQueuePrefix) {
        this.paymentDelayQueuePrefix = paymentDelayQueuePrefix;
    }

    public String getExpiryExchange() {
        return expiryExchange;
    }

    public void setExpiryExchange(String expiryExchange) {
        this.expiryExchange = expiryExchange;
    }

    public String getExpiryQueue() {
        return expiryQueue;
    }

    public void setExpiryQueue(String expiryQueue) {
        this.expiryQueue = expiryQueue;
    }

    public String getExpiryRoutingKey() {
        return expiryRoutingKey;
    }

    public void setExpiryRoutingKey(String expiryRoutingKey) {
        this.expiryRoutingKey = expiryRoutingKey;
    }

    public List<Long> getDelayBucketsMs() {
        return Collections.unmodifiableList(delayBucketsMs);
    }

    public void setDelayBucketsMs(List<Long> delayBucketsMs) {
        this.delayBucketsMs = normalizeBuckets(delayBucketsMs);
    }

    public String bucketQueueName(long bucketMs) {
        return delayQueuePrefix + "bucket." + bucketMs + "ms";
    }

    /**
     * Selects the largest configured bucket {@code <= remainingMs}, or the smallest
     * bucket when {@code remainingMs} is smaller than every configured bucket.
     */
    public long selectBucketMs(long remainingMs) {
        List<Long> buckets = delayBucketsMs;
        long selected = buckets.get(0);
        for (long bucket : buckets) {
            if (bucket <= remainingMs) {
                selected = bucket;
            } else {
                break;
            }
        }
        return selected;
    }

    @Deprecated
    public String delayQueueName(long reservationId, long expiresAtEpochMs) {
        return delayQueueName(ReservationDeadlineKind.APPROVAL, reservationId, expiresAtEpochMs);
    }

    /**
     * @deprecated Replaced by O(1) time-bucketed shared delay queues ({@link #bucketQueueName(long)}).
     */
    @Deprecated
    public String delayQueueName(ReservationDeadlineKind kind, long reservationId, long deadlineEpochMs) {
        String prefix = kind == ReservationDeadlineKind.PAYMENT ? paymentDelayQueuePrefix : delayQueuePrefix;
        return prefix + reservationId + "." + deadlineEpochMs;
    }

    private static List<Long> normalizeBuckets(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>(DEFAULT_DELAY_BUCKETS_MS);
        }
        TreeSet<Long> sorted = new TreeSet<>();
        for (Long value : raw) {
            if (value != null && value > 0) {
                sorted.add(value);
            }
        }
        return sorted.isEmpty() ? new ArrayList<>(DEFAULT_DELAY_BUCKETS_MS) : new ArrayList<>(sorted);
    }
}
