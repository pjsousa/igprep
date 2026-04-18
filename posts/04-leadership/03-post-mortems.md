# Post-mortems: Turning Production Incidents Into Engineering Assets

## Introduction

A production incident is not just a problem to solve and forget. In the best engineering teams, it is the start of a deliberate learning process — a ritual that, when done well, strengthens the system, the team, and the culture simultaneously.

That ritual is the post-mortem. For a Tech Lead in a financial trading environment, running effective post-mortems is one of the highest-leverage activities available. The Latency Investigation from Series 2 and the EOS pipeline failures from Series 3 are exactly the kind of incidents that deserve one. Done right, a post-mortem produces action items that prevent recurrences — and signals to your team that mistakes are treated as systemic failures, never personal ones.

This post explains how to run a blameless post-mortem, how to structure it for maximum learning, and how to frame the experience as a compelling leadership story in an interview.

## Why "Blameless" Is the Critical Prefix

Blameless does not mean without accountability. It means the investigation is focused on *systems and processes*, not on assigning personal fault. The distinction matters enormously.

When blame enters the room, people stop talking honestly. Engineers bury the contributing factors that actually caused the incident because they fear repercussions. The result is a post-mortem that finds the convenient scapegoat — the intern, the on-call engineer who "should have caught it" — while the real systemic issue goes unaddressed and the incident recurs.

Blameless post-mortems surface the truth because people are safe to be candid. In a high-stakes trading environment, where a single latency spike can cost the firm money and a data loss incident can breach regulatory requirements, you *need* the honest analysis. Psychological safety is not a soft HR concept here — it is a reliability instrument.

## The Post-Mortem Structure

A good post-mortem is not a free-form discussion. It has a defined structure that keeps the investigation rigorous and ensures outcomes are concrete.

### 1. Incident Summary

Write a concise paragraph that answers: what happened, when it happened, what the impact was (in concrete terms — latency spike duration, data loss quantity, affected clients), and when it was resolved. This is the overview that anyone reading the document years later will read first.

### 2. Timeline

Walk the incident minute by minute. This is where precision matters. Include:

- When the issue first manifested
- When it was detected (and by whom or what — monitoring alert, customer complaint, on-call engineer)
- The key decisions and actions taken during the incident
- When it was resolved

The timeline often reveals the gap between when the incident started and when it was detected — a gap that is almost always a monitoring problem, not a people problem.

### 3. Contributing Factors

This is the analytical heart of the post-mortem. Resist the temptation to name a single "root cause." Real incidents have multiple contributing factors, and naming only one blinds you to the others.

Contributing factors for a trading system incident might include:

- The monitoring alert threshold was set too wide, delaying detection by 11 minutes
- The G1GC evacuation pause exceeded the alert threshold under the given allocation rate
- The deployment last week had changed the object allocation profile of the price handler without a corresponding review of GC settings
- There was no runbook for this specific failure mode

Notice that all of these are systemic — none of them blame an individual engineer. Each one points to a process or configuration decision that can be improved.

### 4. Action Items

Every contributing factor should map to at least one action item. Vague action items are the most common post-mortem failure mode. A good action item has:

- A specific owner (a named individual, not a team)
- A deadline
- A verifiable outcome

**Poor:** "Improve monitoring." **Good:** "Reduce the GC pause alert threshold from 500ms to 50ms for the price-ingest service; owner: SRE team; deadline: end of sprint; verified by: updated Grafana dashboard."

### 5. What Went Well

Often omitted, this section is genuinely valuable. It acknowledges the parts of the incident response that worked — fast detection by the monitoring system, effective coordination, a clean rollback. It prevents the post-mortem from feeling like a witch hunt and reinforces the behaviours you want to see repeated.

## The Tech Lead's Facilitation Role

As a Tech Lead, your job in a post-mortem is to be the facilitator and the guardian of the blameless culture. Concretely, this means:

**You control the narrative, not the blame.** When the conversation drifts toward "he should have caught that," you redirect: "what in our system allowed that to happen undetected for that long?"

**You ensure action items are followed up.** A post-mortem with untracked action items is a waste of everyone's time. You are responsible for bringing them back in the next team sync and closing the loop. If an action item slips, that itself is notable — it may reveal something about your team's capacity or priorities.

**You publish findings widely.** Post-mortems should be company-readable documents. In a trading firm, this builds institutional knowledge — the next team that faces a similar issue should be able to find the document and learn from it.

## An Example: The GC Pause Incident

Consider a fictional but realistic scenario drawn from a price-streaming service:

> **Incident:** A price-distribution service experienced a 34-second delivery outage during peak market hours. Downstream risk engines stopped receiving price updates. The outage lasted from 10:04 to 10:38 UTC.
>
> **Timeline:** At 10:01, a deployment of the price-normaliser service completed. At 10:04, the G1GC Mixed GC pause exceeded 500ms. Monitoring did not alert until 10:31 (the threshold was too coarse). At 10:34, the on-call engineer identified GC as the cause and initiated a JVM restart. Service recovered at 10:38.
>
> **Contributing factors:** G1GC Mixed GC pause exceeded threshold due to elevated allocation rate after the deployment; monitoring alert threshold was set to 1000ms; no GC tuning review was part of the deployment checklist; the price-normaliser's new buffer池 implementation created more short-lived objects per tick.
>
> **Action items:** Reduce G1GC alert threshold to 50ms for all price services (owner: SRE); add GC review gate to deployment checklist (owner: Tech Lead); investigate ZGC migration for price-ingest tier (owner: Platform team, Q2).

Notice what this post-mortem *does not* say: it does not blame the engineer who deployed, nor the on-call engineer who took 27 minutes to identify the cause. It identifies the systemic gaps and fixes them.

## Framing This in an Interview

A post-mortem story is one of the most credible signals of production leadership maturity you can offer in an interview. The key is to frame it with S.T.A.R.

**Situation:** Our price-streaming service had a 34-second outage during peak market hours. G1GC pauses were the cause, but the real problem was that our monitoring did not catch it for 27 minutes.

**Task:** As Tech Lead, I needed to ensure the incident was analysed honestly, the systemic gaps were identified, and the action items were actually delivered.

**Action:** I ran a blameless post-mortem within 48 hours. I facilitated the session, kept the analysis systemic, and personally owned the cross-team action items. I followed up on them every week until they were closed.

**Result:** We migrated the price-ingest tier to ZGC within six weeks. Monitoring thresholds were tightened across all services. In the following quarter, our p99 latency improved by 40%, and we had zero incidents caused by undetected GC pauses.

That answer demonstrates systems thinking, blameless leadership, delivery ownership, and measurable impact — exactly what a FTSE 100 interviewer is listening for.

## The Compounding Value of Post-Mortem Culture

The true value of post-mortems is not any single action item. It is the compounding effect of running them consistently over time.

A team with a strong post-mortem culture gets measurably better at detecting failures earlier, at designing systems that fail more gracefully, and at trusting each other enough to be honest about what went wrong. Each post-mortem feeds the next — the action items from one incident often prevent the next one, or reduce its severity.

As a Tech Lead, your job is to make post-mortems a natural and expected part of how the team operates — not a painful obligation after a bad week, but a disciplined practice that makes the team measurably more reliable every quarter.
