# Thread Affinity: Pinning Java Threads to CPU Cores for Predictable Latency

In high-frequency trading systems, a context switch is not a minor inconvenience — it is a latency event. When the OS scheduler preempts a price-ingestion thread running on core 3 and migrates it to core 7, the thread cold-starts on a new socket with a cold L2 cache, and every subsequent price tick will suffer an unpredictable delay before the cache warms up again. Thread affinity gives you control over this layer of the stack.

## OS Scheduling and the Cost of Migration

The Linux CFS (Completely Fair Scheduler) makes no promises about which physical core a thread runs on across time slices. It optimises for fairness and throughput across the entire system — not for the cache behaviour of your price-streaming thread. The result is involuntary context switches: the scheduler decides your thread's time slice is up, or a higher-priority RT task preempts it.

The costs are concrete:

- **L1/L2 cache flush**: the thread's hot data evicts from the core's private caches when it leaves.
- **TLB miss**: the new core must reload the thread's page tables.
- **Memory latency**: if the thread was running on a core local to a NUMA node, migration to another socket crosses a QPI or Infinity Fabric link.

For a thread processing one million price updates per second, each involuntary migration can add hundreds of microseconds to a single tick — enough to breach a p99 SLA.

## What Thread Affinity Is

Thread affinity (or "CPU pinning") is the ability to tell the OS: *this thread, or this set of threads, may only run on this specific set of CPU cores*. The Linux system call for this is `sched_setaffinity`. When a thread is pinned, the scheduler respects those boundaries. The thread is still preemptible by higher-priority real-time tasks, but it will not migrate between cores during normal scheduling decisions.

In Java, you use the **Java Thread Affinity** library (part of OpenHFT, maintained by Peter Lawrey) to set affinity programmatically:

```java
import net.openhft.affinity.AffinityLock;
import net.openhft.affinity.CpuLayout;

public class AffinityExample {
    public static void main(String[] args) {
        CpuLayout layout = new CpuLayout(); // discovers topology
        System.out.println("CPU layout: " + layout);

        // Pin the current thread to core 2
        AffinityLock lock = AffinityLock.acquireLock(2);
        try {
            System.out.println("Running on core: " + lock.cpuId());
            // Critical path: price ingestion here
        } finally {
            lock.release();
        }

        // Or: acquire a specific core and release it later
        AffinityLock another = AffinityLock.acquireLock(3);
        try {
            // this thread is now pinned to core 3
        } finally {
            another.release();
        }
    }
}
```

At the OS level, `taskset` achieves the same thing without code changes:

```bash
taskset -c 2,3 java -cp app.jar com.example.PriceIngestionMain
```

This pins the JVM to cores 2 and 3 only. The OS will not migrate threads to cores outside that set.

## NUMA Topology: Respecting Socket Boundaries

Modern servers are NUMA (Non-Uniform Memory Access) machines. A dual-socket server has two processor sockets, each with its own DRAM. Accessing memory local to your socket is roughly 80–100ns faster than accessing memory on the remote socket.

A price-streaming system typically runs on a specific NUMA node when pinned. The `taskset` example above places the JVM on cores 2 and 3 — but which socket are those cores on? The affinity library can answer this:

```java
AffinityLock lock = AffinityLock.acquireLock();
try {
    int socketId = lock.numaNode();
    System.out.println("Pinned to NUMA node: " + socketId);
    System.out.println("Core: " + lock.cpuId());
} finally {
    lock.release();
}
```

A common anti-pattern is pinning threads to cores that span two NUMA nodes. If your ingestion thread runs on cores 0 and 4 (one on each socket), half your memory accesses cross the inter-socket link. Pin the entire pipeline — ingestion, encoding, and dispatch — to cores on a single NUMA node, and ensure the network card IRQ affinity is also pinned to that same node to avoid remote-memory reads on packet receipt.

## Practical Use Cases in a Trading System

**Network I/O thread**: Dedicated to receiving market data from the wire. Pinned to a core near the NIC IRQ handler (set via `irqbalance` or manual `set_irq_affinity`). This thread does almost nothing except receive bytes and hand them off — its working set is tiny, and pinning it prevents cache pollution from unrelated processes.

**Matching engine thread**: Runs the core order-book logic. In a pure Java matching engine, this thread is entirely CPU-bound on a small, hot data structure. Pinning it to an isolated core — possibly with `isolcpus=2,3` in the kernel boot line — ensures no other process preempts it.

**Disruptor publisher thread**: The thread that calls `ringBuffer.publish()` must have predictable latency to the ring buffer's write pointer. Pinning it alongside the consumer on adjacent cores (within the same NUMA node) keeps the lock-free handover within a single cache coherent domain.

## When Not to Use Affinity

Thread affinity is a precision tool, not a general-purpose optimisation:

- **General-purpose thread pools** (e.g. a REST API server handling business logic): pinning causes more harm than good. The OS scheduler is better at load-balancing across many threads on many cores. Forcing affinity here creates per-core imbalance.

- **Threads that block on I/O**: if a thread spends 95% of its time waiting on a socket, pinning it wastes a core that other threads could productively use.

- **Environment where you don't control the machine**: in containerised environments (Kubernetes), CPU pinning requires `cpuset` cgroups and careful pod resource requests. Misconfigured affinity in a container can cause unexpected throttling.

## Observability: Detecting Migration Events

You can detect when a thread has migrated using Linux `perf` or by instrumenting with `Affinity.getCpuId()` at known points:

```java
int initialCore = Affinity.getCpuId();
processNextTick();
int finalCore = Affinity.getCpuId();
if (initialCore != finalCore) {
    System.out.println("WARNING: thread migrated from core " + initialCore + " to " + finalCore);
}
```

In production, use `perf sched` to capture scheduling events across the system:

```bash
perf sched record -a -o sched.perf &
# run load
perf sched latency --input sched.perf
```

High "migration" counts or high "scheduling latency" values in the output are signals that either your affinity configuration is wrong or the system is overloaded.

## Why This Matters in Production

Thread affinity sits at the intersection of OS scheduling and Java runtime — a layer that most application developers never touch but that a Tech Lead in a trading firm must understand intimately. When a latency spike is traced to cache-line bouncing across sockets, or to TLB misses after an involuntary migration, the solution is not more tuning at the JVM level — it is pinning.

The ability to diagnose a scheduling-induced latency event, propose a pinning strategy, and articulate the NUMA implications is exactly the kind of "mechanical sympathy" that separates a Tech Lead who knows the theory from one who can fix the production system.
