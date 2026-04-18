# Dirty Checking: How Hibernate Knows What Changed (Without You Telling It)

## Introduction

Every Java developer who uses Spring Data JPA has written code like this:

```java
@Entity
public class Trade {
    @Id @GeneratedValue
    private Long id;
    private String instrument;
    private BigDecimal price;
    private int quantity;
}

@Service
public class TradeService {
    @Transactional
    public void updatePrice(Long tradeId, BigDecimal newPrice) {
        Trade trade = tradeRepository.findById(tradeId).orElseThrow();
        trade.setPrice(newPrice);   // <-- changed
        // no save() call, no merge(), nothing
    }
}
```

The `updatePrice` method modifies a field on a managed entity and exits the transaction — and Hibernate somehow writes the change to the database. No explicit `save()`. No `entityManager.merge()`. Hibernate just *knows*.

This is dirty checking — and understanding exactly how and when it fires is the difference between a developer who uses JPA successfully when defaults work and one who understands why things go wrong in production.

## What Dirty Checking Actually Is

Dirty checking is Hibernate's automatic mechanism for synchronising the in-memory state of managed entities with the database. When you load an entity into the persistence context, Hibernate takes a snapshot of its state. At flush time, it compares the current values of all managed entities against that original snapshot. Any field that differs is marked *dirty* and generates an SQL `UPDATE`.

This happens inside the `EntityManager` — not in your application code. You never call `entityManager.checkDirty()` or anything like it. Hibernate does it for you.

```java
@Transactional
public void demonstrateDirtyChecking(Long tradeId) {
    Trade trade = tradeRepository.findById(tradeId).get();

    // Hibernate's snapshot at load time: price = 100.50
    // Entity is now managed — Hibernate is tracking it

    trade.setPrice(BigDecimal.valueOf(101.25)); // dirty!

    // At flush (automatic unless flushMode = MANUAL):
    // Hibernate generates:
    // UPDATE trade SET price = 101.25 WHERE id = ?
    // It does NOT update fields that haven't changed
}
```

The key insight: **dirty checking is per-entity-field, not per-entity**. Hibernate generates a `WHERE` clause that updates only the specific columns that changed. This is not a full entity rewrite — it is a surgical update.

## The Persistence Context: First-Level Cache and Change Tracker

To understand dirty checking fully, you need to understand where it lives: the persistence context.

The persistence context is an in-memory cache of managed entities, tied to a Hibernate `Session` (which is the same as a JPA `EntityManager`, since `EntityManager` is the JPA wrapper around the Hibernate `Session`). It serves two purposes simultaneously:

1. **First-level cache**: Reduces repeated database hits within a transaction. If you call `find()` twice for the same ID within the same transaction, the second call returns the cached instance — same Java object reference.

2. **Change tracker**: Holds the snapshot that dirty checking compares against.

```java
Session session = entityManager.unwrap(Session.class);
Trade t1 = session.get(Trade.class, 1L); // DB hit, cached
Trade t2 = session.get(Trade.class, 1L); // cache hit — same object
assert t1 == t2; // true — identical reference

t1.setPrice(BigDecimal.valueOf(99.99)); // modifies the cached object
// Flush at transaction commit:
// UPDATE trade SET price = 99.99 WHERE id = 1
```

When you modify `t1`'s price, you're modifying the same object in the persistence context. Hibernate doesn't need a separate "modified copy" — it just knows that the object in the cache has changed relative to its snapshot.

## When Dirty Checking Runs: Flush Modes

Dirty checking does not run immediately when you call a setter. It runs at **flush time**. The default flush mode (`FlushMode.AUTO`) triggers a flush before certain queries execute and again at transaction commit.

The flush order is:

1. **Before query execution** — if a JPQL or native query is about to run, Hibernate flushes first to ensure query results see the latest changes.
2. **At transaction commit** — the session's `Transaction.commit()` triggers a flush.
3. **When `entityManager.flush()` is called explicitly** — manual flush (rare in application code, common in tests).

```java
@Transactional
public void flushTimingDemo(Long tradeId) {
    Trade trade = tradeRepository.findById(tradeId).get();
    trade.setPrice(BigDecimal.valueOf(77.00));

    // No flush yet — entity is dirty but not written

    List<Trade> trades = tradeRepository.findAll();
    // BEFORE this query executes, Hibernate flushes:
    // UPDATE trade SET price = 77.00 WHERE id = ?

    // Query results correctly reflect the price change
    assert trades.stream().anyMatch(t -> t.getPrice().compareTo(BigDecimal.valueOf(77.00)) == 0);
}
```

This is why a query can return stale results if your flush mode is `COMMIT` — the query sees the database state, not the persistence context's dirty state, until flush occurs.

## Dirty Checking and the Entity Lifecycle

Dirty checking only applies to entities in the **managed** state. Understanding entity lifecycle states is prerequisite to understanding dirty checking's scope:

| State | Dirty Checked? | Generated SQL |
|---|---|---|
| **Transient** (new object, `new`) | No | `INSERT` on persist |
| **Managed** (in persistence context) | Yes — automatic | `UPDATE` at flush |
| **Detached** (outside persistence context) | No | `UPDATE` on merge |
| **Removed** (marked for deletion) | No | `DELETE` at flush |

The critical implication: once you `entityManager.detach()` or let the transaction end, changes to that object are no longer tracked. This is a common source of confusion:

```java
@Transactional
public void detachedDemo(Long tradeId) {
    Trade trade = tradeRepository.findById(tradeId).get();
    entityManager.detach(trade); // explicit detach

    trade.setPrice(BigDecimal.valueOf(55.00)); // no effect!
    // At flush: no UPDATE generated for this entity
}
```

## Performance Implications

Dirty checking by default affects **all managed entities** in the persistence context. In a long-running transaction that loads many entities, Hibernate compares each one. For a small number of entities this is negligible. At scale — loading hundreds or thousands of entities in a single transaction — this becomes measurable.

```java
@Transactional
public void bulkProcess(List<Long> tradeIds) {
    List<Trade> trades = tradeRepository.findAllById(tradeIds); // 500 entities loaded
    for (Trade trade : trades) {
        if (trade.getPrice().compareTo(threshold) > 0) {
            trade.setProcessed(true); // marks 200 as dirty
        }
    }
    // At flush: Hibernate checks all 500 entity snapshots,
    // generates 200 individual UPDATE statements
}
```

This is the "N+1 of updates" problem — not a query N+1, but an update storm. Solutions include:

- **Batch updates**: Enable `hibernate.jdbc.batch_size` — Hibernate groups updates into fewer JDBC batch statements.
- **Stateless session**: `StatelessSession` bypasses the persistence context entirely — no dirty checking, no first-level cache. Use for bulk operations where you know exactly what you're doing.
- **Native UPDATE query**: `UPDATE Trade t SET t.processed = true WHERE t.price > :threshold` — a single statement, no entity state management.

```java
// Statless session for bulk update — no dirty checking
StatelessSession session = sessionFactory.openStatelessSession();
Transaction tx = session.beginTransaction();
session.createQuery("update Trade t set t.processed = true where t.price > :threshold")
       .setParameter("threshold", threshold)
       .executeUpdate();
tx.commit();
session.close();
```

## Dirty Checking in the Context of N+1 and Collections

Dirty checking interacts poorly with uninitialised lazy collections. Loading a managed entity with a lazy `@OneToMany` or `@ManyToMany` collection can trigger a `SELECT` to initialise that collection — and if you then modify the entity, Hibernate dirty-checks the parent including its collection state, which may itself be a source of unnecessary work.

More importantly: the dirty check walks the entire entity graph of managed entities. A large lazy collection that gets partially initialised and then modified can cause Hibernate to flush more than expected.

```java
@Transactional
public void lazyCollectionDirtyCheck(Long portfolioId) {
    Portfolio portfolio = portfolioRepository.findById(portfolioId).get();
    // positions is lazy — not loaded yet

    List<Position> positions = portfolio.getPositions(); // triggers SELECT
    positions.get(0).setQuantity(100); // dirty check now tracks the collection too

    // If portfolio has 10,000 positions but you only loaded and changed 1,
    // Hibernate still evaluates the whole managed entity graph
}
```

## Common Pitfalls

**1. Assuming changes are persisted immediately**
Setters on managed entities don't generate SQL until flush. If you read a field on the same entity after modification, you get the in-memory value — not a fresh database read. The persistence context owns that object for the transaction.

**2. Modifying a detached entity and expecting it to save**
```java
Trade trade = tradeRepository.findById(1L).get();
entityManager.close(); // or transaction ends
trade.setPrice(BigDecimal.valueOf(99.99));
// trade is now detached — no dirty checking
tradeRepository.save(trade); // or merge — this saves, but via merge, not dirty check
```
With `save()` on a detached entity, JPA treats it as transient and issues an `INSERT` — potentially violating a database `UNIQUE` constraint.

**3. Confusing flush with commit**
`flush()` writes pending SQL to the database but does **not** commit the transaction. A rollback can still undo the changes. `commit()` does both. Developers sometimes call `flush()` expecting durability and are surprised when a rollback undoes the work.

**4. Dirty checking with `@Transient` fields**
Fields marked `@Transient` are excluded from both dirty checking and persistence. If you store derived state in a non-transient field and expect it to persist, you will be disappointed — and the derived value can go stale without Hibernate noticing.

## Why This Matters in Production

Dirty checking is invisible until it isn't. In simple CRUD operations it Just Works. In production systems with high-throughput trading or financial pipelines, it becomes a source of subtle bugs and performance problems:

- A long-running transaction that loads thousands of entities and modifies a few will pay the full dirty-check cost across all managed entities.
- An uninitialised lazy collection accessed inside a transaction causes Hibernate to materialise and dirty-check more than expected — in the worst case causing unexpected `SELECT` storms inside what should be a simple update.
- `COMMIT` flush mode can cause queries to return stale data that the developer expected to be fresh, because dirty entities haven't been written yet.

For a Tech Lead reviewing code, the question to ask is: *does this transaction load more entities than it needs to?* A discipline of narrow transactions and early flushing of large reads prevents dirty checking from becoming a hidden performance drag.

## Conclusion

Dirty checking is Hibernate's most convenient hidden mechanism: it means you never write `UPDATE` statements for simple field changes on managed entities. But it only works within the persistence context, only at flush time, and it comes with a cost that scales with the number of managed entities in your transaction.

Understanding when it fires, what it tracks, and when to bypass it is the mark of a developer who understands JPA — not just someone who has used it successfully when defaults happened to work. In an interview, be ready to trace the exact SQL Hibernate generates for a given entity modification, explain the difference between `flush()` and `commit()`, and discuss how dirty checking interacts with lazy collections and long transactions.
