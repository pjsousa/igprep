# Compacted Topics: Kafka as a Latest-State Key-Value Store

In a price-streaming system, state is fluid. A price for instrument AAPL might be published a thousand times per second as the market moves. But a new consumer joining the system — whether a fresh instance of your price cache or a new risk engine — needs to know the *current* price for *every* instrument, not the last hour of price history. You could send the entire history on startup, but that is slow and wasteful. Kafka's compacted topics solve this elegantly: they let you treat a Kafka topic as a persistent, replayable key-value store where only the latest value per key is guaranteed to be available.

This is Post 4 in the series. Previous posts covered idempotency, transactional producers, and exactly-once semantics. If you have not read those, the core idea to carry forward is this: in a reliable price pipeline, every design decision is oriented around one question — what happens when a consumer crashes and restarts?

---

## Kafka's Default Retention: Deletion by Time or Size

Out of the box, Kafka retains messages in two ways: by time or by total partition size. A topic configured with `retention.ms=604800000` keeps messages for seven days. A topic configured with `retention.bytes=10737418240` keeps at most 10 GB per partition. Once either threshold is crossed, Kafka deletes entire log segments — the oldest ones first.

This model works well for event streams where history matters: audit logs, user action histories, order trails. But for a latest-state use case — "what is the current price of every instrument?" — it is the wrong tool. You do not need seven days of price history to bootstrap a price cache. You need one value per instrument: the most recent one.

Log compaction switches Kafka into a different operational mode entirely.

---

## Log Compaction: Retaining Only the Latest Value per Key

When a topic has cleanup policy `compact` (set via `cleanup.policy=compact`), Kafka's log cleaner thread runs in the background and rewrites the log to retain only the latest record for each key. Older records with the same key are discarded.

To understand how this works, picture a simplified log for an `instrument-prices` topic:

```
Offset 0: key=AAPL, value=142.50
Offset 1: key=GOOG, value=2800.00
Offset 2: key=AAPL, value=142.57
Offset 3: key=MSFT, value=410.20
Offset 4: key=AAPL, value=142.61
```

After compaction, the log becomes:

```
Offset 0: key=GOOG, value=2800.00   (latest for GOOG)
Offset 1: key=MSFT, value=410.20    (latest for MSFT)
Offset 2: key=AAPL, value=142.61    (latest for AAPL — offsets 0 and 2 are gone)
```

The cleaner thread scans the "dirty" portion of the log — the segment files that have received new writes since the last compaction run — and for each key it encounters, keeps only the most recent record. This is a memory-efficient operation: it does not rewrite the entire log, only the parts that have changed.

The compaction guarantee is explicit: **after compaction, a consumer reading the full topic from offset 0 will always see the latest value for every key that existed at the time of the last compaction run.**

---

## Tombstone Records: Deleting Keys

Sometimes a key should not just be updated — it should be deleted entirely. Perhaps an instrument has been delisted and should no longer appear in the price cache.

Kafka handles this with tombstone records. A record with a null value (a "tombstone") signals to the cleaner that this key should be removed. The cleaner does not remove it immediately — it keeps the tombstone record for a configurable `delete.retention.ms` period (default: 24 hours). This grace period exists because a consumer that was offline during the tombstone write needs time to catch up and learn that the key was deleted. Without this window, the consumer might replay the old value on restart.

After `delete.retention.ms` expires, the cleaner removes the tombstone and the key vanishes from the compacted topic entirely.

---

## The Cold-Start Use Case: Building a Price Cache Without a Database

The most compelling production use case for compacted topics in a trading system is state bootstrap. Consider the following architecture:

1. A market data ingestor subscribes to exchange FIX/FAST feeds, normalises them, and publishes each price tick to a `instrument-prices` topic — keyed by instrument ID.
2. A price cache service runs multiple consumer instances. It needs the current price for every instrument to serve downstream clients.
3. A new price cache instance starts up. It has no local state.

Without compacted topics, the new instance has two poor options: replay the entire retained history (slow, potentially days of data), or query an external database (adds a dependency, introduces latency). With a compacted topic, the new instance simply reads the topic from the beginning — but because compaction has already run, it sees exactly one record per instrument: the latest price. It can rebuild its in-memory cache in seconds.

This is a significant architectural simplification. You no longer need a write-through cache or a separate key-value store to hold current state. Kafka *is* your state store. If a consumer crashes and restarts, it replays and is immediately consistent with the latest prices — no cache warm-up protocol required.

---

## The Dirty Ratio Trade-Off

Compaction is not instantaneous. The cleaner thread runs on a schedule and only rewrites segments that have accumulated enough "dirty" records (records that are older than the compaction threshold). During the window between a new record being written and that record becoming part of a successful compaction pass, consumers may see older values alongside newer ones.

In practice, this window is small — typically minutes — but in a high-frequency trading context, "minutes" of stale price data is unacceptable. You should monitor the cleaner via the `KafkaLogCleanerManager` JMX metrics and track the "cleanable dirty ratio." If this ratio spikes, it indicates the cleaner is falling behind, which can happen if the topic write rate is extremely high or if the cleaner is under-resourced.

A related issue: if a consumer is too far behind (its current offset is older than the cleaner's progress), it will see a gap — some keys will be invisible to it because compaction has already removed the older records those offsets pointed to. This is why `delete.retention.ms` is critical: it gives lagging consumers time to catch up before tombstones are removed.

---

## Compacted Topics vs Snapshot + Delta

An alternative pattern is snapshot + delta: periodically persist a full snapshot of state to object storage (S3) and then stream deltas on top. On restart, load the snapshot and replay the deltas.

This pattern has advantages at very large scale — snapshots can be distributed across a cluster in ways that compacted topics cannot. But for most trading system use cases, compacted topics are simpler and offer stronger guarantees with less infrastructure. You do not need to manage a separate snapshot schedule, coordinate snapshot versions, or handle partial delta replay failures. The compacted topic is the snapshot.

The trade-off is operational: compaction adds CPU overhead on the brokers, and the dirty ratio window introduces a small but real consistency lag. For a price cache serving downstream clients, this lag is usually acceptable — downstream clients expect eventual consistency from a Kafka consumer anyway.

---

## Why This Matters in Production

Compacted topics are the mechanism that lets Kafka serve double duty as both an event bus and a state store. For a Tech Lead designing a price distribution system, this is a fundamental building block:

A new service instance can join the cluster and immediately have correct state — no external cache, no database warm-up query, no special boot protocol. A crashed consumer can restart and catch up without coordination from the broker. And because compaction retains the latest value per key indefinitely (subject only to your broker disk capacity), the topic itself becomes a durable, replayable record of current state.

The pitfall to avoid is treating compaction as a replacement for understanding your consumer lag. If a consumer falls behind by more than `delete.retention.ms`, it will lose deletion tombstones and may replay stale values on restart. Monitoring consumer lag via Burrow or Kafka's `consumer-lag` metric is not optional — it is the safety net that makes compacted topics safe to rely on.

In the next post, we will look at consumer group rebalancing — what happens when a consumer dies and its partitions need to be reassigned. Combined with compacted topics, these two mechanisms form the foundation of a self-healing, stateful price distribution pipeline.