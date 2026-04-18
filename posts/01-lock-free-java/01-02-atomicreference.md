# AtomicReference: CAS Applied to Object State in Java

When a thread needs to atomically update a *reference* to an object — not just a primitive value — `AtomicReference` is the tool. It wraps the compare-and-swap primitive and applies it to object references, making lock-free state mutations both practical and readable.

## What AtomicReference Does

`AtomicReference<T>` lives in `java.util.concurrent.atomic`. Its core API is `compareAndSet`:

```java
public final boolean compareAndSet(T expectedValue, T newValue)
```

This atomically sets the internal reference to `newValue` if and only if the current value is `expectedValue`. The operation succeeds only if no other thread modified the reference between read and write. No locks are acquired; the retry is a CAS loop, built in.

For read-heavy paths, `get()` returns the current reference with plain load semantics.

## AtomicReference vs volatile

`volatile` guarantees *visibility* — any write is immediately visible to all subsequent readers. But it gives you no atomic *update* operation. Two threads running this are not safe:

```java
volatile PriceTick current;

// Thread A
current = new PriceTick(tick); // clobbers Thread B's update

// Thread B
current = new PriceTick(tick); // clobbers Thread A's update
```

`AtomicReference` gives you atomicity on the update:

```java
AtomicReference<PriceTick> current = new AtomicReference<>();

// Thread A
current.compareAndSet(current.get(), new PriceTick(tick));

// Thread B — same, no race
current.compareAndSet(current.get(), new PriceTick(tick));
```

Only one `compareAndSet` wins; the other retries with the updated value. Use `volatile` when you only need visibility. Use `AtomicReference` when you need read-modify-write semantics.

## A Lock-Free Price Ticker

Here is a minimal, production-credible example — a `PriceTicker` holding the latest price for an instrument, updated lock-free:

```java
import java.util.concurrent.atomic.AtomicReference;

public class PriceTicker {
    private final String instrument;
    private final AtomicReference<PriceTick> latest = new AtomicReference<>();

    public PriceTicker(String instrument, PriceTick initialTick) {
        this.instrument = instrument;
        this.latest.set(initialTick);
    }

    public void onTick(PriceTick tick) {
        PriceTick current = latest.get();
        while (!latest.compareAndSet(current, tick)) {
            current = latest.get();
        }
    }

    public PriceTick getLatest() {
        return latest.get();
    }
}
```

In a high-throughput price stream, millions of updates arrive per second across multiple threads. The retry loop in `onTick` ensures only one writer wins — the one whose CAS observes the current value and successfully overwrites it. The others loop and retry. No locks are acquired. No thread blocks.

## The ABA Problem

CAS-based approaches have a known weakness: the **ABA problem**.

Thread A reads the reference and sees value `A`. Thread B changes `A` → `B` → `A`. Thread A's CAS still succeeds because the value is again `A` — but the object may have been replaced entirely and the state A observed is stale.

In financial systems this matters: a price state machine transitioning `RECEIVED → PROCESSING → PUBLISHED → RECEIVED` — a thread observing `RECEIVED` and retrying after another thread cycles through the intermediate states would see the same enum value but an entirely different object lifecycle.

Java provides `AtomicStampedReference<V>` for exactly this scenario:

```java
import java.util.concurrent.atomic.AtomicStampedReference;

AtomicStampedReference<PriceTick> latest = 
    new AtomicStampedReference<>(initialTick, 0);

int stamp = latest.getStamp();
while (!latest.compareAndSet(currentTick, newTick, stamp, stamp + 1)) {
    stamp = latest.getStamp();
    currentTick = latest.getReference();
}
```

The stamp is a logical version number. A thread that reads the same stamp it observed is guaranteed that no intermediate write occurred.

## Weak vs Strong CAS

`compareAndSet` is the strong CAS variant — it only succeeds if the current value matches expected. `weakCompareAndSet` may fail spuriously on some architectures (notably ARM) even when the value matches. Prefer `compareAndSet` in financial path code unless profiling has confirmed weak CAS is acceptable.

## Why This Matters in Production

In a price-streaming system, the latest state for an instrument flows through a chain of downstream consumers — risk engine, UI aggregation, audit logger. Using `AtomicReference` to hold that state means:

- No lock acquisition overhead on the critical path
- No thread preemption while holding a contended lock
- Predictable latency even under high contention

The tradeoff is the retry loop: under heavy contention, CAS-spin can consume CPU. A Tech Lead should benchmark actual allocation rate and contention depth before assuming lock-free is always faster.

The next post in this series covers **False Sharing** — the hardware hazard that can make even correct `AtomicReference` usage disappointingly slow, because cache-line contention defeats the lock-free guarantee at the silicon level.