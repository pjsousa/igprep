# AGENTS.md

## 1. Repository Overview

This repository is a personal interview preparation guide for a **Tech Lead Engineer** role at **IG Group**, a FTSE 100 fintech company offering trading on stocks, futures, and CFDs to 1.3 million customers worldwide.

**Target role:** Tech Lead Engineer (Java-focused backend)  
**Target company:** IG Group  
**Core evaluation areas:** High-performance Java, distributed systems, JVM internals, ORM at scale, fintech leadership  
**Preparation strategy:** A structured body of 35 blog-style posts (organised into 5 series), live mock interview sessions, and hands-on Java showcase projects

Each post maps directly to a specific interview question identified by an elite AI coach. The body of work is designed to be:

- **Interview-focused** — every concept is anchored to a question the interviewer is likely to ask
- **Senior-level** — assumes a capable Java engineer; depth is at Tech Lead standard
- **Production-credible** — all examples are grounded in real fintech scenarios (price feeds, matching engines, risk systems)
- **Narrative-ready** — structured so answers can be articulated verbally in an interview

---

## 2. Repository Structure

```
igprep/
├── AGENTS.md                        ← this file
├── README.md                        ← minimal project header
├── interview-blog-plan.md           ← master editorial plan: all 35 posts, statuses, kick-off prompts
├── prep-plan-2.md                   ← coach report: 12-hour prep split, risk profile, keyword targets
├── run_loop.sh                      ← shell script for running autonomous writing loops
│
├── mock_interviews/
│   ├── company.md                   ← company context: IG Group background and culture
│   ├── interview-index.md           ← index of all completed mock interview sessions
│   ├── interview1-block1/           ← index of all completed mock interview sessions
│   └── interview2-block2/           ← candidate and coach transcripts for session 1
│       ├── candidate.pdf            ← candidate and coach transcripts for session 2
│
├── posts/
│   ├── 01-lock-free-java/           ← Series 1: 7 posts on lock-free concurrency
│   ├── 02-jvm-internals/            ← Series 2: 6 posts on JVM internals & GC
│   ├── 03-distributed-reliability/  ← Series 3: 6 posts on Kafka & distributed guarantees
│   ├── 04-fintech-tech-lead/        ← Series 4: 6 posts on leadership & communication
│   └── 05-hibernate-jpa/            ← Series 5: 11 planned posts (6 written)
│
├── prompts/
│   ├── docs.prompt.md               ← prompt for generating this AGENTS.md file
│   ├── loop.prompt.md               ← prompt for the autonomous post-writing loop agent
│   └── plan.prompt.md               ← prompt for creating editorial plans from coach reports
│
└── showcase_projects/               ← showcase projects and walkthoughts
```

---

## 3. Agent Operating Guidelines

### Primary Source of Truth

Before taking any action, an agent must read:

1. **`interview-blog-plan.md`** — the master editorial plan. It contains the canonical list of all posts, their statuses (`written` or `planned`), kick-off prompts, dependencies, and series context.
2. **`prep-plan-2.md`** — the coach report. It defines the interview risk profile and keyword targets that drive post prioritisation.

### Navigation Principles

- Always check `interview-blog-plan.md` before writing a new post. Do not overwrite any post marked `written`.
- The master plan's kick-off prompts are the authoritative brief for each post. Follow them precisely unless the user provides a different instruction.
- Use `interview-index.md` to understand what mock interview sessions exist before adding a new one.
- `company.md` provides IG Group context required to frame all technical writing.

### Safety Rules

- **Never overwrite a `written` post.** Check the status in `interview-blog-plan.md` first.
- **Never fabricate an interview transcript.** Mock interview content must come from a real session.
- **Always update `interview-blog-plan.md`** after writing a post: change its status from `planned` to `written`.
- **Always commit both the new post file and the updated plan** in the same commit or sequential commits.

---

## 4. Content Conventions

### 4.1 Posts (`posts/`)

#### File Naming

```
{series-number:02d}-{post-number:02d}-{slug}.md
```

- Series and post numbers are zero-padded to two digits.
- Slugs use lowercase hyphen-separated words.
- Examples: `01-01-cas-explained.md`, `05-06-fetch-join.md`

#### Directory Naming

```
{series-number:02d}-{descriptive-slug}/
```

Examples: `01-lock-free-java/`, `04-fintech-tech-lead/`

#### Post Types

| Type | Read Time | Word Count | Purpose |
|---|---|---|---|
| Keyword post | ~5 min | 800–1000 words | Explain one focused concept with code and fintech context |
| Showcase article | ~15 min | 2500–3000 words | Synthesise the full series in a realistic production architecture |

#### Writing Style

- **Audience:** senior Java engineers who know the basics; do not explain foundational concepts unless the post is specifically about them.
- **Tone:** technical and precise, not conversational; no filler, no padding.
- **Opening:** always frame the problem in a fintech production context (price feeds, matching engines, risk systems, market data).
- **Code samples:** always include concrete Java examples; compare naive vs. optimised approaches where relevant.
- **Hardware awareness:** where applicable, reference the CPU instruction, cache line behaviour, or OS interaction that explains the mechanism.
- **Tradeoffs:** explicitly state the cost and when the optimisation applies; avoid presenting any approach as universally correct.
- **Closing:** connect the concept back to a specific interview question or production scenario.
- **No padding sections:** do not add sections ("Conclusion", "Summary", "Key Takeaways") unless they add material that was not already stated.

#### Post Structure (Keyword Post)

1. Opening — fintech context framing the problem
2. Concept explanation — mechanism, not just definition
3. Code example — naive approach, then optimised
4. Hardware/JVM layer — what makes it work at the system level
5. Tradeoffs and failure modes
6. Why this matters in production (fintech framing)

#### Post Structure (Showcase Article)

1. Scenario setup — realistic production architecture with a named problem
2. Series synthesis — each relevant concept applied in the architecture
3. Code walkthrough — a coherent, non-trivial implementation
4. Failure modes and mitigations
5. Interview payoff — explicit connection to the expected interview question

### 4.2 Mock Interviews (`mock_interviews/`)

#### Directory Naming

```
interview{n}-block{m}/
```

Where `n` is the session number and `m` is the block number. Example: `interview2-block2/`.

#### File Contents

Each session directory contains:
- `candidate.pdf` — the candidate's answers and transcript
- `coach.pdf` — the coach's feedback and scoring

#### Index (`interview-index.md`)

The index lists all sessions with:
- Session number and block
- Date (if known)
- Topics covered
- Direct link to interactive session (Claude AI or Perplexity)

Update `interview-index.md` whenever a new session directory is added.

### 4.3 Prompts (`prompts/`)

#### File Naming

```
{domain}.prompt.md
```

Examples: `loop.prompt.md`, `plan.prompt.md`, `docs.prompt.md`

#### Content Standards

- Each prompt must be fully self-contained — an agent with no prior context must be able to execute it.
- Include explicit safety constraints (e.g., "do not overwrite `written` posts").
- Define the exact output format expected (file paths, commit messages, plan update instructions).

### 4.4 Showcase Projects (`showcase_projects/`)

Each project is a standalone Java directory implementing a specific production scenario. Conventions:

- Directory name describes the scenario or implementation variant (e.g., `naive/` for unoptimised baseline).
- Code must compile and run without external dependencies beyond standard Java tooling unless a build file is present.
- Include inline comments only where the intent is non-obvious from the code itself.
- Each project should be linkable from the corresponding showcase post.

---

## 5. Java & Technical Standards

### Language Version

Target **Java 21** unless a post or project specifically addresses a version-specific feature (e.g., Virtual Threads, Pattern Matching). Use language features that a senior engineer at a FTSE 100 fintech would recognise and respect.

### Performance Expectations

This repository covers the performance tier where latency is measured in microseconds. The following concerns are first-class:

- **Allocation pressure** — prefer object reuse and pooling over frequent allocation in hot paths.
- **False sharing** — use `@Contended` or manual padding for hot fields accessed by multiple threads.
- **Memory barriers** — use `volatile`, `VarHandle`, or `Unsafe` with explicit barrier semantics; explain which barrier (`LoadLoad`, `StoreStore`, etc.) is being inserted and why.
- **Lock-free patterns** — prefer CAS loops and `Atomic*` classes over `synchronized`; use `LockSupport` and condition variables intentionally.
- **GC impact** — reason about allocation rate, promotion rate, and pause time; name the GC algorithm and its pause model.
- **CPU cache behaviour** — structure data layouts for cache-line friendliness; address NUMA where relevant.

### Coding Conventions

- Class names: `UpperCamelCase`
- Method and variable names: `lowerCamelCase`
- Constants: `UPPER_SNAKE_CASE`
- Package names: lowercase, no underscores
- No wildcard imports
- No commented-out code in committed files
- Exceptions must be handled or explicitly propagated; no swallowed exceptions

### Technical Focus Areas by Series

| Series | Core Keywords |
|---|---|
| Lock-Free Java | CAS, `compareAndSet`, `AtomicReference`, false sharing, cache line, memory barriers, `VarHandle`, LMAX Disruptor, ring buffer, thread affinity |
| JVM Internals | Eden, survivor spaces, tenuring threshold, TLAB, JIT, C1/C2 compiler, OSR, Shenandoah, ZGC, concurrent marking, GC pause, JMC, async-profiler |
| Distributed Reliability | Kafka idempotency, `enable.idempotence`, transactions, `transactional.id`, exactly-once semantics, compacted topics, consumer group rebalancing, partition assignment |
| Fintech Tech Lead | S.T.A.R. method, technical debt prioritisation, blameless post-mortem, incident timeline, mentoring, stakeholder communication, "raise the bar" |
| Hibernate / JPA | Persistence context, entity lifecycle (`NEW`, `MANAGED`, `DETACHED`, `REMOVED`), dirty checking, flush mode, N+1 problem, fetch join, `@BatchSize`, optimistic locking, `@Version`, transaction propagation |

---

## 6. Contribution Workflow

### 6.1 Writing a New Post

1. **Read `interview-blog-plan.md`** and identify a post with status `planned`.
2. **Confirm dependencies** — verify that all prerequisite posts listed in the plan are already `written`.
3. **Read the kick-off prompt** for the target post from `interview-blog-plan.md`.
4. **Read `company.md`** for IG Group context to frame the opening and closing.
5. **Write the post** following the conventions in Section 4.1. Save to the correct path:
   ```
   posts/{series-dir}/{series-number}-{post-number}-{slug}.md
   ```
6. **Update `interview-blog-plan.md`**: change the post's status from `planned` to `written`.
7. **Commit** with the following message format:
   ```
   {series-number}-{post-number}-{slug}: {Post Title}
   ```
   Example: `05-06-fetch-join: Fetch Join — Solving N+1 without switching to native SQL`

### 6.2 Adding a Mock Interview Session

1. **Create the session directory**:
   ```
   mock_interviews/interview{n}-block{m}/
   ```
2. **Add the PDFs**: `candidate.pdf` and `coach.pdf`.
3. **Update `interview-index.md`** with the session number, date, topics, and any session links.
4. **Commit** with message format:
   ```
   mock_interviews: add interview {n} block {m} — {topic focus}
   ```

### 6.3 Adding a Showcase Project

1. **Create the project directory** under `showcase_projects/`:
   ```
   showcase_projects/{scenario-slug}/
   ```
2. **Implement the Java project**. Include a build file (`pom.xml` or `build.gradle`) if dependencies are required.
3. **Link the project** from the corresponding showcase post (add a reference in the post's opening or a dedicated section).
4. **Commit** with message format:
   ```
   showcase_projects/{slug}: {brief description}
   ```

### 6.4 Adding or Updating a Prompt

1. Name the file `{domain}.prompt.md` under `prompts/`.
2. Ensure the prompt is fully self-contained (see Section 4.3).
3. **Commit** with message format:
   ```
   prompts/{name}.prompt.md: {brief description of change}
   ```

---

## 7. Goals & Success Criteria

### Overall Goal

Produce a body of preparation material that enables the candidate to answer any senior Java or Tech Lead interview question with immediate depth, production credibility, and narrative clarity.

### Per-Post Success Criteria

- The post can be read in isolation and understood by a senior Java engineer with no prior context from the series.
- Every claim about performance or JVM behaviour is mechanistically correct — not just conceptually accurate.
- The fintech framing is specific enough to be credible to an interviewer at a trading firm.
- The post maps to exactly one or more interview questions identified in `interview-blog-plan.md` or `prep-plan-2.md`.
- Code examples compile and represent patterns a senior engineer would actually write in production.

### Series Completion Criteria

- All posts in the series are marked `written` in `interview-blog-plan.md`.
- The showcase article synthesises every keyword post in the series into a coherent architecture.
- The series covers all keywords listed for its block in `prep-plan-2.md`.

### Mock Interview Success Criteria

- The session index is up to date with all completed sessions.
- Coach feedback has been reviewed and any identified gaps have been added to `interview-blog-plan.md` as `planned` posts or kick-off prompt amendments.

### Repository-Level Success Criteria

- **35 of 35 posts written** (currently 31/35 as of April 2026).
- At least one showcase project per series, implemented and linked.
- Mock interview sessions completed for all 5 blocks identified in `prep-plan-2.md`.
- An agent with no prior context can pick up `interview-blog-plan.md`, read the next `planned` post's kick-off prompt, and produce a `written` post that meets the per-post criteria above without additional guidance.
