# Senior-Level Price Match Service — Exercise

## Objective

Take the naive Price Match Service from Exercise 01 and transform it into a lock-free, cache-line-aware, allocation-efficient system — without changing the overall architecture. Same ingestion pipeline, same listener model, same single-JVM scope. Every change you make is a focused, surgical optimisation grounded in hardware-aware Java.

By the end of this exercise you should be able to sustain **300,000–600,000 updates/second** on commodity hardware and explain exactly which hardware or JVM constraint limits further scaling. The remaining gap to 1,000,000 updates/second points directly toward the architectural changes in Exercise 03.

---

## Background & Motivation

Exercise 01 taught you *where* the bottlenecks are. This exercise teaches you *how to remove them* using the techniques from **Series 1 — Lock-Free Java: Mechanical Sympathy for the Trading Floor**.

The naive implementation had four compounding problems:

| Problem | Root cause | The fix you will apply |
|---|---|---|
| `synchronized` on every update | Kernel mutex contention — context switches when the lock is contested | Replace with CAS-based lock-free state |
| `ArrayBlockingQueue` with a shared lock | Single `ReentrantLock` for all producers and the consumer | Replace with a lock-free, multi-producer SPSC structure |
| New `PriceTick` per update | Eden saturation, TLAB refill stalls, Minor GC pauses | Replace with a fixed object pool |
| Unpadded shared state | False sharing between independently-updated `long` fields | Add cache-line padding via `@Contended` or manual padding |

None of these fixes requires changing your API surface or adding a new dependency on the LMAX Disruptor (that comes in Exercise 03). This exercise is about proving fluency with the underlying primitives: CAS, `AtomicReference`, false sharing mitigation, and memory barriers.

**Series 1 posts to study before starting:**
- Post 1.1 — *CAS Explained: The Atomic Primitive That Replaced the Lock*
- Post 1.2 — *AtomicReference: CAS Applied to Object State in Java*
- Post 1.3 — *False Sharing: The Silent Cache-Line Killer in Multithreaded Java*
- Post 1.4 — *Memory Barriers: What the JVM Guarantees (and What It Doesn't)*

---

## System Specification

### Functional Requirements

Same as Exercise 01:
- Accept price updates for up to 10,000 instruments.
- Maintain the latest known price for each instrument.
- Deliver updates to registered `PriceListener` callbacks.
- Support multiple concurrent producers.

### Non-Functional Requirements

- **Throughput target:** 300,000–600,000 updates/second sustained over 30 seconds (up from Exercise 01's baseline, still short of the 1M target — that gap motivates Exercise 03).
- **Latency target:** p99 update-to-listener latency below 100 microseconds under sustained load. You will measure this for the first time in this exercise.
- **GC target:** Young GC pauses below 10ms, frequency below 1 per second.

### Constraints

- Java standard library + `jdk.internal.vm.annotation.Contended` (available without `--add-opens` on JDK 17+ with `-XX:-RestrictContended`).
- No LMAX Disruptor, no OpenHFT, no off-heap memory.
- Single JVM, single process.

---

## Step-by-Step Exercise Guide

### Step 1 — Replace `synchronized PriceCache` with a Lock-Free AtomicReference Cache

**What to implement:**

Replace the `HashMap<String, PriceTick>` behind a `synchronized` block with an `AtomicReference<Map<String, PriceTick>>` using a CAS-based copy-on-write update pattern:

```
// Pseudocode — do not copy verbatim
do {
    snapshot = ref.get()
    updated  = new HashMap(snapshot)  // copy
    updated.put(instrumentId, tick)
} while (!ref.compareAndSet(snapshot, updated))
```

**Key decisions:**
- This copy-on-write pattern is correct but still allocates a new `HashMap` per update. Is that acceptable? Think about allocation rate vs lock contention as a trade-off. For now, accept it — Step 3 will address allocation.
- The read path (`getLatest`) becomes `ref.get().get(instrumentId)` — completely lock-free. Reads from multiple threads never contend with each other or with writers.
- Should you use `Collections.unmodifiableMap()` on the snapshot? Think about whether the listeners or other readers might accidentally mutate the returned map.

**Concepts to study:**
- Post 1.2 — *AtomicReference*: the `compareAndSet(expected, update)` contract.
- Why does CAS produce a linearisable result without a mutex? What does "linearisable" mean in this context?
- What is the ABA problem? Does it apply here? (Hint: we're replacing an entire map reference, not a node pointer — think about why ABA is or isn't a risk in this specific scenario.)

**Validation:**
- Re-run the Exercise 01 load test. Throughput should increase. Confirm that the `jstack` output no longer shows threads blocked on `PriceCache`'s monitor.

---

### Step 2 — Eliminate False Sharing on the Throughput Counters

**What to implement:**

Your load test tracks submitted and delivered counts using two `AtomicLong` fields, likely declared adjacent to each other in the same class:

```java
private AtomicLong submittedCount = new AtomicLong();
private AtomicLong deliveredCount = new AtomicLong();
```

These two fields almost certainly share a 64-byte cache line. When the producer threads increment `submittedCount` and the dispatcher thread increments `deliveredCount`, every update by one invalidates the other's cache line on all cores — even though they are logically independent.

Fix this by padding both fields to occupy their own cache lines:

```java
@jdk.internal.vm.annotation.Contended
private AtomicLong submittedCount = new AtomicLong();

@jdk.internal.vm.annotation.Contended
private AtomicLong deliveredCount = new AtomicLong();
```

Run with `-XX:-RestrictContended` to allow `@Contended` on user classes. Alternatively, implement manual padding by wrapping each `AtomicLong` in a class that has 7 `long` fields on each side (56 bytes of padding on each side of the value, filling a 64-byte cache line).

**Key decisions:**
- How do you verify that false sharing is actually occurring before you fix it? Consider using JMH with `@BenchmarkMode(Throughput)` on a micro-benchmark that increments two adjacent `AtomicLong` fields from two threads — then repeat with padding and compare.
- Is `@Contended` available without `--add-opens`? What JVM flag is required?

**Concepts to study:**
- Post 1.3 — *False Sharing*: why two logically-unrelated fields on the same cache line cause cross-core invalidations.
- What is a cache line? How wide is it on modern x86 hardware (typically 64 bytes)?
- The LMAX Disruptor's `Sequence` class uses exactly this pattern — hand-padding to 56 bytes on each side. Skim the Disruptor source on GitHub to see it live.

**Validation:**
- Add a `@Contended` padded `AtomicLong` to your service's sequence counter (the one the dispatcher uses to track processed events). Measure throughput before and after. The gain may be 5–20% depending on the hardware — document it.

---

### Step 3 — Replace Per-Update Allocation with a Pre-Allocated Object Pool

**What to implement:**

Create a `PriceTickPool` that pre-allocates a fixed ring of `PriceTick` objects at startup and hands them out to producers using an `AtomicInteger` index:

```
// Pseudocode
class PriceTickPool {
    PriceTick[] pool  // pre-allocated at construction
    AtomicInteger cursor

    PriceTick acquire():
        index = cursor.getAndIncrement() % pool.length
        return pool[index]
}
```

Producers call `pool.acquire()`, populate the returned `PriceTick`, and submit it. The pool object is never put back — the ring wraps around and the producer overwrites the slot naturally (as long as the consumer is not still reading it).

**Key decisions:**
- Pool size: how large must the pool be to ensure a producer never overwrites a `PriceTick` the dispatcher is still reading? Think about the maximum number of in-flight updates between `acquire()` and the dispatcher completing its notification.
- Is `getAndIncrement()` safe under concurrent access? Yes — it uses CAS internally. But does modular indexing on `getAndIncrement()` with a non-power-of-two pool size cause issues? (Hint: integer overflow. How does the Disruptor handle this? It uses a power-of-two ring and a bitmask instead of modulo.)
- Should `PriceTick` fields be `volatile`? Think about memory visibility: the producer writes the fields, then a different thread (the dispatcher) reads them. Is there a happens-before relationship? If not, the dispatcher could see stale field values.

**Concepts to study:**
- Post 1.4 — *Memory Barriers*: when are `volatile` writes necessary to establish visibility across threads?
- Post 2.2 (JVM Internals) — *TLABs*: how object allocation rate drives TLAB refill stalls and Minor GC frequency.
- Compare your GC logs before and after adding the pool. How does allocation rate (MB/s) change? How does Minor GC frequency change?

**Validation:**
- Re-run the load test with `-Xlog:gc*`. The allocation rate should drop substantially. Document the before/after allocation rate as reported by JMC or `-Xlog:gc+tlab=debug`.

---

### Step 4 — Replace the BlockingQueue with a Lock-Free Hand-off

**What to implement:**

The `ArrayBlockingQueue` uses a `ReentrantLock` that serialises all producers competing to `put()`. Replace it with a multi-producer single-consumer (MPSC) lock-free queue.

Java does not provide one in its standard library, so you will build a simplified version using `AtomicReferenceArray` and CAS:

Design a fixed-size, power-of-two ring buffer where:
- Each slot is an `AtomicReference<PriceTick>`.
- Producers atomically claim a slot by CAS-incrementing a shared `AtomicLong producerSequence`.
- The dispatcher reads from a `consumerSequence` that only it advances, so no CAS is needed on the read side.

This is a simplified version of the pattern the LMAX Disruptor implements fully.

**Key decisions:**
- What happens if the ring wraps before the consumer has read old slots? You need to either block the producer (spinning until the slot is null) or overwrite (dropping stale prices). In a price-matching context, "latest wins" is usually correct — overwriting is acceptable.
- The producer's `producerSequence` and the consumer's `consumerSequence` should be padded to separate cache lines. Do this now using the technique from Step 2.
- Should the slot `AtomicReference` be null-initialised and set to null after consumption? This lets the producer detect whether the consumer has cleared the slot.

**Concepts to study:**
- Post 1.1 — *CAS*: how CAS enables lock-free slot claiming.
- Post 1.6 — *LMAX Disruptor*: the ring buffer is exactly this structure, refined. After implementing your version, re-read the Disruptor post and identify what it adds that your version lacks.

**Validation:**
- Re-run the load test. Throughput should now be limited by CPU speed and cache bandwidth, not by lock contention. Use `jstack` to confirm no threads are blocked waiting for a queue lock.

---

### Step 5 — Add Latency Instrumentation

**What to implement:**

Instrument the end-to-end path from `submit()` to listener invocation. Add a `startNanos` field to `PriceTick` (populated by the producer at submission time) and record the elapsed time in the dispatcher before invoking listeners.

Use a simple histogram — a `long[]` of 1,000 buckets, each representing 1 microsecond, covering 0–1,000μs — to accumulate the distribution. After the load test, print p50, p95, p99, and p99.9.

For production-quality histograms, read about HDRHistogram (referenced in the showcase article *Zero-Lock Price Distribution*) — but implementing it yourself is not required here.

**Key decisions:**
- `System.nanoTime()` returns elapsed nanoseconds from an arbitrary origin. It is monotonic on modern Linux with a constant TSC clock. Is it safe to call from multiple threads simultaneously? (Yes — it does not acquire a lock.)
- Why should you record the timestamp *before* the CAS that claims the queue slot, not after? (The CAS itself can spin under contention — you want to measure the full wait time, not just the dispatch time.)

**Concepts to study:**
- Why does mean latency mislead and p99 reveal? Under GC pressure or lock contention, the tail is far worse than the average.

---

### Step 6 — Re-Measure and Document the Remaining Gap

**What to run:**

With all four optimisations applied, repeat the full 30-second load test from Exercise 01 with identical parameters (4 producer threads, 10,000 instruments, maximum submission rate).

Document:
1. Achieved throughput (updates/second).
2. p50, p99, p99.9 latency (from your Step 5 histogram).
3. GC pause frequency and max pause duration (from GC logs).
4. The single remaining bottleneck that prevents reaching 1,000,000 updates/second.

The answer to point 4 is almost always: "The dispatcher is a single thread. No matter how fast each operation is, one thread processing one event at a time creates a theoretical ceiling of roughly `1s / dispatch_time_per_event`. Measure your per-event dispatch time and calculate that ceiling."

---

## Bottleneck & Reflection Questions

1. **CAS under contention.** When four producer threads all try to CAS-claim the next slot in the ring simultaneously, how many will succeed on the first attempt? What do the others do? What is the cost of a failed CAS vs the cost of a failed mutex acquisition? (Hint: a failed CAS is a few CPU cycles; a failed mutex acquisition can cause a kernel transition and a context switch.)

2. **Copy-on-write AtomicReference.** The lock-free price cache CAS-loops over a copied `HashMap`. At 300,000 updates/second, how many `HashMap` copies are being made per second? Is this sustainable? What alternative data structure would eliminate the copy while remaining lock-free? (Hint: `ConcurrentHashMap` with per-key `AtomicReference` — but think about what consistency guarantee that loses.)

3. **Memory barriers and the object pool.** You set `volatile` on `PriceTick` fields so the dispatcher sees the producer's writes. What specific barrier does a `volatile` write insert? What does a `volatile` read insert? Is this the cheapest option, or is there a lighter-weight approach? (Hint: `VarHandle.setRelease` + `VarHandle.getAcquire` use acquire/release semantics instead of full StoreLoad barriers — read Post 1.4 on memory barriers.)

4. **False sharing in the ring buffer itself.** If your producer and consumer sequences are packed adjacent in memory, every CAS by the producer invalidates the consumer's cache line, and vice versa. How did you verify this was happening (or not) in your implementation? What does `perf c2c` report on Linux?

5. **The single dispatcher ceiling.** Calculate the theoretical maximum throughput of your single dispatcher thread, given the average per-event cost (HashMap lookup + 1–2 listener callbacks). How does this compare to your measured throughput? What would you need to change architecturally to break through this ceiling without introducing locks?

6. **Object pool safety.** Your `PriceTickPool` can overwrite a slot the dispatcher is still reading if the pool is too small. How would you detect this in a test? What invariant should always hold: `producerIndex - consumerIndex ≤ poolSize`?

---

## Success Criteria

You have completed this exercise when:

- [ ] The `synchronized` block on `PriceCache` has been replaced with a CAS-based `AtomicReference` update.
- [ ] The `ArrayBlockingQueue` has been replaced with a lock-free ring buffer backed by `AtomicReferenceArray`.
- [ ] A `PriceTickPool` is in use and GC logs confirm a measurable reduction in allocation rate and Minor GC frequency.
- [ ] Producer and consumer sequences are padded to separate cache lines.
- [ ] You can report p99 latency (not just throughput) from your load test.
- [ ] You can explain the single remaining bottleneck that prevents reaching 1,000,000 updates/second and articulate what architectural change would address it.
- [ ] You can answer all six Bottleneck Questions verbally, with reference to specific blog posts from Series 1.
