package com.arthur.labops.reservation.expiry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Schedules reservation deadlines via an <strong>O(1) fixed time-bucket delay ladder</strong>
 * with queue-level TTL + dead-lettering to the shared expiry work queue.
 *
 * <p>Unlike per-reservation private queues (O(N) broker queues) or a single FIFO queue
 * with per-message expiration (HOL blocking), each fixed bucket queue holds messages
 * sharing one uniform {@code x-message-ttl}. Payloads carry their target epoch millisecond
 * so the consumer cascades multi-step delays and lazily drops already-settled deadlines.
 */
@Component
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "rabbit")
public class RabbitReservationExpiryScheduler implements ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RabbitReservationExpiryScheduler.class);

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitExpiryTopologyProperties topology;
    private final Clock clock;

    @Autowired
    public RabbitReservationExpiryScheduler(AmqpAdmin amqpAdmin,
                                            RabbitTemplate rabbitTemplate,
                                            RabbitExpiryTopologyProperties topology) {
        this(amqpAdmin, rabbitTemplate, topology, Clock.systemUTC());
    }

    RabbitReservationExpiryScheduler(AmqpAdmin amqpAdmin,
                                     RabbitTemplate rabbitTemplate,
                                     RabbitExpiryTopologyProperties topology,
                                     Clock clock) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.topology = topology;
        this.clock = clock;
    }

    @Override
    public void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        String body = ReservationDeadlinePayload.encode(kind, reservationId, deadline);
        if (deadline == null) {
            publishImmediate(kind, reservationId, null, body);
            return;
        }
        long remainingMs = Duration.between(clock.instant(), deadline).toMillis();
        if (remainingMs <= 0L) {
            publishImmediate(kind, reservationId, deadline, body);
            return;
        }

        long bucketMs = topology.selectBucketMs(remainingMs);
        String bucketQueue = topology.bucketQueueName(bucketMs);
        ensureBucketDeclared(bucketQueue, bucketMs);

        // Default exchange routes directly to the shared fixed-TTL bucket queue.
        rabbitTemplate.convertAndSend("", bucketQueue, body);
        log.info(
                "RabbitMQ deadline scheduled kind={} reservationId={} remainingMs={} bucketMs={} deadline={} bucketQueue={} dlx={}",
                kind,
                reservationId,
                remainingMs,
                bucketMs,
                deadline,
                bucketQueue,
                topology.getExpiryExchange());
    }

    /**
     * Lightweight no-op under the shared O(1) bucket topology.
     *
     * <p>Shared bucket queues must never be deleted on individual cancellations.
     * Settled reservations are lazily dropped on the next bucket hop (before
     * re-scheduling) and remain guarded by entity state checks on final firing.
     */
    @Override
    public void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        log.debug("Lazy-disarm no-op for shared bucket deadline kind={} reservationId={} deadline={}",
                kind, reservationId, deadline);
    }

    private void publishImmediate(ReservationDeadlineKind kind, Long reservationId, Instant deadline, String body) {
        rabbitTemplate.convertAndSend(topology.getExpiryExchange(), topology.getExpiryRoutingKey(), body);
        log.info("RabbitMQ deadline already due, routed immediately kind={} reservationId={} deadline={} exchange={} routingKey={}",
                kind, reservationId, deadline, topology.getExpiryExchange(), topology.getExpiryRoutingKey());
    }

    private void ensureBucketDeclared(String queueName, long bucketMs) {
        // A process-local declaration cache cannot prove that the broker still
        // has the queue after a restart, replacement, or administrative deletion.
        // Queue declaration is idempotent; declare before routing each message.
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", bucketMs);
        args.put("x-dead-letter-exchange", topology.getExpiryExchange());
        args.put("x-dead-letter-routing-key", topology.getExpiryRoutingKey());
        Queue queue = new Queue(queueName, true, false, false, args);
        amqpAdmin.declareQueue(queue);
    }
}
