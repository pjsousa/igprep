# Mentoring Junior Devs: Growing Engineers Without Becoming a Bottleneck

The Tech Lead's most leveraged skill isn't the code you write — it's the code your team writes after you've left the room. Yet mentoring sits in an uncomfortable tension with delivery pressure: every hour spent teaching is an hour not spent shipping. The best Tech Leads find a way to grow others without grinding themselves into a bottleneck. This post explores the practical techniques that make that possible.

## The Mentoring Paradox

There is a version of the Tech Lead role that looks impressive on a org chart but quietly destroys a team: the "hero developer" who reviews every PR, makes every architectural decision, and has to be CC'd on every critical conversation. This person becomes the bottleneck because the team has learned they cannot function without them.

The paradox is real: mentoring takes time upfront, but it pays back compound interest. A junior who learns to reason about concurrency is worth ten hours of your review time, multiplied across every PR they will ever own. The goal of mentoring is to make yourself unnecessary — not to prove you are indispensable.

In a trading firm context, this matters even more. When your team owns a price-streaming pipeline running millions of updates per second, you cannot afford to be the single point of failure for every architectural question. The team has to be able to reason about the system without you in the room.

## Practical Mentoring Techniques

### Structured Code Review as Teaching

Most code review culture treats the reviewer as a gatekeeper: approved or rejected. A more effective framing is that code review is a teaching opportunity — for both the author and the reviewer.

When you leave a comment on a PR, ask yourself whether the comment explains *why* the suggestion applies, not just *what* to change. "This should use `LongAdder` instead of `AtomicLong` under high contention" teaches a principle. "Use `LongAdder`" just enforces a rule. The former creates a developer who can reason about the next similar situation; the latter creates a developer who waits for your comment.

A useful habit: comment on the first occurrence of a pattern, not every occurrence. If a junior is inconsistent about error handling across a PR, comment once on the first instance with the principle — then let them find and fix the rest. This scales better and develops pattern recognition.

### Pair Programming That Actually Helps

Pair programming is widely recommended and widely abused. Sitting next to someone while you write code is not pair programming — it's watching someone else code.

The useful version has a clear driver/navigator split. The driver writes the code; the navigator thinks ahead — about architecture, edge cases, and whether the current approach will scale. Swap roles regularly.

The trap for a Tech Lead is always driving. Driving feels productive — you are writing code. But if you always drive, you are doing the work rather than teaching the other person to do it. A better split: let the junior drive when the task is in their growth zone; you drive when the problem requires experience they haven't built yet.

In a price-streaming context, this might look like: the junior drives the implementation of a new price transformation; you navigate by asking questions — "what happens if the upstream feed goes quiet for 30 seconds? Does the downstream consumer see a stale price or a gap?" The code they write is theirs; the questions you ask are the teaching.

### Stretch Tasks With a Safety Net

The fastest growth happens when someone operates just beyond their current capability — not so far beyond that they flounder, not so far within that they coast. A stretch task is a piece of work calibrated to this zone.

The critical配套: a stretch task needs a safety net. This might mean:
- A more experienced peer who can unblock them when they hit a wall.
- A design review before they start coding, to catch fundamental misunderstandings early.
- A check-in cadence (daily or every two days) that surfaces blockers before they become week-long stalls.

Without the safety net, a stretch task just becomes a stressful task. With it, it becomes genuine growth.

### The 1:1 Cadence That Matters

One-on-ones are where mentoring gets personal. The trap is to use 1:1s as a status update: "what are you working on? Any blockers?" This is a standup in disguise.

A more useful 1:1 structure separates three concerns:
- **Blockers**: unblock them now. If something is genuinely blocked, resolve it immediately.
- **Growth goals**: what are they working toward? What skill do they want to develop in the next quarter? Connect their day-to-day work to their trajectory.
- **Feedback**: what have you observed in the last two weeks that they should keep, start, or stop? Be specific. "Your PRs have been getting more thorough on edge case handling — keep that up" lands differently than "good job."

The most underused part is the growth goals question. Most engineers have never been asked to articulate what they want to be doing in a year. Creating that space — and then connecting their work to it — is one of the highest-leverage things a Tech Lead does.

## Mentoring Without Creating Dependency

The failure mode of mentoring is creating a relationship where the junior needs you to function. This usually happens through one of two patterns.

The first is solving problems for them. When a junior hits a blocker and you immediately tell them the solution, you have optimised for short-term speed and sacrificed long-term capability. A better approach: ask questions that guide them toward the solution. "What have you tried? What do you think is happening? What would happen if you tested that hypothesis?" The goal is to transfer reasoning, not to transfer answers.

The second failure mode is making decisions for them. If a junior asks "should we use a concurrent hash map or a synchronised block here?", the wrong answer is "use a concurrent hash map." The right answer is "what are the tradeoffs? What does the profiling data say about our contention profile?" Even if you know the answer, helping them reason to it is the actual mentoring.

The litmus test: if you were on holiday for two weeks, would your mentee be able to do your job in your area? If the answer is no, you have built dependency, not capability.

## Interview Framing: The S.T.A.R. of a Mentoring Story

Behavioural interview questions often probe mentoring directly: "tell me about a time you helped a junior grow" or "describe a time you had to delegate." A strong answer follows S.T.A.R. and centres on a specific, personal outcome.

A weak version: "I mentored a junior on my team. I did code reviews and pair programming. They improved a lot and the team got better."

A strong version (S.T.A.R.):

> **Situation**: I joined a team that had no code review culture. PRs were merged with minimal scrutiny, and a junior developer — let's call him A — had been writing production code for eight months without structured feedback.
>
> **Task**: As Tech Lead, I wanted to both improve code quality and help A accelerate his growth. I had a six-week window before a major release that would tie up the whole team's capacity.
>
> **Action**: I introduced a mandatory review for all PRs touching the price-ingestion path. For A specifically, I shifted my approach from "approving" to "teaching": every comment I left explained the *why*, not just the *what*. Over four weeks, I tracked the pattern of his comments shifting — he started catching his own issues before requesting review. I also ran a 30-minute fortnightly 1:1 focused purely on his growth goals, where he told me he wanted to understand concurrent data structures. I pointed him to the `java.util.concurrent` source and asked him to present his findings to the team.
>
> **Result**: By the end of the six weeks, A was independently reviewing his own code for the patterns we had discussed. He presented his `ConcurrentHashMap` findings to the team — confidently, and with working code examples. Twelve months later he was promoted to mid-level and now owns the order-book reconciliation module. The code review culture we introduced has since spread across two other teams.

Notice what makes this strong: it names the person, describes the specific approach, and ends with a measurable, believable outcome. The interviewer can picture the scene. The leadership dimension is clear without being preachy.

## The Leadership Multiplier

Mentoring is the highest-leverage activity a Tech Lead can do — but only if it builds independence, not dependency. The best outcome of any mentoring relationship is that the person no longer needs you in the way they did at the start. That is not a failure of mentoring; it is the goal.

For a Tech Lead preparing for a FTSE 100 interview: mentoring stories signal that you think about team capability, not just personal output. That distinction — "I grew the team" vs "I wrote the code" — is exactly what separates a lead from a senior developer. Every interview question about delegation, conflict, or technical decision-making is an opportunity to show that you have already been thinking about this. Have your mentoring story ready, and make it land with specifics.
