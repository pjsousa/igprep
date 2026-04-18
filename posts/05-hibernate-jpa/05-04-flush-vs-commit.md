# Flush vs Commit: When Hibernate Writes to the Database (and When It Doesn't)

## Introduction

Every JPA developer has written code like this:

```java
@Transactional
public void placeTrade(Trade trade) {
    entityManager.persist(trade);
    // is it in the database yet?
}
```

The answer: **not necessarily**. Until you understand the difference between `flush()` and `commit()`, you cannot reliably reason about when data is actually in the database — and that uncertainty will bite you in production, particularly in financial systems where "saved" and "committed" have very different meanings.

`flush()` and `commit()` do two different things. Confusing them is one of the most common sources of data-integrity bugs in JPA applications. This post dissects both operations precisely.

## What Flush Actually Does

`EntityManager.flush()` synchronises the persistence context's managed entities to the database by executing all pending SQL statements — `INSERT`, `UPDATE`, `DELETE` — without ending the transaction.

Concretely:

```java
@Transactional
public void flushDemo() {
    Trade trade = new Trade();
    trade.setInstrument("VOD.L");
    trade.setPrice(BigDecimal.valueOf(150.25));
    trade.setQuantity(1000);

    entityManager.persist(trade);
    // At this point: entity is managed, but NO SQL has been issued yet.

    entityManager.flush();
    // NOW the INSERT is sent to the database.
    // The transaction is still open — other work can continue.

    // You can still roll back. flush() did not commit.
}
```

After `flush()`, the entity is still managed within the current transaction. The `INSERT` has been executed at the database level, but it is not yet committed — it is held in the transaction's uncommitted state, visible to other transactions depending on their isolation level.

## What Commit Actually Does

`Transaction.commit()` does two things:

1. **Flushes** the persistence context — executing all pending SQL.
2. **Commits** the underlying database transaction — making the changes durable and visible to other transactions.

```java
@Transactional
public void commitDemo() {
    Trade trade = new Trade();
    trade.setInstrument("HSBA.L");
    trade.setPrice(BigDecimal.valueOf(580.00));
    trade.setQuantity(500);

    entityManager.persist(trade);
    // No SQL yet

    transaction.commit();
    // Internally calls flush() first, then DB COMMIT
    // Data is now durable. Transaction is closed.
}
```

Once `commit()` succeeds, the changes survive a database restart (assuming default `READ_COMMITTED` or stronger isolation). Until `commit()` completes, a rollback can undo everything.

## The Default Flush Behaviour

By default, Hibernate uses `FlushMode.AUTO`. Under `AUTO`, Hibernate flushes automatically in two situations:

1. **Before a query executes** — if a JPQL or native query is about to run, Hibernate flushes pending changes first so the query sees the latest data.
2. **At transaction commit** — when `Transaction.commit()` is called, Hibernate flushes before committing.

```java
@Transactional
public void autoFlushDemo(Long tradeId) {
    Trade trade = entityManager.find(Trade.class, tradeId);
    trade.setPrice(BigDecimal.valueOf(200.00)); // dirty

    // No explicit flush() here.
    // But if I run a query...

    List<Trade> trades = entityManager
        .createQuery("SELECT t FROM Trade t WHERE t.instrument = 'VOD.L'", Trade.class)
        .getResultList();

    // Hibernate flushes BEFORE the query executes.
    // So the query sees price = 200.00, not the old value.
}
```

This automatic pre-query flush is what makes the "no `save()` call needed" pattern work — but it also means a query can be slower than expected if there are many dirty entities to flush first.

## Flush Modes

JPA defines four flush modes:

| Mode | When Hibernate Flushes |
|---|---|
| `AUTO` (default) | Before queries; at commit |
| `COMMIT` | Only at commit — queries may see stale data |
| `ALWAYS` | Before every query, even if no pending changes (rarely needed) |
| `MANUAL` | Never automatically — only explicit `flush()` writes changes |

`COMMIT` mode is the one most likely to surprise developers:

```java
entityManager.setFlushMode(FlushModeType.COMMIT);

@Transactional
public void commitModeDemo(Long tradeId) {
    Trade trade = entityManager.find(Trade.class, tradeId);
    trade.setPrice(BigDecimal.valueOf(999.00)); // dirty

    // No flush yet.

    List<Trade> trades = entityManager
        .createQuery("SELECT t FROM Trade t", Trade.class)
        .getResultList();

    // Query sees OLD price — the dirty change is NOT flushed first.
    // This is deliberate: you chose COMMIT mode to defer flushing.
}
```

`MANUAL` mode is useful in batch processing where you want full control over when SQL is issued, and you never want Hibernate to flush unexpectedly before a query:

```java
entityManager.setFlushMode(FlushModeType.MANUAL);

@Transactional
public void manualModeDemo() {
    Trade trade = new Trade();
    entityManager.persist(trade);

    // entityManager.flush(); // only this will write it

    // A query here will NOT trigger a flush.
    // Nothing is written until you call flush() explicitly.
}
```

## Flush vs Commit: Side-by-Side

| | `flush()` | `commit()` |
|---|---|---|
| Executes pending SQL | Yes — immediately | Yes — as part of its operation |
| Makes changes durable | No — other session can still roll back | Yes |
| Closes the transaction | No | Yes |
| Reversible via rollback | Yes (before commit) | No |
| Triggers automatically | Only if `AUTO` and before queries | At transaction end |

## Why This Matters in a Financial System

In a trading system, the distinction is not academic — it is a data-integrity question.

Consider a trade confirmation service:

```java
@Transactional
public TradeConfirmation confirmTrade(Long tradeId) {
    Trade trade = tradeRepository.findById(tradeId).orElseThrow();

    if (trade.getStatus() != TradeStatus.PENDING) {
        throw new IllegalStateException("Trade already confirmed");
    }

    trade.setStatus(TradeStatus.CONFIRMED);
    trade.setConfirmedAt(Instant.now());

    // At this point, the entity is dirty.
    // But has it been written to the DB?

    return buildConfirmation(trade); // reads from the entity
}
```

If this method returns a confirmation object that is logged or sent downstream, you need to be certain the database write has happened. If the confirmation is sent and the subsequent `commit()` fails (e.g., a constraint violation), you have sent a confirmation for a trade that was never persisted — a serious integrity issue.

The correct pattern:

```java
@Transactional
public TradeConfirmation confirmTrade(Long tradeId) {
    Trade trade = tradeRepository.findById(tradeId).orElseThrow();

    if (trade.getStatus() != TradeStatus.PENDING) {
        throw new IllegalStateException("Trade already confirmed");
    }

    trade.setStatus(TradeStatus.CONFIRMED);
    trade.setConfirmedAt(Instant.now());

    entityManager.flush(); // Force the write before building confirmation

    // Now the write is in the DB (uncommitted), but confirmed.
    // If anything below here throws, commit() will fail and we roll back.
    // The confirmation must not be sent until flush() is confirmed.

    return buildConfirmation(trade);
}
```

## Cascade Persist and the Flush Order Problem

`CascadeType.PERSIST` creates another subtlety. When you persist a parent entity with a collection of child entities that also need persisting, Hibernate must flush in a specific order:

```java
@Entity
public class Portfolio {
    @Id @GeneratedValue
    private Long id;

    @OneToMany(cascade = CascadeType.PERSIST, mappedBy = "portfolio")
    private List<Position> positions = new ArrayList<>();
}

@Entity
public class Position {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne
    private Portfolio portfolio;
}

@Transactional
public void cascadePersistDemo() {
    Portfolio portfolio = new Portfolio();
    entityManager.persist(portfolio); // portfolio gets an ID via DB sequence

    Position p1 = new Position();
    p1.setPortfolio(portfolio); // FK is set, but position not yet persisted
    portfolio.getPositions().add(p1);

    // portfolio has ID, p1 does not — FK constraint would fail if flushed now
    // Hibernate must persist p1 BEFORE flushing portfolio's changes

    entityManager.flush(); // Hibernate figures out the order: p1 first, then portfolio
}
```

Hibernate handles this automatically via its action queue — it schedules `INSERT` statements in a safe order. But when you mix `flush()` calls with manual SQL or native queries, you can break this ordering.

## The Zombie Connection: Flush, Rollback, and Isolation Levels

`flush()` writes data to the database — but under `READ_COMMITTED` isolation (the default for most databases), uncommitted data is invisible to other sessions. This means:

```java
// Session A
@Transactional
public void sessionA() {
    Trade trade = entityManager.find(Trade.class, 1L);
    trade.setPrice(BigDecimal.valueOf(300.00));
    entityManager.flush(); // written to DB, but not committed

    // Session B, running concurrently in another thread:
    // Trade trade = entityManager.find(Trade.class, 1L);
    // sees OLD price — Session A's change is not visible yet

    transaction.commit(); // now Session B would see the new price
}
```

If Session A rolls back after `flush()` but before `commit()`, the flushed write is undone. This is why `flush()` alone is not a durability guarantee.

## Common Mistakes

**1. Calling flush() instead of commit() and expecting durability**

```java
entityManager.flush();
// Database has the data...
// ...but if the process crashes before commit(), it's gone.
```

**2. Using a query result after flush() but before commit() and assuming it is safe**

```java
entityManager.flush();
List<Trade> trades = tradeRepository.findAll(); // reads from DB
// These results are from the uncommitted transaction.
// If commit() throws, these results represent data that won't exist.
```

**3. Confusing FlushMode.COMMIT with "nothing will be written"**

`COMMIT` mode only defers automatic flushing until commit time. It does not prevent you from calling `flush()` manually. Calling `flush()` explicitly overrides the flush mode.

**4. Expecting flush() to clear the persistence context**

`flush()` executes SQL but does not evict entities from the persistence context. They remain managed. Use `entityManager.clear()` if you want to evict all managed entities.

## Why This Matters in Production

Flush and commit are distinct operations with distinct semantics. A Tech Lead reviewing JPA code must spot the assumptions that developers make:

- "I called `persist()`, so it's saved" — false until flush.
- "The query returned the right data, so the update worked" — only if `AUTO` flush ran first.
- "I'll flush at the end of the method to make sure it saves" — still not committed; a rollback after `flush()` will undo it.

In a financial trading system, these distinctions have real consequences: duplicate confirmations sent, audit records that don't match the committed state, or downstream risk engines processing data that never actually made it to the database.

The discipline is: **until `commit()` succeeds, the data is not durable**. Flush is a tool for controlling visibility and ordering within a transaction, not a substitute for commit.

## Conclusion

`flush()` writes pending SQL to the database within the current transaction. `commit()` does that, plus ends the transaction and makes the changes durable. Under `AUTO` flush mode (the default), Hibernate flushes before queries and at commit time — so most of the time you don't need to call `flush()` manually. But when you do need to control the exact timing of writes — for confirmation signals, for ordering of cascade operations, or for batching — `flush()` is the right tool.

The interview question is straightforward: *what is the difference between flush and commit?* The production question is subtler: *are you calling flush() when you mean commit(), or committing when you only needed to flush?* Knowing the distinction is the difference between code that happens to work and code that is correct by design.