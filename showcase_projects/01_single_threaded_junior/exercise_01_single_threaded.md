# Naive Single-Threaded Price Match Service — Exercise 01

## Objective

Build the simplest possible correct implementation of a High-Frequency Price Match Service: a single-threaded system that accepts price updates, maintains the latest price per instrument, and notifies registered listeners — all without threads, queues, or synchronisation primitives of any kind.

By the end of this exercise you will have a service that works correctly and is entirely comprehensible to a junior engineer. You will then load-test it to observe the hard ceiling of a single CPU core and understand exactly why concurrency is the next step — not a premature optimisation.

---

## Background & Motivation

Every high-performance system starts as a single-threaded prototype. Before optimising, you need a baseline that is unambiguously correct. A single-threaded implementation has no race conditions, no visibility problems, and no lock contention — because there is only ever one thread.

The embarrassing throughput you will measure here is intentional. It quantifies the problem. You cannot argue for multi-threading, lock-free data structures, or architectural changes until you can say: "The single-threaded ceiling is X updates/second. Our SLA requires 1,000,000. Therefore we need Y."

This exercise establishes X. Everything that follows is about closing the gap.

---

## System Specification

### Functional Requirements

- Accept price updates for up to 10,000 distinct instruments (e.g. `"EUR/USD"`, `"AAPL"`, `"BTC-USD"`).
- Each update is a tuple of `(instrumentId: String, price: double, timestamp: long)`.
- Maintain the latest known price for each instrument in a shared price cache.
- Support registration of `PriceListener` callbacks, each invoked synchronously when a new price arrives for a subscribed instrument.

### Non-Functional Requirements

- **Throughput target (aspirational):** 1,000,000 price updates per second. You will not meet this target. The target exists so you can measure how far short you fall.
- **Correctness:** After N sequential calls to `submit()`, the cache must reflect the latest price for each instrument and every listener must have been invoked exactly once per update.
- **No external dependencies:** Single JVM, in-process, no database, no message queue, no external frameworks.

### Constraints

- Java standard library only.
- No threads, no `ExecutorService`, no `synchronized`, no `volatile`, no atomics.
- Single JVM instance, no off-heap memory.
- The G1GC garbage collector with default heap settings.

---

## Step-by-Step Exercise Guide

### Step 1 — Define the Core Data Model

**What to implement:**

Create a `PriceTick` class with three fields: `instrumentId` (String), `price` (double), and `timestamp` (long). Make it immutable: fields assigned in the constructor, no setters, no mutation after construction.

**Key decisions:**
- Should `price` be `double` or `BigDecimal`? For this exercise, `double` is fine — note that `double` cannot represent all decimal fractions exactly (e.g. 0.1 has no exact binary representation). In real financial systems this matters; here it does not.
- Should `PriceTick` be a `record`? Java 16+ records are a clean fit for immutable value types. If your target JDK supports it, prefer `record` and note that it gives you `equals`, `hashCode`, and `toString` for free.

**Concepts to study:**
- What does "immutable" mean in Java? Why does immutability simplify reasoning about correctness, even in single-threaded code?

---

### Step 2 — Implement the Price Cache

**What to implement:**

Create a `PriceCache` class backed by a `HashMap<String, PriceTick>`. Expose two methods:

```
void update(PriceTick tick)
PriceTick getLatest(String instrumentId)
```

No `synchronized`. No `volatile`. No locks. This is a single-threaded system — thread safety is not a concern here.

**Key decisions:**
- Should `update` apply a last-write-wins policy, or should it discard updates whose `timestamp` is older than the current entry? Implement last-write-wins for simplicity; note the trade-off (out-of-order delivery from a slow producer would corrupt state).
- What does `getLatest` return when the instrument has never been seen? Return `null` and document this contract. Callers must handle it.

**Concepts to study:**
- What is the time complexity of `HashMap.put()` and `HashMap.get()` in the average case? What causes worst-case O(n) behaviour in a `HashMap`? (Hint: hash collisions.)
- What is `HashMap`'s default initial capacity and load factor? When does it resize?

---

### Step 3 — Implement the Listener Subscription Model

**What to implement:**

Create a `PriceListener` interface:

```java
interface PriceListener {
    void onPrice(PriceTick tick);
}
```

Create a `SubscriptionRegistry` backed by a `HashMap<String, List<PriceListener>>`. Expose:

```
void subscribe(String instrumentId, PriceListener listener)
List<PriceListener> getListeners(String instrumentId)
```

When `getListeners` is called for an instrument with no subscribers, return an empty list (never `null`).

**Key decisions:**
- Should `subscribe` allow the same listener to be registered twice for the same instrument? Define the contract and enforce it if needed (e.g. check with `contains` before adding).
- What happens if a listener's `onPrice` throws a `RuntimeException`? Does it prevent other listeners for the same instrument from being invoked? Decide on a policy and implement it consistently.

---

### Step 4 — Implement the Update Pipeline

**What to implement:**

Create a `PriceUpdateService` that composes `PriceCache` and `SubscriptionRegistry`. Expose a single method:

```
void submit(PriceTick tick)
```

The implementation is deliberately trivial:

```
// Pseudocode — do not copy verbatim
void submit(PriceTick tick):
    cache.update(tick)
    for listener in registry.getListeners(tick.instrumentId):
        listener.onPrice(tick)
```

There is no queue. There is no background thread. The calling thread executes everything synchronously and returns from `submit()` only after all listeners have been notified.

**Key decisions:**
- Should `submit` validate that `tick` is not null? Add a null check at the boundary and throw `IllegalArgumentException` — this is the outermost entry point and the only place where external input arrives.
- Should listener notification happen inside or outside the `update` call? Keep them separate — `PriceCache` owns the cache state, `PriceUpdateService` owns the dispatch logic.

**Concepts to study:**
- What is the call stack depth when `submit()` invokes 10 listeners each of which do a simple counter increment? Is there any risk of `StackOverflowError`? (No — but thinking through this builds the habit of reasoning about execution paths.)

---

### Step 5 — Write a Single-Threaded Load Test

**What to implement:**

Write a `PriceLoadTest` class with a `main` method (not a JUnit test). The test must:

1. Create a `PriceUpdateService` with 10,000 instruments pre-registered.
2. Register one `PriceListener` per instrument that increments a `long` counter (a plain `long` field is fine — single thread, no races).
3. Call `submit()` in a tight loop for 30 seconds, cycling through all 10,000 instruments.
4. After 30 seconds, print:
   - Total updates submitted.
   - Total updates delivered to listeners (should equal total submitted — verify this).
   - Throughput (updates/second).

**Key decisions:**
- Use `System.nanoTime()` for timing, not `System.currentTimeMillis()`. Why? `System.currentTimeMillis()` can jump backwards when the OS clock is adjusted (NTP slew). `System.nanoTime()` is monotonic and wall-clock-independent.
- Allocate a new `PriceTick` per iteration: `new PriceTick(instrument, price, System.nanoTime())`. This is the naive approach — note the allocation rate.

**Concepts to study:**
- What is JVM JIT warm-up? Your first few thousand iterations may run as bytecode interpreter rather than compiled native code. How would you add a warm-up phase to avoid counting slow early iterations in your throughput measurement? (Run for 5 seconds without measuring, then start the 30-second timed window.)

---

### Step 6 — Observe the Single-Thread Ceiling

**What to run:**

Add GC logging to your JVM startup flags:

```
-Xlog:gc*:file=gc-single.log:time,uptime,level,tags
```

Run your load test and observe:

1. **Throughput:** Your actual peak updates/second. Record this number — it is your baseline for the entire series.
2. **Correctness check:** Confirm that `totalSubmitted == totalDelivered`. If they differ, your listener is broken.
3. **GC log:** How frequently are Young GC events occurring? Each `new PriceTick()` call contributes to allocation pressure. At your measured throughput, calculate the allocation rate in MB/s (each `PriceTick` is roughly 40–56 bytes).
4. **CPU:** Run `top` or `htop` during the load test. Is one CPU core at 100%? Are the other cores idle? This is the definitive proof that you are CPU-bound on a single thread.

You do not need to fix anything yet. Observe, record numbers, and prepare to answer the Bottleneck Questions below.

#### First Run

> make run-gclog
Total submitted: 492007096
Total delivered: 492007096
Throughput: 1.6400236533333333E7 updates/second

(~16_400_236 updates/sec)

---

## Bottleneck & Reflection Questions

Think through each question before moving to Exercise 02.

1. **The single-thread ceiling.** Your load test shows that one thread can process roughly X updates/second. At 1,000,000 updates/second, you need approximately `1,000,000 / X` threads to meet the target (ignoring overhead). What was your measured X? Does that calculation suggest parallelism is the right lever?

> A: 1,000,000 / 16,500,000 ≈ 0.06 threads

2. **Synchronous listener invocation.** Each call to `submit()` invokes all registered listeners before returning. If a listener does expensive work (e.g. serialises a message and sends it over a socket), how does that affect throughput? What would the call-graph look like in a profiler? Is the bottleneck in `PriceCache.update()` or in listener dispatch?

> A: Synchronous listener invocation A slow listener (onPrice does socket I/O, serialisation) blocks submit() — the caller can't proceed until the listener finishes. The call-graph in a profiler would show submit() at the top, with listener time nested inside it. Bottleneck is in the listener, not cache.update().

3. **Allocation rate.** At your measured throughput, calculate: `throughput × sizeof(PriceTick)` in MB/s. Compare this to a typical L1 cache size (32–64KB) and TLAB size (512KB–4MB). How quickly are you filling TLABs? How does this manifest in the GC log?

> A: Allocation rate At 16.5M updates/second × ~60 bytes per tick ≈ 990 MB/s allocation. TLAB fills in ~1ms; GC fires every ~80ms collecting ~80 TLABs. Each Young GC pause is ~0.5ms — 0.6% overhead, sustainable. System is in equilibrium until thread count increases.


4. **Why parallelism won't simply fix this.** If you spawn 4 producer threads all calling `submit()` simultaneously with the current implementation, what will happen? (Hint: they will all share the same `HashMap` without any protection. Think about what `HashMap` does when two threads call `put()` concurrently.) This is exactly what Exercise 02 explores.

> A: Two threads without synchronisation HashMap.put() and HashMap.get() are not thread-safe. Concurrent writes cause: lost updates (one thread's write overwritten by another), hash chain corruption, and undefined internal state. SubscriptionRegistry and PriceCache both corrupt. submitted != delivered.

5. **Profiling the hot path.** If you ran a CPU profiler (JFR, async-profiler, or YourKit) on this load test, which method would dominate the CPU samples? `HashMap.put()`? `HashMap.get()`? `PriceTick` construction? Reason through it before profiling, then verify.

> A: Profiling the hot path CPU profiler: StringBuilder.append() and String.valueOf() inside "INST-" + instrumentIndex dominate. HashMap.put() also appears but is lighter. Listener is a no-op so onPrice() is invisible. Allocation profiler shows PriceTick and String creation at ~990 MB/s.

6. **The correct-but-slow trade-off.** This implementation is trivially correct and trivially slow. Name three properties of this design that make it correct. Name three properties that make it slow. This question has a precise answer — write it down before moving on.

> A: Correct-but-slow trade-off 
    Correct: 
        (1) No race conditions — one thread, sequential state transitions. 
        (2) No visibility problems — all writes visible to subsequent ops. 
        (3) No lock contention — zero synchronisation overhead, trivially reasoning about state. 
    Slow: 
        (1) Single CPU core — ceiling at one thread's throughput. 
        (2) Synchronous dispatch — slow listener blocks the pipeline. 
        (3) High allocation rate — ~990 MB/s creates GC pressure even at 0.5ms pauses

---

## Success Criteria

You have completed this exercise when:

- [ ] The service runs correctly under single-threaded load: `totalSubmitted == totalDelivered` for a 30-second run.
- [ ] You have measured actual throughput (updates/second) and recorded it as your baseline.
- [ ] You have GC logs showing pause frequency and allocation rate under load.
- [ ] You have verified that a single CPU core is at 100% and all other cores are idle during the test.
- [ ] You can answer all six Bottleneck Questions verbally, with specific numbers from your measurements.
- [ ] You have written down what you expect to happen when you add a second thread without any synchronisation — that prediction is what Exercise 02 tests.

---

## Reviewer Verdict

**🔁 REVISIT — Specific Gap**

### What was demonstrated well

- Functional correctness: `totalSubmitted == totalDelivered` (480,782,691) confirmed over a 30-second run.
- Throughput measured at ~16M updates/second — a credible single-thread baseline.
- GC logs produced and interpreted: G1 collecting every ~80ms with ~0.6ms pauses, Eden filling to ~112-117MB per cycle, consistent with ~960 MB/s allocation rate.
- Q5 answered correctly: `"INST-" + instrumentIndex` via `StringBuilder` is the dominant allocation pressure, not `PriceTick` construction itself.
- Q1 partial comprehension: correctly identified that 16M >> 1M target, and that parallelism + real-world listener costs are what drive the need for scaling.

### The specific gap

**Q2 (synchronous listener invocation) and Q3 (TLAB/GC calculation) were imprecise; Q4 lacked the specific mechanism.**

Q2 required recognising that in a synchronously-dispatched system, `submit()` is blocked inside `listener.onPrice(tick)` for the entire duration of the listener's work. The bottleneck is in the listener — `cache.update()` is irrelevant when the listener is slow. The call stack must be traced: main thread → `submit()` → `cache.update()` → `registry.getListeners()` → **`listener.onPrice(tick)` ← socket I/O happens here, caller is blocked**.

Q3 required working through: allocation rate (960 MB/s) ÷ TLAB size = TLABs per second; then ÷ GC interval to get TLABs per GC cycle. The exercise's reference answer gives ~80 TLABs per ~80ms cycle, but the engineer's answer ("TLABs 1MB > 80 TLABs between events") did not complete the reasoning.

Q4 required naming the specific mechanism — `HashMap.put()` is not thread-safe; two threads calling `put()` concurrently can overwrite entries or corrupt the hash-chain. "Lost updates and data corruption" names the symptoms, not the mechanism.

### Actionable direction to close the gap

Trace the `submit()` call stack on paper with a slow listener. Draw it: `main → submit() → cache.update() → registry.getListeners() → listener.onPrice(tick)`. Label where socket I/O occurs and which thread is blocked at each step. Once that stack is traceable and the blocking relationship is explainable in words, the core learning objective of Exercise 01 is met.
