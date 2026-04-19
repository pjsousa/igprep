# Naive Multi-Threaded Price Match Service with Mutex Locking — Exercise 03

## Objective

Fix the data corruption from Exercise 02 by adding `synchronized` blocks to all shared mutable state. Achieve correctness — then measure the throughput penalty that correctness costs under concurrent load.

By the end of this exercise you will have a service that is both multi-threaded and correct. You will also have measured, with JVM tooling, exactly how much of your available parallelism is destroyed by a coarse-grained lock — and you will be able to articulate why the lock is the bottleneck, not the CPU or the algorithm.

---

## Background & Motivation

Exercise 02 demonstrated that unsynchronised access to shared mutable state produces silent, non-deterministic corruption. The instinctive fix is to add `synchronized`. This is correct — and it does restore correctness. But `synchronized` on a single shared object serialises all threads that contend for it.

Imagine four lanes of traffic merging into one tollbooth. Each car represents a thread. The tollbooth is your `synchronized` block. The cars can approach in parallel, but they must pass through one at a time. At low traffic rates the wait is negligible. At 1,000,000 updates per second, the queue becomes the bottleneck.

This is **lock contention**: the situation where the time threads spend *waiting* to acquire a lock dominates the time they spend *doing useful work*. It is a correctness-preserving, throughput-destroying trade-off — and it is what motivates the lock-free techniques in Exercise 04.

---

## System Specification

### Functional Requirements

Same as Exercises 01 and 02:
- Accept price updates for up to 10,000 distinct instruments.
- Maintain the latest known price per instrument.
- Invoke `PriceListener` callbacks on each update.
- Support multiple concurrent producers.

### Non-Functional Requirements

- **Correctness:** The corruption stress test from Exercise 02 must produce zero lost updates. This is the primary success gate.
- **Throughput target (aspirational):** 1,000,000 updates/second. You will not meet it. Your ceiling will be dictated by lock acquisition rate, not CPU speed.
- **Measurable contention:** Thread wait time must be observable via `jstack` or JFR, and you must be able to report what fraction of each thread's time is spent BLOCKED versus RUNNABLE.

### Constraints

- Java standard library only — no `ConcurrentHashMap`, no `AtomicReference`, no `LockSupport`.
- Use `synchronized` blocks or `ReentrantLock` for all synchronisation. No lock-free primitives.
- The lock scope must be **coarse-grained**: a single lock guards the entire `PriceCache` (all instruments under one monitor). This is intentional — fine-grained locking belongs in Exercise 04.
- Reuse `PriceTick`, `PriceListener`, and `SubscriptionRegistry` from Exercise 01. Only `PriceCache` and `PriceUpdateService` change.

---

## Step-by-Step Exercise Guide

### Step 1 — Add `synchronized` to the Price Cache

**What to implement:**

In `PriceCache`, add `synchronized` to both `update()` and `getLatest()`:

```java
synchronized void update(PriceTick tick) {
    map.put(tick.instrumentId(), tick);
    totalUpdatesProcessed++;   // now safe: inside synchronized block
}

synchronized PriceTick getLatest(String instrumentId) {
    return map.get(instrumentId);
}
```

The `synchronized` keyword on an instance method acquires the intrinsic lock (monitor) of `this`. All threads calling `update()` or `getLatest()` on the same `PriceCache` instance must acquire this lock before proceeding — only one thread executes at a time.

**Key decisions:**
- Should the lock be on `this` (the `PriceCache` instance) or on a private `Object lock = new Object()`? Both are correct. A dedicated lock object is slightly better practice — it prevents external code from synchronising on the same monitor and accidentally blocking your internal methods. Either choice is fine for this exercise.
- Do the `SubscriptionRegistry` methods also need `synchronized`? Yes, if multiple threads call `subscribe()` or `getListeners()` concurrently. Add `synchronized` there too.

**Validation — the correctness test:**

Re-run the corruption stress test from Exercise 02 exactly as written. Change nothing except the implementation of `PriceCache`. The discrepancy must be zero on every run. If it is not, you have a synchronisation gap — find it before proceeding.

---

### Step 2 — Verify Correctness with the Exercise 02 Stress Test

**What to run:**

Run the corruption stress test (1,000,000 updates, 4 threads, `CountDownLatch` for simultaneous start) 10 times. Print the discrepancy each time. Every run must show:

```
Expected: 1000000
Actual:   1000000
Lost:     0
```

If any run shows a non-zero loss, you have an unsynchronised code path. Use the following diagnostic:

- Add `System.err.println(Thread.currentThread())` inside `update()` — you should never see output from two threads simultaneously.
- Check `SubscriptionRegistry`: if `getListeners()` is not synchronised and a subscriber is added concurrently, you can get a `ConcurrentModificationException` from the `List` iteration.

**What has changed vs Exercise 02:**

The stale-read failure from Step 4 of Exercise 02 should also disappear. `synchronized` establishes a happens-before relationship: a thread exiting a `synchronized` block happens-before a thread subsequently entering the same `synchronized` block on the same monitor. Writes inside the first thread's block are visible to the second thread.

---

### Step 3 — Run the Multi-Threaded Load Test

**What to implement:**

Adapt the load test from Exercise 01 to use 4 producer threads (same as Exercise 02's load test). Run for 30 seconds.

Use `AtomicLong` for the per-listener delivery counter this time — the listener counter is no longer the corrupted variable under test, so it should be correct. You want to measure real throughput, not corrupted throughput.

**What to observe:**

1. **Throughput:** Compare to Exercise 01 (single-threaded) and Exercise 02 (multi-threaded, broken). Where does it land?
   - Hypothesis: multi-threaded + synchronized may be *slower* than single-threaded for this workload because the synchronisation overhead and context switches cost more than the parallelism gains. Test this hypothesis.

2. **Thread states:** While the load test is running, take a thread dump:
   ```
   jstack <pid>
   ```
   Look for threads in `BLOCKED` state, waiting on `PriceCache`'s monitor. Count how many are blocked at peak. A thread in `BLOCKED` is burning wall-clock time doing nothing useful.

3. **Queue depth (if using an internal queue):** If you kept the dispatcher pattern from Exercise 01 (a queue + dispatcher thread), monitor its depth. A full queue means producers are blocking at `submit()`, waiting for the dispatcher to drain — which is itself waiting for the lock.

---

### Step 4 — Measure Contention with JVM Tooling

**What to run:**

**Option A — JStack sampling:**

Take 5 thread dumps at 2-second intervals during peak load:

```bash
for i in {1..5}; do jstack <pid> >> jstack-output.txt; sleep 2; done
```

Count how many threads appear in `BLOCKED` state on `PriceCache`'s monitor in each dump. Calculate the percentage of threads blocked at each sample.

**Option B — JFR (Java Flight Recorder):**

Start the load test with JFR enabled:

```bash
java -XX:StartFlightRecording=duration=30s,filename=contention.jfr \
     -XX:FlightRecorderOptions=stackdepth=64 \
     PriceLoadTest
```

Open `contention.jfr` in JDK Mission Control (JMC). Navigate to:
- **Lock Instances** view: shows which monitors have the most contention, how many threads blocked, and total blocked duration.
- **Thread Dump** view: shows the distribution of thread states over time.

Look for `PriceCache` (or whatever class owns the monitor) in the top contended locks. Note the **mean blocked duration** per acquisition — this is the average time a thread spends waiting for the lock.

**Key decisions:**
- JFR is available in OpenJDK 11+ without commercial flags. If you are on an older JDK, use the JStack sampling approach instead.
- JMC can be downloaded separately from the JDK. If unavailable, use the command-line: `jfr print --events jdk.JavaMonitorEnter contention.jfr | head -50` to dump raw contention events.

---

### Step 5 — Quantify the Contention Ceiling

**What to calculate:**

Given your JFR or JStack data, answer these questions with measured numbers:

1. What is the average time a thread spends BLOCKED on the `PriceCache` lock per update?
2. If each update takes `T_work` nanoseconds of actual work (HashMap.put + listener call) and `T_wait` nanoseconds of blocking, what fraction of each thread's time is wasted waiting?
3. What is the theoretical maximum throughput of this implementation, assuming `T_work` is fixed and the lock is perfectly fair (FIFO)?

The formula: `max_throughput = 1 / T_work` (one thread's maximum). With 4 threads and a perfectly serialising lock: `actual_throughput ≈ 1 / T_work` (not 4x, because only one thread runs at a time through the critical section). Verify this matches your measurement.

**What to observe:**

If your measured throughput is *lower* than `1 / T_work`, the overhead of context-switching threads in and out of the BLOCKED state is adding extra cost on top of the serialisation. This is the kernel-mode transition cost of mutex contention — a failed mutex acquisition triggers a system call, which is orders of magnitude more expensive than a failed CAS.

---

### Step 6 — Experiment with Lock Scope

**What to try (optional, high-value):**

The current lock covers the entire `PriceCache` — all 10,000 instruments share one monitor. As an experiment (do not keep this as your final answer), try splitting the lock:

- Create an array of 16 `Object` lock objects.
- Assign each instrument to a lock by: `locks[instrumentId.hashCode() & 15]`.
- In `update()`, `synchronized(locks[hash(tick.instrumentId())])` instead of `synchronized(this)`.

This is lock striping — a coarser version of what `ConcurrentHashMap` does internally. Measure whether throughput improves.

**Key decisions:**
- Does this change preserve correctness? Re-run the stress test.
- What is the risk if two instruments hash to the same lock bucket? (No data corruption — they still share the lock and serialise correctly. Only contention reduction suffers.)
- Does this fully solve the contention problem? No — under a hot instrument (e.g. `"EUR/USD"` receiving 90% of all updates), one bucket remains a bottleneck regardless of striping.

Document your findings but do not generalise this into a production solution — the correct answer is lock-free data structures, which is what Exercise 04 addresses.

---

## Bottleneck & Reflection Questions

Think through each question before moving to Exercise 04.

1. **The serialisation tax.** With 4 threads and one lock, throughput should theoretically be similar to single-threaded (only one thread executes the critical section at a time). Why might your measured throughput be *lower* than the single-threaded baseline from Exercise 01? List at least two sources of overhead that `synchronized` adds that a single-threaded run does not pay.

2. **Contended vs uncontended mutex cost.** An uncontended `synchronized` acquisition costs roughly 10–30 nanoseconds on modern JVMs (it uses a lightweight bias-locked or thin-lock path). A contended acquisition can cost 1,000–10,000 nanoseconds (kernel futex syscall, context switch, cache invalidation). At 1,000,000 updates/second with 4 threads, what is the maximum fraction of your time budget a contended acquisition can consume before it dominates every other cost?

3. **Head-of-line blocking.** Suppose one producer thread calls a `PriceListener` whose `onPrice()` implementation takes 500 microseconds (e.g. it writes to a socket). That listener is called while holding the `PriceCache` lock. What happens to the other three producer threads during those 500 microseconds? How does this multiply the lock-hold time and what does it do to tail latency? (This is head-of-line blocking — the slowest operation determines the queue length for everyone else.)

4. **BLOCKED vs WAITING in JStack.** When you took thread dumps, you saw threads in `BLOCKED` state. What is the difference between `BLOCKED` and `WAITING`? When would a thread enter `WAITING` instead of `BLOCKED` in a concurrency scenario? (Hint: `BLOCKED` = trying to acquire a monitor; `WAITING` = voluntarily waiting via `Object.wait()`, `LockSupport.park()`, or `Thread.join()`.)

5. **Why `ReentrantLock` would not help here.** `ReentrantLock` is more flexible than `synchronized` (it supports try-lock, interruptible lock acquisition, and fairness). But switching from `synchronized` to `ReentrantLock` with the same coarse scope would not meaningfully improve throughput. Why? What does `ReentrantLock` offer that `synchronized` does not, and why is none of it relevant to the bottleneck you are facing?

6. **The path to Exercise 04.** The root cause of contention in this exercise is that every update, for every instrument, must pass through a single serialisation point. Exercise 04 eliminates this serialisation point entirely using Compare-And-Swap (CAS) instead of mutex acquisition. Before reading Exercise 04, write down your prediction: if you replace `synchronized(this)` with a CAS operation that retries on failure, how does the failure cost compare to the mutex contention cost? And what happens when many threads CAS on the same location simultaneously?

---

## Success Criteria

You have completed this exercise when:

- [ ] The corruption stress test from Exercise 02 produces zero lost updates on 10 consecutive runs.
- [ ] The stale-read failure from Exercise 02 Step 4 is gone (verified by re-running the reader/writer test).
- [ ] You have captured thread state data (JStack or JFR) showing threads BLOCKED on `PriceCache`'s monitor during peak load, and can state the percentage of thread time spent blocked.
- [ ] You have measured the mean blocked duration per lock acquisition from JFR, or estimated it from JStack sampling.
- [ ] You have calculated the theoretical throughput ceiling of a single serialising lock given your measured per-operation work time, and compared it to your actual measured throughput.
- [ ] (Optional) You have tried lock striping with 16 buckets, measured whether it improves throughput, and documented why it does or does not fully solve the contention problem.
- [ ] You can answer all six Bottleneck Questions verbally, with specific numbers from your measurements.
- [ ] You can explain, in one sentence, why correctness via coarse-grained locking is the right foundation for understanding what comes next — not the right production solution.
