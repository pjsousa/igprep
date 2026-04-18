# False Sharing: The Silent Cache-Line Killer in Multithreaded Java

When two Java threads update logically independent fields and yet somehow slow each other down — even when both fields are protected by their own `AtomicReference` or `volatile` — the culprit is almost always **false sharing**.

False sharing is a hardware-level phenomenon that can hollow out the performance of a high-throughput system before you even know it's happening. For a Tech Lead working on a price-streaming engine processing millions of updates per second, understanding this is not optional.

## How CPU Cache Lines Work

Modern CPUs don't read memory one byte at a time. They fetch data in **cache lines** — contiguous 64-byte chunks of memory that are loaded into L1 cache as a single atomic unit. When a core reads a memory location, the entire cache line containing that location is loaded, not just the 8-byte `long` you asked for.

Each CPU core has its own private L1 (and L2) cache, with L3 shared across cores on the same socket. When Core A writes to a field in a cache line it owns, Core B's copy of that same cache line is immediately **invalidated**. Core B must re-fetch the line from memory (or L3) before it can read that location again.

```
        L1 Cache          L1 Cache
       ┌────────┐         ┌────────┐
Core A │  [X]   │  ←──────→  [Y]   │ Core B
       └────────┘  invalidate
            ↑            ↑
            └────────────┘
              64-byte cache line
```

This is the MESI cache-coherency protocol in action. It's correct and necessary for correctness — but it becomes a performance problem when two *unrelated* variables happen to share the same cache line.

## The Problem in Java

Consider a naive price-ticker class used by two separate threads: one updating the bid price, another updating the ask price.

```java
public class PriceTicker {
    private long bidPrice;
    private long askPrice;

    public void setBid(long price) { this.bidPrice = price; }
    public long getBid() { return this.bidPrice; }

    public void setAsk(long price) { this.askPrice = price; }
    public long getAsk() { return this.askPrice; }
}
```

In memory, `bidPrice` and `askPrice` sit adjacent to each other — likely within the same 64-byte cache line. Thread 1 on Core A updates `bidPrice` constantly (every price tick). Thread 2 on Core B updates `askPrice`. Every update by Thread 1 invalidates the cache line that Thread 2 is reading from, forcing a re-fetch. Thread 2's updates do the same to Thread 1.

The result: both threads are constantly waiting on cache-line transfers instead of doing useful work. Your 10-core machine behaves more like a 2-core machine for this workload.

In a real order-book system with dozens of fields — bid, ask, last-trade, open, high, low, volume — spread across multiple threads, the contention is severe.

## Detecting False Sharing

False sharing is notoriously hard to diagnose with standard profilers because the symptoms look like ordinary lock contention or high CPU usage. The telltale sign is **high CPU utilization with low useful work**: threads are running but stalling on memory, not computing.

Two tools that surface this:

**JMH (Java Microbenchmark Harness)** — benchmarks with `@Affinities` or `State` annotations can reveal cache-line pressure. A benchmark that shows throughput collapsing as thread count increases is a strong signal.

```java
@Benchmark
@Threads(4)
public void measureFalseSharing(Blackhole bh) {
    for (int i = 0; i < 1_000_000; i++) {
        ticker.setBid(i);
        bh.consume(ticker.getBid());
    }
}
```

**async-profiler** with `cache-line` mode (or `perf` with cache-line analysis) can show which memory locations are causing cross-core traffic. You look for cache-miss rates that spike unexpectedly on logically idle threads.

You can also verify the hypothesis by looking at hardware counters: on Linux, `perf stat -e cache-references,cache-misses` will show cache-miss ratios. A ratio above ~10% for L1 misses on a concurrent benchmark is a red flag.

## Fix 1: Manual Padding

The most straightforward fix is to ensure each field lands on its own cache line. Insert unused padding fields to push adjacent fields onto separate lines:

```java
public class PaddedPriceTicker {
    // Unused padding fields — 8 bytes each
    private long p0, p1, p2, p3, p4, p5, p6, p7;
    private long p8, p9, p10, p11, p12, p13, p14, p15;

    private volatile long bidPrice;  // now isolated on its own cache line (at offset 128)

    // Another padding gap
    private long q0, q1, q2, q3, q4, q5, q6, q7;
    private long q8, q9, q10, q11, q12, q13, q14, q15;

    private volatile long askPrice; // isolated on its own cache line (at offset 256)

    // ... rest unchanged
}
```

This is the approach used by the LMAX Disruptor: its `Sequence` class contains a 128-byte field dedicated to a single `long` value, guaranteeing no two sequence numbers share a cache line. The cost is memory — but in a ring buffer with a fixed number of slots, it's a worthwhile trade.

## Fix 2: `@Contended` Annotation

JDK 8 introduced `@jdk.internal.vm.annotation.Contended` for exactly this purpose. Mark a field as contended and the JVM will pad it to its own cache line automatically:

```java
public class ContendedPriceTicker {
    @jdk.internal.vm.annotation.Contended
    private volatile long bidPrice;

    @jdk.internal.vm.annotation.Contended
    private volatile long askPrice;
}
```

With `@Contended`, the JVM adds the necessary padding behind the scenes. This is cleaner than manual padding and is the mechanism the JDK itself uses internally — `AtomicLong`, `AtomicReferenceArray`, and the Disruptor's sequence fields all use `@Contended` or equivalent padding.

Note: `@Contended` requires `--add-opens=jdk.internal.vm.annotation=ALL-UNNAMED` on the JVM command line to access, or you can use the internal annotation directly in JDK code. In production trading systems, this is typically enabled as part of the low-latency JVM profile.

## Fix 3: `LongAdder` Instead of `AtomicLong`

JDK 8's `LongAdder` (from `java.util.concurrent`) is designed specifically to mitigate false sharing in high-contention accumulation scenarios. Instead of a single `AtomicLong` value that all threads contend on, `LongAdder` maintains an array of counters — one per thread — and sums them on `sum()`. Each thread's slot lands on a separate cache line, eliminating the contention:

```java
// Instead of this (high contention on single cache line):
private final AtomicLong tickCount = new AtomicLong(0);

// Do this (each thread writes to its own cache line):
private final LongAdder tickCount = new LongAdder();
```

The tradeoff is memory (the array is sized to the number of cores) and the cost of `sum()` (which must aggregate all slots). For high-frequency counters in price feeds, this is almost always the right trade.

## Why This Matters in Production

In a price-streaming system running at 2 million updates per second across 8 cores, false sharing can reduce effective throughput by an order of magnitude. The latency histogram that should show sub-microsecond median latencies instead shows multi-millisecond tails — not because of GC, not because of locks, but because threads are evicting each other's cache lines.

This is the kind of problem that a Tech Lead must be able to reason about when reviewing concurrent code. Checking field layout in a performance-critical class, verifying that hot fields are `@Contended`, and understanding whether a `LongAdder` is more appropriate than an `AtomicLong` — these are not premature optimisations. They are the difference between a system that meets its SLA and one that doesn't.

The mechanical sympathy mindset — thinking from silicon up to Java — is what separates a candidate who knows *what* to write from one who knows *why* it will be fast.