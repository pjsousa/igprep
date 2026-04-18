# Raising the Bar Without Breaking the Team: A Tech Lead's Year of Change

*Series: The Fintech Tech Lead: Leadership Under Pressure — Showcase Article*

---

## Introduction: The Starting Line

Twelve months ago I became Tech Lead of a team of seven engineers delivering a pricing and risk service for a derivatives desk. The service worked. Orders were being placed. The business was profitable. On the surface, nothing was broken.

Under the surface, things were different. Test coverage sat at 34%. There had been three production incidents in the preceding six months, each followed by a brief conversation in Slack rather than a structured review. Two of the seven engineers had been with the company less than six months; their onboarding had been "read the code and ask questions." Code reviews, when they happened, focused on syntax rather than design. A senior developer on the team had told me privately that the codebase "felt like it was written by people who were afraid to be judged."

I was not brought in to fire anyone. I was brought in to raise the bar — and to do it without breaking the team that had to live with the changes every day.

This is the story of that year. It is not a story about a hero Tech Lead who swooped in and fixed everything. It is a story about how structural changes, patient mentoring, and deliberate culture-building transformed a team that was capable of more than it realised. It is also a preparation artifact: every scenario described here is a S.T.A.R.-structured story I can tell in an interview, with the specific actions I took and the measurable outcomes that followed.

---

## Month 1: The Situation — What I Found and How I Framed It

Before I made any changes, I spent the first three weeks listening. I read the post-mortems (all three of them). I attended stand-ups, sprint planning, and a code review session. I paired with each engineer on their area of the codebase. I asked the product manager what they felt was slowing the team down.

The picture that emerged was not a team with low standards. It was a team that had never been shown what high standards looked like in a concrete, achievable way. The existing code review process was informal to the point of being fictional: a developer would post a PR, and if no one commented within a day, it merged. There was no agreed definition of what "ready to review" meant. The test coverage number existed in a Jira ticket from 2021 but had never been translated into a sprint goal.

I also found genuine strengths. The two junior engineers were curious and asked good questions. The senior developer who had told me the codebase felt shameful was actually deeply invested in quality — they had just never had permission to enforce it. The team shipped fast when they weren't blocked, which meant velocity was not the underlying problem.

**Framing for the interview:** The S.T.A.R. for this Situation was not hard to construct. The Task was clear: I needed to raise the bar on a functioning team without creating a morale crisis or a delivery slowdown. But I was careful in how I framed it. I did not walk in saying "your code is bad." I walked in saying "here is what good looks like, and here is how we get there together."

---

## Month 2–3: Selling the Technical Debt Initiative

My first concrete move was to build the case for a technical debt initiative. I was not naive enough to announce a refactoring sprint — that is the fastest way to get a product manager to push back and the fastest way to lose the team's trust.

Instead, I did three things in parallel.

**First, I quantified the debt.** I ran a SonarQube analysis and extracted the cyclomatic complexity distribution, the duplicate code percentage, and the test coverage by package. What I found: 23 methods with a cyclomatic complexity above 15, concentrated in the pricing engine and the order-routing layer. These were the same areas that had produced the three production incidents. The correlation was not coincidence.

**Second, I translated the debt into business terms.** I wrote a short paper — two pages — titled "The Cost of Our Code Quality Gap." I framed the three incidents in terms of mean time to recovery, the number of customer-impacting minutes, and the estimated revenue risk from incorrect pricing under load. I did not use the words "technical debt" in the title. The audience was the product manager and the engineering manager, and they needed to see this as a business risk, not a technical purity argument.

**Third, I proposed a bounded, incremental approach.** Rather than asking for a dedicated sprint, I proposed the "20% rule": 20% of each sprint's capacity — approximately one developer-day per sprint — would be allocated to debt reduction. I presented three options: the 20% incremental approach, a dedicated two-week debt sprint, and doing nothing. I recommended the 20% approach and explained why: it kept the debt backlog visible in every sprint without halting feature delivery.

The product manager agreed to a three-sprint trial. I presented this to the team not as a mandate but as an experiment: "Let's try this for six weeks and measure whether it changes anything."

**Framing for the interview:** The S.T.A.R. for this moment was about stakeholder management — specifically the ability to sell a quality initiative to a product manager who had delivery pressure. The Action was: I quantified the debt, translated it to business risk, and proposed a bounded incremental solution rather than a disruptive sprint. The Result (three months later): test coverage rose from 34% to 51%, and the number of medium-complexity methods in the pricing engine dropped by 40%.

---

## Month 4: The First Post-Mortem — A GC Pause and a Culture Shift

In month four, we had a production incident. A GC pause of 1.2 seconds — caused by a humongous allocation in the pricing engine that none of us had noticed — caused a brief price delivery outage. The incident lasted four minutes and impacted no trades, but it was a genuine close call.

This was the moment I had been preparing for. I scheduled a post-mortem within 48 hours.

I was explicit with the team about what a blameless post-mortem meant. I said: "The goal of this session is to understand the systemic factors that allowed this to happen, not to find someone to blame. If we find a person to blame, we have failed." I wrote that on the whiteboard at the start of the session.

The post-mortem itself followed a structured format: incident summary, a timeline built from Kibana logs and GC logs, contributing factors (I deliberately avoided "root cause" — there are always multiple factors), and action items with owners and deadlines.

Three contributing factors emerged from the discussion:

1. No one had reviewed the GC logs before the incident. There was no alerting on allocation rate.
2. The humongous allocation was introduced in a merge three months prior, during a sprint that had been under delivery pressure. The code review had not caught it.
3. There was no written runbook for the pricing engine's memory behaviour under load.

The action items were specific: introduce GC log monitoring with PagerDuty alerting (owner: me), add a JMH benchmark for the pricing engine's allocation hot path (owner: the senior developer who had originally flagged the codebase quality), and write the runbook (owner: the junior engineer who had joined six months prior, which gave them a structured way to learn the system).

The junior engineer who wrote the runbook told me afterward that it was the first time someone had asked her to own a piece of documentation for the whole team. She took it seriously. The runbook was thorough, and the senior developer reviewed it and added two pages of context. That collaboration — assigning high-status work to a junior in a way that respected their capability — was the first mentoring signal I had deliberately set.

**Framing for the interview:** The post-mortem was a S.T.A.R. in itself. Situation: a 1.2-second GC pause caused a price delivery outage. Task: run a blameless review that produced systemic improvements, not finger-pointing. Action: I facilitated the post-mortem using a structured format, deliberately framed the session as systemic rather than personal, and assigned action items with owners and deadlines. Result: within six weeks, GC log alerting was live, the allocation benchmark identified two further hot spots, and the team's approach to reviewing memory behaviour in code reviews changed measurably.

The GC incident connected to Series 2 of this blog series. After the post-mortem, I spent a afternoon walking the team through the JVM internals posts — Survivor Spaces, TLABs, and ZGC — as a lunchtime reading group. The connection between humongous allocations and GC pauses became part of our code review checklist. This is an example of how a production incident directly fed into technical education.

---

## Month 5–6: The Mentoring Inflection Point

By month five, the 20% debt initiative was showing results. Test coverage was at 48% and climbing. The team had started flagging complexity in PRs without being asked. Two of the seven engineers had started submitting PRs with benchmark results attached for performance-sensitive changes.

This was the right moment to invest more deliberately in mentoring. I identified two engineers who were ready for more responsibility than they currently had: the junior engineer who had written the runbook, and a mid-level engineer who had been writing similar features repeatedly but had never been asked to design a new one.

For the junior engineer, I paired with her on the pricing engine's order book update path — a concurrent Java structure that had been flagged in code review several times but never refactored. I drove the first two sessions, explaining the concurrent data structure and why the existing implementation had a race condition. Then I gave her the next refactoring task with a safety net: she would own it, but I would review every PR before merge, and we would pair on any test failures.

She completed the refactoring in three weeks. The race condition was eliminated. In the PR review, she explained the concurrent design choices she had made — not because I asked her to, but because she hadinternalised the vocabulary. Six months later, she was the team expert on concurrent Java. She now reviews all PRs that touch the pricing engine without being asked.

For the mid-level engineer, I ran a "design review" session — not a formal architecture review, but a 45-minute whiteboard session where we walked through a proposed feature design together before they wrote any code. The pattern I used was deliberate: I asked questions, not answers. "What happens if the external price feed is slow?" "What is the failure mode if the cache update fails?" By the third session, they were asking those questions themselves before our review.

**Framing for the interview:** Mentoring is a S.T.A.R. story when it has a measurable outcome. The mentoring S.T.A.R. I use in interviews focuses on the junior engineer: Situation — a junior engineer who had never owned a concurrent feature was assigned the pricing engine refactoring. Task — grow their ownership and technical depth without creating a delivery risk. Action — I paired on the first sessions, then gave progressive independence with a safety net review. Result — she completed the refactoring, became the team's go-to expert on concurrent Java, and now reviews all pricing engine PRs independently.

---

## Month 7–9: The Velocity versus Quality Tension Peaks

The six-week trial of the 20% debt rule was ending. The product manager wanted to assess whether it was worth continuing. The data was clear: test coverage had risen from 34% to 56%, two high-complexity methods had been refactored, and the team's self-reported sense of code quality (measured informally in retro) had improved significantly.

But the product manager had a counter-argument: in the same period, story point velocity had dropped by approximately 15%. Some of that was the debt work. Some of it was a genuinely complex new feature that had been underestimated. The product manager's position was reasonable: "I need the team to deliver, and I am not sure the debt investment is paying for itself in velocity terms."

This was the stakeholder management scenario the coach report had prepared me for. I did not argue that quality was more important than velocity. I reframed the question.

I presented three data points. First, the incident rate: in the six months before the debt initiative, there had been three incidents. In the six months during, there had been one — and it had been caught in the staging environment before production. Second, the deployment frequency: the team was deploying twice as often as before the debt initiative, because the improved test coverage meant staging sign-offs were faster. Third, the estimate accuracy: the two most complex features estimated in the previous quarter had both undershot by more than 30%. I argued — with evidence from the SonarQube analysis — that high complexity in the existing codebase was the primary driver of estimation error. Reducing complexity was an investment in future velocity, not a cost.

I did not get everything I wanted. The product manager agreed to 15% debt allocation rather than 20%, and we agreed to a quarterly review of whether the investment was working. But the initiative survived.

**Framing for the interview:** This is a S.T.A.R. about stakeholder management under conflicting priorities. Situation: a quality initiative was showing results but had reduced measured velocity. Task: convince the product manager to continue the investment without damaging the relationship or the team's morale. Action: I quantified the business impact of both the quality gap (incident rate, estimation error) and the quality investment (deployment frequency, incident reduction), and presented them as a coherent model rather than a qualitative argument. Result: the initiative continued at 15% allocation, and a quarterly review cadence was established.

---

## Month 10–12: The Bar Has Been Raised

By month twelve, the team was measurably different from the team I had inherited.

Test coverage was at 64% — still short of a theoretical ideal, but up from 34%. The code review process had a written definition of "ready for review": all tests passing, SonarQube gate passing, no unresolved comments. The senior developer who had told me the codebase felt shameful was now the most active code reviewer on the team, not because I had asked them to be, but because they had found something worth investing in.

The junior engineer who had refactored the pricing engine had been promoted to mid-level. The mid-level engineer who had learned to run design reviews independently was now running them without my involvement.

There had been one production incident in the final quarter — a configuration error, not a code quality issue — and the post-mortem had been run by the team without my facilitation. The action items were tracked in Jira, reviewed in the weekly sync, and closed within the sprint. The culture I had hoped to build was self-sustaining.

On the technical side, we had completed the migration to ZGC for the pricing service JVM — a change that reduced p99 GC pause from 800ms to under 2ms. The decision to migrate was made by the team, not by me. The senior developer had championed it after reading the ZGC post in this blog series. I provided context and backup; they made the case and drove the implementation.

---

## What I Would Do Differently

Honesty in an interview answer is as important as specificity. There are three things I would do differently.

**I would have involved the team in choosing the first debt items earlier.** I spent too much time in months one and two making the case upward before I had made the case to the team. The engineers on the team knew where the bodies were buried — they had the best view of the debt. I should have run a debt inventory session in week two and made the team the authors of the backlog.

**I would have introduced the S.T.A.R. method to the whole team earlier.** Not just for interviews — for all technical presentations. When engineers learn to structure their thinking as Situation, Task, Action, Result, their technical writing improves. Code review comments become more specific. Design documents become more readable. I introduced this in month eight and wished I had done it in month one.

**I would have been more explicit about the personal cost of the change.** Raising the bar is tiring. It requires more energy to review code thoroughly than to rubber-stamp it, more energy to write a good test than to ship without one, more energy to attend a post-mortem and follow up on action items than to move on. I did not acknowledge this cost to the team enough, and I think it contributed to a dip in morale around month eight that I could have managed better with more explicit recognition.

---

## The Interview Version of This Story

In a two-minute interview answer, this year compresses to something like this:

"I inherited a team with low test coverage and no incident culture. In the first month, I ran a debt inventory and made the business case to the product manager for a 20% debt allocation per sprint. In parallel, I ran a blameless post-mortem on a production GC pause — that incident became the catalyst for introducing GC monitoring and a ZGC migration. I mentored a junior engineer to take ownership of a concurrent refactoring; she became the team's Java concurrency expert. When the product manager questioned the velocity impact of the quality initiative, I quantified the incident rate reduction and deployment frequency improvement to make the case. Twelve months later, test coverage is up from 34% to 64%, the team runs its own post-mortems, and the junior engineer I mentored has been promoted."

The structure is S.T.A.R. throughout: Situation (inherited team, quality gap), Task (raise the bar, grow engineers), Action (debt initiative, post-mortem, mentoring, stakeholder negotiation), Result (measurable outcomes on coverage, incidents, team growth).

That compression — specific, quantified, personal — is what a Tech Lead interview at a FTSE 100 trading firm is looking for. The full story above is the preparation. The two-minute version is what you say in the room.
