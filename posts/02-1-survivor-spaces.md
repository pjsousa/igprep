# Survivor Spaces: How the JVM Decides Which Objects Deserve to Live

In a high-throughput price-streaming system — one handling a million market data updates per second — every single object allocation has a cost. Not just the memory cost, but the GC cost: every `new PriceTick()` pushed onto the heap is garbage that must eventually be collected. Understanding *when* and *why* the JVM collects some objects but not others is the first step to diagnosing the latency spikes that kill p99 SLAs in financial trading systems.

The answer lives in the **Survivor Spaces** — and the generational model that surrounds them.

## The Generational Hypothesis

The foundational assumption behind all modern JVM garbage collectors is simple: **most objects die young**.

This is not a philosophical statement — it is an empirical observation backed by decades of production workloads. A price tick arrives, is consumed by a downstream handler, and is discarded. A request object is processed in milliseconds and becomes unreachable. A temporary string buffer used during log formatting is dereferenced after the log line is written.

The corollary is that *most objects that survive their first use will continue to survive for a long time*. The objects worth keeping are the ones that have already proven they are not temporary.

The generational heap exploits this asymmetry. By separating short-lived objects from long-lived ones, the JVM can collect the young generation aggressively and frequently — using a fast, stop-the-worldMinor GC — without disturbing the old generation where permanent state lives.

## The Young Generation Layout

The Young Generation is divided into three regions:

```
┌──────────────────────────────────────────────────────────────┐
│                          HEAP                                │
│  ┌─────────────────┐                    ┌──────────────────┐  │
│  │  Young Gen      │                    │   Old Gen        │  │
│  │  ┌────┐ ┌────┐  │                    │                  │  │
│  │  │Eden│ │ S0 │  │                    │                  │  │
│  │  │    │ │From│  │                    │                  │  │
│  │  └────┘ └────┘  │                    │                  │  │
│  │  ┌────┐          │                    │                  │  │
│  │  │ S1 │          │                    │                  │  │
│  │  │ To │          │                    │                  │  │
│  │  └────┘          │                    │                  │  │
│  └─────────────────┘                    └──────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

- **Eden**: Where all new object allocations live. Every `new PriceTick()` starts here.
- **Survivor 0 (From)** and **Survivor 1 (To)**: The two survivor spaces. Objects that survive a Minor GC are copied here. At any point, one is the "To" space (target for the next copy) and one is the "From" space (contains objects that have survived at least one GC).

The names can be confusing — just remember that they swap roles after every Minor GC. The JVM uses this ping-pong mechanism to age objects without promoting them prematurely.

## What a Minor GC Cycle Actually Does

When Eden fills, the JVM triggers a Minor GC. Here is the exact sequence:

1. **Eden is swept.** All live objects in Eden are identified. An object is "live" if it is reachable from a GC root (a stack frame, a static field, a JNI reference).

2. **Live objects are copied to Survivor To.** The JVM does not compact Eden — it copies the live objects out to whichever survivor space is currently the destination. This is the "copy" in "copying collector."

3. **Survivor From is scanned.** Objects already in Survivor From that are still live are also copied to Survivor To. Each object has an **age** field — the number of Minor GCs it has survived. These objects get their age incremented.

4. **Eden and Survivor From are now empty.** After the copy, both spaces contain only garbage. The JVM simply discards the contents — no compaction needed, no scanning needed.

5. **The survivor spaces swap roles.** Survivor From becomes the new To, Survivor To becomes the new From. Ready for the next cycle.

This is why Minor GC is fast: it only looks at a small portion of the heap (Young Gen), it copies rather than compacts, and it discards dead objects en masse. There is no scanning of the old generation. There is no evacuation of terabytes of live data.

## Tenuring Threshold: The Promotion Gate

An object does not stay in the survivor spaces forever. The JVM maintains a **tenuring threshold** — typically between 6 and 15 Minor GCs depending on JVM flags and ergonomics. Once an object's age reaches the tenuring threshold, it is promoted to the old generation on the next Minor GC.

The threshold is not arbitrary. It is tuned to separate the truly short-lived objects (which will die before they are ever promoted) from the objects that have proven they are worth keeping permanently. Promoting an object that dies immediately after promotion is wasteful — it moves garbage into old generation where it will only be collected by a much more expensive Full GC.

The JVM also has a **dynamic tenuring threshold**. If Survivor To fills up before objects have aged enough to promote, the JVM will promote younger objects early rather than let the survivor space overflow. Conversely, if objects are consistently being promoted and dying quickly, the threshold may be increased to keep them in the young generation longer.

You can observe the tenuring threshold and age distribution in GC logs:

```
-Xlog:gc*,gc+age=debug
```

Output looks like:

```
[age=1]: 144336 bytes
[age=2]: 11240 bytes
[age=3]: 0 bytes
Desired survivor size: 8388608 bytes
```

The `age=N` lines show how many bytes of live objects exist at each age. When you see objects in age 1 and 2 but nothing in age 3, it tells you most objects die before their second Minor GC — which is exactly what the generational hypothesis predicts.

## Why High-Throughput Price Pipelines Hammer Eden

Consider a price-streaming engine that processes 1 million price updates per second. For each update, it allocates a `PriceTick` object:

```java
public void onMarketDataUpdate(ByteBuffer raw) {
    PriceTick tick = new PriceTick();   // allocated in Eden
    tick.parse(raw);                    // fields populated
    publishToSubscribers(tick);         // tick passed downstream
}                                       // tick becomes unreachable here
```

Each `PriceTick` is short-lived — it exists only during the method call and is dereferenced immediately after `publishToSubscribers` returns. In a generational heap, these objects die in Eden before ever reaching a survivor space. After a Minor GC, Eden is clean.

The problem is the *rate*. At 1M allocations per second, Eden fills in milliseconds. The JVM triggers Minor GC every few milliseconds. Each Minor GC must pause the world for a few milliseconds — and under the allocation pressure of a hot price pipeline, the pause frequency can become a latency concern.

The critical observation: **if the Minor GC pause time itself becomes a problem, you have either a survivor space sizing issue or an allocation rate that needs to be addressed**. Survivor Spaces are where this battle is fought.

## Why This Matters in Production

A Tech Lead responsible for a price-streaming JVM cannot afford to ignore the generational model. Every `new` in the hot path is a micro-decision to allocate on the heap. At scale, allocation rate is GC rate.

If your GC logs show frequent Minor GCs with significant pause times, it means Eden is filling too fast — the system is creating garbage faster than the young generation can clean it. The response options are:

- **Reduce allocation rate**: Object pooling, primitive reuse, avoiding temporary allocations in the hot path.
- **Increase Eden size**: Larger Eden delays Minor GC but increases the pause time of each Minor GC. The trade-off is frequency vs duration.
- **Move to a low-pause GC**: ZGC or Shenandoah reduce the cost of GC pauses to sub-millisecond levels regardless of heap size (covered in posts 2.4 and 2.5).

Understanding Survivor Spaces is the foundation for all of this. When an interviewer asks "why does my trading engine keep pausing under load?", the generational model and the Minor GC cycle are part of the answer — and Survivor Spaces are where that model becomes concrete.
