# Exactly-Once Semantics in Kafka: The Full End-to-End Guarantee

## Introduction

In分布式 systems中，at-least-once delivery is easy — just retry until the broker acknowledges. At-most-once is also straightforward — fire and forget. But exactly-once? That requires coordinating the producer, the broker, and the consumer in a way that no message is lost and no message is duplicated, even when failures occur mid-transaction.

For a financial trading firm, exactly-once semantics aren't a luxury — they are a requirement. A price tick published twice could cause a risk engine to double-count a position. An order acknowledgment delivered twice could trigger a duplicate fill. The cost of getting this wrong is real money and real regulatory exposure.

This post explains what EOS really means in Kafka, why it requires all three layers — idempotent producer, transactional producer, and `read_committed` consumer — to work together, and when the cost is worth paying.

## The Three Delivery Semantics

Before diving into exactly-once, let's define the three guarantees clearly:

**At-most-once**: The producer sends the message and forgets. If the broker doesn't acknowledge (timeout, network error), the message is lost. It is never retried. This is the weakest guarantee and is rarely acceptable for financial use cases.

**At-least-once**: The producer retries until it receives an acknowledgment. If the broker crashes after persisting but before acknowledging, the producer retries and the broker may deliver a duplicate. This is the most common guarantee in practice — easier to implement, but it requires idempotent consumers to deduplicate.

**Exactly-once (EOS)**: Every message is delivered precisely once — no more, no less. This is the strongest guarantee and requires all three layers of the Kafka delivery stack to cooperate.

## The Three-Layer Stack

EOS in Kafka is not a single configuration flag. It is a composition of three distinct mechanisms, each solving a different failure mode.

### Layer 1: Idempotent Producer

The idempotent producer, enabled with `enable.idempotence=true`, assigns each producer instance a Producer ID (PID) at initialisation. Every message sent to a partition includes the PID and a monotonically increasing sequence number. If the broker receives a duplicate (same PID and sequence), it returns success without persisting again.

This deduplication works within a single producer session and within a single partition. It solves the "retry on timeout" problem — the classic case where a producer sends a message, the broker persists it, the acknowledgment times out, and the producer retries.

### Layer 2: Transactional Producer

The idempotent producer only covers one partition at a time. Real systems need to publish to multiple partitions atomically — for example, publishing a price tick to both a `prices` topic and a `price-audit` topic in a single logical operation.

Kafka transactions extend the idempotent producer across partitions using a two-phase commit protocol. The producer calls `initTransactions()`, then `beginTransaction()`, sends messages to multiple partitions, and finally calls `commitTransaction()`. Internally, a Transaction Coordinator broker manages the commit by writing a `COMMIT` marker to an internal `__transaction_state` topic. All participating partitions only make the records visible to consumers after the coordinator confirms the commit.

If the broker crashes mid-transaction, the coordinator detects the failure via heartbeat, rolls back the in-flight transaction, and the consumer never sees the half-written state.

### Layer 3: read_committed Consumer

Even with a transactional producer, a consumer running with `isolation.level=read_uncommitted` can see uncommitted transactions — including rolled-back ones. Only consumers configured with `isolation.level=read_committed` will wait until a transaction is committed before exposing those records to the application.

This is the final piece: the producer and broker guarantee atomicity, and the consumer guarantees visibility.

## Delivery Semantics vs Processing Semantics

A critical distinction that trips up many engineers: **delivery semantics** and **processing semantics** are not the same thing.

EOS *delivery* means the message reaches the broker exactly once. EOS *processing* means the consumer's side-effects also happen exactly once. Kafka can guarantee the former, but the latter requires additional work.

Consider a consumer that reads a price tick and writes it to a database. The Kafka broker delivered the record exactly once — but if the database write fails and the consumer offsets are committed before the retry succeeds, you get a duplicate database write. Kafka Streams solves this by managing consumer offsets inside the same transaction as the state store writes, but for custom consumers you need idempotent database operations or an outbox pattern.

For financial pipelines, the question to ask is: where are the side-effects, and can they tolerate duplication? A risk engine writing to a position table needs EOS processing. A price cache that rebuilds from a compacted topic on restart does not — it can tolerate duplicates during catch-up.

## When EOS Is Worth the Cost

Transactional producers add latency. Each `commitTransaction()` call involves a round-trip to the Transaction Coordinator and a write to `__transaction_state`. In high-throughput price streams operating at millions of messages per second, the overhead is measurable.

For that reason, EOS is not always the right choice:

- **Appropriate**: Financial audit trails, order acknowledgment pipelines, billing event streams, any pipeline where duplicate delivery causes regulatory or financial harm.
- **Inappropriate**: High-volume analytics pipelines where approximate counts are acceptable, internal metrics pipelines where a lost reading is noise not signal, any pipeline where the throughput cost outweighs the duplication risk.

The engineering decision is not "always EOS" or "never EOS" — it is "where in this pipeline does duplication cost more than the latency overhead of a transaction?"

## Why This Matters in Production

In a production price-distribution system, EOS is the mechanism that allows you to publish a price tick to both a `prices` topic and a `price-audit` topic atomically — so that the audit trail is never out of sync with the live price. It is what allows a consumer to crash and restart without missing a message or reprocessing one it has already handled. It is what gives the risk engine confidence that a published price is real and will not appear twice.

Without EOS, every consumer must implement its own deduplication logic — a distributed systems problem that is harder than it sounds. With EOS, Kafka absorbs that complexity at the infrastructure layer, and the application can reason about its pipeline as if it were a local transaction.

For a Tech Lead designing a mission-critical pipeline, the question is not "can we afford EOS?" — it is "can we afford the cost of not having it?"