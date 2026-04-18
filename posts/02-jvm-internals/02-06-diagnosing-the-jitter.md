# Diagnosing the Jitter: A JVM Latency Investigation in a Live Price Stream

## Introduction

At 14:32 on a Tuesday, your on-call phone lights up. The price-distribution service — the one that fans out normalised market data to downstream risk engines, UI clients, and compliance systems — is throwing latency alerts. The SLA for p99 latency is 10 milliseconds. The monitoring shows p99 sitting at 140ms and climbing.

You are the Tech Lead on call. What do you do?

This article walks through a full latency investigation of a live price-streaming JVM service: reading GC logs, profiling with Java Mission Control and async-profiler, identifying the root causes, and applying a remediation path that leads from a short-term workaround to a long-term architectural decision. Every step is what a Tech Lead would actually do — and every step is what an interviewer wants to hear you reason through.

---

## The Incident: A Price Stream Under Load

The service in question is a Java 17 application running on a 16-core machine, ingesting FAST-encoded market data from an exchange feed, normalising it into `PriceTick` objects, and publishing them onto an internal LMAX Disruptor ring buffer for downstream consumption.

Under normal load — roughly 800,000 price updates per second during open hours — the p99 latency sits comfortably below 5ms. But on this particular Tuesday, as Asian markets overlap with the London open, the ingestion rate climbs to 1.2 million updates per second. The p99 latency begins to climb: 20ms, 50ms, 80ms, then spikes into the 140–200ms range.

The on-call engineer (this time, you) pulls up the Grafana dashboard. The latency histogram shows a bimodal distribution — a tight cluster below 5ms and a long tail stretching past 150ms. The pattern is unmistakably a stop-the-world pause, not a throughput problem.

The investigation begins.

---

## Step 1: Reading the GC Logs

The first tool is always the humble GC log. You had it enabled with:

```bash
-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=50m
```

You download the latest log and open it. The first thing you notice is the sheer frequency of Young GC events:

```
[2024-11-12T14:31:42.183+0000][info][gc,age] GC(4123) Young GC: 847M->128M. 94.3ms.
[2024-11-12T14:31:42.287+0000][info][gc,age] GC(4124) Young GC: 901M->134M. 102.1ms.
[2024-11-12T14:31:42.401+0000][info][gc,age] GC(4125) Young GC: 876M->141M. 88.7ms.
[2024-11-12T14:31:42.519+0000][info][gc,age] GC(4126) Young GC: 923M->129M. 118.4ms.
```

These are not the <10ms pauses the G1GC is designed for. Under peak load, G1's evacuation pauses are stretching to 80–120ms. The pauses are also clustering: every ~100ms the application experiences a 100ms stall, which maps exactly to the latency spikes you're seeing in production.

You also spot a Mixed GC event buried in the log:

```
[2024-11-12T14:32:01.044+0000][warn][gc] GC(4156) Pause Young (Normal) 1240M->890M. 184.3ms. \
  GC-CCSet 124. Reason: critical pause ratio exceeded.
```

The G1 is triggering Mixed GCs — where it collects both Young and Old regions — because the old generation is filling up faster than G1 can collect it in the background. The 184ms pause corresponds exactly to one of the worst latency outliers.

**What this tells you:** The GC is definitely the culprit. But *why* is G1 struggling? That's the next question.

---

## Step 2: Java Mission Control — Allocation Rate as the Key Signal

You connect JMC to the live process (using JMX over a dedicated management port — you had this instrumented in advance, of course). The Memory view gives you the allocation rate over time.

The chart is striking. During normal hours, the allocation rate sits around 400 MB/s. During the incident window, it climbs to 1.1 GB/s. The dominant allocation source: a `PriceTick` object, allocated once per incoming market data update.

At 1.2 million updates per second, with each `PriceTick` being roughly 64–80 bytes (including the `InstrumentDescriptor` it wraps), you are allocating approximately 90–100 MB/s just in price ticks. But the allocation rate is showing 1.1 GB/s — an order of magnitude higher. Something else is allocating heavily as a side effect of the hot path.

You open the Allocator Allocations view. The top two allocation sites are:

1. `PriceTick.<init>` — the expected path, 31% of allocations.
2. `StringUTF16.compress` — 24% of allocations, triggered by the FAST decoder's string handling when building instrument identifiers.
3. `ReentrantLock$NonfairSync` — 18% of allocations, from a lock in the connection pool used to query the instrument reference data cache.

That lock contention is a red herring for the GC problem, but it's a performance problem in its own right. Back to the GC story: the allocation rate of 1.1 GB/s is saturating Eden. G1 is spending so much time evacuating live objects that the evacuation pauses are stretching.

**What this tells you:** Eden is too small relative to the allocation rate. Either the heap is undersized, the tenuring threshold is too aggressive, or the objects themselves are too large.

---

## Step 3: JIT Compilation View — A Deoptimisation Contribution

While you're in JMC, you also check the JIT Compilation view. You notice a hot method — `PriceNormaliser.apply` — had been running at Tier 4 (fully optimised C2 code) but experienced a deoptimisation event at 14:28, four minutes before the incident:

```
[2024-11-12T14:28:03.211+0000][info][jvm,compilation] \
  Deoptimise: PriceNormaliser.apply reason='class hierarchy change', size=3240 bytes.
```

A class hierarchy change — likely a dynamic CDS loading of an updated instrument reference class — caused the C2 compiler to revoke its optimistic assumption that `PriceNormaliser.apply` had only one concrete implementation. The method fell back to interpreted mode, then spent the next 90 seconds re-compiling.

During that re-warm window, the method was running at interpreted speed — roughly 5–10x slower than the optimised version. The extra CPU pressure from this caused the allocation rate to spike further, pushing G1 over its pause time budget.

This is not the primary cause, but it was a contributing factor that amplified the incident.

**What this tells you:** JVM warm-up is a production concern. A deoptimisation at the wrong time can cascade into a latency incident.

---

## Step 4: async-profiler — Confirming TLAB Refill Contention

You take a 60-second async-profiler capture on the live system (using the `async-profiler` agent attached at runtime, which has less than 5% overhead under load):

```bash
./async-profiler.sh profiler -d 60 -e alloc -f profile.html \
  -XX:+DebugNonSafepoints pid
```

The flame graph tells the story with surgical precision. The hottest stack shows:

```
[Wall] java.lang.Thread.run
  PriceIngestionLoop.poll
    FastDecoder.decode
      PriceNormaliser.apply         ← 34% of wall time
        InstrumentCache.get         ← 12%
          ReentrantLock.lock        ← 8%
    DisruptorRingBuffer.publish    ← 11%
      Unsafe.park                   ← 6% (scheduling jitter)
```

The `[alloc]` view confirms the TLAB story:

```
[Alloc] TLAB refill
  PriceTick.<init>                 890 MB/s
  StringUTF16.compress             620 MB/s
```

Thread Local Allocation Buffer refill events are showing up in the allocation profile — which means threads are exhausting their TLABs and blocking while the JVM hands them a new one. At 1.1 GB/s allocation rate across 16 threads, each thread's 2MB TLAB is exhausted in roughly 1 millisecond. The refill stall is contributing measurable latency on top of the GC pauses.

---

## Step 5: The Remediation Path

You now have three distinct problems to solve:

1. **Short-term:** GC pauses are too long because Eden is too small relative to the allocation rate.
2. **Medium-term:** G1GC is the wrong collector for this workload — its pause time is unbounded under allocation rate spikes.
3. **Long-term:** The `PriceNormaliser.apply` deoptimisation and the instrument cache lock contention are secondary issues that will compound under future load growth.

### Short-term: Object Pooling for PriceTick

The immediate fix is to reduce allocation pressure by reusing `PriceTick` objects. Instead of allocating a new `PriceTick` per update, the ingestion thread borrows a pre-allocated `PriceTick` from a pool, populates it, publishes it, and returns it to the pool.

```java
public class PriceTickPool {
    private final PriceTick[] pool;
    private final AtomicInteger index = new AtomicInteger(0);

    public PriceTickPool(int capacity) {
        pool = new PriceTick[capacity];
        for (int i = 0; i < capacity; i++) {
            pool[i] = new PriceTick();
        }
    }

    public PriceTick borrow() {
        return pool[index.incrementAndBitwise() % pool.length];
    }

    public void recycle(PriceTick tick) {
        tick.clear();
    }
}
```

This alone drops the allocation rate from 1.1 GB/s to approximately 300 MB/s (mostly the FAST decoder's byte buffer operations). Within minutes of deploying this change, the GC pause times drop to 20–30ms. Not acceptable for p99 < 10ms, but a significant improvement.

### Medium-term: Migrating to ZGC

G1GC's pause time is fundamentally bounded by the amount of live data it must copy during a stop-the-world evacuation. Under a 16 GB heap with 60% old-generation occupancy, that copy can take 100–200ms. ZGC is designed specifically to eliminate this problem.

You schedule a migration to ZGC:

```bash
-XX:+UseZGC
-XX:SoftMaxHeapSize=16G
-XX:MaxGCPauseMillis=1
-Xlog:gc*:file=zgc.log
```

ZGC achieves sub-millisecond pauses by performing all evacuation work concurrently — while the application threads are running. It uses coloured pointers (extra bits in the 64-bit object reference) to track object states (marked, remapped) and load barriers to ensure safe concurrent access during relocation.

After the migration, you validate with HDRHistogram. Under the same 1.2M updates/sec load:

| Collector | p50 | p95 | p99 | p99.9 |
|---|---|---|---|---|
| G1GC | 1.2ms | 8ms | 142ms | 201ms |
| ZGC | 0.8ms | 1.4ms | 2.1ms | 4.3ms |

The p99 drops from 142ms to 2.1ms. The incident is resolved.

### Long-term: LMAX Disruptor to Eliminate Allocation Entirely

Object pooling is a partial solution. But the deeper problem is that even pooled `PriceTick` objects still go through the heap, still generate GC pressure in the old generation over time, and still suffer TLAB refill stalls.

The long-term architectural decision is to move the critical path to a pre-allocated LMAX Disruptor ring buffer — exactly as described in [Series 1, Post 6: The LMAX Disruptor](/posts/01-6-lmax-disruptor.md). With a ring buffer, the `PriceTick` event objects are pre-allocated at startup and never promoted to old generation. Sequence numbers replace locks. The result is a completely GC-pause-free critical path.

This decision bridges Series 1 and Series 2 — mechanical sympathy meets JVM internals.

---

## Is It GC, or Is It Infrastructure?

Before declaring victory, you always need to rule out infrastructure as a contributing factor. Latency spikes that look like GC pauses can have other causes:

**Network jitter:** If the NIC is experiencing buffer exhaustion or interrupt coalescing latency, a price update can be delayed before it even reaches the JVM. Use `perf stat` for NIC interrupt counts and `cat /proc/interrupts` to check for unbalanced interrupt distribution across cores.

**OS scheduling:** If your ingestion thread is being pre-empted by other processes on the same host, the scheduling latency can manifest as application-level jitter. Pin the ingestion thread to an isolated core (see [Series 1, Post 5: Thread Affinity](/posts/01-5-thread-affinity.md)).

**Clock skew:** In a distributed system, if your price timestamps are taken from `System.nanoTime()` on a machine with a drifting TSC clock, you can get non-monotonic timestamp sequences that appear as delivery latency spikes.

In this case, `mpstat` and `perf stat` ruled out infrastructure — the interrupt rate and context-switch rate were normal. The problem was unambiguously JVM.

---

## Instrumentation Hygiene: Ongoing Observability

After the incident, you ensure the service is instrumented for ongoing observability. The key metrics for a low-latency price-streaming JVM are:

```java
// Micrometer metrics exposed to Prometheus
Counter priceUpdatesTotal = meterRegistry.counter("price.updates.total");
Timer publishLatency = meterRegistry.timer("price.publish.latency");
Gauge("price.pool.available", priceTickPool, Pool::availableCount);
```

```yaml
# Prometheus scrape config
- job_name: price-ingestion
  static_configs:
    - targets: ['price-ingestion:8080']
  metric_relabel_configs:
    - source_labels: [__name__]
      regex: 'price\..+'
      action: keep
```

For latency histograms, you use [HDRHistogram](http://hdrhistogram.org/) — it records the entire distribution and allows you to query p50, p95, p99, p99.9, and p99.99 from the same dataset, with no loss of resolution at the tail.

GC logs remain the first signal to check in any latency investigation. A Grafana dashboard with `GC_pause_ms` plotted against `price_publish_p99_latency` overlaid on the same timeline makes the correlation immediately visible.

---

## Lessons Learned

1. **Allocation rate is a first-class production metric.** If your service allocates at more than 500 MB/s, you are one traffic spike away from a GC-induced incident. Measure it, alert on it, and have a plan to reduce it.

2. **G1GC is not a low-latency collector.** Its pause time is bounded by the live set size. Under allocation pressure, it will miss its pause time target. ZGC and Shenandoah are the correct choices for latency-sensitive workloads.

3. **JIT warm-up is a production concern.** Deoptimisation events can cascade. Use Class Data Sharing (CDS), pre-warm your services with synthetic traffic before opening to production, and consider GraalVM AOT compilation for latency-critical paths.

4. **async-profiler is the most powerful latency profiling tool in your kit.** It has sub-microsecond overhead, requires no safepoint instrumentation, and produces flame graphs that immediately show where time is spent — in both wall-clock and allocation dimensions.

5. **Object pooling is a bridge, not a destination.** Pooling reduces allocation rate but doesn't eliminate it. The long-term answer for a price-streaming system is a pre-allocated ring buffer architecture.

---

## When to Reach for ZGC

ZGC is the right choice when:

- Your p99 latency SLA is sub-10ms and G1GC cannot consistently meet it.
- Your heap size is 8 GB or larger — ZGC's pause time guarantee is independent of heap size.
- You are running on JDK 11 or later (ZGC was production-ready from JDK 11; Generational ZGC from JDK 21).
- Your application is sensitive to tail-latency rather than throughput.

ZGC is **not** the right choice when:

- Your CPU is already at 80%+ utilisation — ZGC uses more CPU for its concurrent threads.
- You are memory-constrained — ZGC requires extra headroom for its in-heap metadata structures.
- Your application is throughput-bound rather than latency-bound — G1GC or Shenandoah may give you better overall throughput.

For a financial trading platform where latency is revenue and tail-latency directly impacts the risk engine's ability to hedge correctly, ZGC is almost always the right call.

---

## Summary

When a price-streaming JVM starts producing 100ms+ latency spikes, the diagnostic sequence is always the same: GC logs first to confirm GC as the cause, JMC to identify the allocation rate, async-profiler to pinpoint the allocation sites, and the JIT compilation view to rule out warm-up effects. The remediation follows a clear path: reduce allocation pressure short-term with pooling, migrate to ZGC medium-term, and consider a lock-free ring buffer architecture long-term.

As a Tech Lead, your job is not just to fix the incident — it is to instrument the system so the next incident is diagnosed in minutes, not hours. The investment in observability pays back on day one.
