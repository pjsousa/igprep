# Kafka Transactions: Atomically Producing to Multiple Partitions

## Introduction

In the previous post, we saw how Kafka's idempotent producer eliminates duplicate writes within a single partition by assigning each producer a unique PID and tracking per-partition sequence numbers. But the idempotent producer has a fundamental limitation: it only guarantees exactly-once delivery *within a single partition*. Real financial systems don't work that way — a single price tick might need to land in multiple topics simultaneously: a live price topic, an audit topic, a risk-engine topic. If any of those writes fails while the others succeed, you have an inconsistent state. That's not acceptable in a trading firm.

Kafka transactions solve exactly this problem. They extend the exactly-once guarantee across multiple partitions and topics, allowing a producer to treat a multi-partition write as a single atomic unit.

## The Limitation of Idempotent Producers

Before transactions, the idempotent producer gave you:

```
Producer PID=42, Partition=0, Seq=5 → exactly-once on partition 0
```

But if your application needs to write to partition 0 AND partition 1 AND a different topic entirely, each write is independent. If the broker for partition 1 crashes after accepting the write but before the write to partition 0 is committed, you have a split-brain state: partition 0 has the record, partition 1 doesn't.

Idempotent producers don't solve this. They only deduplicate retries for a single partition.

## The Transactional API

Kafka transactions are built on a two-phase commit protocol, exposed through a straightforward Java API:

```java
KafkaProducer<String, PriceTick> producer = new KafkaProducer<>(props);

producer.initTransactions();

try {
    producer.beginTransaction();

    producer.send(recordPricess, (metadata, exception) -> { /* ... */ });
    producer.send(recordAudit, (metadata, exception) -> { /* ... */ });

    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
    // retry or propagate
}
```

The key methods are:
- `initTransactions()` — initialises the transactional state, must be called once before any transaction.
- `beginTransaction()` — starts a new transaction boundary.
- `send()` — queues records; they are not visible to consumers until `commitTransaction()`.
- `commitTransaction()` — atomically commits all records sent since `beginTransaction()`.
- `abortTransaction()` — discards all records sent since `beginTransaction()`.

## How It Works Under the Hood

The magic sits in the **Transaction Coordinator**, a special broker-side component that manages the lifecycle of each transaction.

### Phase 1: Begin

When `beginTransaction()` is called, the producer sends a `InitProducerId` request to the Transaction Coordinator. The coordinator records the PID's transactional state as `InTransaction` and returns a `producerEpoch` — a fencing token that increments on each new transaction. This epoch is how Kafka fences zombie producers (instances of the same `transactional.id` that survived a crash and are still trying to write).

### Phase 2: Send

As records are sent, the producer batches them per partition. Crucially, the records are not yet committed. The producer is writing to the broker as normal, but the broker marks these records as "uncommitted" — invisible to consumers using `read_committed` isolation.

### Phase 3: Commit or Abort

When `commitTransaction()` is called, the producer sends a `EndTransaction` request to the Transaction Coordinator with the list of partitions involved. The coordinator writes a **commit marker** to an internal topic called `__transaction_state`. This is Kafka's own internal transactional log — it stores the outcome of every transaction. Once the commit marker is successfully replicated, the records become visible to consumers.

If `abortTransaction()` is called, an abort marker is written instead.

## The `__transaction_state` Topic

This internal topic is the backbone of Kafka transactions. It's a compacted topic (more on this in Post 3.4) with entries keyed by `transactional.id`. Each entry records:
- The `producerEpoch`
- The set of partitions involved
- The transaction outcome (commit or abort)

Because this topic is replicated and compacted, Kafka can recover the state of any transaction even if the Transaction Coordinator itself crashes and a new one is elected. This is how Kafka achieves durability for transactions without a traditional two-phase commit with a coordinator that could be a single point of failure.

## Consumer Isolation: `read_committed`

On the consumer side, the `isolation.level` setting controls what you see:

```java
props.setProperty("isolation.level", "read_committed");
```

With `read_committed`, the consumer's poll loop will never return records from a transaction that hasn't been committed. This is critical for the risk engine example: you absolutely do not want to see a price tick in the audit topic that hasn't also landed in the live price topic — that would be a compliance gap.

With `read_uncommitted` (the default), consumers see all records as soon as they are written, regardless of transaction state.

## The Fencing Mechanism

One of the subtlest and most important safety features is producer fencing. Consider this failure scenario:

1. Producer with `transactional.id=trader-1` sends `beginTransaction()`.
2. The producer crashes before `commitTransaction()`.
3. A new producer instance starts with the same `transactional.id`.
4. The new producer calls `initTransactions()`, gets a new `producerEpoch` (higher than the crashed instance's epoch).
5. Any attempt by the zombie producer to send using the old epoch is rejected by the broker.

This prevents the zombie producer from writing a conflicting transaction. The new producer's epoch acts as a total ordering mechanism for all transactions from that `transactional.id`.

## Performance Trade-offs

Transactional producers are not free. Each `commitTransaction()` call involves a round-trip to the Transaction Coordinator and a write to `__transaction_state`. In a high-throughput price-streaming system — potentially hundreds of thousands of messages per second — this cost matters.

In practice, you wouldn't wrap every single price tick in its own transaction. That would be catastrophic for throughput. Instead, batch multiple ticks into a single transaction and commit on a cadence (e.g., every 100ms). This gives you near-exactly-once semantics with manageable overhead.

There's also a throughput cost on the consumer side: `read_committed` consumers must skip uncommitted records, which adds complexity to the poll loop.

## A Worked Example

```java
public class PriceTickProducer {
    private final KafkaProducer<String, PriceTick> producer;
    private final String transactionalId;

    public PriceTickProducer(KafkaProducer<String, PriceTick> producer, String transactionalId) {
        this.producer = producer;
        this.transactionalId = transactionalId;
    }

    public void publishPriceAndAudit(PriceTick tick) {
        producer.initTransactions();

        ProducerRecord<String, PriceTick> priceRecord =
            new ProducerRecord<>("prices", tick.getInstrumentId(), tick);
        ProducerRecord<String, PriceTick> auditRecord =
            new ProducerRecord<>("price-audit", tick.getInstrumentId(), tick);

        producer.beginTransaction();
        try {
            producer.send(priceRecord);
            producer.send(auditRecord);
            producer.commitTransaction();
        } catch (KafkaException e) {
            producer.abortTransaction();
            throw e;
        }
    }
}
```

In a trading context, the audit topic is a regulatory requirement — every price tick must be auditable. Using a transaction ensures both writes succeed or both fail. There's no risk of a price appearing in `prices` without a corresponding entry in `price-audit`.

## Why This Matters in Production

Without Kafka transactions, a trading system that writes to multiple topics has to manage partial failure manually — typically with a distributed transaction pattern (saga) or a write-ahead log. Both are complex and error-prone. Kafka transactions provide a clean, broker-supported mechanism for atomic multi-partition writes.

In production, you'd also monitor the transaction abort rate. If `transaction.error.ratio` is elevated, it typically indicates one of two things: either the downstream consumers are too slow (causing `transaction.timeout.ms` to expire), or there's genuine contention on a shared resource. Both are actionable signals — the first is an ops problem, the second is a design problem.

## Summary

Kafka transactions extend the idempotent producer's deduplication across partition boundaries using a two-phase commit protocol managed by the Transaction Coordinator. The key concepts are:

- **Transaction Coordinator** — broker component that manages transaction boundaries.
- **`__transaction_state`** — internal compacted topic storing transaction outcomes.
- **`transactional.id`** — ties a producer's sessions together, enabling fencing of zombies.
- **`producerEpoch`** — fencing token incremented on each new transaction.
- **`isolation.level=read_committed`** — consumer-side setting to only read committed records.

Transactions are not free — they add latency per commit and require careful tuning of batch size and commit cadence. But for financial pipelines where correctness is non-negotiable, the cost is justified.

In the next post, we'll look at exactly-once semantics end-to-end: how idempotent producers + transactions + `read_committed` consumers combine to give you the full EOS guarantee.
