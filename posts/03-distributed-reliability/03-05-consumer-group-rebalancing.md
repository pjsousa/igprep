# Consumer Group Rebalancing: Why Your Kafka Consumer Stops and How to Minimise It

In a production price-delivery pipeline, availability is not a nice-to-have — it is the product. When a consumer instance crashes during a live trading session, the system must redistribute its partitions to the remaining consumers without creating a prolonged delivery gap. Kafka's consumer group rebalancing protocol is what makes this possible. It is also one of the most commonly misconfigured and misunderstood mechanisms in a Kafka deployment. Get it wrong and a single crashed instance can cause a 30-second delivery blackout at the worst possible moment.

This is Post 5 in the series. Previous posts covered idempotency, transactional producers, exactly-once semantics, and compacted topics. If you have been following the series, the operating assumption is that we are building a price pipeline where no message is lost and none is duplicated — even under failure. Consumer rebalancing is the mechanism that determines what happens to in-flight work when a consumer disappears.

---

## Consumer Group Membership: Who Owns What

Kafka's consumer group protocol is the mechanism by which partitions are distributed across consumer instances. Each consumer in a group is assigned a subset of the topic's partitions. When a consumer joins, leaves, or crashes, the group coordinator — a broker elected as the group leader — triggers a **rebalance**: a redistribution of partition ownership to the surviving consumers.

The partition assignment strategy determines *how* partitions are distributed. The default `RangeAssignor` assigns contiguous partition ranges to each consumer. The `RoundRobinAssignor` distributes partitions more evenly across consumers. Kafka 2.4+ also offers the `CooperativeStickyAssignor`, which we will come to shortly — it changes the behaviour of rebalancing in a critical way.

When a rebalance occurs, all consumers in the group must stop consuming, surrender their current partition assignments, and wait for the coordinator to issue new ones. During this stop-the-world pause, no messages are delivered. For a price stream operating at millisecond latency requirements, even a 5-second gap is a significant incident.

---

## What Triggers a Rebalance

A rebalance is initiated whenever the group coordinator detects a change in membership. The triggering events are:

**Consumer joins:** A new instance starts and calls `poll()` for the first time. The coordinator adds it to the group and triggers a rebalance to give it a partition assignment.

**Consumer crashes:** This is the scenario we care most about. If a consumer stops sending heartbeats for longer than `session.timeout.ms`, the coordinator assumes it is dead and removes it from the group. A rebalance redistributes its partitions.

**Consumer fails to poll in time:** If a consumer takes longer than `max.poll.interval.ms` between successive `poll()` calls, the coordinator considers it stuck and removes it. This can happen if a consumer handler blocks on a downstream call — for example, if a downstream price cache is unresponsive and the handler thread is waiting on a network call.

**Partition count changes:** If an administrator adds partitions to a topic, the coordinator triggers a rebalance to give consumers additional partitions.

**Consumer group leader fails:** In some assignment strategies, the group leader (one of the consumers, not a broker) is responsible for computing partition assignments. If it fails, a rebalance is needed.

---

## The Classic "Eager" Rebalance: Stop the World

The original rebalance protocol is **eager**: when a rebalance is triggered, every consumer in the group immediately stops consuming, revokes all its partition assignments, and waits for the coordinator to issue new ones. This is a full "stop the world" event.

The sequence for a 3-partition, 3-consumer group under the eager protocol is:

1. Consumer C3 crashes. It stops sending heartbeats.
2. After `session.timeout.ms` (e.g., 10 seconds), the coordinator declares C3 dead.
3. The coordinator triggers a rebalance. It sends `JoinGroup` requests to C1 and C2, which both leave the group and stop consuming.
4. C1 and C2 wait. No messages are consumed during this window.
5. The coordinator collects membership info and computes a new partition assignment.
6. The coordinator sends `SyncGroup` to C1 and C2 with their new assignments.
7. C1 and C2 resume consuming.

In a price-streaming context, the pause in step 4 is the delivery gap. For a topic with 10 partitions and 3 consumers, one consumer dying means 10/3 ≈ 3–4 partitions being redistributed. If `session.timeout.ms` is 10 seconds and the rebalance itself takes 2–3 seconds, you are looking at a 12–13 second delivery outage — entirely unacceptable for a live trading session.

The root cause of eager rebalance's latency is the revocation step: all consumers must surrender their partitions before any new assignment can begin.

---

## Incremental Cooperative Rebalancing: Only Move What Changes

Kafka 2.4 introduced a fundamentally different approach: **incremental cooperative rebalancing**. Instead of revoking all partitions at once and waiting for new assignments, the coordinator revokes only the partitions that need to move. Survivors keep consuming everything else.

The `CooperativeStickyAssignor` implements this. Under cooperative rebalancing:

1. Consumer C3 crashes. After `session.timeout.ms`, the coordinator declares it dead.
2. The coordinator sends a `JoinGroup` to C1 and C2 with a special instruction: revoke only the partitions previously owned by C3.
3. C1 and C2 revoke only C3's partitions — 3–4 partitions each — and continue consuming from all their other partitions.
4. C1 picks up some of C3's partitions; C2 picks up the rest. Both continue consuming without a full stop.

The result is a rebalance that causes a brief pause for only the partitions being moved, not the entire consumer group. In practice, this reduces rebalance-induced delivery gaps from tens of seconds to hundreds of milliseconds — a night-and-day difference for latency-sensitive trading workloads.

The trade-off is complexity: incremental rebalancing requires consumers to handle the case where a partition is revoked while they are mid-way through processing it. Your consumer must be idempotent and must commit offsets only after successfully processing messages. Fortunately, this is exactly the guarantee that EOS (covered in Post 3.3) provides.

---

## Tuning the Failure Detection Parameters

The three key parameters governing rebalance behaviour are:

**`session.timeout.ms`:** The time a coordinator waits without a heartbeat before declaring a consumer dead. Default is 10 seconds. Lower values detect failures faster but increase the risk of false positives — a consumer that is慢 but alive (e.g., undergoing a long GC pause) will be incorrectly removed. For low-latency trading systems, 5–6 seconds is a reasonable minimum.

**`heartbeat.interval.ms`:** How often a consumer sends a heartbeat to the coordinator. Must be less than `session.timeout.ms` — typically 1/3 of `session.timeout.ms`. A higher heartbeat rate detects failures faster but adds network overhead.

**`max.poll.interval.ms`:** The maximum time between `poll()` calls. If a consumer takes longer than this to process a batch (e.g., due to a downstream timeout), it is removed from the group. Default is 5 minutes. For a price-streaming handler that calls a downstream HTTP API, set this to comfortably exceed your expected processing time for a batch — but not so high that a genuine crash is undetected for too long.

**`max.poll.records`:** Controls the batch size returned by each `poll()`. Smaller batches reduce the likelihood of hitting `max.poll.interval.ms` but increase overhead.

A typical configuration for a trading system consumer:

```java
Properties props = new Properties();
props.setProperty("bootstrap.servers", "kafka-broker-1:9092,kafka-broker-2:9092");
props.setProperty("group.id", "price-cache-consumers");
props.setProperty("session.timeout.ms", "6000");
props.setProperty("heartbeat.interval.ms", "2000");
props.setProperty("max.poll.interval.ms", "30000");
props.setProperty("max.poll.records", "100");
props.setProperty("partition.assignment.strategy", "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
props.setProperty("enable.auto.commit", "false");
props.setProperty("isolation.level", "read_committed");
```

Note `read_committed` — covered in Post 3.2 on transactional producers. A consumer in a trading pipeline must use `read_committed` to avoid seeing rolled-back transactions that would otherwise create phantom price updates.

---

## The Rebalance During Peak Market Hours: A Concrete Scenario

Consider a risk engine consuming from a `prices` topic with 12 partitions, running 3 consumer instances on 3 separate VMs. During a volatile market open, one VM suffers a network partition and its consumer stops sending heartbeats. The coordinator waits 6 seconds (`session.timeout.ms`), then declares it dead and triggers a rebalance.

Under the eager protocol, the two surviving consumers stop consuming for 2–3 seconds while partitions are revoked and reassigned. Downstream clients receive no price updates for that window. A risk engine that relies on real-time prices to compute Greeks may breach a position limit during the gap — a serious operational incident.

Under the cooperative sticky assignor, the surviving consumers lose only the 4 partitions previously owned by the dead instance. They continue processing their other 8 partitions without interruption. The gap affects only those 4 partitions, and for those, the pause is measured in milliseconds as ownership transfers incrementally.

The lesson: for any production Kafka consumer handling price data, `CooperativeStickyAssignor` is not optional. It is the minimum viable configuration for a high-availability trading system.

---

## When Rebalancing Goes Wrong: Zombie Consumers and Fencing

There is a subtler failure mode that cooperative rebalancing does not fully solve: the **zombie consumer**. A consumer that is declared dead but is still alive — network partition, long GC pause — may continue processing with its old partition assignment after a new consumer has taken over those partitions. Two consumers now own the same partition. One is a zombie.

Kafka's fencing mechanism addresses this. Each transactional producer is assigned a `transactional.id` that persists across restarts. When a new consumer instance with the same `transactional.id` joins and triggers a rebalance, the coordinator sends a `FENCED` error to the old producer instance. The fenced producer can no longer write — its `send()` calls throw `ProducerFencedException`. This prevents the zombie from publishing duplicate or conflicting price updates.

On the consumer side, a similar fencing protocol exists for consumers within a consumer group, though it is less well-documented. The key defence is idempotency: if your consumer processes messages in an idempotent way (or uses transactional state stores as described in Post 3.3 on EOS), a zombie delivering a duplicate message has no lasting effect.

---

## Why This Matters in Production

Consumer group rebalancing is the mechanism that makes Kafka's partitioned consumer model resilient to instance failures. Without it, a single crashed consumer would leave its partitions permanently unconsumed. With it, the system self-heals — but the healing process can itself cause a significant delivery gap if the protocol is misconfigured.

For a Tech Lead operating a price-delivery pipeline, the implications are concrete:

**Use `CooperativeStickyAssignor` in production.** The default eager assignor will cause full stop-the-world pauses on every rebalance. Cooperative rebalancing limits the blast radius to only the partitions being moved.

**Set `session.timeout.ms` low enough to detect failures quickly, but not so low that false positives cause spurious rebalances.** For a trading system, 5–6 seconds is a reasonable starting point.

**Make your consumer idempotent.** Whether through transactional state stores or application-level deduplication, a consumer that can safely process the same message twice is immune to the duplicate-delivery risk that a rebalanced consumer introduces.

**Monitor rebalance frequency.** A healthy consumer group should rebalance rarely — only on genuine membership changes. If you see frequent rebalances, your `session.timeout.ms` may be too low, your `max.poll.interval.ms` may be too tight, or a downstream dependency may be causing processing delays that look like crashes.

Consumer rebalancing is the self-healing mechanism that makes Kafka's availability guarantees possible. Configuring it correctly is one of the first things a Tech Lead should validate before a trading system goes live.