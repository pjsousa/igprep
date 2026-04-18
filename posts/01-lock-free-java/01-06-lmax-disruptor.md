# The LMAX Disruptor: How a Ring Buffer Replaced Every Queue You Know

## The Problem with Queues Under Pressure

In a trading system processing a million price updates per second, the humble `ArrayBlockingQueue` becomes a liability. It uses a single lock for both `put` and `take` — even with dual condition variables, the path from `offer()` to `poll()` crosses the OS scheduler at least once per call. Under sustained load, that scheduler involvement compounds into measurable latency jitter.

The LMAX Disruptor was designed to eliminate that entire class of overhead. At LMAX, the team published benchmarks showing 100K–300K TPS on commodity server hardware — numbers that looked implausible until the industry replicated them. The key architectural decision: replace the lock-based queue with a pre-allocated ring buffer and coordinate producers and consumers via sequence numbers, not mutexes.

## The Ring Buffer Structure

The Disruptor's core is a fixed-size circular buffer allocated at startup. Each slot is a pre-allocated event object — mutated in-place rather than allocated fresh per publish. The ring never grows or shrinks; if the producer gets ahead of consumers, it waits. This eliminates all heap allocation from the hot path.

```java
public final class PriceTick {
    private long instrumentId;
    private long timestampNanos;
    private double bid;
    private double ask;
    // immutable after construction — no setters, no state mutation post-publish
}
```

A typical ring buffer is sized to a power of two (1,024, 4,096, 8,192), which makes modulo arithmetic collapse to a simple bitmask: `slot = sequence & (bufferSize - 1)`.

## Sequence Numbers: CAS Without Locks

Each producer and consumer owns a `Sequence` object — a `volatile long` with padding to sit on its own cache line (more on that shortly). The critical path works like this:

**Publishing (producer side):**
```java
long sequence = ringBuffer.next();       // CAS: claim next free slot
PriceTick tick = ringBuffer.get(sequence);
tick.instrumentId = 12345L;
tick.timestampNanos = System.nanoTime();
tick.bid = 102.30;
tick.ask = 102.35;
ringBuffer.publish(sequence);           // publish with full memory barrier
```

`next()` uses CAS to atomically increment the claimed sequence and reserve that slot. No lock is acquired. The producer never blocks unless the buffer is completely full — a rare condition in a well-dimensioned system.

**Consuming (consumer side):**
```java
long available = ringBuffer.getCursor(); // volatile read — highest published sequence
if (consumerSequence < available) {
    PriceTick tick = ringBuffer.get(available);
    process(tick);
    consumerSequence = available;         // advance consumer cursor
}
```

Each consumer tracks only its own `Sequence`. The buffer's cursor is the single point of coordination — the highest sequence number that has been published. A consumer only reads slots whose sequence is ≤ that cursor.

## Eliminating False Sharing: The Padded Sequence

If you've read the False Sharing post in this series, you know that adjacent `long` fields in memory share a 64-byte cache line, causing cross-core invalidation even when threads operate on logically independent data.

The Disruptor's `Sequence` class solves this with explicit padding:

```java
public final class Sequence extends RhsPadding {
    private volatile long value;
}

abstract class RhsPadding {
    protected long p1, p2, p3, p4, p5, p6, p7;  // 56 bytes of padding
}

abstract class LhsPadding {
    protected long p1, p2, p3, p4, p5, p6, p7;  // another 56 bytes
}
```

The `Sequence` value sits surrounded by 112 bytes of padding, guaranteeing it occupies its own cache line on every core. When 10 consumer threads each hold their own `Sequence` on separate cache lines, they can update independently with zero cross-invalidation. This is the direct payoff of Post 1.3 applied in a real production library.

## No GC Pressure: Pre-Allocation Pays Off Long-Term

Because every ring buffer slot is pre-allocated at startup, the hot path generates zero short-lived objects. No `new PriceTick()` per tick. No object header, no eden allocation, no Minor GC scan. The price events live in old generation space and are reused ad infinitum.

For a system ingesting 1 million ticks per second, this is not a marginal optimisation — it's the difference between allocating ~1M objects/sec (with GC pressure) and allocating zero. This connects forward to Series 2: the Disruptor effectively removes itself from GC consideration entirely on the critical path.

## ArrayBlockingQueue vs Disruptor: Why the Gap Is So Large

The canonical Disruptor benchmark compares a single-producer/single-consumer (1P1C) configuration against `ArrayBlockingQueue`:

| Metric | ArrayBlockingQueue | Disruptor |
|---|---|---|
| Throughput (TPS) | ~30K–50K | ~250K–350K |
| P99 latency | 10–50 µs | < 1 µs |
| GC pressure | High (per-event allocation) | Near zero |

The causes are compounding: `ArrayBlockingQueue` allocates a node per offer, incurs mutex acquisition and cache-line stealing on every operation, and generates GC objects at the full event rate. The Disruptor does none of this.

## A Minimal Disruptor Wiring Example

```java
Disruptor<PriceTick> disruptor = new Disruptor<>(
    PriceTick::new,          // EventFactory: pre-allocates all slots
    4096,                   // ring buffer size (power of 2)
    DaemonThreadFactory.INSTANCE,
    ProducerType.SINGLE,    // single producer; enables more aggressive optimisations
    new BlockingWaitStrategy()
);

// Define the consumer handler
disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
    priceCache.update(event.instrumentId, event.bid, event.ask);
});

// Start and get the producer
RingBuffer<PriceTick> ringBuffer = disruptor.start();

// Publish
long nextSeq = ringBuffer.next();
try {
    PriceTick event = ringBuffer.get(nextSeq);
    event.instrumentId = instrumentId;
    event.bid = bid;
    event.ask = ask;
    event.timestampNanos = System.nanoTime();
} finally {
    ringBuffer.publish(nextSeq);
}
```

`ProducerType.SINGLE` is worth noting: if your architecture genuinely has one producer thread, telling the Disruptor this enables further lock-free optimisations on the claim path. This aligns with the thread affinity discussion in Post 1.5 — pinning that single producer thread to its own core makes the entire pipeline effectively lock-free from the network card to the application.

## Where It Appears in Practice

Financial matching engines are the canonical use case — LMAX open-sourced the pattern specifically from their matching engine work. Beyond that, any system that must fan out a single high-rate stream to multiple consumers benefits:

- **Price distribution**: one market-data ingress, multiple downstream consumers (risk engine, UI, order management, audit trail)
- **Order routing**: one incoming order, fan-out to risk checks, latency monitors, and the matching core
- **FIX/FAST message normalisation**: decode once, publish to all subscribers

The journal/dispatcher pattern is the production pattern here: the ring buffer is the dispatcher, consumer handlers are the subscribers. Each consumer processes independently and at its own pace — slow consumers do not block fast ones.

## Why This Matters in Production

The Disruptor is the capstone of this series precisely because it is not a toy example. It is used in production by trading firms worldwide, including firms like LMAX, Sonic!, and numerous systematic hedge funds. Its design represents the point where mechanical sympathy stops being theory and starts being a concrete set of engineering choices.

As a Tech Lead, the Disruptor is a useful mental model for any high-throughput, low-latency pipeline: pre-allocate, pad, use CAS, avoid allocation on the critical path, and pin your threads. Every one of those decisions connects back to a concept from this series.

When an interviewer asks "how would you design a price distribution system that needs to handle 1M updates/sec with sub-millisecond latency?" — the Disruptor is not just a possible answer. It is the answer. And knowing it deeply means being able to justify every design decision inside it.