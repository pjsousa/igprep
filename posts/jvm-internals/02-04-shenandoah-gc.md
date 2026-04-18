# Shenandoah GC: Concurrent Compaction Without Stopping the World

Shenandoah is the answer to a question that G1GC leaves dangling: *what happens when the heap grows large and evacuation pauses — which scale with the live data set — start measuring in tens or hundreds of milliseconds?* For a price-streaming JVM handling multi-gigabyte heaps under a p99 latency SLA of 10ms, that gap is unacceptable. Shenandoah closes it by moving the work that other collectors do in a stop-the-world pause into concurrent threads, so the application never has to stop.

## The G1GC Problem Shenandoah Solves

G1GC partitions the heap into equally-sized regions and collects them incrementally,优先 collecting regions with the most garbage. It is a fine design for throughput-oriented workloads. But G1GC's **evacuation pause** — the stop-the-world phase where live objects are copied from one region to another — scales with the amount of live data being moved. Under a 16 GB heap with 10 GB live objects, an evacuation pause can stretch to 50–100 ms. That is the pause budGet consumed before a single price tick is processed.

Shenandoah was designed at Red Hat specifically to attack this problem. Its core guarantee: **pause times remain under 10 ms regardless of heap size**, whether you are running 8 GB or 128 GB. The trick is that Shenandoah does the evacuation work concurrently — while Java threads are still running.

## The Brooks Pointer: Indirection as the Key to Concurrent Moves

The hard problem with concurrent compaction is not marking objects — that is relatively easy to do in the background. The hard problem is **object forwarding**. When an object is moved from address A to address B in memory, any existing reference in the application that pointed to A now points to stale memory. In a stop-the-world pause, you update all references atomically. In a concurrent collector, references are being dereferenced while you are trying to update them.

Shenandoah's solution is the **Brooks pointer**, named after the Brooks computer from the 1970s used to study indirection in self-referential data structures. Every object in Shenandoah's heap has an extra word — a forwarding pointer — inserted at the start of the object header. When an object is in its original position, the Brooks pointer points to itself. When an object is evacuated (moved), the Brooks pointer at the old location is atomically updated to point to the new location. Every read of an object reference goes through the Brooks pointer indirection:

```
// Before evacuation:
[Brooks ptr] → points to self (identity)
object fields...

// After evacuation to new location:
[Brooks ptr at OLD location] → updated to point to NEW location
[Brooks ptr at NEW location] → points to self
object fields...
```

The application always reads through the Brooks pointer. If the pointer has been updated mid-read to a new location, the read simply follows the pointer to wherever the object currently lives. This means **object relocation can happen entirely concurrently** — no reference is ever left dangling because every reference dereference is implicitly a lookup.

There is a cost: every object access now has one extra pointer indirection. For a hot price-tick object accessed millions of times per second, this is measurable. Shenandoah mitigates this with aggressive **collector caching** — the JVM keeps a local cache of recently resolved forwarding pointers to avoid repeated indirection for hot objects. The net effect is a small, acceptable tax on throughput in exchange for consistent sub-10ms pauses.

## Shenandoah's GC Phases

Shenandoah breaks its cycle into phases, each marked as either stop-the-world (STW) or concurrent:

1. **Initial Mark** (STW) — Marks the root set: static fields, thread stacks, JNI handles. This is a short pause, typically under 1 ms, because it only traverses known root references, not the whole heap.

2. **Concurrent Marking** (Concurrent) — Traverses the heap graph from the root set, marking all reachable objects. This runs in the background alongside the application. No pause.

3. **Final Mark** (STW) — Completes marking and determines which regions have the most garbage. Also queues objects for evacuation. Short pause, under 2 ms.

4. **Concurrent Cleanup** (Concurrent) — Frees regions that contain only garbage — no live objects. Runs without pausing.

5. **Concurrent Evacuation** (Concurrent) — The core innovation. Shenandoah copies live objects from regions with high garbage to regions with low garbage density, updating Brooks pointers as it goes. This is the phase that would be a stop-the-world evacuation pause in G1GC.

6. **Concurrent Update References** (Concurrent) — Walks all references in the heap and updates any that still point to the old (pre-evacuation) addresses. Because all application threads were running during evacuation, some may have loaded an old address into a local variable. This phase catches those.

7. **Reference Processing** (Concurrent) — Handles soft, weak, and phantom references. Standard Java reference handling.

Steps 1, 3, and 5 are the only STW phases, and they are designed to be very short regardless of heap size.

## Trade-offs: Shenandoah vs G1GC vs ZGC

**Throughput cost.** Shenandoah's concurrent phases consume CPU cycles that G1GC does not. You typically pay 5–15% more CPU overhead compared to G1GC running the same workload. For a trading system already CPU-saturated, this matters.

**Latency gain.** G1GC's Mixed GC pauses can hit 50–200 ms under a 16+ GB heap with high live data. Shenandoah keeps pauses under 10 ms by design, even as the live set grows. This is the fundamental tradeoff: Shenandoah trades throughput for consistent latency.

**Comparison with ZGC.** ZGC (covered in the next post) takes a different technical approach — **coloured pointers** rather than Brooks indirection. ZGC encodes GC state in the bits of the pointer itself, requiring no indirection on reads, which means lower throughput overhead than Shenandoah in many workloads. ZGC also targets sub-1ms pauses (not sub-10ms), and supports generational mode in JDK 21+ which improves throughput significantly. Shenandoah, however, works on a broader range of JDK versions (back to JDK 8 via a backport, though production use is on JDK 11+), and its Brooks pointer model is simpler to reason about in some debugging scenarios.

**Generational Shenandoah (JDK 17+).** Older Shenandoah was non-generational — it collected the entire heap together, which meant higher throughput cost because more objects had to be scanned. JDK 17 introduced **Generational Shenandoah**, which adds young/old generation support, significantly improving throughput by focusing most collection work on the young generation. This makes modern Shenandoah far more competitive with ZGC for throughput-sensitive workloads.

## Enabling Shenandoah

Shenandoah is available in OpenJDK 8 update 40 and later, and in all major JDK distributions (OpenJDK, Red Hat, AdoptOpenJDK). Enable it with a single flag:

```bash
-XX:+UseShenandoahGC
```

For a price-streaming JVM on JDK 21, you would typically also enable generational mode:

```bash
-XX:+UseShenandoahGC -XX:ShenandoahGCHeuristics=adaptive \
    -XX:MaxGCPauseMillis=10
```

GC logging is the same as other collectors:

```bash
-Xlog:gc*:file=gc.log:time,uptime,level,tags
```

## Why This Matters in Production

A Tech Lead running a financial JVM with a tight latency SLA cannot afford to discover at 3 AM that a 16 GB G1GC Mixed pause caused 200 ms of price update jitter in a risk-engine feed. Shenandoah's design philosophy is exactly right for this context: accept a small, bounded CPU overhead in exchange for eliminating the worst-case pause. When your p99 latency SLA is 10 ms, the difference between G1GC's tail and Shenandoah's tail is the difference between meeting and missing that SLA.

The Brooks pointer indirection is also a useful mental model — it shows that concurrent compaction is not magic but an engineering tradeoff: a small constant overhead per dereference buys you the ability to move objects without stopping the world. That is the kind of tradeoff a Tech Lead must be able to evaluate and defend to a team, an architect, or an interviewer.
