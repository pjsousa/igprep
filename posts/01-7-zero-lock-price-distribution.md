# Zero-Lock Price Distribution: Building a High-Throughput Ticker with the LMAX Disruptor

> *Series: Lock-Free Java — Mechanical Sympathy for the Trading Floor — Showcase Article*

You've read the theory. CAS at the CPU level, AtomicReference wrapping object state, false sharing destroying your cache lines, memory barriers enforcing visibility, thread affinity taming the scheduler, and the LMAX Disruptor tying it all into a ring buffer that outperforms every queue in the JDK. Now it's time to put every one of those concepts to work in a single, coherent, production-credible system.

This article walks through the architecture of a price-distribution service at a fictional trading firm — from FIX/FAST market-data ingest to downstream fan-out — and explains precisely where and why each lock-free concept appears in the design. If an interviewer asks *"walk me through how you'd build a high-performance price feed,"* this is the blueprint.

---

## The Scenario

Acme Markets operates a price-streaming platform that receives normalised market data from multiple FIX/FAST feeds, maintains a real-time price cache for thousands of instruments, and distributes price ticks to three downstream consumers:

1. **Risk Engine** — consumes every tick to recalculate exposure in real time.
2. **UI Gateway** — pushes the latest price to web and mobile clients via WebSocket.
3. **Order Management System (OMS)** — needs the authoritative last price for order validation and slippage checks.

The SLA is strict: **p99 end-to-end latency from wire to consumer must be under 500 microseconds**, and the system must sustain **1 million price updates per second** with no back-pressure stalls under normal market conditions.

A lock-based architecture — `synchronized` blocks, `ArrayBlockingQueue`, `ConcurrentHashMap` locks — cannot meet these targets. Every mutex acquisition is a potential context switch; every queue head is a contention hotspot. We need lock-free design from end to end.

---

## Architecture Overview

```
                    ┌─────────────────────────┐
                    │    FIX/FAST Ingest       │
                    │  (Thread-Affinity Pinned)│
                    └───────────┬───────────────┘
                                │
                          PriceTick event
                                │
                    ┌───────────▼───────────────┐
                    │   Disruptor Ring Buffer   │
                    │  (CAS-sequence claiming)  │
                    │  (Padded to avoid false   │
                    │   sharing on sequences)   │
                    └───────────┬───────────────┘
                                │
                    ┌───────────┼───────────────┐
                    │           │               │
              ┌─────▼───┐ ┌─────▼───┐   ┌───────▼─────┐
              │Risk      │ │UI       │   │OMS          │
              │Handler   │ │Handler  │   │Handler      │
              │(Consumer)│ │(Consumer│   │(Consumer)   │
              └─────┬────┘ └────┬────┘  └──────┬──────┘
                    │           │               │
              ┌─────▼────┐ ┌────▼─────┐ ┌───────▼─────┐
              │AtomicRef │ │WebSocket │ │Last-Price   │
              │Price     │ │Push      │ │Cache        │
              │Cache     │ │          │ │(AtomicRef)  │
              └──────────┘ └──────────┘ └─────────────┘
```

Let's walk through each layer and explain the design decisions.

---

## Layer 1: Ingestion Thread — Thread Affinity

The ingestion thread receives FIX/FAST messages from the network, decodes them into `PriceTick` objects, and publishes them into the Disruptor. This thread sits on the critical path — every price update must pass through it before reaching any consumer.

### Why Pin It?

On a stock Linux kernel, the scheduler can migrate a thread between CPU cores at any time. Each migration invalidates the L1/L2 cache the thread was warming up, and the thread must rebuild cache residency on the new core. For a thread processing a million events per second, a single context switch can introduce a **10–40 microsecond latency spike** — enough to blow the p99 SLA.

Using the [Java Thread Affinity](https://github.com/OpenHFT/Java-Thread-Affinity) library:

```java
AffinityLock affinityLock = AffinityLock.acquireLock();

try {
    while (running) {
        PriceTick tick = fixFeed.receive();
        long sequence = ringBuffer.next();
        PriceEvent event = ringBuffer.get(sequence);
        event.set(tick);
        ringBuffer.publish(sequence);
    }
} finally {
    affinityLock.release();
}
```

By pinning the ingest thread to an isolated core (one not used by the OS for other processes), we eliminate involuntary context switches on that core. The thread's cache stays hot, and latency becomes predictable.

**Key trade-off:** Thread affinity is only appropriate for threads that are always busy. A thread that blocks or idles wastes an entire core. The ingest thread in our design never blocks — it spins on the FIX feed and publishes into the Disruptor — so affinity is the right call.

---

## Layer 2: Disruptor Ring Buffer — CAS and Padded Sequences

The Disruptor is the structural skeleton of the entire pipeline. Let's trace how each concept from this series appears inside it.

### CAS for Sequence Claiming

A producer claims a slot in the ring buffer by incrementing a sequence counter. In the single-producer case, this is a simple increment. In the multi-producer case, the Disruptor uses **CAS** to atomically claim the next available slot:

```java
long current = cursor.get();
long next = current + 1;
while (!cursor.compareAndSet(current, next)) {
    current = cursor.get();
    next = current + 1;
}
```

This is the same CAS primitive we explored in the first post of the series. The thread never blocks — it spins and retries. Under low contention, CAS succeeds on the first attempt; under high contention, a brief spin is still orders of magnitude cheaper than a kernel mutex transition.

### Padded Sequences to Eliminate False Sharing

The Disruptor maintains several sequence cursors: the producer's `cursor`, and each consumer's `sequence`. These are `long` fields updated frequently by different threads. If they happen to share a 64-byte cache line, every update by one core invalidates the entire line for the other cores — even though they don't care about each other's values.

The Disruptor eliminates this by **padding** each sequence variable to occupy its own cache line:

```java
public class LhsPadding {
    volatile long p0, p1, p2, p3, p4, p5, p6;
}

public class Value extends LhsPadding {
    volatile long value;
}

public class RhsPadding extends Value {
    volatile long p0, p1, p2, p3, p4, p5, p6;
}
```

The seven `long` fields on each side of `value` ensure that no other variable can share `value`'s cache line. This is false sharing prevention applied directly to the hottest data structure in the pipeline.

On JDK 8+, you could use `@jdk.internal.vm.annotation.Contended` instead, but the Disruptor retains manual padding for backward compatibility and because `@Contended` requires a JVM flag (`-XX:-RestrictContended`) to take effect on user classes.

### Memory Barriers in Producer-Consumer Coordination

When the producer publishes an event by setting the sequence cursor, it must ensure that all writes to the event's fields are visible to the consumer thread **before** the consumer sees the updated sequence number. The Disruptor achieves this by using `volatile` on the sequence cursor, which inserts a **StoreLoad barrier** — exactly the memory barrier we discussed earlier.

From the consumer side, reading the volatile sequence cursor inserts a **LoadLoad barrier**, ensuring the consumer sees the event data after confirming the sequence has been published. Without these barriers, the CPU or compiler could reorder the writes, and the consumer could see a partially-initialised event.

### Pre-allocation to Avoid GC Pressure

The ring buffer is pre-allocated at startup:

```java
Disruptor<PriceEvent> disruptor = new Disruptor<>(
    PriceEvent::new,
    bufferSize,
    DaemonThreadFactory.INSTANCE,
    ProducerType.MULTI,
    new BlockingWaitStrategy()
);
```

Every slot in the ring is a `PriceEvent` object created once and reused forever. No `new PriceTick()` per update inside the ring — the producer **overwrites** fields in the existing event object. This means **zero GC pressure on the critical path**: no Young Generation collections triggered by the ring buffer, no TLAB exhaustion, no allocation-rate-induced Minor GC pauses.

---

## Layer 3: Consumer Handlers

Each downstream consumer is wired as a Disruptor `EventHandler`. In a multi-consumer setup, the Disruptor supports two coordination patterns:

### Sequential Consumers (Dependency Graph)

If consumer B must process events only after consumer A has finished, you define a dependency:

```java
EventHandler<PriceEvent> riskHandler = (event, sequence, endOfBatch) -> {
    riskEngine.process(event.getInstrumentId(), event.getPrice());
};

EventHandler<PriceEvent> uiHandler = (event, sequence, endOfBatch) -> {
    uiGateway.push(event.getInstrumentId(), event.getPrice());
};

disruptor.handleEventsWith(riskHandler).then(uiHandler);
```

### Parallel Consumers (Independent Handlers)

If consumers are independent (our risk engine and UI gateway don't depend on each other), they can run in parallel:

```java
disruptor.handleEventsWith(riskHandler, uiHandler);
```

Each consumer thread advances its own sequence cursor independently. The Disruptor guarantees that no consumer will read an event until all preceding consumers in the dependency graph have marked it complete.

---

## Layer 4: The Published Price Cache — AtomicReference

After processing, the OMS handler needs to update a shared last-price cache that other services query. This is where `AtomicReference` appears.

```java
public class InstrumentPriceCache {
    private final AtomicReference<Map<String, PriceTick>> priceMapRef =
        new AtomicReference<>(Map.of());

    public void update(String instrumentId, PriceTick tick) {
        Map<String, PriceTick> current;
        Map<String, PriceTick> updated;
        do {
            current = priceMapRef.get();
            updated = new HashMap<>(current);
            updated.put(instrumentId, tick);
        } while (!priceMapRef.compareAndSet(current, updated));
    }

    public PriceTick getLatest(String instrumentId) {
        return priceMapRef.get().get(instrumentId);
    }
}
```

The CAS loop here ensures that if two consumers try to update the cache simultaneously, one will succeed and the other will retry. No locks, no blocking — the loser spins briefly and re-applies its update on the latest snapshot.

For the critical-path design in our price service, we'd likely replace this with a copy-on-write approach using `volatile` references for read-mostly access, or even a `ConcurrentHashMap` if the contention pattern allows it — but the AtomicReference pattern is the most direct demonstration of CAS applied to object state, which is the point.

**ABA consideration:** In our scenario, ABA is not a risk — we're replacing a map reference, not comparing individual entries. But in designs where you compare-and-swap on individual values (e.g. a reference to a linked node), `AtomicStampedReference` would be required to distinguish between "same value, different version" and "same value, same version."

---

## The Critical Path in Code

Here is a minimal but complete wiring of the price-distribution service:

```java
public class PriceEvent {
    private String instrumentId;
    private double price;
    private long timestamp;

    public void set(String instrumentId, double price, long timestamp) {
        this.instrumentId = instrumentId;
        this.price = price;
        this.timestamp = timestamp;
    }

    public String getInstrumentId() { return instrumentId; }
    public double getPrice() { return price; }
    public long getTimestamp() { return timestamp; }
}

public class PriceDistributionService {
    private final Disruptor<PriceEvent> disruptor;
    private final RingBuffer<PriceEvent> ringBuffer;

    public PriceDistributionService() {
        int bufferSize = 1024 * 64; // must be power of 2
        disruptor = new Disruptor<>(
            PriceEvent::new,
            bufferSize,
            DaemonThreadFactory.INSTANCE,
            ProducerType.MULTI,
            new YieldingWaitStrategy()
        );

        EventHandler<PriceEvent> riskHandler = (event, seq, endOfBatch) ->
            riskEngine.process(event.getInstrumentId(), event.getPrice());

        EventHandler<PriceEvent> uiHandler = (event, seq, endOfBatch) ->
            uiGateway.push(event.getInstrumentId(), event.getPrice());

        EventHandler<PriceEvent> omsHandler = (event, seq, endOfBatch) ->
            priceCache.update(event.getInstrumentId(),
                new PriceTick(event.getInstrumentId(), event.getPrice(), event.getTimestamp()));

        disruptor.handleEventsWith(riskHandler, uiHandler, omsHandler);
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
    }

    public void publish(String instrumentId, double price, long timestamp) {
        long sequence = ringBuffer.next();
        try {
            PriceEvent event = ringBuffer.get(sequence);
            event.set(instrumentId, price, timestamp);
        } finally {
            ringBuffer.publish(sequence);
        }
    }
}
```

Notice what's absent: no `synchronized`, no `ReentrantLock`, no `ArrayBlockingQueue`. The entire critical path uses CAS for sequence claiming, volatile for visibility, and padding for cache-line isolation. This is lock-free concurrency from ingest to delivery.

---

## Validation in Production

A design is only as good as its observability. Here's how we validate that the system meets its latency targets in production.

### JMH Benchmark Setup

Before deployment, we benchmark the publish-to-consume path:

```java
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Threads(1)
public class DisruptorBenchmark {

    @Benchmark
    public void publishAndConsume(Blackhole bh) {
        service.publish("EUR/USD", 1.0845, System.nanoTime());
        bh.consume(service.getLatestPrice("EUR/USD"));
    }
}
```

We use `Mode.SampleTime` to capture the full latency distribution, not just the mean. The p99 and p99.9 numbers are what matter for our SLA.

### Async-Profiler for Hotspot Detection

Run async-profiler in ` Wall` and `CPU` modes during load tests:

```bash
java -jar async-profiler.jar -d 60 -e cpu -f cpu.html <pid>
java -jar async-profiler.jar -d 60 -e wall -f wall.html <pid>
```

Look for:
- **Unexpected contention** on Disruptor sequence cursors (would suggest false sharing is not fully eliminated).
- **TLAB refill stalls** in allocation flame graphs (should be minimal since the ring buffer pre-allocates, but check consumer-side allocations).
- **Thread migration** events (if the ingest thread is migrating between cores, affinity pinning is not holding).

### GC Log Validation

Run with ZGC to confirm GC pauses stay below the p99 target:

```bash
java -XX:+UseZGC -Xlog:gc*:file=gc.log:time,uptime,level,tags ...
```

Scan the log for pause times. With ZGC, every pause should be sub-millisecond. If you see pauses above 1ms, grep for the cause — it's almost always a safepoint outside the GC itself (e.g. thread dump, JVMTI, or class unloading).

### HDRHistogram for Latency Percentiles

Instrument the publish-to-consume path with HDRHistogram to capture the full latency distribution:

```java
private final LongHistogram latencyHistogram = new LongHistogram(TimeUnit.HOURS.toNanos(1), 3);

public void recordLatency(long startNanos) {
    long elapsed = System.nanoTime() - startNanos;
    latencyHistogram.recordValue(elapsed);
}

public void printSummary() {
    System.out.printf("p50=%.0fμs p99=%.0fμs p999=%.0fμs max=%.0fμs%n",
        TimeUnit.NANOSECONDS.toMicros(latencyHistogram.getValueAtPercentile(50)),
        TimeUnit.NANOSECONDS.toMicros(latencyHistogram.getValueAtPercentile(99)),
        TimeUnit.NANOSECONDS.toMicros(latencyHistogram.getValueAtPercentile(99.9)),
        TimeUnit.NANOSECONDS.toMicros(latencyHistogram.getMaxValue()));
}
```

HDRHistogram is essential because it captures the tail — mean and median latency are irrelevant for a p99 SLA.

---

## Trade-offs and Failure Modes

### Consumer Lag

If a consumer falls behind, the ring buffer wraps and the producer overwrites slots the consumer hasn't read yet. The Disruptor provides a `waitFor` mechanism that can block the producer when consumers are too slow, but this introduces back-pressure on the ingest side — exactly what we're trying to avoid.

**Design choice:** For our price service, we accept that stale prices are overwritten. The *latest* price is always correct; delayed consumers receive the current state rather than a replay of every missed tick. If a consumer needs a full replay, it reads from the compacted Kafka topic (which feeds the service upstream), not from the Disruptor.

### Handler Exceptions

If a `EventHandler` throws, the Disruptor's default behaviour is to stop processing that sequence and log the error. In production, this is unacceptable — a single bad tick should not halt the pipeline.

**Design choice:** Wrap every handler in a try-catch that logs the error and continues:

```java
EventHandler<PriceEvent> resilientRiskHandler = (event, seq, endOfBatch) -> {
    try {
        riskEngine.process(event.getInstrumentId(), event.getPrice());
    } catch (Exception e) {
        errorLogger.log(seq, event.getInstrumentId(), e);
    }
};
```

The Disruptor also provides an `ExceptionHandler` callback for global exception handling, which we configure to log and continue rather than halt.

### Back-Pressure

When the ring buffer is full and the producer calls `next()`, the Disruptor blocks (using the configured `WaitStrategy`). This is the back-pressure mechanism. For our architecture:

- **`YieldingWaitStrategy`** — the producer spins then yields. Best latency, highest CPU usage. Appropriate for our always-running ingest thread since it's pinned anyway.
- **`BlockingWaitStrategy`** — uses `LockSupport.park()`. Lower CPU, but higher latency. Use only for non-critical consumers or when CPU budget is constrained.

We use `YieldingWaitStrategy` for the ingest thread (pinned, always busy) and `BlockingWaitStrategy` for the UI consumer (WebSocket push can tolerate slight delay).

---

## Concept Recap: Where Each Idea Lives

| Concept from the series | Where it appears in this architecture |
|---|---|
| **CAS** | Disruptor sequence claiming — the producer spins on `compareAndSet` to claim the next slot |
| **AtomicReference** | Published price cache — lock-free update via CAS loop on the map reference |
| **False Sharing** | Padded sequence cursors in the Disruptor — each cursor occupies its own cache line |
| **Memory Barriers** | Volatile sequence cursor — `StoreLoad` barrier ensures event data is visible before the sequence advances |
| **Thread Affinity** | Ingest thread pinned to an isolated core via OpenHFT Java Thread Affinity |
| **LMAX Disruptor** | The central ring buffer — pre-allocated, lock-free, sequence-number-driven, zero-GC |

Each concept is not a theoretical decoration — it is the **mechanism** that makes the next layer work. Without CAS, there is no lock-free sequence claiming. Without padding, false sharing invalidates those sequences across cores. Without memory barriers, consumers see stale data. Without thread affinity, the ingest thread's cache is evicted by the scheduler. And the Disruptor is the composition of all of these into a single data structure that the LMAX team proved can process 20+ million events per second on commodity hardware.

---

## When to Reach for This Pattern

This architecture is the right choice when:

- **You have a single-producer or low-contention multi-producer pipeline** — the Disruptor excels when the write pattern is predictable and fast.
- **Your SLA requires sub-millisecond p99 latency** — any design involving locks will introduce tail-latency spikes that violate this SLA.
- **You can tolerate overwriting stale data** — if your system needs the *latest* state, not a full event log, the ring buffer's wrap-around is a feature, not a bug.
- **You have dedicated CPU cores to spare** — thread affinity and busy-spin wait strategies consume cores whether or not there is data. This is a trade: spend CPU to buy latency predictability.

Do **not** use this architecture when:

- **You need event replay or full audit trail** — use Kafka (with compacted topics) for the audit path; the Disruptor is an in-memory hot path, not a durable log.
- **Your workload is bursty with idle periods** — busy-spin strategies burn CPU even when idle. Use `BlockingWaitStrategy` or a traditional queue if latency targets are more relaxed.
- **You cannot afford to dedicate cores** — if your JVM shares a machine with other services, thread affinity will conflict with the OS scheduler and hurt more than it helps.

The best real-world designs layer these technologies: a Disruptor-based hot path for latency-critical price distribution, backed by a Kafka compacted topic for durability and replay. The Disruptor handles the "now"; Kafka handles the "what happened."

---

## Interview Takeaway

If an interviewer at a trading firm asks you to walk through a high-performance price distribution system, the answer is not "I'd use a ConcurrentLinkedQueue and hope for the best." The answer is:

1. **Ingest on an affinity-pinned thread** to eliminate scheduler jitter.
2. **Publish into a Disruptor ring buffer** to get lock-free, zero-GC, pre-allocated event passing.
3. **Fan out to parallel consumers** that process independently without coordination locks.
4. **Use AtomicReference** for published state that downstream services query.
5. **Validate with JMH, async-profiler, HDRHistogram, and GC logs** — because a design you can't measure is a design you can't debug.

Every concept in this series — CAS, AtomicReference, false sharing, memory barriers, thread affinity, and the Disruptor itself — appears in this architecture because **removing any one of them degrades the p99 latency by an order of magnitude**. That's the standard a Tech Lead must hold: not "it works," but "I can prove it works under pressure, and I can explain why."