
---

## Block 1: Mechanical Sympathy & High-Performance Java
**Goal:** Prove you can write code that doesn't just "work" but thrives under the pressure of millions of price updates.

* **The Concept:** At IG's scale, standard `synchronized` blocks create bottlenecks. You need to understand how to share data between threads without "locking" the CPU.
* **Keyword Bank:** `CAS (Compare and Swap)`, `AtomicReference`, `False Sharing`, `LMAX Disruptor`, `Memory Barriers`, `Thread Affinitiy`.
* **Actionable Task:** Research the **LMAX Disruptor architecture**. It is the industry standard for financial exchange "matching engines." Focus on how it uses a **Ring Buffer** to avoid Garbage Collection.
* **Interactive Interview Prompt:** > "Act as **The Java Purist**. I am a Lead Engineer who understands high-level threading, but I need you to grill me on **Lock-Free concurrency**. Ask me to explain the difference between `volatile` and `AtomicInteger` and why I would choose one over the other in a price-streaming ticker."



---

## Block 2: JVM Internals & Latency Control
**Goal:** Understand why your application "pauses" and how to stop it.

* **The Concept:** In fintech, a 200ms "Stop-the-World" Garbage Collection pause can result in thousands of dollars in lost trades. You need to talk about the JVM as a living organism.
* **Keyword Bank:** `ZGC (Zero Garbage Collection)`, `Shenandoah GC`, `Survivor Spaces`, `TLAB (Thread Local Allocation Buffers)`, `JIT Compilation (C1 vs C2)`.
* **Actionable Task:** Compare **G1GC** vs. **ZGC**. ZGC is the modern "Low Latency" king—understand how it keeps pauses under 1ms regardless of heap size.
* **Interactive Interview Prompt:** > "Act as **The Skeptical Architect**. You noticed that our price stream has intermittent 'jitter' (random latency spikes). Interview me on how I would use **Java Mission Control (JMC)** or **GC Logs** to find the culprit. Challenge me on whether the issue is 'Object Allocation' or 'Infrastructure'."

---

## Block 3: Distributed Reliability (Kafka & Consistency)
**Goal:** Bridge your "Worker Node" experience with IG's streaming requirements 58].

* **The Concept:** You’ve built distributed pipelines before. Now, you must ensure that if a node crashes, no price is lost and no price is sent twice.
* **Keyword Bank:** `Exactly-once Semantics (EOS)`, `Kafka Transactional Producer`, `Consumer Group Rebalancing`, `Idempotency`, `Compacted Topics`.
* **Actionable Task:** Read up on **Kafka Idempotent Producers**. How does Kafka ensure a retry doesn't result in a duplicate message?
* **Interactive Interview Prompt:** > "Act as **The Streaming Expert**. We are building a critical price-delivery system. Ask me 'What happens if...?' scenarios. Specifically, what happens if the **Lead Kafka Broker** fails during a transaction, and how do we ensure the client doesn't see a 'flicker' of old data?"

---

## Block 4: Behavioral Leadership (The "Fintech" Mindset)
**Goal:** Translate your "Lead Developer" wins into the specific values IG rewards: "Think Big" and "Raise the Bar" 29, 39].

* **The Concept:** They want a Senior who acts like a Lead but still loves to code. You need to show you can mentor others while being "impatient for change" 58].
* **Keyword Bank:** `S.T.A.R. Method`, `Technical Debt Management`, `Post-mortems`, `Mentoring Junior Devs`, `Stakeholder Management`.
* **Actionable Task:** Prepare your story about the **"Path to Clean Code"** initiative you led 52]. Frame it as: "I saw a quality gap, I defined the new standard, and I coached the team to reach it."
* **Interactive Interview Prompt:** > "Act as **The IG Hiring Committee**. You care about 'culture fit' and 'velocity.' Ask me behavioral questions focused on **Conflict Resolution**. Specifically, ask about a time I had to push a technical improvement (like TDD) to a team that just wanted to ship fast."

---

### Final Preparation Tip
Start with **Block 4** tonight. It uses your existing experience 29]. Use the momentum from those "wins" to tackle the heavy Java theory in **Block 1** tomorrow morning when your brain is fresh.

Which of these blocks would you like to run a "mock session" for first?
