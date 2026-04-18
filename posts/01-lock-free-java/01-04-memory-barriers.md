# Memory Barriers: What the JVM Guarantees (and What It Doesn't)

In a price-streaming system processing millions of updates per second, a subtle class of bug can silently corrupt state across threads: a thread publishes a price, another thread reads it, and — nothing happens. The price isn't seen. No exception, no deadlock, just wrong behaviour. The root cause is almost always the same: missing memory barriers.

Before we can reason about lock-free code, we need to understand what the hardware actually guarantees when threads share data through memory.

## Why CPUs Reorder

Modern CPUs do not execute memory operations in program order. To keep the pipeline full and hide memory latency, they are free to:

- **Store the value to a local store buffer** before it reaches cache, so the writing thread can continue immediately.
- **Reorder loads** such that a later load executes before an earlier one that is stalled on a cache miss.
- **Write back to cache out of order** relative to other stores.

The same flexibility exists at the compiler level. The JIT compiler may reorder Java code for optimisation — as long as single-threaded behaviour is unchanged.

This reordering is invisible within a single thread. The problem emerges when Thread A writes, and Thread B reads that same memory location. Without a synchronisation event between them, Thread B may never see Thread A's write — or may see a stale or partially-constructed value.

## What a Memory Barrier Is

A memory barrier (also called a memory fence) is a CPU or JVM-level instruction that prevents reordering across the barrier point. It is a hardware concept, but the JVM maps Java constructs onto barrier semantics.

There are three fundamental types:

**Load barrier (LoadLoad + LoadStore on x86):** Ensures all loads before the barrier complete before any load after the barrier. On x86, a load barrier is effectively a no-op because x86 uses a strong memory model where loads are already ordered — but on ARM/RISC architectures, it is a real fence instruction.

**Store barrier (StoreStore + LoadStore on x86):** Ensures all stores before the barrier are visible to other cores before any store after the barrier. On x86, `SFENCE` is the store fence.

**Full barrier (LoadLoad + LoadStore + StoreStore):** Both load and store sides. On x86, the full barrier is `MFENCE`. More importantly, the `LOCK` prefix on certain instructions (like `LOCK CMPXCHG`) acts as a full barrier.

## The Java Memory Model: Happens-Before

Java's memory model is defined in JEP 147 and JLS Chapter 17. The key abstraction is the **happens-before** partial order: a guarantee that if action A happens-before action B, then the memory effects of A are visible to B.

Crucially, the JVM does not require every action to be globally ordered. It only guarantees the happens-before order between specific pairs of operations — those established by language constructs or explicit library calls.

Some foundational happens-before rules:

- **Monitor unlock** of a monitor happens-before every subsequent **monitor lock** of the same monitor.
- A **volatile write** happens-before every subsequent **volatile read** of the same field.
- A **thread start** happens-before any action in the started thread.
- **Thread.join()** returns happens-before any action in the joining thread that observed the termination.

If you violate these orderings — for example, writing to a shared field without a synchronising action and then reading it from another thread — the JVM is free to make that read return any value, including a zero or a stale cached value.

## How `volatile` Inserts Barriers

The `volatile` keyword in Java maps directly to memory barrier semantics. For a volatile field:

- A **write** inserts a **StoreStore barrier** (ensures all prior stores are visible before the volatile store) followed by a **StoreLoad barrier** (ensures the volatile store is visible to other cores before subsequent loads).
- A **read** inserts a **LoadLoad barrier** (ensures all prior loads are complete before the volatile load) followed by a **LoadStore barrier** (ensures the volatile load is ordered against subsequent stores).

The **StoreLoad barrier** is the most expensive — it forces the store buffer to flush and all other cores to invalidate their store buffers before loads can proceed. This is why volatile reads in a high-contention hot path can become a bottleneck.

```java
public class PricePublisher {
    private volatile long lastPrice = 0L;

    public void publish(long price) {
        this.lastPrice = price; // StoreLoad barrier inserted here
    }

    public long getLastPrice() {
        return this.lastPrice; // LoadLoad barrier inserted here
    }
}
```

Without `volatile`, the JIT compiler could hoist the read of `lastPrice` outside a loop, or the CPU could read a stale value from a local store buffer. With it, every consumer thread is guaranteed to see either the old price or the new price — never a torn or cached value.

## CAS Implies a Full Barrier

The `compareAndSet` operation — the foundation of lock-free programming covered in the first post of this series — does not just compare and swap. By specification, it is defined as having **the same memory ordering semantics as a volatile read followed by a volatile write**.

On x86, `LOCK CMPXCHG` is the instruction used. The `LOCK` prefix makes it act as a full memory barrier. This is why a CAS loop on a contended field can be expensive under high contention: each CAS is a full barrier, forcing store buffer flushes and cross-core coherency traffic.

```java
public class LockFreePriceCache {
    private final AtomicLong lastPrice = new AtomicLong(0L);

    public boolean updatePrice(long expectedPrice, long newPrice) {
        // compareAndSet is a full memory barrier:
        // - all prior loads and stores are complete before the CAS
        // - the CAS result is visible to all cores before any subsequent operation
        return lastPrice.compareAndSet(expectedPrice, newPrice);
    }
}
```

When you use `AtomicReference.compareAndSet()`, the JMM guarantees that the update is atomically visible to all threads. No additional synchronisation is needed between a producer that calls `compareAndSet` and a consumer that subsequently reads the `AtomicReference` — the CAS itself establishes the happens-before edge.

## The Visibility Bug: A Worked Example

Consider this seemingly innocent code:

```java
public class PriceTicker {
    private PriceTick currentTick;

    public void update(PriceTick tick) {
        this.currentTick = tick; // NOT volatile, NOT synchronised
    }

    public PriceTick get() {
        return this.currentTick;
    }
}
```

In a multi-threaded price-streaming system, one thread calls `update()` with a new `PriceTick`, and another thread calls `get()`. The reader thread may observe:

- A `null` reference, even after the writer completed.
- A partially constructed object (if the writer was in the middle of constructing the `PriceTick`).
- A reference to the previous `PriceTick`.

None of these outcomes violate any Java language rule without a happens-before edge between the write and the read. The JIT is permitted to reorder the assignment, the CPU is permitted to keep the written value in a store buffer, and the reader's core is permitted to use a stale cache line.

The fix is straightforward:

```java
public class PriceTicker {
    private volatile PriceTick currentTick; // StoreLoad on write, LoadLoad on read

    public void update(PriceTick tick) {
        this.currentTick = tick;
    }

    public PriceTick get() {
        return this.currentTick;
    }
}
```

Now a happens-before edge exists: the volatile write happens-before the volatile read. The price is guaranteed to be visible.

In a real trading system, a visibility bug like this could manifest as a UI thread displaying yesterday's closing price for several seconds after a market open — or worse, as a risk engine reading a stale position size that understates exposure.

## Why This Matters in Production

Memory barriers are not academic concerns. Every time you choose `volatile`, `synchronized`, `Lock`, or a `java.util.concurrent` atomic, you are making a statement about the memory ordering semantics of your system. As a Tech Lead reviewing concurrent code, you must be able to answer:

- What happens-before edges exist between this writer and this reader?
- Is a full barrier necessary, or is a lighter barrier sufficient?
- What is the performance cost of the chosen primitive under the expected contention profile?

In high-frequency price-streaming systems, barrier cost is measured in nanoseconds — but at millions of events per second, nanoseconds compound. This is why the LMAX Disruptor (the final post in this series) is architected to minimise cross-thread barrier costs: it uses a single producer thread, a volatile-like sequence cursor, and a ring buffer where consumers never block or fence unless they are the sole claiming thread.

Understanding memory barriers is what separates a candidate who knows that `volatile` "makes things visible" from one who can explain *which* barriers are inserted, *why* they are necessary, and *what* would break without them. That distinction is exactly what a FTSE 100 interviewer is probing for.