# Naive Multi-Threaded Price Match Service — Exercise 02

## Objective

Take the single-threaded service from Exercise 01 and introduce multiple producer threads — without adding any synchronisation. The result is a system that appears to work under light load and silently corrupts data under heavy load.

By the end of this exercise you will have **observed** data races in a running JVM: lost updates, stale reads, and counter corruption that you can reproduce on demand. Understanding *how* to detect races empirically is more valuable than knowing they exist theoretically.

---

## Background & Motivation

Exercise 01 showed you the throughput ceiling of a single thread. The natural response is: add more threads. If one thread processes X updates/second, then four threads should process 4X, right?

The instinct is correct. The execution is where engineers go wrong. When you introduce multiple threads without carefully managing shared mutable state, you do not get 4X throughput — you get undefined behaviour dressed up as a working system.

The Java Memory Model (JMM) makes no guarantees about when writes from one thread become visible to other threads, unless you use synchronisation. Without `volatile`, `synchronized`, or an atomic, a write on Thread A may never be seen by Thread B, or may be seen in a different order than it was written. The JVM is free to reorder operations, cache values in registers, and optimise across thread boundaries as long as single-thread semantics are preserved.

This is not a theoretical concern. You will make it concrete and observable.

**Series references to study before starting:**
- The JMM section of any Java concurrency reference (Java Concurrency in Practice, Chapter 3)
- What `happens-before` means: a write to a variable happens-before a read of that variable only under specific conditions — `synchronized` blocks on the same monitor, `volatile` fields, `Thread.start()`, `Thread.join()`.

---

## System Specification

### Functional Requirements

Same as Exercise 01:
- Accept price updates for up to 10,000 distinct instruments.
- Maintain the latest known price for each instrument.
- Invoke `PriceListener` callbacks on price updates.

### Non-Functional Requirements

- **Throughput target (aspirational):** 1,000,000 price updates per second. You will not meet it — and you will discover the reason is not just throughput, it is correctness.
- **Correctness (deliberate failure):** Data integrity will be violated under concurrent load. This is the intended outcome of this exercise.

### Constraints

- Java standard library only.
- Use `Thread`, `Runnable`, or `ExecutorService` to introduce parallelism.
- **No `synchronized`, no `volatile`, no `AtomicLong`, no `ConcurrentHashMap`** — not even on the counter you use to detect corruption. The absence of these is what creates the observable failure.
- Reuse `PriceTick`, `PriceListener`, and `SubscriptionRegistry` from Exercise 01 unchanged.

---

## Step-by-Step Exercise Guide

### Step 1 — Introduce a Mutable Update Counter (Intentionally Unsafe)

**What to implement:**

Add a plain (non-volatile, non-atomic) `long` counter field to your `PriceCache` or `PriceUpdateService`:

```java
// Intentionally unsafe — do not use AtomicLong
private long totalUpdatesProcessed = 0;
```

In the `update()` method of `PriceCache`, increment it:

```java
totalUpdatesProcessed++; // read-modify-write — NOT atomic
```

Expose a `long getTotalUpdatesProcessed()` getter.

**Why this matters:**

The expression `totalUpdatesProcessed++` compiles to three bytecode instructions: load the value, increment it, store it back. If two threads execute this simultaneously, both may load the same original value, both increment it to `original + 1`, and both store `original + 1`. The net result is one increment, not two. This is a lost update.

**Key decisions:**
- Do not make any field `volatile` here — not even `totalUpdatesProcessed`. The entire point of this exercise is to observe what happens without visibility guarantees.

---

### Step 2 — Introduce Concurrent Producers

**What to implement:**

Modify `PriceUpdateService` to dispatch incoming updates to a fixed thread pool:

```
// Pseudocode — do not copy verbatim
class PriceUpdateService {
    ExecutorService pool = Executors.newFixedThreadPool(4)

    void submit(PriceTick tick):
        pool.execute(() -> {
            cache.update(tick)
            for listener in registry.getListeners(tick.instrumentId):
                listener.onPrice(tick)
        })
}
```

Four threads now concurrently call `cache.update()`, which writes to a plain `HashMap` and increments a plain `long` counter — with no synchronisation of any kind.

**Key decisions:**
- Use `pool.execute()`, not `pool.submit()`. The difference: `submit()` returns a `Future` and captures exceptions silently; `execute()` will propagate exceptions to the thread's uncaught exception handler. You want to see exceptions if the HashMap explodes.
- Add an `UncaughtExceptionHandler` to your thread pool's thread factory that logs any exception with its stack trace. `HashMap` under concurrent modification can throw `ConcurrentModificationException` and you want to capture it.

```java
ThreadFactory factory = r -> {
    Thread t = new Thread(r);
    t.setUncaughtExceptionHandler((thread, ex) ->
        System.err.println("Thread " + thread.getName() + " threw: " + ex));
    return t;
};
ExecutorService pool = Executors.newFixedThreadPool(4, factory);
```

**Concepts to study:**
- What does `HashMap` do internally when two threads call `put()` simultaneously? (Modern JVMs: the write may corrupt the bucket list, but usually does not infinite-loop as in Java 6/7. It will silently drop one of the writes.)
- What is a data race? Specifically: a data race occurs when two threads access the same memory location concurrently, at least one access is a write, and the accesses are not ordered by a happens-before relationship.

---

### Step 3 — Write a Corruption Stress Test

**What to implement:**

Write a `CorruptionStressTest` class with a `main` method. This test must *reliably trigger observable corruption* — not just theoretically possible corruption.

**Test design:**

```
TOTAL_UPDATES = 1_000_000
THREAD_COUNT  = 4
UPDATES_PER_THREAD = TOTAL_UPDATES / THREAD_COUNT

1. Create PriceUpdateService (multi-threaded, no sync)
2. Create CountDownLatch(THREAD_COUNT) for synchronised start
3. Spawn THREAD_COUNT threads, each:
   a. Waits on the latch (to ensure all threads start simultaneously)
   b. Submits UPDATES_PER_THREAD price updates as fast as possible
   c. Tracks how many it submitted locally (with a plain local int — safe, local variables are thread-confined)
4. After all threads finish (use thread.join()), print:
   - Expected totalUpdatesProcessed: TOTAL_UPDATES
   - Actual   totalUpdatesProcessed: cache.getTotalUpdatesProcessed()
   - Discrepancy: TOTAL_UPDATES - actual (this is the lost-update count)
```

**Why `CountDownLatch` here?** Starting threads one at a time means the first thread is already running before the last one starts — reducing contention. You want maximum simultaneous contention to reliably trigger the race.

**What to observe:**
- The discrepancy between expected and actual `totalUpdatesProcessed` will be non-zero. On a 4-core machine with 1,000,000 updates it is typically 50,000–300,000 lost updates. The exact number is non-deterministic — run the test 5 times and record the range.
- Occasionally the test may throw a `ConcurrentModificationException` from inside `HashMap`. Capture it with the uncaught exception handler. It is evidence of structural corruption of the map itself.

**Key decisions:**
- Do not use `AtomicLong` for the expected count or any coordination counter — all counting must be done with plain `long` fields to keep the corrupt behaviour observable.
- Use a barrier (`CountDownLatch` or `CyclicBarrier`) to maximise simultaneous thread contention. Without it, the race window narrows and the test becomes flaky.

---

### Step 4 — Observe Memory Visibility Failure

**What to implement:**

Add a second observable failure: a stale-read scenario.

Add a plain (non-volatile) `double lastPrice` field to `PriceCache` alongside the `HashMap`:

```java
// Not volatile — intentionally stale
private double lastPriceObserved = 0.0;
```

In `update()`, write: `lastPriceObserved = tick.price;`

Now add a "reader" thread that loops 1,000,000 times, reading `lastPriceObserved` and checking whether it is still zero:

```
// In the reader thread
long stalReads = 0;
double prev = 0.0;
while (!done) {
    double current = cache.getLastPriceObserved();
    if (current == prev) staleReads++;
    else prev = current;
}
```

Run this reader concurrently with 4 producer threads submitting updates at maximum speed. The reader should see `lastPriceObserved` change frequently. Without `volatile`, it may see the same value for many consecutive reads — or never update at all, if the JIT decides to keep the read in a register.

**What to observe:**
- The stale read count per second. Without `volatile`, this can be very high, or infinite (the JIT may hoist the read out of the loop entirely under certain optimisation conditions).
- Compare behaviour between `-server` (C2 JIT, aggressive optimisation) and `-Xint` (interpreter only, no JIT). Under `-Xint`, the stale read may disappear — but that does not mean the code is correct, it means the bug is latent.

**Concepts to study:**
- Why can the JIT legally hoist a non-volatile read out of a loop? (The JVM's as-if-single-threaded rule: the JIT may reorder operations as long as the result is the same *within a single thread*. From Thread B's perspective, Thread A might as well never have written the field.)
- What does `volatile` actually do at the bytecode level? It inserts a memory barrier that prevents the JIT from caching the value in a register across loop iterations.

---

### Step 5 — Run the Full Load Test and Measure Broken Throughput

**What to implement:**

Adapt the load test from Exercise 01 to use the new multi-threaded `PriceUpdateService`. Run 4 producer threads for 30 seconds.

**Key decisions:**
- Use `AtomicLong` for the per-listener delivery counter (the one that counts how many times each listener was called). Wait — this is a contradiction: the whole exercise is about *not* using atomics. Use a plain `long` for the listener counter too, and accept that its final value is also unreliable. Your goal is to show that *everything* that touches shared state without synchronisation is broken.
- At the end, print: expected deliveries, actual counter value, and discrepancy.

**What to observe:**
- Throughput may actually be *higher* than Exercise 01's single-threaded baseline — because you are skipping all correctness guarantees. A system that drops half its updates is "fast" in the same way a broken scale is "light".
- The delivery count will be incorrect.

---

### Step 6 — Document What You Observed

Do not fix anything yet. Write down (in comments, a README, or a notebook):

1. The exact discrepancy from the corruption stress test (run it 5 times, record min/max/typical lost-update count).
2. Whether a `ConcurrentModificationException` occurred at any point.
3. The stale-read count from Step 4.
4. Your hypothesis: which of the three concurrent failures (lost updates, stale reads, structural HashMap corruption) is most dangerous in a real price-distribution system, and why?

---

## Bottleneck & Reflection Questions

Think through each question before moving to Exercise 03.

1. **Why `totalUpdatesProcessed++` is not atomic.** The JVM does not guarantee that a non-volatile `long` read or write is atomic on all platforms. On 32-bit JVMs, a `long` write is two 32-bit operations — two threads can interleave on a half-written value, producing a *torn write*. On 64-bit JVMs, the write is usually atomic at the hardware level, but the Java Language Specification does not guarantee this. What the JLS *does* guarantee is that `volatile long` writes are always atomic. Why does this distinction matter?

2. **HashMap under concurrent modification.** You observed that a plain `HashMap` under concurrent writes either drops updates silently or throws `ConcurrentModificationException`. Why does `HashMap` throw `CME`? (Hint: the `modCount` field and the fail-fast iterator.) In what scenario does `CME` *not* trigger even though data is corrupt? (Hint: CME only fires during iteration — a concurrent write that corrupts a bucket without being detected during a `put()` is silently lost.)

3. **The JIT and memory visibility.** Under the C2 JIT (`-server`), the stale read in Step 4 may be far worse than under `-Xint`. Why does the JIT make memory visibility problems *worse*, not better? What does this tell you about the usefulness of "it worked fine in testing" as a correctness argument for unsynchronised code?

4. **Happens-before and the JMM.** The Java Memory Model defines a partial order over memory operations. A write W is visible to a read R if and only if W happens-before R. List the four most common ways to establish a happens-before relationship in Java. Does a plain field write establish happens-before? Does calling a method on the same object from two different threads establish happens-before? (No — the object reference is shared, but no synchronisation action occurs.)

5. **The danger of non-determinism.** Your corruption stress test sometimes loses 50,000 updates and sometimes loses 300,000. A naive fix might be: "It doesn't happen very often in production — only under heavy load." Why is this argument wrong? What property must a correct concurrent program have that your current implementation lacks?

6. **Setting up Exercise 03.** The fix for every problem observed in this exercise is synchronisation. But the most obvious fix — wrapping every `HashMap` access in `synchronized(this)` — introduces a new problem. What is that problem, and how would you measure it? (Hint: this is the subject of Exercise 03.)

---

## Success Criteria

You have completed this exercise when:

- [ ] The corruption stress test runs and prints a non-zero lost-update discrepancy on at least 4 out of 5 runs.
- [ ] You have captured at least one `ConcurrentModificationException` from `HashMap`, OR can explain why CME was not triggered in your run (e.g. no iteration occurred during concurrent modification).
- [ ] You have measured the stale-read count from the visibility test in Step 4 and observed a difference between `-server` and `-Xint` runs.
- [ ] You can describe the exact bytecode sequence that makes `totalUpdatesProcessed++` non-atomic.
- [ ] You can answer all six Bottleneck Questions verbally, with specific numbers from your runs.
- [ ] You have written down a precise description of what synchronisation you would add to restore correctness — that is the starting point for Exercise 03.

---

## Review Verdict

**Verdict: 🔁 REVISIT — Specific Gap**

**Reviewed:** 2026-04-20  
**Engineer:** Pedro  
**Exercise:** 02 — Naive Multi-Threaded Price Match Service

### Phase 1 — Correctness: ✅ PASS

Implementation satisfies functional requirements. Verified:

- Corruption stress test runs and produces non-zero lost-update discrepancy reliably (verified: 196K–222K on repeated runs)
- `ClassCastException: HashMap$Node cannot be cast to HashMap$TreeNode` captured multiple times — structural bucket corruption of `HashMap` under concurrent `put()` calls is observable
- Stale reads confirmed: ~7,700–8,000 in ~300ms under `-server` (C2 JIT), ~7,300 in ~2,600ms under `-Xint`
- Per-second stale-read rate: **~27,000/sec under C2 vs ~4,500/sec under interpreter**
- `PriceLoadTest` (30s, 4 producers): ~2.2M updates/sec submitted with ~13M lost updates (~19% loss rate)
- All constraint requirements met: no `synchronized`, no `volatile`, no `AtomicLong`, no `ConcurrentHashMap`; `Thread`, `Runnable`, and `ExecutorService` used correctly
- Uncaught exception handler not explicitly configured on the thread pool, but `ClassCastException` is printed to `System.err` via the default uncaught exception handler — corruption was observable

### Phase 2 — Bottleneck Comprehension: ⚠️ PARTIAL

**Questions 1, 2, 4, 5: ✅ Demonstrated understanding**

Engineer correctly identified:
- Lost updates on `totalUpdatesProcessed` counter (non-atomic read-modify-write)
- Stale reads on `lastPrice` (non-volatile field, JIT can hoist reads)
- HashMap structural corruption (`ClassCastException` during `treeifyBin`)
- Why C2 JIT makes stale reads worse per unit time (more aggressive optimisation)
- Why `synchronized` everywhere in Exercise 03 will introduce contention and reduce throughput

**Question 3: ❌ GAP — bytecode sequence not known**

Engineer answered "i don't know" when asked to describe the exact bytecode sequence for `totalUpdatesProcessed++`.

`totalUpdatesProcessed++` compiles to three JVM bytecode instructions:

```
0:  iload_2          // read totalUpdatesProcessed into operand stack
1:  iinc    #4, 1   // increment local variable slot 4 by 1 (does NOT use operand stack)
4:  istore_2         // store result back to local variable slot 2
```

Two interleaving threads produce a lost update because:
- `iload` reads the value into a thread-local register
- `iinc` modifies the register copy, not memory
- `istore` writes the register back to memory — overwriting any concurrent write

Without understanding this mechanism, reasoning about `AtomicLong` (CAS loop), `volatile` (memory barrier), or any lock-free pattern is impossible. This is the foundational concept the lock-free series builds on.

### Phase 3 — Code Quality: ✅ Acceptable

Code is clean, intentional, and demonstrates ownership of the design. Variable names are clear. No red flags suggesting copy-paste or guesswork.

---

### Required Remediation

**Must address before advancing to Exercise 03:**

Run `javap -c src/main/java/com/igprep/showcase/pricer/PriceCache.class` and study the bytecode output for `update()`. Then, without looking at reference material, explain in your own words:

1. Why `iload`, `iinc`, and `istore` allow two concurrent threads to both increment the counter but only count once
2. What would change if `totalUpdatesProcessed` were `volatile` (hint: the memory barrier inserted by `volatile` affects both the read and write paths)
3. What would change if you used `AtomicLong.incrementAndGet()` instead (hint: CAS loop vs load/increment/store)

Once you can explain the three-instruction sequence fluently, Exercise 03 (`synchronized`) and Exercise 04 (lock-free CAS) will make conceptual sense rather than feel like cargo-cult solutions.

---

### Summary

Three of six Bottleneck Questions answered correctly with specific numbers.  
**One critical gap:** inability to describe the bytecode mechanism behind the lost update.  
**Code and empirical observations:** solid — the corruption is genuinely present and observable.

Fix the bytecode gap, then proceed to Exercise 03.

---

---

## Observation summary

Here's a draft based on your data:

---

**Exercise 02 — Observation Summary**

**1. Corruption stress test (5 runs, 1M updates, 4 threads, barrier-synchronised)**

| Run | Expected | Actual | Lost | Loss % |
|-----|----------|--------|------|--------|
| 1 | 1,000,000 | 884,826 | 115,174 | 11.5% |
| 2 | 1,000,000 | 816,926 | 183,074 | 18.3% |
| 3 | 1,000,000 | 832,104 | 167,896 | 16.8% |
| 4 | 1,000,000 | 921,344 | 78,656 | 7.9% |
| 5 | 1,000,000 | 831,328 | 168,672 | 16.9% |

Lost-update range: **79K–183K** per 1M updates.

**2. ConcurrentModificationException**

Not observed in the stress test — no iteration occurred during concurrent modification, so the fail-fast detector did not fire. However, `ClassCastException: HashMap$Node cannot be cast to HashMap$TreeNode` was observed during PriceLoadTest runs, indicating structural bucket corruption. This is more dangerous than CME — the data is silently corrupt with no exception.

**3. Stale-read visibility (Step 4)**

| Mode | Stale reads | Time | Rate |
|------|-------------|------|------|
| -server (C2) | 7,976–8,623 | ~300ms | ~27K/sec |
| -Xint (interpreter) | 9,263–10,069 | ~2,100ms | ~4.5K/sec |

The absolute stale-read count is similar between modes, but Xint runs 6–7x slower. Per-unit-time, C2 JIT produces far more stale reads. Lost updates were 50x worse under C2 (295K–430K vs 1K–8K).

**4. Load test (4 producers, 30 seconds)**

| Run | Submitted | Updates lost | Deliveries lost | Throughput |
|-----|-----------|--------------|-----------------|------------|
| 1 | 174,562,336 | 92,061 (<0.1%) | 60,630 | 5.8M/s |
| 2 | 72,002,012 | 14,047,961 (19%) | 14,036,530 | 2.4M/s |

Both counters diverge from submitted. Throughput collapses with contention.

**Hypothesis**

The most dangerous failure mode is HashMap structural corruption. Lost updates can be detected with monitoring. CME only fires during iteration, silently losing data in the common case. A price-distribution system that drops updates silently — with no exception, no alarm — is worse than one that loses counts.

