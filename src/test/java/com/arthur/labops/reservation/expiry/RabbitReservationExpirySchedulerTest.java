package com.arthur.labops.reservation.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Verifies the O(1) time-bucketed delay ladder topology, idempotent queue declaration and recovery,
 * lazy-disarm no-op cancel, and backwards-compatible payload encoding.
 */
class RabbitReservationExpirySchedulerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-26T10:00:00Z");

    private RecordingAmqpAdmin amqpAdmin;
    private CapturingRabbitTemplate rabbitTemplate;
    private RabbitExpiryTopologyProperties topology;
    private RabbitReservationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        amqpAdmin = new RecordingAmqpAdmin();
        rabbitTemplate = new CapturingRabbitTemplate();
        topology = new RabbitExpiryTopologyProperties();
        scheduler = new RabbitReservationExpiryScheduler(
                amqpAdmin,
                rabbitTemplate,
                topology,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void longThenShortDelayUseSeparateFixedBucketQueuesWithUniformTtl() {
        Instant longExpiry = FIXED_NOW.plusSeconds(900); // 900s -> 900_000ms bucket
        Instant shortExpiry = FIXED_NOW.plusSeconds(12); // 12s -> 5_000ms bucket

        scheduler.schedule(101L, longExpiry);
        scheduler.schedule(202L, shortExpiry);

        assertThat(amqpAdmin.declaredQueues).hasSize(2);

        Queue longQueue = amqpAdmin.declaredQueues.get(0);
        Queue shortQueue = amqpAdmin.declaredQueues.get(1);

        assertThat(longQueue.getName()).isEqualTo(topology.bucketQueueName(900_000L));
        assertThat(shortQueue.getName()).isEqualTo(topology.bucketQueueName(5_000L));
        assertThat(longQueue.getName()).isNotEqualTo(shortQueue.getName());

        long longTtl = ((Number) longQueue.getArguments().get("x-message-ttl")).longValue();
        long shortTtl = ((Number) shortQueue.getArguments().get("x-message-ttl")).longValue();
        assertThat(longTtl).isEqualTo(900_000L);
        assertThat(shortTtl).isEqualTo(5_000L);

        assertThat(longQueue.getArguments().get("x-dead-letter-exchange"))
                .isEqualTo(RabbitReservationExpiryConfiguration.EXPIRY_EXCHANGE);
        assertThat(shortQueue.getArguments().get("x-dead-letter-routing-key"))
                .isEqualTo(RabbitReservationExpiryConfiguration.EXPIRY_ROUTING_KEY);

        // Shared O(1) bucket queues persist without x-expires.
        assertThat(longQueue.getArguments()).doesNotContainKey("x-expires");
        assertThat(shortQueue.getArguments()).doesNotContainKey("x-expires");
        assertThat(longQueue.isDurable()).isTrue();
        assertThat(longQueue.isExclusive()).isFalse();
        assertThat(longQueue.isAutoDelete()).isFalse();

        // Messages carry target deadline epoch ms.
        assertThat(rabbitTemplate.sends).hasSize(2);
        assertThat(rabbitTemplate.sends.get(0).exchange()).isEmpty();
        assertThat(rabbitTemplate.sends.get(0).routingKey()).isEqualTo(longQueue.getName());
        assertThat(rabbitTemplate.sends.get(0).body())
                .isEqualTo("101@" + longExpiry.toEpochMilli());
        assertThat(rabbitTemplate.sends.get(1).routingKey()).isEqualTo(shortQueue.getName());
        assertThat(rabbitTemplate.sends.get(1).body())
                .isEqualTo("202@" + shortExpiry.toEpochMilli());

        assertThat(rabbitTemplate.sends)
                .noneMatch(s -> RabbitReservationExpiryConfiguration.LEGACY_SHARED_DELAY_QUEUE.equals(s.routingKey()));
    }

    @Test
    void repeatedDeclarationsKeepTotalBrokerQueuesBoundedByLadderSize() {
        for (int i = 1; i <= 50; i++) {
            scheduler.schedule(ReservationDeadlineKind.APPROVAL, (long) i, FIXED_NOW.plusSeconds(15 + i));
        }

        // The declaration is idempotent: 50 publishes still use only two broker queues.
        assertThat(amqpAdmin.declaredQueues).hasSize(50);
        assertThat(amqpAdmin.byName.keySet()).containsExactlyInAnyOrder(
                topology.bucketQueueName(15_000L),
                topology.bucketQueueName(60_000L));
        assertThat(rabbitTemplate.sends).hasSize(50);
    }

    @Test
    void redeclaresBucketAfterBrokerLosesQueueWhileSchedulerRemainsAlive() {
        Instant deadline = FIXED_NOW.plusSeconds(900);
        String bucket = topology.bucketQueueName(900_000L);
        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 1L, deadline);
        amqpAdmin.byName.clear(); // simulate loss of broker topology
        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 2L, deadline);
        assertThat(amqpAdmin.byName).containsKey(bucket);
        assertThat(amqpAdmin.declaredQueues).hasSize(2);
        assertThat(rabbitTemplate.sends.getLast().routingKey()).isEqualTo(bucket);
    }

    @Test
    void subMinimumRemainingDelayUsesSmallestBucket() {
        Instant almostDue = FIXED_NOW.plusMillis(30);
        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 77L, almostDue);

        assertThat(amqpAdmin.declaredQueues).hasSize(1);
        assertThat(amqpAdmin.declaredQueues.get(0).getName()).isEqualTo(topology.bucketQueueName(100L));
        assertThat(rabbitTemplate.sends.get(0).routingKey()).isEqualTo(topology.bucketQueueName(100L));
        assertThat(rabbitTemplate.sends.get(0).body()).isEqualTo("77@" + almostDue.toEpochMilli());
    }

    @Test
    void dueOrPastDeadlineRoutesDirectlyToWorkExchangeWithoutDeclaringBucket() {
        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 11L, FIXED_NOW);
        scheduler.schedule(ReservationDeadlineKind.PAYMENT, 12L, FIXED_NOW.minusMillis(50));

        assertThat(amqpAdmin.declaredQueues).isEmpty();
        assertThat(rabbitTemplate.sends).hasSize(2);
        assertThat(rabbitTemplate.sends.get(0).exchange()).isEqualTo(topology.getExpiryExchange());
        assertThat(rabbitTemplate.sends.get(0).routingKey()).isEqualTo(topology.getExpiryRoutingKey());
        assertThat(rabbitTemplate.sends.get(0).body()).isEqualTo("11@" + FIXED_NOW.toEpochMilli());
        assertThat(rabbitTemplate.sends.get(1).exchange()).isEqualTo(topology.getExpiryExchange());
        assertThat(rabbitTemplate.sends.get(1).routingKey()).isEqualTo(topology.getExpiryRoutingKey());
        assertThat(rabbitTemplate.sends.get(1).body()).isEqualTo("PAYMENT:12@" + FIXED_NOW.minusMillis(50).toEpochMilli());
    }

    @Test
    void cancelIsNoOpAndNeverDeletesSharedBucketQueue() {
        Instant expiry = FIXED_NOW.plusSeconds(900);
        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 55L, expiry);
        String bucketQueue = topology.bucketQueueName(900_000L);
        assertThat(amqpAdmin.byName).containsKey(bucketQueue);

        scheduler.cancel(ReservationDeadlineKind.APPROVAL, 55L, expiry);

        assertThat(amqpAdmin.byName).containsKey(bucketQueue);
        assertThat(amqpAdmin.deletedQueues).isEmpty();
    }

    @Test
    void paymentDeadlineCarriesTaggedPayloadWithTargetTimestamp() {
        Instant deadline = FIXED_NOW.plusSeconds(600); // 600s -> 300_000ms bucket
        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 8L, deadline);
        scheduler.schedule(ReservationDeadlineKind.PAYMENT, 8L, deadline);

        // Both land in the same O(1) 300_000ms bucket; redeclaration is idempotent.
        assertThat(amqpAdmin.declaredQueues).hasSize(2);
        assertThat(amqpAdmin.byName).hasSize(1);
        assertThat(amqpAdmin.declaredQueues.get(0).getName()).isEqualTo(topology.bucketQueueName(300_000L));

        assertThat(rabbitTemplate.sends.get(0).body()).isEqualTo("8@" + deadline.toEpochMilli());
        assertThat(rabbitTemplate.sends.get(1).body()).isEqualTo("PAYMENT:8@" + deadline.toEpochMilli());
    }

    @Test
    void payloadCodecSupportsVersionedAndLegacyFormats() {
        Instant deadline = Instant.ofEpochMilli(1_732_600_000_000L);

        assertThat(ReservationDeadlinePayload.encode(ReservationDeadlineKind.APPROVAL, 123L, deadline))
                .isEqualTo("123@1732600000000");
        assertThat(ReservationDeadlinePayload.encode(ReservationDeadlineKind.PAYMENT, 123L, deadline))
                .isEqualTo("PAYMENT:123@1732600000000");

        assertThat(ReservationDeadlinePayload.decode("123@1732600000000"))
                .isEqualTo(new ReservationDeadlinePayload.Decoded(
                        ReservationDeadlineKind.APPROVAL, 123L, 1_732_600_000_000L));
        assertThat(ReservationDeadlinePayload.decode("PAYMENT:123@1732600000000"))
                .isEqualTo(new ReservationDeadlinePayload.Decoded(
                        ReservationDeadlineKind.PAYMENT, 123L, 1_732_600_000_000L));

        // Legacy bare formats still decode with null deadlineEpochMs.
        assertThat(ReservationDeadlinePayload.decode("123"))
                .isEqualTo(new ReservationDeadlinePayload.Decoded(ReservationDeadlineKind.APPROVAL, 123L, null));
        assertThat(ReservationDeadlinePayload.decode("PAYMENT:123"))
                .isEqualTo(new ReservationDeadlinePayload.Decoded(ReservationDeadlineKind.PAYMENT, 123L, null));

        // Malformed payloads return null.
        assertThat(ReservationDeadlinePayload.decode("123@")).isNull();
        assertThat(ReservationDeadlinePayload.decode("123@not-a-ts")).isNull();
        assertThat(ReservationDeadlinePayload.decode("123@1@2")).isNull();
        assertThat(ReservationDeadlinePayload.decode("UNKNOWN:123@1000")).isNull();
    }

    private record Send(String exchange, String routingKey, String body) {
    }

    private static final class CapturingRabbitTemplate extends RabbitTemplate {
        private final List<Send> sends = new ArrayList<>();

        CapturingRabbitTemplate() {
            super((ConnectionFactory) null);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            sends.add(new Send(exchange, routingKey, String.valueOf(object)));
        }
    }

    private static final class RecordingAmqpAdmin implements AmqpAdmin {
        private final List<Queue> declaredQueues = new ArrayList<>();
        private final List<String> deletedQueues = new ArrayList<>();
        private final Map<String, Queue> byName = new ConcurrentHashMap<>();

        @Override
        public void declareExchange(Exchange exchange) {
        }

        @Override
        public boolean deleteExchange(String exchangeName) {
            return false;
        }

        @Override
        public Queue declareQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String declareQueue(Queue queue) {
            declaredQueues.add(queue);
            byName.put(queue.getName(), queue);
            return queue.getName();
        }

        @Override
        public boolean deleteQueue(String queueName) {
            deletedQueues.add(queueName);
            return byName.remove(queueName) != null;
        }

        @Override
        public void deleteQueue(String queueName, boolean unused, boolean empty) {
        }

        @Override
        public void purgeQueue(String queueName, boolean noWait) {
        }

        @Override
        public int purgeQueue(String queueName) {
            return 0;
        }

        @Override
        public void declareBinding(Binding binding) {
        }

        @Override
        public void removeBinding(Binding binding) {
        }

        @Override
        public Properties getQueueProperties(String queueName) {
            return null;
        }

        @Override
        public QueueInformation getQueueInfo(String queueName) {
            return null;
        }
    }
}
