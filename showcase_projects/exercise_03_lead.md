# Lead-Level Price Match Service — Exercise

## Objective

Elevate the senior implementation from Exercise 02 into a production-grade system that a Principal or Staff Engineer can probe without finding major gaps. This means replacing the hand-rolled lock-free ring buffer with the LMAX Disruptor, introducing correct JVM tuning, pinning critical threads to CPU cores, establishing a full observability stack, and reasoning carefully about failure modes, operational concerns, and design trade-offs.

The throughput target of **1,000,000 updates/second** must be met and *proven* — with latency percentiles, GC pause budgets, and a documented capacity envelope that tells an operator exactly when the system will start to degrade.

---

## Background & Motivation

Exercise 02 produced a measurably better system. It removed the most egregious lock contention and false sharing. But a Lead Engineer reviewing that system would still ask hard questions: *Why are you rolling your own ring buffer when the LMAX Disruptor is the proven production implementation? What GC collector are you running, and why? What happens to latency during a JVM warm-up window after a rolling restart? What does your p99.9 look like, and do you know why it is what it is?*

This exercise answers those questions by drawing on both series:

**From Series 1 — Lock-Free Java:**
- Post 1.6 — *The LMAX Disruptor* (the structural skeleton)
- Post 1.7 — *Zero-Lock Price Distribution* (the production architecture blueprint)
- Post 1.5 — *Thread Affinity* (pinning the ingest thread)
- Posts 1.3 & 1.4 — *False Sharing* and *Memory Barriers* (already applied in Exercise 02, now validated by profiling)

**From Series 2 — JVM Internals:**
- Post 2.4 — *Shenandoah GC* and Post 2.5 — *ZGC* (selecting and configuring the right collector)
- Post 2.3 — *JIT Compilation* (managing warm-up and deoptimisation)
- Post 2.2 — *TLABs* (validating that the Disruptor's pre-allocation eliminates allocation pressure)
- Post 2.6 — *Diagnosing the Jitter* (the observability and diagnosis playbook)

A Lead Engineer's job is not just to write fast code. It is to build a system whose properties are *understood*, *observable*, and *operationally maintainable* under realistic production conditions.

---

## System Specification

### Functional Requirements

Same as Exercises 01 and 02, plus:
- Support a configurable number of downstream consumer handlers (minimum: risk engine, UI gateway, OMS price cache — three independent handlers running in parallel).
- Provide a JMX or Micrometer-based metrics endpoint exposing: throughput, p50/p99/p99.9 latency, GC pause duration, ring buffer fill level (backpressure indicator), and consumer lag per handler.

### Non-Functional Requirements

- **Throughput target:** 1,000,000 sustained updates/second over a 60-second load window.
- **Latency target:** p99 update-to-consumer latency ≤ 500 microseconds; p99.9 ≤ 1 millisecond.
- **GC pause target:** Zero stop-the-world pauses above 1ms (ZGC or Shenandoah).
- **Warm-up constraint:** After a JVM restart, latency SLA must be met within 60 seconds of first traffic (JIT must reach Tier 4 before latency is counted).

### Constraints

- LMAX Disruptor library allowed (`com.lmax:disruptor:4.x`).
- OpenHFT Java Thread Affinity library allowed (`net.openhft:affinity`).
- HDRHistogram allowed (`org.hdrhistogram:HdrHistogram`).
- Micrometer allowed for metrics export.
- JVM: JDK 21 (enables Generational ZGC, virtual threads if needed for non-critical paths).
- Single JVM, single process — no distribution.

---

## Step-by-Step Exercise Guide

### Step 1 — Replace the Hand-Rolled Ring Buffer with the LMAX Disruptor

**What to implement:**

Remove your Exercise 02 `AtomicReferenceArray`-based ring buffer and replace it with a properly wired `Disruptor<PriceEvent>`.

Define a `PriceEvent` that the Disruptor pre-allocates:

```java
public class PriceEvent {
    String instrumentId;
    double price;
    long timestamp;
    long publishNanos;  // for latency measurement

    // mutable — the producer overwrites fields in-place
    public void set(String id, double price, long ts) { ... }
}
```

Wire the Disruptor with three parallel consumers: `RiskHandler`, `UiHandler`, `OmsHandler` — each implementing `EventHandler<PriceEvent>`.

**Key decisions:**
- **ProducerType:** Use `ProducerType.MULTI` if multiple threads call `ringBuffer.next()`. For a single ingest thread, `ProducerType.SINGLE` is faster (avoids the CAS on sequence claiming). Which applies to your architecture?
- **WaitStrategy:** `YieldingWaitStrategy` gives the lowest latency but burns CPU on idle cores. `BlockingWaitStrategy` is CPU-efficient but adds latency. `BusySpinWaitStrategy` is the lowest possible latency but requires an isolated core. Match the strategy to the thread affinity configuration from Step 3.
- **Ring buffer size:** Must be a power of two. Size it to accommodate the maximum burst of in-flight events: `maxInFlightEvents = producers × averageProcessingLatency × targetThroughput`. Start with 65,536 (64K) and tune from there.

**Concepts to study:**
- Post 1.6 — *The LMAX Disruptor*: sequence claiming, padded sequence cursors, pre-allocation.
- Post 1.7 — *Zero-Lock Price Distribution*: the complete wiring pattern with `handleEventsWith()`.
- Why does the Disruptor use manual padding (7 longs on each side) instead of `@Contended`? (Hint: backward compatibility and the `-XX:-RestrictContended` requirement.)

**Validation:**
- GC logs must show zero `new PriceEvent` allocations on the hot path. If you see allocation, the Disruptor is not being used correctly — the factory lambda is being called at publish time instead of at startup.

---

### Step 2 — Replace the AtomicReference Price Cache with a Per-Instrument Lock-Free Cell

**What to implement:**

The `AtomicReference<Map<String, PriceTick>>` copy-on-write cache from Exercise 02 still copies the entire map on every update. At 1,000,000 updates/second across 10,000 instruments, this is 1,000,000 map copies per second — unsustainable.

Replace it with a `ConcurrentHashMap<String, AtomicReference<PriceTick>>` where each instrument has its own `AtomicReference`. Writers CAS on the per-instrument reference; readers call `ref.get()` — a single load, no copy.

For the OMS handler, which needs a snapshot-consistent view of multiple instruments simultaneously (e.g. a spread price from two legs), document why a per-instrument `AtomicReference` breaks snapshot consistency and what the alternative is (hint: version stamps, or accepting eventual consistency at the cache level).

**Key decisions:**
- Should `PriceTick` remain a new object per update, or should it be pooled/pre-allocated? With the Disruptor carrying the hot-path data, the `PriceTick` in the cache is only written once per update and read rarely — allocation pressure here is much lower than in the dispatcher loop.
- How do you handle an instrument ID that has never been seen before? Pre-populate the map at startup with all 10,000 instruments, or use `computeIfAbsent` lazily?

**Concepts to study:**
- `ConcurrentHashMap.computeIfAbsent` — is it atomic? What lock does it hold during computation?
- What is the ABA problem for per-instrument `AtomicReference`? Is it a risk here? (The timestamp in `PriceTick` lets you distinguish "same price, different tick" — but only if the consumer reads atomically.)

---

### Step 3 — Pin Critical Threads with Thread Affinity

**What to implement:**

Identify the threads that must have predictable, jitter-free execution:

1. **Ingest thread** — receives price data and calls `ringBuffer.next()` / `ringBuffer.publish()`.
2. **Disruptor event processor threads** — one per consumer handler.

Pin each of these to an isolated CPU core using the OpenHFT Java Thread Affinity library:

```java
// In the ingest thread's run() method
try (AffinityLock al = AffinityLock.acquireLock()) {
    while (running) {
        // ingest loop
    }
}
```

For the Disruptor's event processor threads, you must provide a custom `ThreadFactory` that acquires an affinity lock before entering the processing loop.

**Key decisions:**
- How many physical cores does your machine have? How many do OS housekeeping, JVM GC threads, and other services need? Budget cores carefully — affinity on a shared machine can cause starvation.
- **NUMA topology:** If your machine has two NUMA nodes (two CPU sockets with separate memory controllers), ensure the ingest thread and its consumer threads are all on the same NUMA node. A cross-NUMA memory access is 3–5x slower than a local one. Use `numactl --hardware` to inspect your topology.
- Should you use `isolcpus` at the kernel level to prevent the OS scheduler from assigning any other thread to the affinity-locked cores? This is the production-grade approach for dedicated trading boxes.

**Concepts to study:**
- Post 1.5 — *Thread Affinity*: OS scheduling, context switch cost, NUMA topology, and when affinity is counterproductive.
- Post 1.7 — *Zero-Lock Price Distribution*: the ingest thread affinity pattern with `AffinityLock.acquireLock()`.

**Validation:**
- Run with affinity disabled, then with affinity enabled. Measure p99.9 latency from your HDRHistogram. Affinity should reduce latency jitter at the tail — the p50 and p99 may not change much, but the p99.9 and max should improve significantly.

---

### Step 4 — Select and Configure the Right Garbage Collector

**What to implement:**

Add the following JVM flag comparison to your load test harness. Run three 60-second tests, changing only the GC flags:

**Test A — G1GC (default):**
```
-XX:+UseG1GC -Xms4G -Xmx4G
-Xlog:gc*:file=gc-g1.log:time,uptime,level,tags
```

**Test B — ZGC:**
```
-XX:+UseZGC -Xms4G -Xmx4G -XX:SoftMaxHeapSize=3G
-Xlog:gc*:file=gc-zgc.log:time,uptime,level,tags
```

**Test C — Generational ZGC (JDK 21+):**
```
-XX:+UseZGC -XX:+ZGenerational -Xms4G -Xmx4G
-Xlog:gc*:file=gc-genzgc.log:time,uptime,level,tags
```

For each test, record: p99 latency, p99.9 latency, GC pause frequency, GC pause max, and CPU utilisation (`top` or `jstat -gcutil`).

**Key decisions:**
- ZGC trades throughput for pause time. If your CPU is already at 80%+ during the load test, ZGC's concurrent threads will make things worse, not better. At what CPU utilisation should you reconsider the GC choice?
- Heap sizing: ZGC needs headroom — it cannot run concurrent collection if the heap is at 95% occupancy. The `SoftMaxHeapSize` flag lets you set a soft ceiling below `Xmx` to maintain headroom.
- Generational ZGC (JDK 21+) improves throughput over non-generational ZGC. Does it also improve latency? Read Post 2.5 — *ZGC* and compare.

**Concepts to study:**
- Post 2.5 — *ZGC*: coloured pointers, load barriers, concurrent relocation, pause time guarantee.
- Post 2.4 — *Shenandoah GC*: when to prefer Shenandoah (smaller heap, more CPU-constrained) vs ZGC (larger heap, strict pause SLA).
- Post 2.1 — *Survivor Spaces*: why the Disruptor's pre-allocation means you have almost no Young Generation pressure — which changes the GC selection calculus.

**Validation:**
- Build a table of results (like Table 2 in *Diagnosing the Jitter*) comparing the three GC configurations. State clearly which you would select for production and why.

---

### Step 5 — Manage JIT Warm-Up

**What to implement:**

Add a warm-up phase to your `PriceUpdateService` that:

1. On startup, submits synthetic price updates at 50% of target throughput for 30 seconds *before* opening to real traffic.
2. Exposes a `boolean isWarmedUp()` method that returns `true` once the warm-up phase has completed and the JIT has had time to reach Tier 4 on the hot path.
3. Adds a JVM startup flag to enable Class Data Sharing (CDS):

```bash
# Generate the CDS archive on first run (offline step)
java -Xshare:dump -XX:SharedArchiveFile=app-cds.jsa -cp app.jar ...

# Use it at runtime
java -Xshare:on -XX:SharedArchiveFile=app-cds.jsa ...
```

**Key decisions:**
- How do you detect that warm-up is "done"? Options: fixed time delay (30s), or monitoring JIT compilation events via JMX (`java.lang:type=Compilation`) until the compilation queue is empty. The latter is more precise but more complex — choose and justify.
- What happens if a deoptimisation occurs after the service is live? (A class hierarchy change causes C2 to discard its optimised code — the service temporarily runs at interpreted speed.) How do you detect this in production? (Hint: JMC JIT compilation events, or `-Xlog:jit+deoptimization=info`.)

**Concepts to study:**
- Post 2.3 — *JIT Compilation*: C1 vs C2 tiers, deoptimisation triggers, warm-up latency spikes.
- Post 2.6 — *Diagnosing the Jitter*: the incident where a deoptimisation contributed to a latency spike at 14:28 — exactly the failure mode you are now building a defence against.

---

### Step 6 — Build the Full Observability Stack

**What to implement:**

Instrument the service with the following observability signals. Use Micrometer with a `SimpleMeterRegistry` (for local testing) — in production this would export to Prometheus.

```java
// Throughput
Counter.builder("price.updates.published").register(registry)
Counter.builder("price.updates.dispatched").register(registry)

// Latency (per consumer handler — use HDRHistogram for accuracy)
Timer.builder("price.dispatch.latency")
    .tag("handler", "risk")
    .publishPercentiles(0.5, 0.95, 0.99, 0.999)
    .register(registry)

// Ring buffer health
Gauge.builder("disruptor.remaining.capacity",
    ringBuffer, RingBuffer::remainingCapacity).register(registry)

// GC
// Use JVM built-in MeterBinder: new JvmGcMetrics().bindTo(registry)
```

Also implement structured logging for the diagnostic playbook from Post 2.6:
- Log a `WARN` when ring buffer remaining capacity drops below 10% (back-pressure risk).
- Log a `WARN` when p99 latency exceeds 200μs (approaching SLA threshold).
- Log an `ERROR` when a consumer handler throws an exception (must not halt the pipeline — wrap in try-catch; see the `resilientHandler` pattern in Post 1.7).

**Key decisions:**
- HDRHistogram vs Micrometer's built-in histogram: Micrometer uses a fixed-bucket histogram that loses resolution at the tail. HDRHistogram records the full distribution. For a p99.9 SLA, which do you use?
- At what cadence do you sample and report metrics? Every 1 second is fine for operational dashboards; continuous sampling with HDRHistogram for latency percentiles.

**Concepts to study:**
- Post 2.6 — *Diagnosing the Jitter*: the "Instrumentation Hygiene" section shows exactly the Micrometer + HDRHistogram pattern used in production.
- Post 1.7 — *Zero-Lock Price Distribution*: the "Validation in Production" section covers the JMH + async-profiler + HDRHistogram trifecta.

---

### Step 7 — Document the Capacity Envelope and Failure Mode Analysis

**What to produce:**

Write a short (one to two pages) engineering decision record (EDR) covering:

1. **Capacity envelope:** At what throughput does p99 latency exceed 500μs? At what throughput does the ring buffer fill and cause producer back-pressure? At what throughput does ZGC's concurrent thread count become a CPU bottleneck? Express these as three distinct ceilings with measured data.

2. **Failure mode analysis:** For each of the following failure scenarios, describe what happens and how to detect/recover:
   - A consumer handler throws an unhandled exception during event processing.
   - The ring buffer fills because one consumer is too slow (consumer lag).
   - The JVM triggers a deoptimisation on the `PriceNormaliser.apply` hot path mid-load.
   - The ingest thread loses its CPU core affinity (the OS reassigns the core to another process).

3. **Design trade-offs you consciously accepted:**
   - LMAX Disruptor has no durable log — data is lost if the JVM dies. Acceptable because Kafka (upstream) is the durable source of truth.
   - `ConcurrentHashMap` per-instrument cache loses snapshot consistency across instruments. Acceptable for UI display; not acceptable for risk calculations requiring a consistent multi-leg view — document where you would add a versioned snapshot instead.
   - Thread affinity wastes a core when the ingest is idle. Acceptable given the always-on nature of market-hours trading; adjust for off-hours with a lighter wait strategy.

**Concepts to study:**
- Post 1.7 — *Zero-Lock Price Distribution*: "Trade-offs and Failure Modes" section.
- Post 2.6 — *Diagnosing the Jitter*: "Is It GC, or Is It Infrastructure?" section — ruling out network jitter and OS scheduling as latency sources before blaming the JVM.

---

## Bottleneck & Reflection Questions

These are the questions a Lead or Principal will ask when reviewing this system. Prepare complete answers — not just "I'd look into it."

1. **Ring buffer sizing under burst.** Your load test uses a steady 1,000,000 updates/second. In production, Asian market open and London overlap create bursts of 2–3x normal throughput for 10–30 seconds. What ring buffer size is required to absorb a 2.5M update/second burst for 5 seconds without filling? Show your calculation. What happens when the ring fills? Does `ringBuffer.next()` block the ingest thread?

2. **Consumer lag asymmetry.** Your three consumers (risk, UI, OMS) have very different processing costs: risk is fast (AtomicReference update), UI is slow (WebSocket write, possible network delay), OMS is medium. The Disruptor does not advance the producer past the *slowest* consumer. How does a slow UI handler affect risk engine latency? What architectural change would decouple slow consumers from the ring buffer? (Hint: a secondary Disruptor, or drop-on-lag policy for the UI handler.)

3. **ZGC concurrent thread contention.** ZGC's concurrent marking and relocation threads share CPU with your ingest and consumer threads. At 1,000,000 updates/second on a 16-core machine, how many ZGC concurrent threads can you afford to allocate without starving the ingest thread? What JVM flag controls ZGC's concurrent thread count? (`-XX:ConcGCThreads`)

4. **Deoptimisation under load.** A new version of the `InstrumentDescriptor` class is deployed via a hot reload while the service is live. The C2 compiler de-optimises the `PriceNormaliser.apply` method. For how long does the method run at interpreted speed? How would you detect this with `-Xlog:jit+deoptimization=info`? What is the impact on p99.9 latency during the re-warm window?

5. **NUMA and memory bandwidth.** Your machine has two NUMA nodes (2 × 8 cores). If the ingest thread is on Node 0 and one consumer thread is on Node 1, every `ringBuffer.get(sequence)` by that consumer crosses the NUMA interconnect. How much latency does a cross-NUMA load add (typical: 80–120ns extra)? How would you detect this with `numastat` or `perf stat -e node-load-misses`?

6. **Observability gap at p99.9.** Your Micrometer timer uses fixed buckets and loses resolution above p99. Why does this matter for a system with a p99.9 latency SLA? How does HDRHistogram's approach differ? What is its memory cost for a histogram with 60-second recording window and nanosecond resolution?

7. **The architectural ceiling.** Even with the Disruptor, thread affinity, ZGC, and per-instrument `AtomicReference`, there is a ceiling: a single ingest thread and a single Disruptor ring. At what point would you need to shard across multiple Disruptors? What would the partitioning key be (instrument ID hash)? How would you ensure that per-instrument ordering is preserved across shards?

---

## Success Criteria

You have completed this exercise when:

- [ ] The service sustains 1,000,000 updates/second for 60 seconds in the load test.
- [ ] p99 latency is ≤ 500μs and p99.9 is ≤ 1ms under sustained load, validated with HDRHistogram.
- [ ] GC logs show zero pauses above 1ms (ZGC or Shenandoah — justified in writing).
- [ ] The ingest thread and Disruptor event processor threads are affinity-pinned.
- [ ] A warm-up phase is implemented and `isWarmedUp()` returns a meaningful signal.
- [ ] Micrometer metrics expose throughput, latency percentiles, ring buffer fill level, and consumer lag per handler.
- [ ] The capacity envelope document states three distinct scaling ceilings with measured data.
- [ ] The failure mode analysis covers all four scenarios from Step 7 with specific detection and recovery actions.
- [ ] You can answer all seven Bottleneck Questions verbally without referring to notes, drawing on concepts from Series 1 and Series 2 of the blog.
