package com.arthur.labops.reservation.expiry;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ delayed expiry topology (no delayed-message plugin required).
 *
 * <p>Design (O(1) time-bucketed delay ladder + cascading requeue + lazy disarm):
 * <ul>
 *   <li>A constant set of shared bucket queues ({@code {delayQueuePrefix}bucket.{bucketMs}ms}),
 *       each with a uniform queue-level {@code x-message-ttl} equal to {@code bucketMs}
 *       dead-lettering into the shared expiry work exchange/queue.</li>
 *   <li>Uniform TTL inside every bucket queue eliminates per-message FIFO head-of-line
 *       blocking while keeping total broker queue count constant {@code O(1)}.</li>
 *   <li>Payloads carry the target {@code deadlineEpochMs}. When a bucket expires early,
 *       the listener checks reservation state (lazy disarm if already settled) or cascades
 *       into the next finer bucket until {@code now >= deadline}.</li>
 *   <li>DB compensation scan remains as a safety net if broker/messages are lost.</li>
 * </ul>
 */
@Configuration
@EnableRabbit
@EnableConfigurationProperties(RabbitExpiryTopologyProperties.class)
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "rabbit")
public class RabbitReservationExpiryConfiguration {

    // Kept for log/assert compatibility with older docs and verify scripts.
    public static final String DELAY_QUEUE_PREFIX = RabbitExpiryTopologyProperties.DEFAULT_DELAY_QUEUE_PREFIX;
    public static final String LEGACY_SHARED_DELAY_QUEUE = RabbitExpiryTopologyProperties.LEGACY_SHARED_DELAY_QUEUE;
    public static final String LEGACY_DELAY_EXCHANGE = RabbitExpiryTopologyProperties.LEGACY_DELAY_EXCHANGE;
    public static final String EXPIRY_EXCHANGE = RabbitExpiryTopologyProperties.DEFAULT_EXPIRY_EXCHANGE;
    public static final String EXPIRY_QUEUE = RabbitExpiryTopologyProperties.DEFAULT_EXPIRY_QUEUE;
    public static final String EXPIRY_ROUTING_KEY = RabbitExpiryTopologyProperties.DEFAULT_EXPIRY_ROUTING_KEY;

    public static String bucketQueueName(long bucketMs) {
        return RabbitExpiryTopologyProperties.DEFAULT_DELAY_QUEUE_PREFIX + "bucket." + bucketMs + "ms";
    }

    @Deprecated
    public static String delayQueueName(long reservationId, long expiresAtEpochMs) {
        return RabbitExpiryTopologyProperties.DEFAULT_DELAY_QUEUE_PREFIX + reservationId + "." + expiresAtEpochMs;
    }

    @Bean
    DirectExchange reservationExpiryExchange(RabbitExpiryTopologyProperties topology) {
        return new DirectExchange(topology.getExpiryExchange(), true, false);
    }

    @Bean
    Queue reservationExpiryQueue(RabbitExpiryTopologyProperties topology) {
        return new Queue(topology.getExpiryQueue(), true);
    }

    @Bean
    Binding reservationExpiryBinding(Queue reservationExpiryQueue,
                                     DirectExchange reservationExpiryExchange,
                                     RabbitExpiryTopologyProperties topology) {
        return BindingBuilder.bind(reservationExpiryQueue)
                .to(reservationExpiryExchange)
                .with(topology.getExpiryRoutingKey());
    }
}
