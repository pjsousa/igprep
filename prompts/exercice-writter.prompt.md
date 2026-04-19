## Context

You are acting as a technical exercise designer for an engineering blog series focused on high-performance Java and JVM internals. The reader is a software engineer preparing for senior/lead-level interviews in high-frequency trading or similar latency-sensitive domains.

You have access to:
- `posts/` — articles covering the blog's knowledge blocks
- `interview-blog-plan.md` — the full editorial plan, including block topics and learning objectives

The two primary knowledge blocks to draw from are:
1. **Block 1 — Lock-Free Java: Mechanical Sympathy for the Trading Floor**
2. **JVM Internals: Taming Latency in Financial Systems**

---

## Task

Design **3 progressive exercise plans** for building a **High-Frequency Price Match Service** capable of handling **1,000,000 price updates per second**.

You are **not** building the service. You are writing structured, self-contained exercise descriptions that guide the reader to build it themselves — progressing from naive to production-grade.

---

## Output Requirements

Create **3 separate Markdown files** inside `showcase_projects/`:

### `exercise_01_naive.md` — Junior-Level Implementation
- Workable but breakable under load
- Uses straightforward, idiomatic Java (no exotic concurrency primitives)
- A junior engineer should be able to understand and implement it
- Deliberately introduces bottlenecks that become obvious under stress

### `exercise_02_senior.md` — Senior-Level Optimization
- Starts from the naive implementation
- Focuses purely on code-level optimizations a strong senior engineer would apply
- Introduces lock-free data structures, `Unsafe`, false sharing mitigation, memory layout awareness, etc.
- References concepts from **Block 1 (Lock-Free Java)** specifically
- No architectural overhaul — same structure, better code

### `exercise_03_lead.md` — Production-Grade System
- Starts from the senior version
- Elevates to a system a Lead Engineer could probe deeply without finding major gaps
- Covers observability, JVM tuning (GC selection, NUMA, CPU pinning), operational concerns, and design tradeoffs
- References concepts from **JVM Internals: Taming Latency in Financial Systems**
- Should anticipate and address grilling questions a Lead or Principal would ask

---

## Format for Each Exercise File

Each file must include the following sections:

```
# [Level] Price Match Service — Exercise

## Objective
What the engineer will build and learn.

## Background & Motivation
Why this design exists at this level. What problems it solves and what it ignores.

## System Specification
- Functional requirements
- Non-functional requirements (throughput, latency targets)
- Constraints (e.g., single JVM, no external DB, etc.)

## Step-by-Step Exercise Guide
Numbered steps the engineer follows to implement the system.
Each step includes:
- What to implement
- Key decisions to make (with hints, not spoilers)
- Concepts to study from the relevant blog posts

## Bottleneck & Reflection Questions
Questions that reveal where this level breaks down (sets up the next exercise).

## Success Criteria
How the engineer knows they're done.
```

---

## Constraints

- Each exercise must be self-contained and progressively build on the prior one
- Do **not** provide solution code — provide clear specs, scaffolding hints, and guiding questions
- Tie each exercise explicitly to specific concepts from the referenced blog posts in `posts/`
- Target audience vocabulary: mid-to-senior engineers familiar with Java concurrency basics
```

