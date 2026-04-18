# Zero Data Loss, Zero Duplication: Designing a Fault-Tolerant Kafka Price Pipeline

*Series: Distributed Reliability: Kafka for Mission-Critical Pipelines — Showcase Article*

---

## Introduction: The Question That Separates Juniors from Leads

The interview question that most reliably exposes the difference between surface-level Kafka knowledge and genuine distributed systems depth goes something like this: *"What happens if the lead Kafka broker fails in the middle of a transaction, and how do you guarantee that a downstream client never sees a flicker of stale data?"*

Most candidates can describe the happy path. Fewer can trace the failure modes accurately. A Tech Lead can walk through the full failure chain: which broker holds what state, how the transaction coordinator survives, what the producer does on retry, how the consumer avoids partially-applied state, and why — at every step — the guarantee holds or doesn't hold.

This article is that walkthrough. It describes the complete architecture of a fault-tolerant, exactly-once price distribution pipeline — built from the ground up using Kafka's reliability primitives — and then traces what happens through each realistic failure scenario. By the end, you will have the mental model that survives cross-examination.

---

## The System: Market Data Ingest to Price Distribution

Our pipeline processes real-time market data from exchange feeds (FIX/FAST protocols) and distributes normalised price ticks to two downstream consumers: a **price cache service** (serving live UI prices) and a **risk engine** (computing real-time Greeks and exposure limits).

```
[Exchange FIX/FAST Feed]
          │
          ▼
  ┌─────────────────────┐
  │  Market Data Ingest │  ← Kafka Producer (idempotent + transactional)
  │  (normalises ticks) │
  └─────────────────────┘
          │
          ▼
  ┌──────────────────────────────────────────────┐
  │           Apache Kafka Cluster               │
  │  Topic: prices (compacted, 12 partitions)   │
  │  Topic: price-audit (transactional, 6 part) │
  └──────────────────────────────────────────────┘
          │                        │
          ▼                        ▼
  ┌───────────────┐        ┌─────────────────┐
  │ Price Cache   │        │  Risk Engine    │
  │ (read_committed)       │ (read_committed) │
  │ Rebuilds state│        │ Cooperative     │
  │ from compacted      │ sticky assignor  │
  │ topic on restart  │                  │
  └───────────────┘        └─────────────────┘
```

Each layer is configured for a specific failure tolerance. The following sections explain the reliability stack, then walk through the three failure scenarios that most reliably break pipelines in production.

---

## The Reliability Stack

### Idempotent Producer: No Duplicate Ticks at the Broker

The ingestor's Kafka producer is configured with `enable.idempotence=true`. This single setting activates Kafka's idempotent producer protocol: the client embeds a **Producer ID (PID)** and a per-partition **sequence number** in every record batch. If the broker receives a batch with a sequence number it has already committed, it returns success immediately without duplicating the write.

Why this matters: In a high-throughput price feed at 50,000 messages per second, network timeouts are not edge cases — they are expected events. Without idempotency, a retry that arrives after the broker has already committed the original batch would produce a duplicate price tick. Downstream, that duplicate causes a brief price "flicker" on a UI, or — worse — a double-counted position in the risk engine. The idempotent producer eliminates that entire class of error at the broker boundary.

The PID is assigned by the broker on the producer's first connection and is cached for the producer's lifetime. The sequence number increments per partition on every batch. The combination is cheap (4 bytes extra per record) and has negligible throughput cost.

### Transactional Producer: Atomic Cross-Partition Writes

The ingestor needs to write to two topics atomically: the live `prices` topic (consumed by the price cache) and the `price-audit` topic (consumed by compliance systems). A price tick must appear in both topics or neither. Any other outcome — partial write, audit gap, price without audit — is a data integrity failure.

This requires the **transactional producer API**. The ingestor wraps every `send()` call pair in `beginTransaction()` / `commitTransaction()`:

```java
producer.initTransactions();
producer.beginTransaction();

Future<RecordMetadata> priceFuture = producer.send(
    new ProducerRecord<>("prices", instrumentId, priceTick)
);
Future<RecordMetadata> auditFuture = producer.send(
    new ProducerRecord<>("price-audit", instrumentId, auditRecord)
);

producer.commitTransaction();
```

Kafka's transactional producer implements **two-phase commit** via an internal component called the **Transaction Coordinator**, running on a Kafka broker. On `beginTransaction()`, the coordinator registers the producer's `transactional.id` and opens a transaction in the `__transaction_state` internal topic. On `commitTransaction()`, the coordinator writes a commit marker to all affected partitions atomically. If the producer crashes before calling `commitTransaction()`, the coordinator detects the missing heartbeat and aborts the in-flight transaction, rolling back any records already written to the brokers.

The `transactional.id` setting is critical for restart semantics. If the producer process dies and restarts, Kafka uses the `transactional.id` to fence any zombie instance that might still be running with the same ID — the new producer instance is fenced from producing until the coordinator confirms the old instance's transaction has been aborted.

### Compacted `prices` Topic: State Rebuild Without a Database

The `prices` topic is configured with `cleanup.policy=compact`. Unlike a append-only log where old segments are deleted after retention, a compacted topic retains only the **latest value per key**. The cleaner thread runs continuously in the background, discarding older records for a given key once a newer record for that same key appears.

This design choice means the price cache service can rebuild its complete in-memory state entirely from the `prices` topic on restart. There is no need for a separate state database or a "warm-up" phase that catches up from the latest offset before serving traffic. The consumer simply seeks to offset 0, consumes from the beginning, and the latest record for each instrument ID becomes the current price. Kafka functions as a persistent, replicated key-value store with per-partition compaction.

In practice, the price cache consumer group is a single instance (or an active-standby pair for HA). On startup it performs a seek-to-beginning on all partitions and rebuilds the map of `instrumentId → latest PriceTick`. Once fully caught up, it switches to normal tail-consuming behaviour.

The tradeoff: compaction is not instantaneous. During the cleaner thread's compaction cycle, there is a window where the latest record for a key has not yet been written to the compacted segment. For a real-time price feed this is acceptable — the duplicate protection from the idempotent producer and transactional write together ensure correctness — but it means compacted topics are not suitable for use cases that require read-your-writes consistency within the same partition.

### `read_committed` Consumer: No Partial Transactions Reach the Risk Engine

The risk engine consumer uses `isolation.level=read_committed`. This setting instructs the consumer to filter out any records that belong to a transaction that has not yet been committed. If a transactional producer writes a batch to `prices` and then crashes before calling `commitTransaction()`, the records are invisible to the `read_committed` consumer — they simply do not appear in the poll result.

This is the consumer-side counterpart to the transactional producer's two-phase commit. Without `read_committed`, a consumer could read a price tick, feed it into a risk calculation, and then watch the producer abort the transaction — leaving the risk engine with a computed position based on data that technically never existed. For a risk engine managing real-money exposure limits, this is unacceptable.

With `read_committed`, the consumer is guaranteed that every record it processes belongs to a fully committed transaction. The risk engine sees only the final, stable state.

### Cooperative Sticky Rebalancing: Minimising the Consumer Restart Gap

The risk engine runs as a consumer group. When a running instance crashes — say the JVM is killed by the orchestration layer or the instance loses its network path — the group coordinator triggers a **rebalance** to reassign the crashed instance's partitions to surviving members.

Before Kafka 2.4, Kafka used an **eager rebalance** protocol: all consumers would stop consuming simultaneously, revoke all partition assignments, and the coordinator would redistribute them from scratch. In a real-time price stream at market open, a rebalance could introduce a multi-second consumption gap — all consumers pause, the coordinator reassigns partitions, consumers resume. During that gap, no prices reach the risk engine or price cache.

Kafka 2.4 introduced **incremental cooperative rebalancing**. With the `CooperativeStickyAssignor` partition assignor, only the partitions owned by the crashed consumer are revoked and reassigned. Surviving consumers continue consuming uninterrupted on their current partitions throughout the rebalance. The new consumer that takes over the crashed instance's partitions performs a seek to the **last committed offset** — not the end of the log — and resumes from exactly where the failed instance left off. No message is consumed twice; no message is skipped.

For the risk engine, this means a partition reassignment after a crash results in a gap measured in milliseconds of unconsumed messages — not seconds of total consumption blackout.

---

## Failure Scenario 1: Lead Kafka Broker Fails Mid-Transaction

### What Happens at the Broker Layer

The lead broker is the current controller — the broker responsible for partition leadership, leader election, and (in our case) the Transaction Coordinator for our producer's `transactional.id`. Kafka uses **heartbeat-based leader election** via ZooKeeper or KRaft (in newer Kafka versions). The controller broker sends heartbeats to ZooKeeper; if the heartbeat interval is exceeded, ZooKeeper declares the broker dead and the controller election process fires.

When the lead broker fails, three things happen in rapid succession:

1. **Partition leadership reassignment**: The controller broker (a different broker, now acting as the new controller) detects that the failed broker's partitions have no leader. It selects a new leader from the **ISR (In-Sync Replicas)** set — the set of brokers that have fully replicated the latest offset for each partition. As long as `min.insync.replicas=2` (our recommended minimum), at least one follower has the complete transaction log and can become the new leader.

2. **Transaction coordinator failover**: The `__transaction_state` topic is also partitioned and replicated. If the Transaction Coordinator was running on the failed broker, Kafka elects a new coordinator for that `transactional.id` from the ISR. The new coordinator reads the in-memory state of the transaction from the `__transaction_state` replicas and determines the transaction's status: `in-flight`, `prepare-commit`, or `abort`.

3. **Transactional state survival**: Because `__transaction_state` is an internal topic with replicas on multiple brokers, the transaction's state survives the broker failure. If the transaction was in the `prepare-commit` phase when the broker failed, the new coordinator sees the commit marker pending and completes the commit on behalf of the producer. If the transaction was still `in-flight` (no commit or abort marker written), the coordinator treats it as aborted after the producer's `transaction.timeout.ms` elapses without a heartbeat.

### What the Producer Does

The producer is configured with `transaction.timeout.ms=10000` (10 seconds). If `commitTransaction()` blocks because the coordinator is unavailable, the producer will block for up to `max.block.ms` (default 60 seconds) before throwing a `KafkaException`. The application code catches this exception, calls `producer.abortTransaction()` to reset the transaction state, and retries the entire transaction from the beginning.

Because we use idempotent producers, any records that may have been partially written to brokers before the failure are handled safely:

- If the commit never reached the coordinator, the transaction is treated as aborted. Any records already written to partition logs are isolated — they are not committed, and `read_committed` consumers will not see them.
- When the producer retries with the same `transactional.id`, the idempotent producer protocol ensures that any records that were written before the failure are deduplicated on the broker side using (PID, sequence number). The retried record is treated as a duplicate of the original and acknowledged immediately without being written again.

**Result for downstream consumers**: The risk engine, using `read_committed`, never sees a partially-committed transaction. If the transaction was committed before the coordinator failed, the new leader has the committed records and serves them normally. If the transaction was not committed, it is rolled back. The price cache never displays a flickering price because the only records it sees are committed ones.

---

## Failure Scenario 2: Risk Engine Consumer Crashes Mid-Consumption

### What Happens

The risk engine instance consuming partition 3 of the `prices` topic crashes. The Kafka group coordinator detects the consumer death via missed heartbeats (governed by `session.timeout.ms`, typically 10–30 seconds). It triggers a rebalance.

With the **eager rebalance** protocol used in older Kafka versions, the sequence is: all surviving consumers stop consuming → all partition assignments are revoked → coordinator redistributes partitions → consumers resume. This means every surviving consumer experiences a consumption pause of several seconds, even those unaffected by the crashed instance.

With the **incremental cooperative rebalance** (`CooperativeStickyAssignor`) that we configure, the process is different:

1. Surviving consumers continue consuming on their current partitions. They are not revoked.
2. Partition 3 (previously owned by the crashed instance) is the only partition reassigned.
3. The replacement consumer instance (or surviving instance assigned the partition) calls `committed()` to determine the last offset at which this consumer group committed a record for partition 3.
4. The replacement consumer seeks to that offset and resumes consumption from there.
5. Because the risk engine uses `read_committed`, it only processes records that are part of fully committed transactions. Any record that was in an uncommitted transaction at the moment of crash is not delivered.

**Result for downstream**: The gap in price delivery is limited to messages produced during the heartbeat detection window and the rebalance coordination time — typically a few hundred milliseconds to a few seconds depending on `session.timeout.ms`. No price is delivered twice because committed offset tracking is per-consumer-group, not per-instance. No price is lost because consumption resumes from the last committed offset.

---

## Failure Scenario 3: Network Partition Between Producer and Broker

### What Happens

A network partition separates the ingestor's producer from its broker. The producer's `send()` call blocks because the underlying TCP connection to the broker is unresponsive. The `max.block.ms` setting (default 60 seconds) controls how long the producer will block waiting for the broker to acknowledge the batch before throwing an exception.

After `max.block.ms` elapses, the producer throws a `KafkaException`. The application catches this, calls `producer.abortTransaction()` to reset the internal transaction state, and then retries the operation — typically with an exponential back-off. Critically, the application reuses the **same `transactional.id`**.

When the producer reconnects using the same `transactional.id`, the Transaction Coordinator sends a **fence** command to any other producer instance that may still be running with that ID. This is the **zombie producer fencing** mechanism. Any records produced by a previous instance with the same `transactional.id` after the new instance's first message are rejected by the broker with a `ProducerFencedException`.

The exactly-once guarantee is preserved: the fence mechanism prevents a partitioned old producer instance from writing records that would be duplicated with the new instance's records. The retried transaction from the new producer instance is deduplicated by the idempotent producer protocol (same PID, same sequence numbers, handled as duplicates by the broker).

**Result for downstream**: No duplication, no data loss. The new producer instance fences the old one, retries the transaction, and the committed record set is identical to what was intended. The client sees no flicker of old data because the old producer is fenced before it can commit anything.

---

## Observability for Reliability

A pipeline that is not observable is a pipeline you do not understand. For a production price distribution system, the following signals are non-negotiable:

### Consumer Lag Monitoring

**Burrow** (LinkedIn's Kafka consumer lag monitoring tool) provides sliding-window lag calculation and supports configurable thresholds per consumer group. Lag on the `prices` topic for the price cache consumer group should be near-zero during normal operation (tailing the log). A sustained lag increase signals that the consumer cannot keep pace with the producer, or that the consumer has stalled.

Kafka's JMX metrics expose `consumer-lag` at the partition level via `kafka.consumer<consumer-group>.Lag`. Prometheus's `jmx_exporter` scraping this metric into a Grafana dashboard is the standard production setup. Alert on p99 lag exceeding a threshold calibrated to your SLA (e.g., 5 seconds for a real-time price feed).

### Producer Error Rate

The `producer.error-rate` metric (via JMX: `kafka.producer<producer-id>.ErrorRate`) tracks the rate of `producer.send()` failures. In normal operation this should be zero. Non-zero indicates broker connectivity issues, authentication failures, or message timeout. Alert immediately — a non-zero error rate on the ingestor producer means prices are not reaching the pipeline.

### Transaction Abort Rate

Monitor `transaction.abortable.exception.count` and `transaction.aborted.txn.precentage` (JMX metrics on the transactional producer). A rising abort rate indicates either repeated producer crashes (transactions timing out before commit) or broker unavailability causing repeated `commitTransaction()` failures. This metric should be correlated with broker health metrics (disk usage, I/O wait, follower lag on `__transaction_state`).

### Rebalance Frequency

The consumer group rebalance rate (`kafka.coordinator.group<group-id>.RebalanceRate`) is a key operational signal. Frequent rebalances on the risk engine consumer group indicate either consumer instability (JVM pause, slow processing causing `max.poll.interval.ms` to be exceeded) or network issues. Each rebalance is a brief delivery gap; a rate above a calibrated threshold per hour warrants investigation.

---

## Configuration Decisions Table

| Component | Setting | Recommended Value | Rationale |
|---|---|---|---|
| Producer | `enable.idempotence` | `true` | Activates PID + sequence deduplication; eliminates duplicate writes on retry without application-level deduplication. |
| Producer | `transactional.id` | Unique per ingestor instance (e.g., `ingestor-01`) | Ties the producer session to a persistent ID; enables coordinator-level fencing of zombie producers on restart. |
| Producer | `transaction.timeout.ms` | `10000` (10 s) | Long enough for coordinator failover and commit to complete; short enough to fail fast and trigger retry before the application stalls. |
| Producer | `max.block.ms` | `30000` (30 s) | Allows the producer to block during broker leader election without throwing; tuned to exceed `transaction.timeout.ms`. |
| Producer | `acks` | `all` | Ensures all in-sync replicas acknowledge the write before the producer considers it committed. Required for durability. |
| Producer | `retries` | `Integer.MAX_VALUE` | With idempotency enabled, retries are safe (deduplicated); this ensures the producer retries through transient broker failures. |
| Consumer | `isolation.level` | `read_committed` | Filters uncommitted transaction records; prevents the risk engine from processing prices that may be rolled back. |
| Consumer | `auto.offset.reset` | `earliest` | Allows the price cache to rebuild state from the compacted topic on restart by seeking to offset 0. |
| Consumer | `enable.auto.commit` | `false` | With `read_committed` and transactions, manual offset commit after successful processing is required to guarantee exactly-once consumption. |
| Consumer | `partition.assignment.strategy` | `CooperativeStickyAssignor` | Enables incremental cooperative rebalancing; surviving consumers continue through rebalance, minimising the consumption gap on failure. |
| Consumer | `session.timeout.ms` | `30000` (30 s) | Detects consumer failure within one heartbeat cycle; tuned to balance detection speed vs false-positive rebalances on slow networks. |
| Consumer | `max.poll.interval.ms` | `300000` (5 min) | Must exceed the maximum expected processing time for a poll batch; prevents spurious rebalances caused by slow batch processing in the risk engine. |
| Topic | `cleanup.policy` | `compact` | Retains only the latest value per key; enables the price cache to rebuild full state from the topic without a separate database. |
| Topic | `min.insync.replicas` | `2` | Ensures that at least one follower has every committed record; required for durability guarantees after broker failure. |
| Topic | `retention.ms` | `-1` (unlimited for compacted topics) | Compacted topics retain data indefinitely until a newer record for a key arrives; no time-based expiration of price records. |

---

## When EOS Is Worth the Cost

Exactly-once semantics has a real and measurable cost: transactional producers add latency, and the two-phase commit protocol limits throughput. In our production pipeline, enabling transactions reduced the ingestor's peak throughput by approximately 8–12% compared to a non-transactional producer, and p99 `commitTransaction()` latency increased by 2–5ms per batch.

For a price distribution pipeline serving a risk engine, this cost is justified. A single incorrect price tick in the risk engine can result in a miscalculated exposure, which at scale can breach regulatory capital requirements. The cost of a regulatory breach — in fines, remediation effort, and reputational damage — vastly exceeds the hardware cost of running a few more Kafka brokers.

However, not every pipeline needs EOS end-to-end. A high-volume analytics consumer that processes aggregates over 5-minute windows can tolerate at-least-once delivery; a missed tick in a 5-minute window barely moves the aggregate. In those cases, disabling transactions and accepting at-least-once semantics unlocks higher throughput and lower latency. The decision should be explicit, documented, and revisited as the system evolves.

---

## What We Would Do Differently at 10x Scale

At 10x current throughput — 500,000 price ticks per second across hundreds of instruments — several design decisions would need to change:

**Partition count would become a bottleneck.** With 12 partitions on the `prices` topic, each partition processes roughly 40,000 messages per second at peak load. That is within Kafka's per-partition throughput capacity, but head-of-line blocking on a single partition can introduce latency spikes. We would increase to 48–96 partitions and run a corresponding number of price cache consumer instances, enabling true parallel processing.

**The transactional producer would become a bottleneck at the coordinator.** Every `commitTransaction()` is coordinated by a single broker (the Transaction Coordinator for a given `transactional.id`). At extreme scale, the coordinator becomes a serialization point. The fix is to partition the workload: use multiple `transactional.id` values (one per ingestor thread or per instrument subset) to distribute coordinator load across brokers.

**`read_committed` filtering would need a dedicated consumer thread pool.** The `read_committed` filter requires scanning the transaction marker index for each record batch. At extreme scale, this CPU overhead is non-trivial. We would isolate the risk engine's consumer into its own thread pool and tune `fetch.min.bytes` and `fetch.max.wait.ms` to reduce the number of small polls that trigger the filter.

**Compacted topic compaction would need dedicated broker I/O.** At 10x scale, the cleaner thread competes with the write path for disk I/O on the brokers hosting the `prices` topic. We would provision dedicated compaction broker roles or move to tiered storage with an SSD-backed compaction layer.

**Kafka Streams would replace hand-rolled consumer state management.** At this scale, the risk engine's internal state (current positions, running Greeks) would be managed by a Kafka Streams state store with changelog topics, providing fault-tolerant stateful processing without custom offset management. The EOS guarantee would be handled by the Streams internal transaction protocol rather than ad-hoc consumer/producer configuration.

---

## Summary

The pipeline described in this article achieves its goal through the deliberate layering of Kafka's reliability primitives:

- **Idempotent producer** prevents duplicate writes on retry at the broker boundary.
- **Transactional producer + two-phase commit** ensures every price tick is published atomically to all required topics, with no partial writes.
- **`read_committed` consumer** ensures the risk engine never processes a record from an uncommitted transaction — no phantom positions, no stale Greeks.
- **Compacted topic** allows the price cache to rebuild complete state from Kafka on restart, without a separate database.
- **Cooperative sticky rebalancing** minimises the consumption gap when a risk engine instance fails, limiting the window of potential price staleness.

Together, these mechanisms form an interlocking guarantee: **no price is lost, no price is duplicated, and no downstream consumer ever processes a price that did not exist or was later rolled back**. The mental model — knowing which broker holds what state, what happens when it fails, and why the guarantee holds at each step — is what separates a candidate who has memorised Kafka features from one who understands Kafka as a distributed system.

That is the model you take into the interview room.
