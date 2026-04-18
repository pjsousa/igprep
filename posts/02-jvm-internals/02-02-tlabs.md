# TLABs: How the JVM Makes Object Allocation Fast (and When It Doesn't)

Every time you write `new PriceTick()` in a hot price-streaming loop, the JVM has to do something extraordinary: find and claim space in memory, without any lock, in nanoseconds. Thread Local Allocation Buffers — TLABs — are the mechanism that makes this possible. Understanding TLABs is not just a GC curiosity; it is a prerequisite for reasoning about allocation-rate-driven latency in any high-throughput Java system.

## The Problem: Allocation Without Contention

Without TLABs, every thread in a JVM would compete for the same Eden space. Every `new` object would require synchronisation — either a lock or an atomic instruction — to reserve memory before writing it. In a system that allocates 800,000 objects per second, that contention would become a bottleneck quickly.

TLABs solve this by giving each thread its own private slice of Eden, pre-reserved at thread creation. The thread allocates into its TLAB using a **bump pointer**: a simple pointer arithmetic operation that reserves the next available chunk. No locks, no CAS, no contention — just a single pointer update.

```
Thread 1 TLAB:  [ chunk | chunk | chunk | chunk | >>> free space >>> ]
                    ^
                 bump pointer moves here on each allocation
```

This is why `new Object()` in a hot loop is genuinely cheap on a per-call basis. The JVM can satisfy the allocation in a handful of CPU cycles.

## When TLABs Fill: The Refill Stall

Nothing is free. TLABs have a fixed size — by default determined dynamically by the JVM based on allocation rate and thread count, typically between 64 KB and 1 MB. When a thread exhausts its TLAB, it must request a new one. That request is where the stall occurs.

The refill sequence:

1. Thread's TLAB is exhausted.
2. Thread requests a new TLAB from Eden (or from the old generation if the object is large).
3. If Eden is fragmented or under pressure, this allocation may fail or require a wait.
4. The thread briefly stalls while the new TLAB is carved out.

Under extreme allocation pressure — say, a price-streaming pipeline receiving 1 million updates per second — TLAB refill events can cluster and create latency spikes visible in your p99 histograms. This is not a Full GC pause; it is a subtle, short stall that is easy to misdiagnose as "network jitter" if you don't know to look for it.

## Humongous Objects: The Direct-to-Old Path

The JVM has a threshold for object size relative to TLAB capacity. Objects that are too large to fit inside a TLAB are called **humongous objects** and are allocated differently: they bypass Eden entirely and go directly into the old generation.

```
Humongous object threshold ≈ 50% of TLAB size (typically ~32 KB – 50 KB depending on JVM)
```

The rationale is pragmatic — allocating a 200 KB object inside a per-thread TLAB would waste most of that TLAB. But allocating directly to old generation has a cost: these objects cannot move during Minor GC, they cannot be collected by the usual Eden sweep, and they create fragmentation pressure in old generation. If your price-tick contains a large byte array for payload, you may be creating humongous objects unintentionally.

Humongous allocations trigger a **Humongous Allocation** GC event, which often involves an immediate Old Gen GC pass. Under sustained high allocation rates of large objects, this can become a consistent source of pause time.

## Reading the GC Logs: TLAB Diagnostics

The JVM exposes TLAB behaviour through GC logging. To enable detailed TLAB output:

```bash
-Xlog:gc*,gc+tlab=debug:file=gc.log:time,uptime,level,tags
```

What you'll see in the logs:

```
[31.412s][info][gc, tlab] GC(5) TLAB: before allocation: thread=0x7f9a8c123800
  allocated=65280, refill_size=8192, allocs_before_gc=1243
[31.412s][debug][gc, tlab] GC(5) TLAB: gc_waste=1024, estimated_thresholds=21760
```

Key fields:
- **allocated**: how much of the TLAB was used before GC
- **refill_size**: the new TLAB size granted
- **allocs_before_gc**: how many allocations this thread performed before the GC
- **gc_waste**: wasted space at the end of the TLAB (a signal of poor TLAB sizing)

The `gc_waste` metric is particularly useful: if waste is high relative to TLAB size, your TLAB may be too large for the actual allocation pattern. Conversely, if `allocs_before_gc` is low (e.g. <100), your threads are refilling TLABs very frequently — a red flag for allocation-rate pressure.

You can also use `jmap` to inspect live TLAB statistics:

```bash
jcmd <pid> VM.native_memory summary
jcmd <pid> GC.heap_info
```

## Allocation Rate: The Metric That Predicts GC Pain

The single most important metric for understanding TLAB pressure is **allocation rate** — measured in bytes per second (or objects per second) that your application writes into Eden.

```
Allocation Rate = (Eden used before GC - Eden used after GC) / time interval
```

You can observe this in JMC (Java Mission Control) under the Memory > Alloc section. In a price-streaming pipeline with `PriceTick` objects:

- 1 million price updates/sec
- Each `PriceTick` ~256 bytes (instrument ID + price + timestamp + metadata)
- Allocation rate ≈ 256 MB/sec

At that rate, a 512 MB Eden will exhaust in ~2 seconds. If your Minor GC is running every 2 seconds and taking 15–20 ms, your p99 latency budget is already compromised.

The relationship is direct: **high allocation rate → frequent Minor GC → compounding latency from GC pauses**.

This is why in Series 1 of this blog series we discussed LMAX Disruptor's pre-allocated ring buffer — by reusing event objects instead of allocating fresh ones, the Disruptor eliminates the allocation-rate problem at its root.

## Why This Matters in Production

TLABs are invisible in normal development but become visible under load. When your latency histograms show sub-millisecond spikes clustering every few seconds, the root cause is often allocation rate and TLAB refill pressure — not the GC pause itself, but the micro-stalls that happen before GC is even triggered.

A Tech Lead debugging a production price-streaming service must be able to:

- Distinguish TLAB refill stalls from genuine Full GC pauses
- Read TLAB GC log output to diagnose refill frequency
- Know that humongous objects may be bypassing Eden unintentionally
- Understand that allocation rate, not object count, is the real pressure signal

The JVM gives you the tools to see this — `-Xlog:gc+tlab=debug` and JMC's allocation view. The difference between a Tech Lead who knows to look and one who doesn't is the difference between a root cause diagnosis in 30 minutes and a week of latency dashboards with no conclusion.

Understanding TLABs is part of the full stack: from hardware cache lines in Series 1, through the generational heap lifecycle here in Series 2, toward the Kafka reliability story in Series 3. Every post in this series touches the same underlying problem: **latency under pressure**. TLABs are the mechanism inside the JVM that makes allocation fast — until the rate of allocation overwhelms it.