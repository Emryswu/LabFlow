package com.arthur.labops.payment.channel;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.arthur.labops.payment.channel.SimulatedChannelProperties.CallbackMode;

/**
 * In-repo stand-in for a payment gateway. It never talks to anything external:
 * it keeps its own ledger, decides when to fire callbacks, and can produce a
 * T+1 settlement file from that ledger.
 *
 * <p>Everything that would be non-deterministic in a real integration is a
 * parameter here — callback timing ({@link CallbackMode}), duplicate delivery
 * ({@link #redeliverAll()}), lost delivery ({@link #discardPending()}), transport
 * failure ({@link #failNextOutbound(int)}) and a terminal channel rejection
 * ({@link #rejectNextOutboundFinal(int)}). The last two are deliberately
 * different: a transport exception leaves the outcome unknown, while a terminal
 * rejection says with certainty that no money moved.
 *
 * <p>The ledger lives in memory. That is a deliberate limit of the simulator:
 * it models "the channel remembers what we do not", which is all reconciliation
 * needs, without pretending to be a durable third party.
 */
@Component
public class SimulatedPaymentChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentChannel.class);

    private final SimulatedChannelProperties properties;
    private final ChannelCallbackSink callbackSink;
    private final TaskScheduler taskScheduler;

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger failNextOutbound = new AtomicInteger();
    private final AtomicInteger rejectNextOutboundFinal = new AtomicInteger();
    private final List<ChannelEntry> ledger = new ArrayList<>();
    private final Map<String, ChannelEntry> byMerchantKey = new HashMap<>();
    private final Map<String, ChannelCallback> rejectedByMerchantKey = new HashMap<>();
    private final Deque<ChannelCallback> pending = new ArrayDeque<>();
    private final List<ChannelCallback> delivered = new ArrayList<>();

    public SimulatedPaymentChannel(SimulatedChannelProperties properties,
                                   ChannelCallbackSink callbackSink,
                                   TaskScheduler taskScheduler) {
        this.properties = properties;
        this.callbackSink = callbackSink;
        this.taskScheduler = taskScheduler;
    }

    /**
     * Charge without a merchant key — models an action taken at the channel's own
     * end (an operator issuing a refund, a payer completing a payment in the
     * channel's app). Each call is a distinct transaction, as it would be.
     */
    public ChannelEntry charge(String orderNo, long amountCents) {
        return record(orderNo, ChannelEntryType.PAYMENT, amountCents, null);
    }

    public ChannelEntry refund(String orderNo, long amountCents) {
        return record(orderNo, ChannelEntryType.REFUND, amountCents, null);
    }

    /**
     * Charge under a merchant idempotency key. Presenting the same key again
     * returns the transaction already created for it instead of creating a
     * second one.
     */
    public ChannelEntry charge(String orderNo, long amountCents, String merchantIdempotencyKey) {
        return record(orderNo, ChannelEntryType.PAYMENT, amountCents, merchantIdempotencyKey);
    }

    public ChannelEntry refund(String orderNo, long amountCents, String merchantIdempotencyKey) {
        return record(orderNo, ChannelEntryType.REFUND, amountCents, merchantIdempotencyKey);
    }

    /**
     * Test seam for an unknown outcome: the call throws before the platform knows
     * whether the channel accepted it. Retrying must therefore use the same key.
     */
    public void failNextOutbound(int count) {
        failNextOutbound.set(count);
    }

    /**
     * Test seam for a known terminal outcome. The channel sends a FAILED callback
     * for the attempt but writes no money entry. Retrying the intent is allowed,
     * but only under a fresh channel-facing key because this key has received a
     * final answer.
     */
    public void rejectNextOutboundFinal(int count) {
        rejectNextOutboundFinal.set(count);
    }

    private ChannelEntry record(String orderNo, ChannelEntryType type, long amountCents,
                                String merchantIdempotencyKey) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("渠道交易金额必须为正数");
        }

        RecordedOutcome outcome = recordSynchronized(orderNo, type, amountCents, merchantIdempotencyKey);
        if (outcome.replayed()) {
            return outcome.entry();
        }
        logRecordedOutcome(type, orderNo, amountCents, outcome);
        scheduleOrDispatch(outcome.callback());
        return outcome.entry();
    }

    private synchronized RecordedOutcome recordSynchronized(String orderNo, ChannelEntryType type,
                                                            long amountCents, String merchantIdempotencyKey) {
        if (merchantIdempotencyKey != null) {
            RecordedOutcome existing = lookupExistingOutcome(merchantIdempotencyKey);
            if (existing != null) {
                return existing;
            }
        }
        if (failNextOutbound.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
            log.warn("Simulated channel transport failure {} orderNo={} (injected)", type, orderNo);
            throw new IllegalStateException("渠道暂时不可用（注入的故障）");
        }

        RecordedOutcome outcome = createOutcome(orderNo, type, amountCents, merchantIdempotencyKey, Instant.now());
        if (properties.getCallbackMode() != CallbackMode.IMMEDIATE) {
            pending.addLast(outcome.callback());
        }
        return outcome;
    }

    private RecordedOutcome lookupExistingOutcome(String merchantIdempotencyKey) {
        ChannelEntry existing = byMerchantKey.get(merchantIdempotencyKey);
        if (existing != null) {
            log.info("Simulated channel returning existing transaction for merchant key={} channelTxnId={}",
                    merchantIdempotencyKey, existing.channelTxnId());
            return RecordedOutcome.replayed(existing);
        }
        if (rejectedByMerchantKey.containsKey(merchantIdempotencyKey)) {
            log.info("Simulated channel returning existing terminal rejection for merchant key={}",
                    merchantIdempotencyKey);
            return RecordedOutcome.replayed(null);
        }
        return null;
    }

    private RecordedOutcome createOutcome(String orderNo, ChannelEntryType type, long amountCents,
                                          String merchantIdempotencyKey, Instant now) {
        String channelTxnId = "CH-" + type.name().charAt(0) + "-" + sequence.incrementAndGet();
        if (rejectNextOutboundFinal.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
            ChannelCallback callback = ChannelCallback.rejected(
                    orderNo, type, amountCents, merchantIdempotencyKey, channelTxnId, now);
            if (merchantIdempotencyKey != null) {
                rejectedByMerchantKey.put(merchantIdempotencyKey, callback);
            }
            return new RecordedOutcome(null, callback, true, false);
        }

        ChannelEntry entry = new ChannelEntry(
                channelTxnId, orderNo, type, amountCents, ChannelEntry.STATUS_SUCCESS, now);
        ledger.add(entry);
        if (merchantIdempotencyKey != null) {
            byMerchantKey.put(merchantIdempotencyKey, entry);
        }
        return new RecordedOutcome(entry, ChannelCallback.of(entry, merchantIdempotencyKey), false, false);
    }

    private static void logRecordedOutcome(ChannelEntryType type, String orderNo, long amountCents,
                                           RecordedOutcome outcome) {
        if (outcome.terminalRejection()) {
            log.info("Simulated channel terminally rejected {} orderNo={} amountCents={} channelTxnId={}",
                    type, orderNo, amountCents, outcome.callback().channelTxnId());
        } else {
            log.info("Simulated channel recorded {} orderNo={} amountCents={} channelTxnId={}",
                    type, orderNo, amountCents, outcome.entry().channelTxnId());
        }
    }

    private void scheduleOrDispatch(ChannelCallback callback) {
        switch (properties.getCallbackMode()) {
            case IMMEDIATE -> dispatch(callback);
            case DELAYED -> taskScheduler.schedule(
                    this::deliverPending, Instant.now().plus(properties.getCallbackDelay()));
            case MANUAL -> log.info("Simulated channel holding callback channelTxnId={} status={} (MANUAL mode)",
                    callback.channelTxnId(), callback.status());
        }
    }

    private record RecordedOutcome(ChannelEntry entry, ChannelCallback callback,
                                   boolean terminalRejection, boolean replayed) {
        static RecordedOutcome replayed(ChannelEntry entry) {
            return new RecordedOutcome(entry, null, false, true);
        }
    }

    /** Delivers every queued callback. No-op in {@link CallbackMode#IMMEDIATE}. */
    public int deliverPending() {
        List<ChannelCallback> batch = drainPending();
        batch.forEach(this::dispatch);
        return batch.size();
    }

    /**
     * Replays every callback the channel has already delivered. This is what a
     * real gateway does when it does not see a timely acknowledgement.
     */
    public int redeliverAll() {
        List<ChannelCallback> replay;
        synchronized (this) {
            replay = new ArrayList<>(delivered);
        }
        replay.forEach(this::dispatch);
        return replay.size();
    }

    /**
     * Throws away callbacks the channel has queued but not delivered. Successful
     * money movements stay in the channel ledger; terminal rejections never had a
     * money row to begin with.
     */
    public int discardPending() {
        List<ChannelCallback> dropped = drainPending();
        if (!dropped.isEmpty()) {
            log.warn("Simulated channel discarded {} undelivered callback(s)", dropped.size());
        }
        return dropped.size();
    }

    private synchronized List<ChannelCallback> drainPending() {
        List<ChannelCallback> batch = new ArrayList<>(pending);
        pending.clear();
        return batch;
    }

    private void dispatch(ChannelCallback callback) {
        synchronized (this) {
            delivered.add(callback);
        }
        try {
            callbackSink.deliver(callback);
        } catch (RuntimeException exception) {
            // A real gateway does not roll its own books back because our endpoint
            // blew up. If money moved, reconciliation finds the gap; if this was a
            // rejection, a later redelivery carries the same attempt identity.
            log.warn("Simulated channel callback delivery failed channelTxnId={} orderNo={}",
                    callback.channelTxnId(), callback.orderNo(), exception);
        }
    }

    public synchronized List<ChannelEntry> ledger() {
        return List.copyOf(ledger);
    }

    public synchronized List<ChannelEntry> entriesFor(LocalDate settlementDate) {
        Instant from = settlementDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = settlementDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return ledger.stream()
                .filter(entry -> !entry.occurredAt().isBefore(from) && entry.occurredAt().isBefore(to))
                .toList();
    }

    /** Writes the T+1 settlement file for {@code settlementDate}. */
    public Path writeDailyBill(LocalDate settlementDate) {
        Path target = billPath(settlementDate);
        ChannelBillFile.write(target, entriesFor(settlementDate));
        log.info("Simulated channel wrote settlement file date={} path={}", settlementDate, target);
        return target;
    }

    public Path billPath(LocalDate settlementDate) {
        return Path.of(properties.getBillDirectory(), "channel-bill-" + settlementDate + ".csv");
    }

    /** Test seam: forget this channel's books. Never called by application code. */
    public synchronized void reset() {
        ledger.clear();
        byMerchantKey.clear();
        rejectedByMerchantKey.clear();
        pending.clear();
        delivered.clear();
        failNextOutbound.set(0);
        rejectNextOutboundFinal.set(0);
    }
}
