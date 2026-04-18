# Interview Blog Post Plan
> Derived from `prep-plan-1.md` — Tech Lead Java Engineer preparation for a FTSE 100 company interview.
> Every post is `planned`. Do not write any post until instructed.

---

## How to Use This Plan

1. Review and approve the plan.
2. When instructed to write a specific post, re-read this file, confirm the post is still `planned`, then delegate writing to a subagent using the post's **kick-off prompt**.
3. On completion: save the post, mark it `written` here, commit both files, then stop.

---

## Series Index

| # | Series Name | Source Block | Posts |
|---|---|---|---|
| 1 | Lock-Free Java: Mechanical Sympathy for the Trading Floor | Block 1 | 6 keyword posts + 1 showcase |
| 2 | JVM Internals: Taming Latency in Financial Systems | Block 2 | 5 keyword posts + 1 showcase |
| 3 | Distributed Reliability: Kafka for Mission-Critical Pipelines | Block 3 | 5 keyword posts + 1 showcase |
| 4 | The Fintech Tech Lead: Leadership Under Pressure | Block 4 | 5 keyword posts + 1 showcase |

---

---

# Series 1: Lock-Free Java — Mechanical Sympathy for the Trading Floor

**Source block:** Block 1 — Mechanical Sympathy & High-Performance Java

**Series goal:** Demonstrate that you can write code that doesn't just "work" but thrives under the pressure of millions of price updates — specifically by mastering lock-free concurrency techniques and the hardware-aware Java programming model.

**Intended audience / interview use:** This series directly targets questions about threading, concurrency, and performance in a fintech price-streaming context. It covers the vocabulary and reasoning patterns expected of a Tech Lead who can justify *why* a particular concurrency primitive was chosen in production.

**Suggested learning progression:**
1. Start with CAS as the foundational primitive.
2. Move to AtomicReference to see CAS applied in Java.
3. Understand False Sharing as the hardware hazard that defeats naive optimisations.
4. Learn Memory Barriers to understand what the CPU and JVM guarantee.
5. Study Thread Affinity to see how OS scheduling is controlled at the hardware level.
6. Culminate with the LMAX Disruptor as the production-grade synthesis of all prior concepts.
7. Showcase ties the entire series together in a realistic trading system scenario.

---

### Post 1.1 — CAS (Compare and Swap)

| Field | Value |
|---|---|
| **Status** | `written` |
| **Post type** | `keyword post` |
| **Title** | *CAS Explained: The Atomic Primitive That Replaced the Lock* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `CAS (Compare and Swap)` |
| **Overview** | Explains what Compare and Swap is at the CPU instruction level, how it enables lock-free updates, and why it is the foundation of all lock-free data structures in Java. Includes a worked example comparing a naive mutex approach with a CAS loop. |
| **Why it matters** | CAS is the conceptual bedrock of lock-free programming. An interviewer probing concurrency will expect fluency here before going anywhere near Disruptor or AtomicReference. |
| **Dependencies** | None — this is the starting post in the series. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate has been coached to demonstrate depth in high-performance, lock-free Java concurrency — specifically as it applies to price-streaming systems operating under millions of updates per second.

Series: "Lock-Free Java: Mechanical Sympathy for the Trading Floor"
Series goal: Prove that the candidate can write code that thrives under the pressure of millions of price updates by mastering lock-free concurrency and hardware-aware Java programming.

Post objective:
Write a 5-minute technical blog post titled: "CAS Explained: The Atomic Primitive That Replaced the Lock"
Keyword covered: CAS (Compare and Swap)

The post must:
- Explain CAS at the CPU instruction level (cmpxchg on x86).
- Show why CAS enables lock-free updates — thread never blocks, it retries.
- Include a simple Java example comparing a synchronized counter with a CAS-based counter.
- Explain what happens under contention (CAS spin, backoff strategies).
- Use fintech price-update context where relevant (e.g. updating a last-seen price atomically).
- Be technically precise and senior-level in tone; the reader is a Java engineer, not a beginner.
- End with a one-paragraph "Why this matters in production" connecting to price-streaming systems.

Do not write the next post in the series. Write only this post. Keep it to approximately 5 minutes reading time (~800–1000 words of prose plus code).
```

---

### Post 1.2 — AtomicReference

| Field | Value |
|---|---|
| **Status** | `written` |
| **Post type** | `keyword post` |
| **Title** | *AtomicReference: CAS Applied to Object State in Java* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `AtomicReference` |
| **Overview** | Covers `AtomicReference` and the `java.util.concurrent.atomic` package: how `compareAndSet` works, the ABA problem, when to prefer `AtomicReference` over `volatile`, and practical usage in a price-ticker state holder. |
| **Why it matters** | Interviewers at fintech companies frequently probe the distinction between `volatile` and `AtomicReference` — this post prepares the candidate to answer that question with a concrete code example. |
| **Dependencies** | Post 1.1 (CAS) recommended first. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in lock-free Java concurrency for high-throughput price-streaming systems.

Series: "Lock-Free Java: Mechanical Sympathy for the Trading Floor"
Series goal: Prove that the candidate can write code that thrives under the pressure of millions of price updates by mastering lock-free concurrency and hardware-aware Java programming.

This is Post 2 in the series. Post 1 covered CAS (Compare and Swap) at the CPU level. This post builds directly on that foundation.

Post objective:
Write a 5-minute technical blog post titled: "AtomicReference: CAS Applied to Object State in Java"
Keyword covered: AtomicReference

The post must:
- Explain `AtomicReference<T>` and the `compareAndSet(expected, update)` API.
- Contrast `AtomicReference` with `volatile`: when is each appropriate?
- Introduce the ABA problem: what it is, why it matters, and how `AtomicStampedReference` solves it.
- Show a concrete Java snippet: a lock-free price holder updated by multiple threads simultaneously.
- Use fintech context throughout (price ticks, instrument state, etc.).
- Be senior-level in tone; assume the reader already understands CAS.
- End with a "Why this matters in production" paragraph.

Do not write any other posts. Approximately 800–1000 words of prose plus code.
```

---

### Post 1.3 — False Sharing

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *False Sharing: The Silent Cache-Line Killer in Multithreaded Java* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `False Sharing` |
| **Overview** | Explains CPU cache lines (64 bytes), how two unrelated variables on the same cache line cause cross-core invalidations, how to detect false sharing via JMH or async-profiler, and how to fix it using `@Contended` or manual padding. |
| **Why it matters** | False sharing is a textbook senior/lead interview question — it tests hardware awareness. A candidate who knows this concept signals that they think about the full stack from JVM to silicon. |
| **Dependencies** | Post 1.1 (CAS) recommended; Post 1.2 (AtomicReference) helpful. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in lock-free Java concurrency and hardware-aware programming for high-throughput financial systems.

Series: "Lock-Free Java: Mechanical Sympathy for the Trading Floor"
Series goal: Prove that the candidate can write code that thrives under the pressure of millions of price updates by mastering lock-free concurrency and hardware-aware Java programming.

This is Post 3 in the series. Previous posts covered CAS and AtomicReference. This post introduces the hardware hazard of false sharing.

Post objective:
Write a 5-minute technical blog post titled: "False Sharing: The Silent Cache-Line Killer in Multithreaded Java"
Keyword covered: False Sharing

The post must:
- Explain CPU cache lines (typically 64 bytes) and why cores share them.
- Show, with a minimal Java example, how two `long` fields on adjacent memory positions can cause cross-core cache invalidation — even though they are logically independent.
- Explain the performance impact in a price-streaming or order-book context.
- Show how to fix it: manual padding, `@jdk.internal.vm.annotation.Contended`, or using `LongAdder`.
- Mention that JMH or async-profiler can surface this problem.
- Be senior-level in tone; the reader is a Java engineer, not a beginner.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus code.
```

---

### Post 1.4 — Memory Barriers

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Memory Barriers: What the JVM Guarantees (and What It Doesn't)* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Memory Barriers` |
| **Overview** | Explains load/store barriers, the Java Memory Model (happens-before), how `volatile` inserts barriers, how CAS operations imply full barriers, and the relationship between barriers and out-of-order execution on modern CPUs. Includes a brief example of a visibility bug caused by missing barriers. |
| **Why it matters** | Memory barriers underpin every safe concurrent program. A Tech Lead must understand what the JMM guarantees when reviewing concurrent code — and an interviewer will probe whether you know *why* `volatile` fixes visibility, not just *that* it does. |
| **Dependencies** | Posts 1.1 and 1.2 (CAS, AtomicReference) recommended. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in lock-free Java concurrency and the Java Memory Model for high-throughput financial systems.

Series: "Lock-Free Java: Mechanical Sympathy for the Trading Floor"
Series goal: Prove that the candidate can write code that thrives under the pressure of millions of price updates by mastering lock-free concurrency and hardware-aware Java programming.

This is Post 4 in the series. Previous posts covered CAS, AtomicReference, and False Sharing.

Post objective:
Write a 5-minute technical blog post titled: "Memory Barriers: What the JVM Guarantees (and What It Doesn't)"
Keyword covered: Memory Barriers

The post must:
- Explain what memory barriers (fences) are: instructions that prevent CPU/compiler reordering.
- Distinguish load barriers, store barriers, and full barriers.
- Explain how the Java Memory Model's happens-before relation maps to barrier insertion.
- Show how `volatile` inserts a StoreLoad barrier and what that means in practice.
- Show how CAS implicitly implies a full memory barrier.
- Give a concrete visibility-bug example that a barrier fixes.
- Reference the trading context (e.g. ensuring a published price is visible across threads before it is consumed).
- Be senior-level in tone; assume the reader knows CAS and volatile.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus code.
```

---

### Post 1.5 — Thread Affinity

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Thread Affinity: Pinning Java Threads to CPU Cores for Predictable Latency* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Thread Affinity` |
| **Overview** | Covers OS thread scheduling, why context switches cause latency jitter, how to pin Java threads to specific CPU cores using the `Java Thread Affinity` library (or JNI/taskset), and when this technique is appropriate in a financial trading system. |
| **Why it matters** | Thread affinity is a production-grade latency technique used in low-latency trading engines. Knowing it signals that you think about the full system, not just JVM-level abstractions. |
| **Dependencies** | Post 1.3 (False Sharing) recommended — both concern CPU-core-level thinking. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in hardware-aware Java programming and low-latency techniques for financial systems.

Series: "Lock-Free Java: Mechanical Sympathy for the Trading Floor"
Series goal: Prove that the candidate can write code that thrives under the pressure of millions of price updates by mastering lock-free concurrency and hardware-aware Java programming.

This is Post 5 in the series. Previous posts covered CAS, AtomicReference, False Sharing, and Memory Barriers.

Post objective:
Write a 5-minute technical blog post titled: "Thread Affinity: Pinning Java Threads to CPU Cores for Predictable Latency"
Keyword covered: Thread Affinity

The post must:
- Explain OS thread scheduling and why preemptive context switches introduce latency jitter.
- Explain thread affinity: locking a thread to a physical CPU core.
- Show how to achieve this in Java using the `Java Thread Affinity` library (Peter Lawrey's OpenHFT library) or via `taskset` at the OS level.
- Explain NUMA topology: why affinity must respect socket boundaries to avoid cross-socket memory access.
- Discuss when to use this (dedicated network I/O thread, matching engine thread) and when not to (general-purpose thread pools).
- Use fintech / price-streaming context throughout.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus code.
```

---

### Post 1.6 — LMAX Disruptor

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *The LMAX Disruptor: How a Ring Buffer Replaced Every Queue You Know* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `LMAX Disruptor` |
| **Overview** | Explains the LMAX Disruptor's Ring Buffer architecture, how sequence numbers replace locks, how it avoids GC pressure by pre-allocating entries, and why it outperforms `ArrayBlockingQueue` by orders of magnitude. Draws the connection back to CAS, false sharing, and memory barriers from earlier posts. |
| **Why it matters** | The LMAX Disruptor is the industry standard for financial matching engines. Interviewers at IG or any trading firm will expect a Tech Lead candidate to know it well and be able to justify its design choices. |
| **Dependencies** | All prior posts in the series (1.1–1.5) recommended — Disruptor synthesises all of them. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in high-performance Java concurrency, specifically as it applies to financial price-streaming and matching engine design.

Series: "Lock-Free Java: Mechanical Sympathy for the Trading Floor"
Series goal: Prove that the candidate can write code that thrives under the pressure of millions of price updates by mastering lock-free concurrency and hardware-aware Java programming.

This is Post 6 — the final keyword post in the series, before the showcase article. Previous posts covered: CAS, AtomicReference, False Sharing, Memory Barriers, and Thread Affinity. This post is the synthesis of all prior concepts.

Post objective:
Write a 5-minute technical blog post titled: "The LMAX Disruptor: How a Ring Buffer Replaced Every Queue You Know"
Keyword covered: LMAX Disruptor

The post must:
- Explain the LMAX Disruptor's core structure: a pre-allocated Ring Buffer, sequence numbers, and producers/consumers.
- Explain how sequence numbers and CAS replace locks for slot claiming.
- Explain how padding eliminates false sharing between sequence fields (connect back to Post 1.3).
- Explain how pre-allocation avoids GC pressure (connect forward to Series 2, JVM Internals).
- Compare throughput with `ArrayBlockingQueue`: mention the "1P1C" benchmark in the Disruptor paper.
- Show a minimal Java wiring example using the Disruptor library.
- Explain how it is used in practice: financial matching engines, price distribution, order routing.
- Be senior-level in tone; this is the most advanced keyword post in the series.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus code.
```

---

### Post 1.7 — Showcase Article

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `showcase` |
| **Title** | *Zero-Lock Price Distribution: Building a High-Throughput Ticker with the LMAX Disruptor* |
| **Target read time** | 15 minutes |
| **Keyword(s)** | `CAS`, `AtomicReference`, `False Sharing`, `Memory Barriers`, `Thread Affinity`, `LMAX Disruptor` |
| **Overview** | A complete architecture walkthrough of a fictional but realistic price-distribution service at a trading firm. The design uses a Disruptor-based pipeline to ingest market data, fan out to multiple subscribers, and publish to a downstream price cache — all lock-free. The article traces how each concept from the series appears in the design and explains why each choice was made. Includes an architecture diagram description, Java code for the critical path, and a section on how this would be profiled and validated in production. |
| **Why it matters** | This article is the "demo reel" — if an interviewer asks "can you walk me through how you'd build a high-performance price feed?", this is the blueprint. It synthesises the entire series into a single, coherent, production-credible answer. |
| **Dependencies** | All keyword posts 1.1–1.6 should be read first. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in high-performance Java concurrency and must be able to articulate a complete system design in this domain.

Series: "Lock-Free Java: Mechanical Sympathy for the Trading Floor"
Series goal: Prove that the candidate can write code that thrives under the pressure of millions of price updates by mastering lock-free concurrency and hardware-aware Java programming.

This is the SHOWCASE ARTICLE — the final and longest post in the series, approximately 15 minutes reading time (~2500–3000 words). It must synthesise all 6 keyword posts into a single coherent architecture story.

Posts in this series (all must be referenced and woven together):
1. CAS (Compare and Swap) — the foundational primitive
2. AtomicReference — CAS applied to object state
3. False Sharing — cache-line hazards and padding
4. Memory Barriers — JMM happens-before and barrier semantics
5. Thread Affinity — OS scheduling and core pinning
6. LMAX Disruptor — ring buffer, sequence numbers, GC-free design

Showcase post objective:
Write a 15-minute technical blog post titled: "Zero-Lock Price Distribution: Building a High-Throughput Ticker with the LMAX Disruptor"

The post must:
- Describe a realistic architecture scenario: a price-distribution service at a trading firm receiving FIX/FAST market data and distributing normalised price ticks to multiple downstream consumers (risk engine, UI, order management).
- Walk through the architecture layer by layer: ingestion thread (Thread Affinity pinned), Disruptor ring buffer, multiple consumer handlers.
- Explain where each concept from the series appears in the design and *why* it was chosen:
  - CAS for sequence claiming in the ring buffer
  - AtomicReference for the published price state cache
  - Padding (`@Contended`) to eliminate false sharing on sequence fields
  - Memory barriers ensuring producers and consumers see consistent state
  - Thread affinity pinning the ingestion and dispatch threads to isolated cores
  - Disruptor architecture as the structural skeleton
- Include Java code for the critical path: defining the Event, wiring the Disruptor, publishing an event, consuming with a handler.
- Include a section on how to validate this in production: JMH benchmark setup, async-profiler, GC logs, latency histogram (HDRHistogram).
- Include a section on tradeoffs and failure modes: what happens under consumer lag, what happens if a handler throws, how to handle back-pressure.
- Be senior/lead-level in tone throughout. This is not a tutorial for beginners.
- End with a "Summary: When to reach for this pattern" section.

Write only this post. Approximately 2500–3000 words of prose plus code.
```

---
---

# Series 2: JVM Internals — Taming Latency in Financial Systems

**Source block:** Block 2 — JVM Internals & Latency Control

**Series goal:** Understand why a Java application "pauses" and how to eliminate those pauses — demonstrating that you think about the JVM as a living system, not just a runtime abstraction.

**Intended audience / interview use:** Targets questions about GC tuning, latency jitter, JVM diagnostics, and JIT compilation in a fintech context. A Tech Lead at a trading firm must be able to diagnose and explain a latency spike without guessing.

**Suggested learning progression:**
1. Start with Survivor Spaces to understand how generational GC works from first principles.
2. Learn TLABs to see how allocation itself introduces latency.
3. Move to JIT Compilation to understand how the JVM optimises — and de-optimises — hot code.
4. Study Shenandoah GC as a modern low-pause alternative.
5. Learn ZGC as the state-of-the-art ultra-low-latency GC.
6. Showcase combines everything into a latency-debugging scenario.

---

### Post 2.1 — Survivor Spaces

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Survivor Spaces: How the JVM Decides Which Objects Deserve to Live* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Survivor Spaces` |
| **Overview** | Explains generational hypothesis, Eden → Survivor → Old generation object lifecycle, what a Minor GC is, and how tenuring threshold determines when objects are promoted. Introduces why short-lived objects in price-streaming pipelines create GC pressure. |
| **Why it matters** | You cannot understand ZGC or Shenandoah without understanding what problem generational collectors were solving. This post builds the conceptual baseline for the whole series. |
| **Dependencies** | None — first post in the series. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in JVM internals and GC tuning as they apply to low-latency financial systems.

Series: "JVM Internals: Taming Latency in Financial Systems"
Series goal: Understand why a Java application pauses and how to eliminate those pauses — demonstrating the ability to diagnose and reason about the JVM as a living system.

This is Post 1 in the series — no prior JVM series posts exist yet.

Post objective:
Write a 5-minute technical blog post titled: "Survivor Spaces: How the JVM Decides Which Objects Deserve to Live"
Keyword covered: Survivor Spaces

The post must:
- Explain the generational hypothesis: most objects die young.
- Walk through the Young Generation layout: Eden, Survivor 0 (From), Survivor 1 (To).
- Explain a Minor GC cycle: Eden is swept, live objects copied to Survivor To, previous Survivor objects that survive are either promoted or copied again.
- Explain tenuring threshold: how many Minor GCs must an object survive before being promoted to Old Generation?
- Explain why object allocation in a high-throughput price-streaming pipeline (creating a new `PriceTick` per update) hammers Eden and causes frequent Minor GCs.
- Be senior-level in tone; assume a Java engineer who has not studied GC deeply.
- End with "Why this matters in production."

Approximately 800–1000 words of prose, possibly with a simple ASCII diagram of the heap regions.
```

---

### Post 2.2 — TLAB (Thread Local Allocation Buffers)

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *TLABs: How the JVM Makes Object Allocation Fast (and When It Doesn't)* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `TLAB (Thread Local Allocation Buffers)` |
| **Overview** | Explains how TLABs pre-allocate Eden space per thread to make `new` nearly free (bump pointer), what happens when a TLAB fills (TLAB refill, potential stall), how TLAB size is tuned, and what "allocation rate" means as a GC pressure signal. |
| **Why it matters** | Understanding TLABs allows a Tech Lead to reason about allocation-rate-driven GC pressure — a common root cause of latency jitter in fintech JVM applications. |
| **Dependencies** | Post 2.1 (Survivor Spaces) recommended. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in JVM internals for low-latency financial systems.

Series: "JVM Internals: Taming Latency in Financial Systems"
Series goal: Understand why a Java application pauses and how to eliminate those pauses.

This is Post 2 in the series. Post 1 covered Survivor Spaces and the generational GC lifecycle.

Post objective:
Write a 5-minute technical blog post titled: "TLABs: How the JVM Makes Object Allocation Fast (and When It Doesn't)"
Keyword covered: TLAB (Thread Local Allocation Buffers)

The post must:
- Explain that without TLABs, every `new` would require synchronised access to Eden — a bottleneck.
- Explain TLABs: each thread is given a private chunk of Eden; allocation is a lock-free bump-pointer operation.
- Explain TLAB refill: when the TLAB is exhausted, the thread must request a new chunk — this is where a stall can occur.
- Explain "humongous objects": objects too large for a TLAB are allocated directly in Old Gen, bypassing Eden entirely, and the cost implications.
- Show how to observe TLAB behaviour in GC logs (`-Xlog:gc*,gc+tlab=debug`).
- Explain allocation rate as a key metric: high allocation rate → frequent Minor GC → latency spikes.
- Connect to the fintech context: a `PriceTick` object created per market update, at 1M updates/sec, can saturate a TLAB quickly.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus command-line flags and log output examples.
```

---

### Post 2.3 — JIT Compilation (C1 vs C2)

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *JIT Compilation: How the JVM Gets Faster Over Time — and When It Slows Down* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `JIT Compilation (C1 vs C2)` |
| **Overview** | Explains interpreted vs compiled execution, the C1 (client) and C2 (server) tiers, tiered compilation, on-stack replacement (OSR), deoptimisation triggers, and why JVM warm-up is a production concern in latency-sensitive services. |
| **Why it matters** | JIT warm-up and deoptimisation are common sources of latency spikes after a deployment or failover — a Tech Lead must be able to explain this and propose mitigations (warm-up traffic, AOT, CDS). |
| **Dependencies** | Posts 2.1–2.2 helpful but not strictly required. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in JVM internals, specifically around compilation and warm-up behaviour in latency-sensitive services.

Series: "JVM Internals: Taming Latency in Financial Systems"
Series goal: Understand why a Java application pauses and how to eliminate those pauses.

This is Post 3 in the series. Previous posts covered Survivor Spaces and TLABs.

Post objective:
Write a 5-minute technical blog post titled: "JIT Compilation: How the JVM Gets Faster Over Time — and When It Slows Down"
Keyword covered: JIT Compilation (C1 vs C2)

The post must:
- Explain interpreted mode: the JVM starts by interpreting bytecode.
- Explain C1 (client compiler): fast to compile, light optimisations, used during warm-up.
- Explain C2 (server compiler): aggressive optimisations (inlining, loop unrolling, escape analysis), used for hot code.
- Explain tiered compilation: the JVM moves code through tiers 0–4 automatically.
- Explain deoptimisation: when an assumption C2 made (e.g. monomorphic call site) becomes invalid, C2's code is discarded and the JVM falls back to interpreted mode temporarily.
- Explain the production impact: post-deployment or post-failover latency spike while the JVM re-warms.
- Mention mitigations: application warm-up traffic, JVM Class Data Sharing (CDS), GraalVM AOT.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose.
```

---

### Post 2.4 — Shenandoah GC

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Shenandoah GC: Concurrent Compaction Without Stopping the World* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Shenandoah GC` |
| **Overview** | Explains Shenandoah's concurrent marking and compaction phases, the Brooks pointer indirection trick, how it achieves sub-10ms pauses regardless of heap size, and how it compares to G1GC. Discusses its suitability for financial services workloads. |
| **Why it matters** | Shenandoah and ZGC are the two modern low-latency GC choices. A Tech Lead must be able to compare them and recommend the right one — this post provides Shenandoah's half of that conversation. |
| **Dependencies** | Posts 2.1 and 2.2 recommended (GC lifecycle). |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from the file `prep-plan-1.md`. The candidate is building depth in JVM GC tuning for low-latency financial systems.

Series: "JVM Internals: Taming Latency in Financial Systems"
Series goal: Understand why a Java application pauses and how to eliminate those pauses.

This is Post 4 in the series. Previous posts covered Survivor Spaces, TLABs, and JIT Compilation.

Post objective:
Write a 5-minute technical blog post titled: "Shenandoah GC: Concurrent Compaction Without Stopping the World"
Keyword covered: Shenandoah GC

The post must:
- Explain the limitation of G1GC: evacuation pauses scale with live data set size.
- Explain Shenandoah's key innovation: concurrent compaction (evacuation happens while the application runs).
- Explain the Brooks pointer: an indirection layer that allows objects to be moved concurrently.
- Walk through Shenandoah's GC phases and which are concurrent vs stop-the-world.
- Explain the pause target: typically sub-10ms regardless of heap size.
- Discuss trade-offs vs G1GC: higher CPU overhead, higher throughput cost.
- Discuss trade-offs vs ZGC (preview for the next post): Shenandoah uses generational data in newer JDKs; ZGC is newer and targets even lower pauses.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose.
```

---

### Post 2.5 — ZGC (Zero Garbage Collection)

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *ZGC: Sub-Millisecond Pauses at Any Heap Size* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `ZGC (Zero Garbage Collection)` |
| **Overview** | Covers ZGC's coloured pointers, load barriers, concurrent relocation, and how it achieves sub-1ms pauses at terabyte-scale heaps. Contrasts with Shenandoah, discusses when ZGC is the right choice in a fintech stack, and covers key JVM flags. |
| **Why it matters** | ZGC is the "modern low-latency king" cited explicitly in the coach report. A Tech Lead candidate at IG must be able to explain it confidently and contrast it with alternatives. |
| **Dependencies** | Posts 2.1–2.4 recommended; Post 2.4 (Shenandoah) especially useful for contrast. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`, which explicitly names ZGC as "the modern low-latency king" that the candidate must understand deeply. The candidate must be able to explain ZGC confidently and contrast it with alternatives in an interview.

Series: "JVM Internals: Taming Latency in Financial Systems"
Series goal: Understand why a Java application pauses and how to eliminate those pauses.

This is Post 5 — the final keyword post in the series, before the showcase. Previous posts: Survivor Spaces, TLABs, JIT Compilation, Shenandoah GC.

Post objective:
Write a 5-minute technical blog post titled: "ZGC: Sub-Millisecond Pauses at Any Heap Size"
Keyword covered: ZGC (Zero Garbage Collection)

The post must:
- Explain ZGC's core mechanism: coloured pointers (using spare bits in 64-bit pointers to encode GC metadata).
- Explain load barriers: how ZGC intercepts pointer dereferences to perform concurrent relocation.
- Explain concurrent relocation: objects are moved while the application runs, with no evacuation pause.
- State the headline guarantee: pause times under 1ms regardless of heap size (tested up to several terabytes).
- Explain Generational ZGC (JDK 21+): how generational support improved throughput without compromising pause times.
- Compare with Shenandoah: both concurrent, but different indirection mechanisms; ZGC uses coloured pointers, Shenandoah uses Brooks pointers.
- Provide key JVM flags: `-XX:+UseZGC`, `-XX:SoftMaxHeapSize`, `-Xlog:gc*`.
- Discuss when to choose ZGC: large heaps, strict sub-millisecond SLA, not memory-constrained.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus JVM flag examples.
```

---

### Post 2.6 — Showcase Article

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `showcase` |
| **Title** | *Diagnosing the Jitter: A JVM Latency Investigation in a Live Price Stream* |
| **Target read time** | 15 minutes |
| **Keyword(s)** | `ZGC`, `Shenandoah GC`, `Survivor Spaces`, `TLAB`, `JIT Compilation (C1 vs C2)` |
| **Overview** | A realistic incident-investigation narrative: a price-streaming service running on G1GC develops intermittent latency spikes under load. The article walks through the full investigation: reading GC logs, using Java Mission Control, profiling with async-profiler, identifying TLAB exhaustion and a JIT deoptimisation as contributing causes, then migrating to ZGC and validating the fix. Frames the story as what a Tech Lead would actually do in production. |
| **Why it matters** | The coach report explicitly cites JMC and GC Logs as the expected diagnostic tools. This showcase is the narrative version of that diagnostic process — exactly what the interviewer wants to hear the candidate walk through. |
| **Dependencies** | All keyword posts 2.1–2.5. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The coach report explicitly states that the interviewer will ask the candidate to walk through how they would use Java Mission Control (JMC) and GC Logs to diagnose latency jitter — and challenge whether the issue is "Object Allocation" or "Infrastructure". This showcase article is the candidate's prepared, detailed answer to that question.

Series: "JVM Internals: Taming Latency in Financial Systems"
Series goal: Understand why a Java application pauses and how to eliminate those pauses.

This is the SHOWCASE ARTICLE — the final and longest post in the series, approximately 15 minutes reading time (~2500–3000 words). It must synthesise all 5 keyword posts into a single coherent incident narrative.

Posts in this series (all must be referenced and woven in):
1. Survivor Spaces — generational GC lifecycle, Minor GC
2. TLABs — allocation as a GC pressure driver
3. JIT Compilation (C1 vs C2) — warm-up, deoptimisation
4. Shenandoah GC — concurrent compaction, Brooks pointers
5. ZGC — coloured pointers, sub-1ms pause guarantee

Showcase post objective:
Write a 15-minute technical blog post titled: "Diagnosing the Jitter: A JVM Latency Investigation in a Live Price Stream"

The post must:
- Open with an incident description: a price-streaming service running G1GC on JDK 17 shows intermittent 80–200ms latency spikes under peak load; the SLA requires p99 < 10ms.
- Walk through the investigation step by step:
  1. Collect GC logs (`-Xlog:gc*:file=gc.log:time,uptime,level,tags`): identify Young GC and Mixed GC pause times; find that pauses cluster around 80ms.
  2. Open Java Mission Control: use the Memory view to observe allocation rate — identify a `PriceTick` object being allocated at 800K objects/sec, saturating Eden.
  3. Use the JIT compilation view in JMC: find a hot method that deoptimised after a class hierarchy change, falling back to interpreted mode momentarily.
  4. Confirm with async-profiler: flame graph shows TLAB refill and allocation contention.
- Describe the remediation path:
  1. Short term: object pooling for `PriceTick` to reduce allocation rate.
  2. Medium term: migrate GC to ZGC (`-XX:+UseZGC`), validate p99 with HDRHistogram.
  3. Long term: investigate LMAX Disruptor to pre-allocate event slots (bridge to Series 1).
- Include relevant GC log snippets, JMC screenshot descriptions, and async-profiler flame graph interpretation.
- Include a section on "How to tell if it's GC or Infrastructure": network jitter, OS scheduling, NIC interrupt affinity — other causes of latency spikes.
- Include a section on "Instrumentation hygiene": how to instrument a Java service for ongoing latency observability (Micrometer, HDRHistogram, JVM metrics in Prometheus).
- Be senior/lead-level in tone throughout. This is a narrative a Tech Lead would tell an interviewer or write in a post-mortem.
- End with "Lessons Learned" and "When to reach for ZGC."

Write only this post. Approximately 2500–3000 words.
```

---
---

# Series 3: Distributed Reliability — Kafka for Mission-Critical Pipelines

**Source block:** Block 3 — Distributed Reliability (Kafka & Consistency)

**Series goal:** Demonstrate that you can design and operate a Kafka-based pipeline where no price is lost and no price is delivered twice, even under broker failure or consumer crash scenarios.

**Intended audience / interview use:** Targets questions about exactly-once delivery, fault tolerance, and Kafka internals in a streaming architecture context. A Tech Lead at a trading firm must be able to reason about failure modes in the message bus without hand-waving.

**Suggested learning progression:**
1. Start with Idempotency as the foundational property.
2. Learn Kafka Transactional Producer to see how idempotency is extended across partitions.
3. Understand Exactly-Once Semantics (EOS) as the end-to-end guarantee.
4. Learn Compacted Topics to understand how Kafka maintains latest-state semantics.
5. Learn Consumer Group Rebalancing to understand the consumer-side failure scenario.
6. Showcase ties everything together in a realistic price-delivery failure scenario.

---

### Post 3.1 — Idempotency

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Idempotency: Why Sending a Message Twice Should Be the Same as Sending It Once* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Idempotency` |
| **Overview** | Defines idempotency in distributed systems, explains why retries in unreliable networks violate it by default, how Kafka's idempotent producer uses a producer ID and sequence number to deduplicate at the broker, and what "at-least-once" vs "exactly-once" means in practice. |
| **Why it matters** | Idempotency is the prerequisite to understanding EOS and transactional producers. It also appears in REST API and database design contexts — a Tech Lead must be fluent here. |
| **Dependencies** | None — first post in the series. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The candidate is building depth in Kafka-based distributed reliability, specifically around ensuring price updates are never lost and never duplicated in a streaming pipeline.

Series: "Distributed Reliability: Kafka for Mission-Critical Pipelines"
Series goal: Design and operate a Kafka-based pipeline where no price is lost and no price is delivered twice, even under broker failure or consumer crash.

This is Post 1 in the series — no prior series posts exist yet.

Post objective:
Write a 5-minute technical blog post titled: "Idempotency: Why Sending a Message Twice Should Be the Same as Sending It Once"
Keyword covered: Idempotency

The post must:
- Define idempotency: an operation that can be applied multiple times with the same result as applying it once.
- Explain why retries break idempotency by default in distributed systems: network timeouts cause the producer to retry, broker may have already committed the first attempt.
- Explain Kafka's idempotent producer: enabled via `enable.idempotence=true`; assigns a Producer ID (PID) and per-partition sequence number; broker deduplicates on (PID, sequence).
- Explain what this guarantees: exactly-once delivery *within a single producer session, within a single partition* (not across partitions or sessions).
- Contrast at-most-once, at-least-once, and exactly-once semantics clearly.
- Use fintech context: a price tick published twice could cause a "flicker" in displayed prices or double-counting in the risk engine.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus producer configuration snippets.
```

---

### Post 3.2 — Kafka Transactional Producer

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Kafka Transactions: Atomically Producing to Multiple Partitions* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Kafka Transactional Producer` |
| **Overview** | Explains how Kafka transactions extend idempotency across multiple partitions and topics, the two-phase commit protocol Kafka uses (transaction coordinator, `__transaction_state` topic), the `isolation.level` consumer setting, and the performance trade-offs of transactional producers. |
| **Why it matters** | Kafka transactions are the mechanism that makes EOS possible across partitions. An interviewer will expect a Tech Lead to know how this works under the hood — not just that it exists. |
| **Dependencies** | Post 3.1 (Idempotency) must come first. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The candidate is building depth in Kafka reliability guarantees for mission-critical price pipelines.

Series: "Distributed Reliability: Kafka for Mission-Critical Pipelines"
Series goal: Design and operate a Kafka pipeline where no price is lost and no price is delivered twice.

This is Post 2 in the series. Post 1 covered idempotency within a single producer session on a single partition.

Post objective:
Write a 5-minute technical blog post titled: "Kafka Transactions: Atomically Producing to Multiple Partitions"
Keyword covered: Kafka Transactional Producer

The post must:
- Explain the limitation of idempotent producer: only covers a single partition, not cross-partition atomicity.
- Explain Kafka's transactional API: `initTransactions()`, `beginTransaction()`, `send()`, `commitTransaction()` / `abortTransaction()`.
- Explain the Transaction Coordinator broker and the `__transaction_state` internal topic: how Kafka achieves two-phase commit.
- Explain `transactional.id`: how it survives producer restarts and ties sessions together for fencing.
- Explain `isolation.level` on the consumer: `read_committed` vs `read_uncommitted` — consumers must use `read_committed` to only see committed transactions.
- Discuss the latency cost: transactions add commit latency; consider whether this is acceptable in a real-time price stream.
- Use fintech context: publishing a price update to both a `prices` topic and a `price-audit` topic atomically.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus Java producer code snippets.
```

---

### Post 3.3 — Exactly-Once Semantics (EOS)

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Exactly-Once Semantics in Kafka: The Full End-to-End Guarantee* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Exactly-once Semantics (EOS)` |
| **Overview** | Explains what EOS means end-to-end: idempotent producer + transactional producer + `read_committed` consumer; how Kafka Streams achieves EOS out of the box; the difference between delivery semantics and processing semantics; and when EOS is actually necessary vs overkill. |
| **Why it matters** | EOS is the explicit goal of the coach report for this block. Interviewers will probe whether the candidate truly understands the full stack required — not just the producer side. |
| **Dependencies** | Posts 3.1 (Idempotency) and 3.2 (Transactional Producer) required. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. EOS is the explicit goal of Block 3 — the candidate must be able to explain the full end-to-end guarantee, not just claim it works.

Series: "Distributed Reliability: Kafka for Mission-Critical Pipelines"
Series goal: Design and operate a Kafka pipeline where no price is lost and no price is delivered twice.

This is Post 3 in the series. Previous posts: Idempotency (Post 1), Kafka Transactional Producer (Post 2).

Post objective:
Write a 5-minute technical blog post titled: "Exactly-Once Semantics in Kafka: The Full End-to-End Guarantee"
Keyword covered: Exactly-once Semantics (EOS)

The post must:
- Clarify the three delivery semantics: at-most-once, at-least-once, exactly-once.
- Explain that EOS in Kafka = idempotent producer + transactional producer + `read_committed` consumer. All three are required.
- Explain delivery semantics vs processing semantics: EOS delivery means the message reaches the broker once; EOS processing means the consumer's side-effects also happen once (harder, requires idempotent consumers or transactional state stores).
- Explain how Kafka Streams achieves EOS processing via its internal transactional commit protocol.
- Discuss when EOS is appropriate: financial audit trails, exactly-once billing, price publication. When it may not be needed: high-volume analytics where approximate counts are acceptable.
- Discuss the cost: latency overhead of transactional commits, reduced throughput.
- Use fintech context: a price update that must appear exactly once in the downstream risk engine to avoid mis-hedging.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose.
```

---

### Post 3.4 — Compacted Topics

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Compacted Topics: Kafka as a Latest-State Key-Value Store* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Compacted Topics` |
| **Overview** | Explains Kafka log compaction, how the cleaner thread retains only the latest value per key while deleting older messages, how tombstone records enable deletion, and how compacted topics enable consumers to rebuild the latest state of an instrument price table after a restart. |
| **Why it matters** | Compacted topics solve the "cold-start" / "state rebuild" problem for price caches. A Tech Lead designing a price distribution system must know this mechanism to avoid incorrect design choices (e.g. using an external cache unnecessarily). |
| **Dependencies** | Post 3.1 (Idempotency) recommended for context. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The candidate is building depth in Kafka reliability for mission-critical price pipelines.

Series: "Distributed Reliability: Kafka for Mission-Critical Pipelines"
Series goal: Design and operate a Kafka pipeline where no price is lost and no price is delivered twice.

This is Post 4 in the series. Previous posts: Idempotency, Kafka Transactional Producer, Exactly-Once Semantics.

Post objective:
Write a 5-minute technical blog post titled: "Compacted Topics: Kafka as a Latest-State Key-Value Store"
Keyword covered: Compacted Topics

The post must:
- Explain Kafka's default log retention: time-based or size-based deletion of entire log segments.
- Explain log compaction: the cleaner thread scans the "dirty" portion of the log and retains only the latest record per key, deleting older values.
- Explain tombstone records: a record with a null value signals deletion; the compaction cleaner eventually removes the key entirely after a `delete.retention.ms` period.
- Explain the compaction guarantee: consumers can always reconstruct the latest state of all keys by reading the entire compacted topic from the beginning.
- Show a concrete use case: `instrument-prices` compacted topic keyed by instrument ID; a new price service starting up replays the topic to rebuild its in-memory price cache without needing a separate database.
- Discuss the trade-off: compaction is not instantaneous; there is a "dirty ratio" window during which newer messages co-exist with older ones.
- Contrast with a snapshot+delta pattern.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose.
```

---

### Post 3.5 — Consumer Group Rebalancing

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Consumer Group Rebalancing: Why Your Kafka Consumer Stops and How to Minimise It* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Consumer Group Rebalancing` |
| **Overview** | Explains Kafka consumer group membership, how partition assignment is done by the group coordinator, what triggers a rebalance (new consumer, crashed consumer, partition change), the "stop the world" rebalance vs incremental cooperative rebalancing (Kafka 2.4+), and the latency impact during rebalancing. |
| **Why it matters** | The coach report explicitly asks "what happens if a node crashes?" — rebalancing is exactly the answer. A Tech Lead must understand both the failure mode and how to mitigate the latency it introduces. |
| **Dependencies** | Posts 3.1–3.3 (delivery semantics) recommended for context. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The coach report explicitly names Consumer Group Rebalancing and challenges the candidate to answer: "What happens if a node crashes?" This post is the candidate's prepared, detailed answer.

Series: "Distributed Reliability: Kafka for Mission-Critical Pipelines"
Series goal: Design and operate a Kafka pipeline where no price is lost and no price is delivered twice.

This is Post 5 — the final keyword post before the showcase. Previous posts: Idempotency, Kafka Transactional Producer, Exactly-Once Semantics, Compacted Topics.

Post objective:
Write a 5-minute technical blog post titled: "Consumer Group Rebalancing: Why Your Kafka Consumer Stops and How to Minimise It"
Keyword covered: Consumer Group Rebalancing

The post must:
- Explain consumer group membership: each consumer in a group owns a subset of partitions.
- Explain what triggers a rebalance: consumer joins, consumer dies (missed heartbeat), consumer calls `poll()` too slowly (`max.poll.interval.ms` exceeded), partition count changes.
- Explain the classic "eager" (stop-the-world) rebalance: all consumers stop consuming, all partition assignments are revoked, the group coordinator reassigns all partitions.
- Explain the latency gap: during rebalance, no messages are consumed — in a price stream, this means a delivery outage.
- Explain incremental cooperative rebalancing (Kafka 2.4+, `CooperativeStickyAssignor`): only the partitions being moved are revoked; the majority of consumers continue consuming uninterrupted.
- Explain how to tune `session.timeout.ms`, `heartbeat.interval.ms`, and `max.poll.interval.ms` to balance failure detection speed vs false-positive rebalances.
- Use fintech context: a rebalance during peak market hours could cause a 2–5 second delivery gap to downstream clients.
- Be senior-level in tone.
- End with "Why this matters in production."

Approximately 800–1000 words of prose plus configuration examples.
```

---

### Post 3.6 — Showcase Article

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `showcase` |
| **Title** | *Zero Data Loss, Zero Duplication: Designing a Fault-Tolerant Kafka Price Pipeline* |
| **Target read time** | 15 minutes |
| **Keyword(s)** | `Exactly-once Semantics`, `Kafka Transactional Producer`, `Consumer Group Rebalancing`, `Idempotency`, `Compacted Topics` |
| **Overview** | A complete architecture walkthrough of a price-distribution pipeline: market data ingest → Kafka → downstream price cache and risk engine. The article walks through failure scenarios (lead broker failure, consumer crash, network partition) and shows how EOS, transactional producers, compacted topics, and cooperative rebalancing together prevent data loss and duplication. Includes architecture diagrams, configuration decisions, and a discussion of observability. |
| **Why it matters** | The coach report explicitly asks "what happens if the Lead Kafka Broker fails during a transaction?" — this showcase is the comprehensive, system-level answer to that question, demonstrating that the candidate can reason about distributed failure modes at a Tech Lead level. |
| **Dependencies** | All keyword posts 3.1–3.5. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The coach report explicitly challenges the candidate to answer: "What happens if the Lead Kafka Broker fails during a transaction, and how do we ensure the client doesn't see a flicker of old data?" This showcase is the candidate's comprehensive, system-level answer.

Series: "Distributed Reliability: Kafka for Mission-Critical Pipelines"
Series goal: Design and operate a Kafka pipeline where no price is lost and no price is delivered twice.

This is the SHOWCASE ARTICLE — approximately 15 minutes reading time (~2500–3000 words). It must synthesise all 5 keyword posts into a single coherent architecture story.

Posts in this series (all must be referenced and woven in):
1. Idempotency — producer deduplication via PID and sequence number
2. Kafka Transactional Producer — cross-partition atomicity, two-phase commit
3. Exactly-Once Semantics (EOS) — full end-to-end guarantee
4. Compacted Topics — latest-state reconstruction after consumer restart
5. Consumer Group Rebalancing — failure detection, cooperative reassignment

Showcase post objective:
Write a 15-minute technical blog post titled: "Zero Data Loss, Zero Duplication: Designing a Fault-Tolerant Kafka Price Pipeline"

The post must:
- Describe the system: a market data ingestor (receives FIX/FAST feed), a Kafka-based price distribution layer, and two downstream consumers (a price cache service and a risk engine).
- Walk through the architecture's reliability stack:
  - Idempotent + transactional producer for the ingestor: ensures each normalised price tick is published atomically to both `prices` and `price-audit` topics.
  - Compacted `prices` topic: allows the price cache to rebuild full state on restart without a separate DB.
  - `read_committed` consumer on the risk engine: ensures it never reads a rolled-back partial transaction.
  - Cooperative sticky rebalancing: minimises consumption gap when a risk engine instance crashes.
- Walk through failure scenarios step by step:
  1. Lead Kafka broker fails mid-transaction: transaction coordinator detects via heartbeat; producer retries `commitTransaction()`; if coordinator was on the failed broker, Kafka elects a new leader from ISR; transactional state survives in `__transaction_state` replicas; producer resumes.
  2. Consumer (risk engine) crashes mid-consumption: partition is reassigned via cooperative rebalance; new consumer reads from last committed offset; because of `read_committed`, it only processes fully committed transactions.
  3. Network partition between producer and broker: `max.block.ms` causes producer to block then throw; application retries with same `transactional.id`, which fences the old zombie producer; exactly-once guarantee preserved.
- Include a section on "Observability for reliability": consumer lag monitoring (Burrow / Kafka JMX), producer error rate, transaction abort rate, rebalance frequency.
- Include a section on "Configuration decisions table": key producer and consumer settings with recommended values and rationale.
- Be senior/lead-level in tone throughout.
- End with "When EOS is worth the cost" and "What we would do differently at 10x scale."

Write only this post. Approximately 2500–3000 words.
```

---
---

# Series 4: The Fintech Tech Lead — Leadership Under Pressure

**Source block:** Block 4 — Behavioral Leadership (The "Fintech" Mindset)

**Series goal:** Translate existing "Lead Developer" wins into the specific values a FTSE 100 trading firm rewards — demonstrating that you can mentor others, manage technical debt, and drive change while remaining "impatient for improvement" without being a blocker.

**Intended audience / interview use:** Targets behavioural questions in the interview. Unlike the technical series, this one prepares structured, story-ready answers to leadership, conflict, and culture-fit questions. Each post frames a leadership concept as a concrete, narrate-able skill.

**Suggested learning progression:**
1. Start with S.T.A.R. Method as the structural scaffolding for all other answers.
2. Learn Technical Debt Management to frame quality initiatives without sounding like a purist.
3. Study Post-mortems to demonstrate blame-free leadership and systems thinking.
4. Cover Mentoring Junior Devs to show the "still loves to code, but grows others" profile.
5. Finish with Stakeholder Management for the "Raise the Bar" and velocity-vs-quality tension.
6. Showcase weaves all five into a single leadership narrative.

---

### Post 4.1 — S.T.A.R. Method

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *The S.T.A.R. Method: Structuring Technical Leadership Stories That Land* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `S.T.A.R. Method` |
| **Overview** | Explains the S.T.A.R. framework (Situation, Task, Action, Result) as a storytelling tool for technical interviews, with worked examples of weak vs strong S.T.A.R. stories from a Tech Lead's perspective. Emphasises quantifiable results and leadership actions. |
| **Why it matters** | Every behavioural question in the interview will be answered better with explicit S.T.A.R. structure. This post is the meta-skill that makes all other posts in this series usable. |
| **Dependencies** | None — first and foundational post for the series. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. Block 4 focuses on behavioural leadership — specifically on framing the candidate's experience to match the values the company rewards: "Think Big", "Raise the Bar", and "impatient for change."

Series: "The Fintech Tech Lead: Leadership Under Pressure"
Series goal: Translate the candidate's Lead Developer wins into structured, interview-ready stories that demonstrate the culture-fit expected at a FTSE 100 trading firm.

This is Post 1 in the series — the foundational post for all behavioural storytelling in the series.

Post objective:
Write a 5-minute technical blog post titled: "The S.T.A.R. Method: Structuring Technical Leadership Stories That Land"
Keyword covered: S.T.A.R. Method

The post must:
- Explain the four components: Situation (context), Task (your role/challenge), Action (specifically what *you* did, not the team), Result (measurable outcome).
- Show a weak example of a behavioural answer (vague, team-focused, no result) vs a strong S.T.A.R. version (specific, personal, quantified outcome) — use a technical leadership scenario (e.g. introducing code review standards under delivery pressure).
- Explain common mistakes: spending too long on Situation, losing the "I" voice (saying "we" too much), skipping the Result.
- Advise on how to prepare: writing out 5–7 S.T.A.R. stories in advance covering different dimensions (quality, conflict, mentoring, stakeholder, technical decision).
- Frame this for a fintech interview specifically: interviewers at trading firms care about speed, reliability, and standards.
- Be conversational but technically credible in tone — this is a blog post about interview technique, not a dry HR guide.
- End with "The discipline this creates."

Approximately 800–1000 words.
```

---

### Post 4.2 — Technical Debt Management

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Technical Debt Management: Making the Case for Quality Without Slowing the Team* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Technical Debt Management` |
| **Overview** | Frames technical debt using the debt metaphor, distinguishes deliberate vs inadvertent debt, shows how to quantify and communicate debt to non-technical stakeholders, and presents strategies for paying it down incrementally without halting delivery — specifically the "20% rule" and the "strangler fig" pattern. |
| **Why it matters** | A Tech Lead candidate must demonstrate they can drive quality improvements without being a blocker. This post frames the "Path to Clean Code" initiative from the coach report in a commercially credible way. |
| **Dependencies** | Post 4.1 (S.T.A.R.) recommended for framing. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The coach report explicitly references a "Path to Clean Code" initiative the candidate led, and advises framing it as: "I saw a quality gap, I defined the new standard, and I coached the team to reach it." This post provides the conceptual and narrative scaffolding for that story.

Series: "The Fintech Tech Lead: Leadership Under Pressure"
Series goal: Translate the candidate's Lead Developer wins into structured interview-ready stories demonstrating FTSE 100 leadership values.

This is Post 2. Post 1 covered the S.T.A.R. method.

Post objective:
Write a 5-minute technical blog post titled: "Technical Debt Management: Making the Case for Quality Without Slowing the Team"
Keyword covered: Technical Debt Management

The post must:
- Explain the technical debt metaphor (Ward Cunningham's original framing) and distinguish deliberate vs inadvertent debt.
- Explain how to quantify debt for stakeholders: code complexity metrics (cyclomatic complexity, coupling), estimated refactoring cost, incident rate attributable to the debt.
- Show a realistic stakeholder conversation: how a Tech Lead makes the business case for paying down debt without just saying "we need to refactor."
- Describe practical strategies: "20% of each sprint for debt", "strangler fig pattern" for legacy rewrites, "boy scout rule" for incremental improvement.
- Frame the "Path to Clean Code" type of initiative: defining standards (style guide, test coverage gate, review checklist), then coaching the team to reach them.
- Address the tension: the team wants to ship fast, you want to raise the bar — how do you hold both?
- Be senior-level in tone; speak from the perspective of a Tech Lead who has done this, not a theorist.
- End with "What this looks like in an interview answer."

Approximately 800–1000 words.
```

---

### Post 4.3 — Post-mortems

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Post-mortems: Turning Production Incidents Into Engineering Assets* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Post-mortems` |
| **Overview** | Explains the purpose of blameless post-mortems, how to structure one (timeline, contributing factors, action items), how a Tech Lead facilitates them to build psychological safety and systemic thinking, and how to use a post-mortem story as a leadership example in an interview. |
| **Why it matters** | Post-mortems signal that the candidate has operated in a production environment at a lead level and can build a learning culture. A strong post-mortem story in an interview demonstrates maturity, systems thinking, and influence. |
| **Dependencies** | Post 4.1 (S.T.A.R.) recommended; Post 4.2 (Technical Debt) useful for systemic framing. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. Block 4 focuses on demonstrating fintech leadership values. Post-mortem leadership is a strong signal of production maturity and blameless culture — both valued at financial trading firms.

Series: "The Fintech Tech Lead: Leadership Under Pressure"
Series goal: Translate the candidate's Lead Developer wins into structured interview-ready stories demonstrating FTSE 100 leadership values.

This is Post 3. Previous posts: S.T.A.R. Method, Technical Debt Management.

Post objective:
Write a 5-minute technical blog post titled: "Post-mortems: Turning Production Incidents Into Engineering Assets"
Keyword covered: Post-mortems

The post must:
- Define a blameless post-mortem and why the "blameless" prefix matters (blame hides systemic issues; blameless surfaces them).
- Describe a recommended post-mortem structure: incident summary, timeline, contributing factors (not "root causes" — there are always multiple), action items (with owners and deadlines), and a "what went well" section.
- Explain the Tech Lead's facilitation role: how to keep the conversation systemic rather than personal, how to ensure action items are followed up, how to publish findings to the wider team.
- Explain how a post-mortem story works as an interview answer: framing it with S.T.A.R., demonstrating systems thinking, showing that you learned and changed something.
- Include a brief example: a fictional latency incident (e.g. a GC pause causing a 30-second price delivery outage) and how the post-mortem led to a ZGC migration (connecting to Series 2).
- Be senior-level in tone; speak from the perspective of a Tech Lead who has run real post-mortems.
- End with "The compounding value of post-mortem culture."

Approximately 800–1000 words.
```

---

### Post 4.4 — Mentoring Junior Devs

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Mentoring Junior Devs: Growing Engineers Without Becoming a Bottleneck* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Mentoring Junior Devs` |
| **Overview** | Explores practical mentoring techniques for a Tech Lead: structured code review as teaching, pair programming sessions, 1:1 cadences, stretch task assignment, and how to mentor without creating dependency. Frames this in the "still loves to code, but grows others" profile the coach report describes. |
| **Why it matters** | The coach report explicitly says the interviewer wants "a Senior who acts like a Lead but still loves to code." Mentoring stories are the evidence. A Tech Lead who cannot speak to how they grew others is missing a core competency signal. |
| **Dependencies** | Post 4.1 (S.T.A.R.) recommended for story framing. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The coach report explicitly states the interviewer wants "a Senior who acts like a Lead but still loves to code." Mentoring junior developers is one of the key signals of that profile.

Series: "The Fintech Tech Lead: Leadership Under Pressure"
Series goal: Translate the candidate's Lead Developer wins into structured interview-ready stories demonstrating FTSE 100 leadership values.

This is Post 4. Previous posts: S.T.A.R. Method, Technical Debt Management, Post-mortems.

Post objective:
Write a 5-minute technical blog post titled: "Mentoring Junior Devs: Growing Engineers Without Becoming a Bottleneck"
Keyword covered: Mentoring Junior Devs

The post must:
- Explain the Tech Lead's mentoring paradox: growing others is the multiplier, but it takes time away from individual output — how do you balance this?
- Describe practical mentoring techniques:
  - Structured code review as teaching (not just approving/rejecting — explaining *why*).
  - Pair programming sessions: when to drive, when to navigate.
  - Stretch task assignment: give juniors tasks that are just beyond their comfort zone, with a safety net.
  - 1:1 cadences: what to cover (blockers, growth goals, feedback).
- Explain how to mentor without creating dependency: the goal is to make the junior not need you.
- Explain how this translates to an interview story: what does a strong mentoring S.T.A.R. look like? What outcome metric makes it land?
- Include a brief example: mentoring a junior through their first implementation of a concurrent Java feature, with the outcome being they now own that area of the codebase.
- Be senior-level in tone; this is practical wisdom, not HR advice.
- End with "The leadership multiplier."

Approximately 800–1000 words.
```

---

### Post 4.5 — Stakeholder Management

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `keyword post` |
| **Title** | *Stakeholder Management: How a Tech Lead Sells Technical Decisions Upward* |
| **Target read time** | 5 minutes |
| **Keyword(s)** | `Stakeholder Management` |
| **Overview** | Covers how a Tech Lead communicates technical decisions to non-technical stakeholders, how to manage competing priorities (quality vs speed), how to say "no" in a way that keeps relationships intact, and how to frame technical improvements as business value — specifically in a fintech context where reliability and latency are revenue-linked. |
| **Why it matters** | The coach report values "Raise the Bar" — but raising the bar requires stakeholder buy-in. A Tech Lead who cannot communicate upward cannot drive change. This post prepares a strong story for the "push TDD/technical improvement to a team that just wants to ship" question. |
| **Dependencies** | Posts 4.1 (S.T.A.R.), 4.2 (Technical Debt) recommended. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. The coach report explicitly asks the candidate to prepare for: "a time I had to push a technical improvement (like TDD) to a team that just wanted to ship fast." Stakeholder management is the skill that resolves that tension.

Series: "The Fintech Tech Lead: Leadership Under Pressure"
Series goal: Translate the candidate's Lead Developer wins into structured interview-ready stories demonstrating FTSE 100 leadership values.

This is Post 5 — the final keyword post before the showcase. Previous posts: S.T.A.R. Method, Technical Debt Management, Post-mortems, Mentoring Junior Devs.

Post objective:
Write a 5-minute technical blog post titled: "Stakeholder Management: How a Tech Lead Sells Technical Decisions Upward"
Keyword covered: Stakeholder Management

The post must:
- Define the stakeholder landscape for a Tech Lead: product managers, engineering managers, senior engineers, and sometimes business stakeholders.
- Explain the core communication challenge: technical depth vs business language — how to translate a latency concern into revenue risk, or a GC tuning effort into incident reduction.
- Describe a practical framework for stakeholder communication: identify the stakeholder's goal → frame the technical decision in terms of that goal → quantify the impact → present options with trade-offs, not just "we need to do X."
- Explain how to say "no" constructively: not "we can't do that" but "if we do that, here's what we trade off; here's an alternative."
- Explain how to push quality initiatives (TDD, code standards, architectural changes) when the team or management wants to prioritise speed: building the incremental case, piloting on one feature, showing the data.
- Include a brief example framed as a S.T.A.R. story: a Tech Lead who wanted to introduce mandatory code coverage gates, faced pushback from PM, and negotiated a phased approach that improved defect rate without slowing delivery.
- Be senior-level in tone.
- End with "The vocabulary shift."

Approximately 800–1000 words.
```

---

### Post 4.6 — Showcase Article

| Field | Value |
|---|---|
| **Status** | `planned` |
| **Post type** | `showcase` |
| **Title** | *Raising the Bar Without Breaking the Team: A Tech Lead's Year of Change* |
| **Target read time** | 15 minutes |
| **Keyword(s)** | `S.T.A.R. Method`, `Technical Debt Management`, `Post-mortems`, `Mentoring Junior Devs`, `Stakeholder Management` |
| **Overview** | A narrative "year in the life of a Tech Lead" that weaves all five behavioural concepts into a single, coherent leadership story. The protagonist inherits a team with a quality gap, technical debt, and no incident culture. Over the arc of the article, they introduce standards, mentor the team, run their first post-mortem, sell a refactor to the business, and manage the tension between velocity and reliability. Written in first person, S.T.A.R.-structured, and explicitly framed for use as interview preparation material. |
| **Why it matters** | The coach report says the interviewer cares about "culture fit" and "velocity." This showcase is a single, memorable, multi-faceted leadership story that signals exactly that combination. A candidate who can narrate this arc in an interview — even in condensed form — will stand out. |
| **Dependencies** | All keyword posts 4.1–4.5. |

**Kick-off prompt:**

```
You are a technical blog post writer helping a candidate prepare for a Tech Lead Java Engineer interview at a FTSE 100 financial trading company.

The preparation plan is derived from `prep-plan-1.md`. Block 4 is about translating existing Lead Developer wins into the language of FTSE 100 leadership values: "Think Big", "Raise the Bar", "impatient for change", "senior who acts like a lead but still loves to code." This showcase article is the candidate's single, cohesive leadership narrative — a story that demonstrates all five leadership keywords in context.

Series: "The Fintech Tech Lead: Leadership Under Pressure"
Series goal: Translate the candidate's Lead Developer wins into structured interview-ready stories demonstrating FTSE 100 leadership values.

This is the SHOWCASE ARTICLE — approximately 15 minutes reading time (~2500–3000 words). It must synthesise all 5 keyword posts into a single first-person leadership narrative.

Posts in this series (all must appear meaningfully in the showcase):
1. S.T.A.R. Method — the structural scaffolding used to narrate each moment
2. Technical Debt Management — identifying and reducing the quality gap
3. Post-mortems — building a blameless incident culture
4. Mentoring Junior Devs — growing engineers, not just owning work
5. Stakeholder Management — selling quality initiatives without losing velocity

Showcase post objective:
Write a 15-minute technical blog post titled: "Raising the Bar Without Breaking the Team: A Tech Lead's Year of Change"

The post must:
- Be written in first person, from the perspective of a Tech Lead who has just joined a fintech team that has a quality problem.
- Use a narrative arc across several months:
  - Month 1: Discovery — the quality gap (low test coverage, no code review standards, no incident retrospectives, ad-hoc onboarding for juniors). Frame this as the "Situation" in S.T.A.R.
  - Month 2–3: The first technical debt initiative — identifying the highest-impact debt, building the business case, presenting to PM. Describe the resistance and how it was navigated. Frame with Stakeholder Management and Technical Debt Management posts.
  - Month 4: The first post-mortem — a production incident (a GC-related latency spike, connecting to Series 2), the candidate runs a blameless post-mortem, the team learns, and an action item results in a ZGC migration.
  - Month 5–6: The mentoring inflection point — a junior engineer takes ownership of a concurrent feature area after a structured mentoring effort. Describe the pair programming sessions, code reviews, stretch assignments.
  - Month 7–9: Velocity vs quality tension peaks — PM pushes back on the new mandatory code coverage gate. The candidate uses stakeholder management to negotiate a phased rollout. Data shows defect rate drops. The team's confidence grows.
  - Month 10–12: The bar has been raised — the team is self-correcting, the juniors are growing, incidents are down, and the technical debt backlog has a plan.
- Use S.T.A.R. structure explicitly at key narrative points: each major challenge introduced as a Situation/Task, the candidate's Action described specifically ("I, not we"), and the Result quantified.
- Connect to the technical series where natural (e.g. the GC incident from Series 2's showcase, the Disruptor from Series 1's showcase).
- End with a section "What I Would Do Differently" and "The interview version of this story" — a compressed 2-minute summary the candidate can use verbatim.
- Be warm but precise in tone — this is a leadership essay, not a Java tutorial.

Write only this post. Approximately 2500–3000 words.
```

---
---

## Summary Table

| Series | Post # | Type | Title | Read Time | Status |
|---|---|---|---|---|---|
| 1 | 1.1 | keyword | CAS Explained: The Atomic Primitive That Replaced the Lock | 5 min | `planned` |
| 1 | 1.2 | keyword | AtomicReference: CAS Applied to Object State in Java | 5 min | `planned` |
| 1 | 1.3 | keyword | False Sharing: The Silent Cache-Line Killer in Multithreaded Java | 5 min | `planned` |
| 1 | 1.4 | keyword | Memory Barriers: What the JVM Guarantees (and What It Doesn't) | 5 min | `planned` |
| 1 | 1.5 | keyword | Thread Affinity: Pinning Java Threads to CPU Cores for Predictable Latency | 5 min | `planned` |
| 1 | 1.6 | keyword | The LMAX Disruptor: How a Ring Buffer Replaced Every Queue You Know | 5 min | `planned` |
| 1 | 1.7 | showcase | Zero-Lock Price Distribution: Building a High-Throughput Ticker with the LMAX Disruptor | 15 min | `planned` |
| 2 | 2.1 | keyword | Survivor Spaces: How the JVM Decides Which Objects Deserve to Live | 5 min | `planned` |
| 2 | 2.2 | keyword | TLABs: How the JVM Makes Object Allocation Fast (and When It Doesn't) | 5 min | `planned` |
| 2 | 2.3 | keyword | JIT Compilation: How the JVM Gets Faster Over Time — and When It Slows Down | 5 min | `planned` |
| 2 | 2.4 | keyword | Shenandoah GC: Concurrent Compaction Without Stopping the World | 5 min | `planned` |
| 2 | 2.5 | keyword | ZGC: Sub-Millisecond Pauses at Any Heap Size | 5 min | `planned` |
| 2 | 2.6 | showcase | Diagnosing the Jitter: A JVM Latency Investigation in a Live Price Stream | 15 min | `planned` |
| 3 | 3.1 | keyword | Idempotency: Why Sending a Message Twice Should Be the Same as Sending It Once | 5 min | `planned` |
| 3 | 3.2 | keyword | Kafka Transactions: Atomically Producing to Multiple Partitions | 5 min | `planned` |
| 3 | 3.3 | keyword | Exactly-Once Semantics in Kafka: The Full End-to-End Guarantee | 5 min | `planned` |
| 3 | 3.4 | keyword | Compacted Topics: Kafka as a Latest-State Key-Value Store | 5 min | `planned` |
| 3 | 3.5 | keyword | Consumer Group Rebalancing: Why Your Kafka Consumer Stops and How to Minimise It | 5 min | `planned` |
| 3 | 3.6 | showcase | Zero Data Loss, Zero Duplication: Designing a Fault-Tolerant Kafka Price Pipeline | 15 min | `planned` |
| 4 | 4.1 | keyword | The S.T.A.R. Method: Structuring Technical Leadership Stories That Land | 5 min | `planned` |
| 4 | 4.2 | keyword | Technical Debt Management: Making the Case for Quality Without Slowing the Team | 5 min | `planned` |
| 4 | 4.3 | keyword | Post-mortems: Turning Production Incidents Into Engineering Assets | 5 min | `planned` |
| 4 | 4.4 | keyword | Mentoring Junior Devs: Growing Engineers Without Becoming a Bottleneck | 5 min | `planned` |
| 4 | 4.5 | keyword | Stakeholder Management: How a Tech Lead Sells Technical Decisions Upward | 5 min | `planned` |
| 4 | 4.6 | showcase | Raising the Bar Without Breaking the Team: A Tech Lead's Year of Change | 15 min | `planned` |

**Total: 24 posts — 21 keyword posts (5 min each) + 3 showcase articles (15 min each) + 1 leadership showcase (15 min)**
