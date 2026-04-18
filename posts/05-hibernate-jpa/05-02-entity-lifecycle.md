# Entity Lifecycle: The Four States Every JPA Object Passes Through

Every JPA entity you work with exists in exactly one of four states at any given moment: **transient**, **persistent** (managed), **detached**, or **removed**. These states are not academic classifications — they determine whether Hibernate will persist your changes, whether dirty checking is active, and whether your next `merge()` call will do what you expect. Getting the state model wrong is the source of most Hibernate surprises: the `LazyInitializationException`, the silently lost update, the `EntityExistsException`. A Tech Lead who can navigate these states confidently is a Tech Lead who will not lose data to Hibernate's invisible machinery.

## The Four States

### Transient

A transient entity is a plain Java object created with `new`, with no persistence identity and no association with a persistence context. Hibernate has never seen it:

```java
Instrument instrument = new Instrument();
instrument.setName("VOD.L");
instrument.setPrice(BigDecimal.valueOf(120.5));
// instrument is TRANSIENT — no @Id, no persistence context
```

A transient entity is entirely ordinary Java. It will not be saved unless you explicitly call `entityManager.persist()`. Calling `persist()` transitions it to the persistent state. If you lose the reference without persisting, the object is garbage collected — no SQL, no audit, no recovery.

### Persistent (Managed)

A persistent entity is one that has a persistence identity (an `@Id` value) and is currently tracked by the persistence context. This is the managed state — Hibernate is watching it:

```java
@Transactional
public void updateInstrument(Long id) {
    Instrument instrument = entityManager.find(Instrument.class, id);
    // instrument is PERSISTENT — Hibernate is tracking it

    instrument.setPrice(BigDecimal.valueOf(125.0));
    // No merge call needed. Hibernate will generate UPDATE on commit/flush.
}
```

When you call `entityManager.find()` or execute a JPQL query that returns entities, those entities enter the persistent state. The persistence context holds a managed reference. Any field changes to a persistent entity are detected by dirty checking at flush time, and Hibernate generates the minimal `UPDATE` statement covering only the changed fields.

The persistence context identity map guarantees that for any primary key within its scope, there is exactly one managed instance. If two code paths load the same entity within a transaction, they get the same Java object reference.

### Detached

A detached entity is an object that was persistent but is no longer tracked by an active persistence context. This happens when the transaction that loaded the entity commits, rolls back, or is otherwise closed — or when you explicitly `entityManager.detach()` it:

```java
@Transactional
public void loadAndReturn(Long id) {
    Instrument instrument = entityManager.find(Instrument.class, id);
    return instrument; // caller receives a DETACHED entity
}

// Caller's code:
Instrument instrument = service.loadAndReturn(1L);
instrument.setPrice(BigDecimal.valueOf(130.0)); // Silently ignored — Hibernate is not watching
entityManager.merge(instrument); // Must reattach to persist changes
```

The critical thing to understand is that detached does not mean "saved." The entity was loaded from the database, but Hibernate is no longer tracking it. Field modifications on a detached entity are not observed by dirty checking. To persist those changes, you must either:

- Call `entityManager.merge()` to reattach and copy changes into a new persistent context
- Call `entityManager.refresh()` to discard local changes and reload from the database
- Keep the entity managed by extending the transaction boundary (not always possible)

The `merge()` operation is often misunderstood. It does **not** attach the existing instance to the current persistence context. Instead, it copies the state of the detached object onto a managed instance (either an existing one with that ID in the current context, or a new one loaded from the database), then returns the managed instance. Your original reference remains detached.

### Removed

A removed entity is persistent but marked for deletion. Hibernate will execute a `DELETE` statement at flush time:

```java
@Transactional
public void deleteInstrument(Long id) {
    Instrument instrument = entityManager.find(Instrument.class, id);
    entityManager.remove(instrument);
    // instrument is now REMOVED — DELETE will fire at flush/commit
}
```

The entity is not deleted from memory immediately. It remains in the persistence context until the flush operation, at which point Hibernate removes it and the `@Id` value becomes meaningless for that entity instance. If the transaction rolls back, the removal is not executed — the entity returns to the persistent state.

A common mistake is conflating `remove()` with actually deleting the row. The deletion happens at flush time. If you remove an entity and then roll back the transaction, no `DELETE` fires and the entity is still in the database.

## State Transitions: The Full Picture

Here is the complete state machine for a JPA entity:

```
[new Instrument()]        → TRANSIENT
[entityManager.persist()] → PERSISTENT (Managed)
[transaction commits]    → DETACHED
[entityManager.remove()] → REMOVED (then flushed → back to nothing)
[entityManager.merge()]   → DETACHED → PERSISTENT (reattaches a copy)
[entityManager.detach()]  → PERSISTENT → DETACHED (explicit detach)
```

The transition from transient to persistent via `persist()` is one-way in the normal flow — once an entity is persistent, it is managed until the persistence context closes (transitioning to detached) or until it is removed.

## Why the Distinction Matters in Practice

The state model has direct consequences in production code. The most common Hibernate production issues all trace back to state confusion:

**The stale data problem.** A service method loads an entity, modifies it, returns it to the caller, which modifies it again and calls `merge()`. If the service method's transaction committed before the second `merge()`, the merge will reattach the entity, but the caller may be working with outdated field values. This is a lost update risk that optimistic locking (`@Version`) addresses — but the root cause is always state confusion.

**The lazy loading problem.** A persistent entity with a lazily-loaded collection (`@OneToMany(fetch = LAZY)`) holds a proxy that initialises only when accessed inside an active persistence context. If you access the collection after the entity has become detached, Hibernate throws `LazyInitializationException`. The solution is either eager fetching, using `JOIN FETCH` in the query that loads the entity, or keeping the entity managed long enough to access the collection.

**The orphaned removal problem.** Removing an entity from a `@OneToMany` collection does not remove the child from the database unless `orphanRemoval = true` is set on the association. Hibernate will delete the orphaned child only in that case — otherwise it simply disassociates the relationship. This surprises developers who assume the child is deleted because it is no longer referenced.

## Entity Lifecycle and the Persistence Context

The four states only make sense in relation to the persistence context, which Post 5.1 covered in detail. Think of the persistence context as the "field of vision" in which Hibernate operates. Anything outside that field — transient objects, detached references — is invisible to Hibernate's change tracking.

This has a subtle but important consequence for queries. When you execute a JPQL query within a transaction, any entities returned are **automatically persistent and managed** from the moment they are instantiated from the result set. This means that if a query returns 500 instrument entities and you iterate through them setting prices, every entity is dirty at flush time and Hibernate will issue 500 `UPDATE` statements. That is correct behaviour, but it can be a performance shock if you were expecting a single bulk update.

## Why This Matters in Production

Understanding entity lifecycle is the foundation for reasoning about every Hibernate behaviour you will encounter in production. The `LazyInitializationException` is a lifecycle problem — the entity is detached when you access the uninitialised proxy. The `EntityExistsException` is a lifecycle problem — you tried to persist an entity that Hibernate already tracks. The `IllegalStateException` after session close is a lifecycle problem.

A Tech Lead who can draw the state diagram on a whiteboard and explain exactly what happens at each transition signals that they have debugged Hibernate issues at depth and understand the machinery they are working with. That is precisely what a trading firm interviewer wants to hear — not just that you know the annotations, but that you understand the runtime contract your objects enter when you call `persist()`.

When an interviewer asks "what happens when you call `entityManager.remove()` on an entity?" — the correct answer is not "it deletes the row." The correct answer explains the removed state, the deferred `DELETE`, and the implications of rolling back vs committing. That depth of understanding is what separates a candidate who has used Hibernate from one who understands it.