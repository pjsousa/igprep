# JIT Compilation: How the JVM Gets Faster Over Time — and When It Slows Down

When a Java process starts, it doesn't immediately run at full speed. For the first few seconds — sometimes minutes — your application runs in a degraded interpretive mode while the JVM learns which code paths are worth investing in. Understanding this warm-up behaviour is not academic: in a live price-streaming service, a JVM that hasn't warmed up yet will produce latency spikes that look exactly like an incident, even though nothing is broken.

This post explains how the JVM's Just-In-Time (JIT) compilation pipeline works, why it matters for production systems, and what a Tech Lead should know before signing off on a deployment.

## Interpreted Execution: The Starting Point

When the JVM first loads a class, it doesn't compile anything. Bytecode is executed by the interpreter — a software emulation layer that reads each bytecode instruction and performs the corresponding operation. The interpreter is fast enough for rarely-called code, but for a trading engine processing millions of price updates per second, running interpreted bytecode is orders of magnitude slower than running compiled native code.

The moment the JVM detects that a particular method is "hot" — called frequently, executing a tight loop, taking significant CPU time — it hands that method off to a JIT compiler.

## C1: The Client Compiler

The first compiler the JVM uses is called C1, also known as the client compiler. C1's job is to compile quickly with relatively conservative optimisations. It attaches to a running JVM and compiles methods on the fly, focusing on methods that are clearly hot but don't need aggressive optimisation yet.

C1 compiles at a lower optimisation level because it must be fast — you don't want compilation overhead to actually slow down your application during warm-up. The trade-off is that the resulting native code is good, but not great. C1 is effective at getting frequently-called code off the interpreter and onto bare metal, reducing the per-call overhead significantly.

## C2: The Server Compiler

The second compiler is C2, the server compiler. C2 takes longer to compile but applies aggressive optimisations: method inlining, loop unrolling, escape analysis, dead code elimination, and branch prediction optimisation. The native code C2 produces can be 10–50× faster than interpreted bytecode for the right workloads.

C2 is where the JVM really earns its reputation for performance. But this power comes with a cost: C2 compilation itself takes significant CPU time and memory. The JVM won't hand a method to C2 until it's confident that method is worth the investment — typically after a method has been called thousands of times.

## Tiered Compilation: The Full Pipeline

Modern JVMs run with **tiered compilation**, which combines C1 and C2 into a pipeline:

- **Tier 0**: Interpreted execution (no compilation yet).
- **Tier 1**: C1 compiled code, with no profiling data.
- **Tier 2**: C1 compiled code, with basic profiling (call counters, loop iteration counts).
- **Tier 3**: C1 compiled code, with full profiling.
- **Tier 4**: C2 compiled code — fully optimised.

When a method starts, it runs interpreted. After enough invocations, the JVM promotes it to Tier 1 C1. As profiling data accumulates, the method may be promoted to Tier 2 or Tier 3. Finally, C2 picks up the hottest methods at Tier 4.

This pipeline means the JVM gets quick wins early (C1 kicks in fast) while deferring the expensive C2 work until it has confidence in what's actually hot.

## On-Stack Replacement: No Stutter on Compilation

A critical problem would arise if the JVM could only compile a method at a method entry point: a long-running loop inside an interpreted method would never get compiled until the method returned. **On-Stack Replacement (OSR)** solves this. The JVM can trigger a compilation mid-loop and replace the running bytecode with compiled code without interrupting the thread — there is no visible stutter.

OSR is what allows a trading engine to have a hot loop compiled mid-execution as the warm-up kicks in. Without OSR, the first execution of a critical price-aggregation loop would always run interpreted, introducing predictable latency jitter at start-up.

## Deoptimisation: When the JVM Gets Smart and Then Rethinks

The most surprising — and most impactful — aspect of JIT compilation is deoptimisation. C2 makes optimistic assumptions about your code in order to apply aggressive optimisations. For example, C2 might assume that a particular call site is **monomorphic** — meaning it only ever dispatches to one concrete class — and use that assumption to inline the method. This is a massive performance win when true.

But if your code later hits a second class at that call site (a phenomenon called **megamorphic** dispatch), C2's assumption collapses. The compiled code is discarded, and execution falls back to interpreted mode or recompiles with a more conservative assumption.

This is a **deoptimisation**. In a trading engine, a deoptimisation typically occurs after:
- A code path is exercised that hasn't been seen before (new instrument type, new market data format).
- Class loading introduces a new subtype that wasn't present during compilation.
- A loop's branch probability changes significantly after warm-up.

A deoptimisation doesn't mean your application crashed — it means the JVM threw away compiled code and re-entered interpreted or lower-tier compiled mode. The latency spike comes from the compilation overhead of the fall-back plus the slower execution.

## Production Impact: Why Your JVM Isn't at Full Speed After Start-Up

In a financial trading service, the warm-up problem is real. After a deployment or failover, the JVM restarts into interpreted mode. C1 compiles gradually. C2 picks up the hottest methods over the next 30–60 seconds. During this window — sometimes called the **ramp-up period** — your latency profile is measurably worse.

This is not theoretical. Trading firms that care about p99 latency will often run warm-up traffic against a newly deployed JVM before routing live traffic to it. They measure latency with HDRHistogram and only promote the instance to production once the p99 falls below the SLA threshold.

## Mitigations: Warm-Up, CDS, and AOT

A Tech Lead responsible for a production Java service has several tools to manage JIT warm-up:

**Application warm-up traffic** means sending realistic synthetic load at start-up to trigger the compilation pipeline before the service receives real traffic. This can be a dedicated warm-up phase in your deployment script or a sidecar that fires pre-recorded price ticks at the process.

**JVM Class Data Sharing (CDS)** allows the JVM to cache compiled code and class metadata between runs. When you start a new JVM instance, it loads pre-compiled classes from an archive rather than compiling from scratch. Enable with `-XX:+UseCDS` and generate the archive with `-Xshare:dump`. This significantly reduces cold-start time.

**GraalVM Native Image and AOT** compilation compiles Java bytecode ahead-of-time to a native executable. There is no JIT at all — the compiled code is ready from the first instruction. This eliminates warm-up entirely, but sacrifices the adaptive compilation that makes JIT so powerful for long-running services. For a trading engine that runs continuously for days or weeks, JIT's ongoing re-optimisation is worth more than cold-start speed.

## Why This Matters in Production

For a Tech Lead running a JVM-based trading platform, understanding JIT compilation is not optional. Latency spikes after a deployment are frequently caused by JIT warm-up — not by a bug, not by GC, not by infrastructure. The JVM is doing exactly what it was designed to do: start conservative and become faster as it learns your workload.

If you're deploying a new price-aggregation service and p99 latency is 40ms for the first 60 seconds then drops to 2ms, the culprit is almost certainly the JIT ramp-up. The fix isn't to tune the GC — it's to implement warm-up traffic or pre-compile with AOT.

An interviewer probing your depth will ask: "What happens when a JIT-compiled method is deoptimised at runtime?" The answer they're looking for is that execution falls back to interpreted mode, the method is re-profiled, and a less optimised version is recompiled — with a measurable latency impact that a Tech Lead must plan for.

JIT compilation is the JVM's most powerful performance feature and its most underestimated source of latency jitter. Know it, measure it, and design your deployment pipeline around it.