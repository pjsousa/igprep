# Junior-Level Price Match Service — Exercise

## Objective

Build a working High-Frequency Price Match Service that accepts price updates for multiple instruments and notifies registered listeners of the latest price for each instrument. The service must handle concurrent producers and consumers using idiomatic Java — `synchronized`, `ArrayBlockingQueue`, and straightforward `HashMap` state.

By the end of this exercise you will have a service that works correctly under low load and breaks in predictable, measurable ways under the 1,000,000 updates/second target. Understanding *where* and *why* it breaks is the primary learning objective — not fixing it yet.

---

## Background & Motivation

Every high-performance system starts somewhere, and that somewhere is usually correct-but-slow. The naive implementation you build here uses the Java concurrency primitives most engineers reach for first: locks, blocking queues, and standard collections. These tools are safe and easy to reason about. They are also catastrophically inadequate at a million updates per second.

This exercise deliberately introduces four bottlenecks:

1. A shared mutable `HashMap` protected by a `synchronized` block — a single-file checkpoint that serialises all price updates.
2. An `ArrayBlockingQueue` that parks producer threads when consumers fall behind — introducing wait time on the critical path.
3. A new `PriceTick` object allocated per update — hammering Eden and triggering frequent Minor GCs.
4. No latency instrumentation — so the performance problem is invisible until the system melts.

The goal is not to make you feel bad about `synchronized`. The goal is to give you a concrete baseline whose failure modes you can observe, measure, and later explain in an interview.

---

## System Specification

### Functional Requirements

- Accept price updates for up to 10,000 distinct instruments (e.g. `"EUR/USD"`, `"AAPL"`, `"BTC-USD"`).
- Each update is a tuple of `(instrumentId: String, price: double, timestamp: long)`.
- Maintain the latest known price for each instrument in a shared price cache.
- Support multiple concurrent producers submitting updates.
- Support multiple concurrent consumers registering as `PriceListener` callbacks, each invoked when a new price arrives for their subscribed instruments.

### Non-Functional Requirements

- **Throughput target (aspirational):** 1,000,000 price updates per second. You will not meet this target. The target exists so you can measure how far short you fall.
- **Correctness:** No update should be lost or duplicated under concurrent load. Final state of the cache must reflect the latest update for each instrument.
- **No external dependencies:** Single JVM, in-process, no database, no message queue, no external frameworks.

### Constraints

- Java standard library only — no Disruptor, no Unsafe, no OpenHFT libraries.
- No off-heap memory.
- Run on a single JVM instance (no clustering, no inter-process communication).
- Use the default G1GC garbage collector with default heap settings.

---

## Step-by-Step Exercise Guide

### Step 1 — Define the Core Data Model

**What to implement:**

Create a `PriceTick` class that holds `instrumentId`, `price`, and `timestamp`. This is an immutable value object — once created, its fields do not change.

**Key decisions:**
- Should `price` be `double` or `BigDecimal`? Think about allocation cost vs precision. For this exercise, `double` is fine — note the trade-off.
- Should `PriceTick` implement `equals`/`hashCode`? You'll need this if you ever put ticks into a `Set` or use them as map keys.

**Concepts to study:**
- What does it mean for an object to be "immutable" in Java? Why does immutability simplify concurrent code?

---

### Step 2 — Implement the Price Cache

**What to implement:**

Create a `PriceCache` class backed by a `HashMap<String, PriceTick>`. Expose two operations:

```
void update(PriceTick tick)
PriceTick getLatest(String instrumentId)
```

Protect both operations with `synchronized` on the `PriceCache` instance. This makes the cache thread-safe.

**Key decisions:**
- Why not use `ConcurrentHashMap` here? It would be better — but the exercise asks you to use `synchronized` deliberately, so you feel the contention it creates under load.
- What happens if `update` is called with a timestamp older than the current entry? Should you discard it? Implement a last-write-wins policy for now.

**Concepts to study:**
- How does `synchronized` work at the JVM bytecode level (`monitorenter` / `monitorexit`)?
- What is a mutex? What is the cost of acquiring an uncontended vs a contended mutex?

---

### Step 3 — Implement the Listener Subscription Model

**What to implement:**

Create a `PriceListener` interface:

```java
interface PriceListener {
    void onPrice(PriceTick tick);
}
```

Create a `SubscriptionRegistry` that maps each `instrumentId` to a list of `PriceListener` instances. Expose:

```
void subscribe(String instrumentId, PriceListener listener)
List<PriceListener> getListeners(String instrumentId)
```

Use a `HashMap<String, List<PriceListener>>` protected by `synchronized`.

**Key decisions:**
- Should listener invocation happen inside the `synchronized` block or outside it? Think about lock duration and potential for listener code to hold the lock while doing slow work (e.g. WebSocket push).
- What happens if a listener throws? Does it prevent other listeners from being invoked?

---

### Step 4 — Implement the Update Pipeline

**What to implement:**

Create a `PriceUpdateService` that:

1. Accepts `PriceTick` submissions from producers via a `submit(PriceTick tick)` method.
2. Internally uses an `ArrayBlockingQueue<PriceTick>` (capacity: 10,000) as the hand-off point between producers and a dispatcher thread.
3. Runs a single dispatcher thread that drains the queue, updates the `PriceCache`, and notifies `PriceListener`s.

**Key decisions:**
- What should happen when the queue is full and a producer calls `submit()`? `put()` blocks; `offer()` drops. Choose one and note the trade-off — blocking producers protects correctness at the cost of back-pressure on the producer; dropping sacrifices completeness for throughput.
- Should the dispatcher thread be a daemon thread? If your JVM exits while the queue still has items, they will be lost.
- How many dispatcher threads do you need? Start with one. Note what happens to throughput when the single thread becomes the bottleneck.

**Concepts to study:**
- `ArrayBlockingQueue` uses a single `ReentrantLock` internally — what does this mean for concurrent `put` calls from multiple producer threads?
- What is the difference between `put()` and `offer()` in `BlockingQueue`?

---

### Step 5 — Write a Load Test

**What to implement:**

Write a `PriceLoadTest` class (not a JUnit test — a `main` method that runs for a fixed duration). The test should:

1. Start the `PriceUpdateService`.
2. Spawn **4 producer threads**, each continuously submitting price updates for 2,500 instruments at maximum speed for 30 seconds.
3. Register a `PriceListener` for every instrument that increments an `AtomicLong` counter each time it is invoked.
4. After 30 seconds, print:
   - Total updates submitted.
   - Total updates delivered to listeners.
   - Throughput (updates/second).
   - Drop rate (submitted - delivered).

**Key decisions:**
- Use `System.nanoTime()` for timing, not `System.currentTimeMillis()`. Why?
- How will you coordinate clean shutdown across producer threads and the dispatcher?

**Concepts to study:**
- What is `AtomicLong` and why is it safe to use from multiple threads without `synchronized`?

---

### Step 6 — Observe the Bottlenecks

**What to run:**

Add GC logging to your JVM startup flags:

```
-Xlog:gc*:file=gc-naive.log:time,uptime,level,tags
```

Run your load test and observe:

1. **Throughput:** What is your actual peak updates/second? Compare to the 1,000,000 target.
2. **GC log:** How frequently are Young GC events occurring? How long are the pauses?
3. **Queue depth:** Add a `Gauge` that samples `queue.size()` every 100ms. Does it grow to capacity? This indicates that the dispatcher thread is slower than the producers.
4. **Thread contention:** Use `jstack <pid>` during the run. How many producer threads are blocked on `ArrayBlockingQueue.put`?

You do not need to fix these problems yet. Observe them, write them down, and prepare to answer the Bottleneck Questions below.

---

## Bottleneck & Reflection Questions

These questions expose the failure modes of the naive implementation. Think through each one before moving to Exercise 02.

1. **The lock is always hot.** Every price update — from every producer thread — must acquire the `synchronized` lock on `PriceCache`. At 1,000,000 updates/second from 4 threads, the lock contention rate is extreme. What does the JVM do when a thread cannot acquire a lock? How does this manifest in your `jstack` output?

2. **One dispatcher, one lane.** The single dispatcher thread drains the queue sequentially. Even if the dispatcher is fast, it processes updates one at a time. What is the theoretical maximum throughput of a single thread performing `HashMap.put()` + listener notification per iteration? How would you measure this ceiling?

3. **A new PriceTick per update.** Every call to `submit()` creates a new `PriceTick` object. At 1,000,000 updates/second, this is 1 million object allocations per second. Each `PriceTick` is roughly 40–60 bytes (object header + 3 fields). What is the allocation rate in MB/s? How does this compare to typical TLAB sizes? What GC event does TLAB exhaustion trigger?

4. **ArrayBlockingQueue is a contention funnel.** `ArrayBlockingQueue` uses a single `ReentrantLock` for both `put` and `take`. Every producer and the dispatcher share this lock. How many threads are competing for it in your setup? What would `jstack` show you?

5. **Listener invocation under the lock.** If you chose to invoke listeners inside the `synchronized` block on `PriceCache`, what happens when a listener does a slow operation (e.g. sends a WebSocket message)? How long is the lock held? Who is blocked while it is held?

6. **No latency measurement.** You measured throughput (updates/second) but not latency (time from `submit()` to listener invocation). How would you measure this? Why does tail latency (p99, p99.9) matter more than mean latency for a price-distribution SLA?

---

## Success Criteria

You have completed this exercise when:

- [ ] The service runs correctly under single-threaded load (correct final cache state, all listeners invoked).
- [ ] The load test runs to completion without deadlock or data corruption.
- [ ] You have measured actual throughput and can state how far short of 1,000,000 updates/second it falls.
- [ ] You have GC logs showing pause frequency and duration under load.
- [ ] You can answer all six Bottleneck Questions verbally, pointing to evidence from your measurements.
- [ ] You have written down a list of the specific changes you believe would most improve throughput — without implementing them yet (that is Exercise 02).
