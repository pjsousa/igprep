# Idempotency: Why Sending a Message Twice Should Be the Same as Sending It Once

In distributed systems, the network is not your friend. It drops packets, reorders frames, and silently timeouts in the middle of a perfectly good request. When a producer sends a message to Kafka and the acknowledgement never arrives, the natural instinct is to retry. But retries — without careful design — turn a reliable system into a duplicate-delivery nightmare.

Idempotency is the property that saves you. An idempotent operation is one that produces the same result regardless of whether it is applied once or multiple times. For a Kafka producer, this means: if the broker receives the same message twice, it treats it as a single message. The consumer never sees the duplicate.

This post is the foundation of everything that follows in this series. Without idempotency, there is no exactly-once delivery, no transactional producers, and no trustworthy price pipeline.

---

## The Problem: Retries Break At-Most-Once by Default

Consider the simplest scenario. A price tick arrives from an exchange feed. Your producer constructs the Kafka record and calls `send()`. The record reaches the broker, is written to the partition, and the broker sends an acknowledgement. The acknowledgement is lost in transit. The producer's `send()` call throws a `TimeoutException`. Your application catches it and retries.

From the producer's perspective, this is the right thing to do. But the broker already processed the original request — the record is safely committed. The retry sends the *same* record a second time. Without idempotency guarantees, the broker stores both versions. Your downstream risk engine receives the price tick twice. A price of 142.57 for instrument AAPL is applied to the position model twice — creating an overstated exposure that could trigger a false margin call.

This is the fundamental problem: **retries in an unreliable network violate the "one delivery" guarantee by default.**

The three delivery semantics frames this precisely:

- **At-most-once**: the producer sends the message once, but if it fails, it never retries. The message might be lost.
- **At-least-once**: the producer retries until it receives an acknowledgement. The message is never lost, but may be delivered multiple times.
- **Exactly-once**: the message is delivered once and only once — no duplicates, no loss. This requires idempotency to be built into the broker.

Most production systems aim for at-least-once and then layer idempotency on top to achieve exactly-once semantics. Trying to achieve exactly-once without idempotency is like trying to build a bridge without concrete.

---

## How Kafka's Idempotent Producer Works

Kafka's idempotent producer is enabled with a single configuration flag:

```java
properties.setProperty("enable.idempotence", "true");
```

When enabled, the broker assigns each producer instance a **Producer Instance ID (PID)** at startup. This PID is persistent across the producer's lifetime but changes after a restart. More importantly, for each partition the producer sends to, it tracks a **sequence number** that increments with every message.

When the broker receives a message, it checks: *have I already processed a message from this PID with this sequence number for this partition?* If the answer is yes, the incoming message is a duplicate and is silently discarded. The acknowledgement is still sent to the producer, preventing an unnecessary retry.

The deduplication happens at the broker level, on the receiving end. The producer does not need to track anything — Kafka handles it entirely.

Here is a minimal working example:

```java
Properties props = new Properties();
props.put("bootstrap.servers", "kafka-broker-1:9092");
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("enable.idempotence", "true");

KafkaProducer<String, PriceTick> producer = new KafkaProducer<>(props);

PriceTick tick = new PriceTick("AAPL", BigDecimal.valueOf(142.57), Instant.now());
Future<RecordMetadata> future = producer.send(
    new ProducerRecord<>("prices", tick.getInstrumentId(), tick)
);

try {
    RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);
    log.info("Published to partition {} at offset {}", metadata.partition(), metadata.offset());
} catch (InterruptedException | ExecutionException | TimeoutException e) {
    log.error("Failed to publish price tick", e);
}
```

The same `PriceTick` sent twice — whether due to a network timeout or an explicit retry — results in exactly one record in the Kafka log. The consumer sees it once.

---

## The Scope of the Guarantee

Idempotent producers give you exactly-once delivery *within a single producer session, within a single partition*. This is precise and important:

- **Single producer session**: if the producer process crashes and restarts with a new PID, the old PID's sequence numbers are no longer tracked. A message sent before the crash with PID=5 and sequence=14 could theoretically be retried after restart as PID=6, sequence=1 — and the broker would accept it as a new message. In practice, producers are configured with `transactional.id` to survive restarts (covered in the next post, Kafka Transactions).
- **Single partition**: the sequence number is per-partition. A producer sending to multiple partitions maintains independent sequences per partition. Deduplication is per-partition.

For a price-distribution system where each instrument's price stream is routed to a partition keyed by instrument ID, this guarantee covers the most important case: no two identical price ticks for the same instrument reach the consumer.

---

## The ABA Problem

A subtlety worth knowing: Kafka's idempotent producer deduplication solves the "duplicate send" problem but does not solve the **ABA problem**. In ABA, a value changes from A to B and then back to A — and from the system's perspective, it looks like nothing changed. In a Kafka context, this could matter if a consumer reads an offset, processes it, commits an offset that happens to land at the same numeric offset after a partition reassignment, and then sees the same offset reused.

The idempotent producer does not protect against ABA — that problem is solved by `AtomicStampedReference` in Java or by the transactional producer's offset tracking mechanism, which is covered in the next post.

For most price-streaming use cases, the idempotent producer's deduplication is sufficient. The ABA problem is more relevant when consumers are doing their own in-memory state tracking based on observed values.

---

## Why This Matters in Production

In a trading firm, a duplicate price tick is not a minor inconvenience. Downstream systems — risk engines, margin calculators, algorithmic trading strategies — all act on price data. A duplicated price seen twice is a doubled position. In a high-volume market with 50,000 instruments and millions of updates per second, the probability of a retry-induced duplicate within a single trading day is not negligible.

Idempotent producers are not optional infrastructure. They are the first line of defence in a reliable price-delivery pipeline. Without them, every retry is a potential integrity violation. With them, the producer's retry budget increases without proportional risk — you can afford to be aggressive about retries because the broker guarantees deduplication.

Enabling idempotency has a small performance cost: the broker must track PID and sequence number for each in-flight record. In a modern Kafka deployment (2.4+), this overhead is negligible for all but the most extreme throughput requirements. The cost of *not* having it — duplicate prices in a risk engine — is unacceptable.

The idempotent producer is the foundation. Next, we extend it across partitions with the transactional producer, and then we examine the full exactly-once semantics stack.