# Fetch Join: Solving N+1 Without Switching to Native SQL

## Introduction

The N+1 problem is the most common performance pitfall in JPA applications. The previous post in this series covered detection and prevention strategies — including batch fetching and entity graphs. This post focuses on the most powerful and interview-tested solution: the JPQL Fetch Join.

Fetch Join is a JPQL clause that tells Hibernate to execute a single `JOIN` query and eagerly fetch associated entities alongside the parent, in one round-trip. No native SQL. No batch fetching approximation. Just a precise, controllable fetch plan.

But Fetch Join has sharp edges. Used incorrectly with collections, it can produce duplicate results, memory blow-up, or unexpected pagination. Understanding those edge cases is what separates a candidate who "has used JPA" from one who understands it deeply.

## What a Fetch Join Actually Is

A Fetch Join is a JPQL `JOIN` operation with the `FETCH` keyword appended:

```java
@EntityGraph(attributePaths = {"positions"})
List<Portfolio> portfolios = entityManager
    .createQuery("SELECT p FROM Portfolio p LEFT JOIN FETCH p.positions", Portfolio.class)
    .getResultList();
```

The `FETCH` keyword tells Hibernate: *do not give me a proxy for `positions`. Load the collection in the same query.*

Without `FETCH`, a regular JPQL `JOIN` behaves identically to a SQL join — it filters rows at the database level but does not change what Hibernate loads into the persistence context. The collection remains lazy.

The distinction is critical in an interview context:

```java
// Regular JOIN — collection remains lazy
SELECT p FROM Portfolio p JOIN p.positions

// Fetch JOIN — collection is eagerly loaded
SELECT p FROM Portfolio p JOIN FETCH p.positions
```

Both execute a SQL `JOIN`. Only the Fetch Join changes Hibernate's loading behaviour.

## Fetch Join vs Entity Graphs

Entity Graphs (covered in the N+1 post) are the JPA-standard alternative to Fetch Join. Both solve the same problem, but through different mechanisms:

| | Fetch Join | Entity Graph |
|---|---|---|
| Standard | JPQL feature (Hibernate, EclipseLink, OpenJPA) | JPA 2.1 standard |
| Applied at | Query time | Query time or globally via `@NamedEntityGraph` |
| Flexibility | Can join multiple collections (with caveats) | Attribute-by-attribute control |
| Pagination | Works with care | Safe with pagination |
| Pitfalls | Duplicate results with multiple collections | Fewer sharp edges |

For a Tech Lead reviewing code, the choice is often stylistic. For an interview answer, Fetch Join is the more commonly tested construct — it appears in JPQL exams and is the go-to solution for developers who need fine-grained control.

## Solving N+1 with Fetch Join

Consider the portfolio scenario from the N+1 post:

```java
// This triggers N+1: 1 query for portfolios, N queries for positions
List<Portfolio> portfolios = portfolioRepository.findAll();
for (Portfolio p : portfolios) {
    p.getPositions().size(); // trigger
}
```

With Fetch Join, one query does the work of N+1:

```java
@Transactional
public List<Portfolio> getPortfoliosWithPositions() {
    return entityManager
        .createQuery(
            "SELECT p FROM Portfolio p LEFT JOIN FETCH p.positions",
            Portfolio.class
        )
        .getResultList();
}
```

The generated SQL is a single `LEFT OUTER JOIN`:

```sql
SELECT p.id, p.name, p.portfolio_name,
       pos.id, pos.portfolio_id, pos.market_value
FROM   portfolio p
LEFT   OUTER JOIN position pos ON p.id = pos.portfolio_id
```

All positions are loaded in the same query. No additional round-trips.

## LEFT vs INNER Fetch Join

The `LEFT` keyword matters. An `INNER JOIN FETCH` silently drops parent entities that have no children:

```java
// INNER JOIN — portfolios without positions are excluded
SELECT p FROM Portfolio p INNER JOIN FETCH p.positions
// SQL: only portfolios with at least one position appear
```

```java
// LEFT JOIN — all portfolios returned, positions may be empty
SELECT p FROM Portfolio p LEFT JOIN FETCH p.positions
// SQL: all portfolios appear, positions are NULL where absent
```

In a trading context, `LEFT` is almost always correct. A portfolio with zero positions is still a valid portfolio — dropping it from results is a data bug, not a performance optimisation.

## Multiple Bag Fetch: The Duplicate Trap

This is where Fetch Join gets dangerous. Fetch Join works cleanly with a single collection. When you try to fetch two collections simultaneously, Hibernate generates a Cartesian product:

```java
// DANGEROUS: two collections with Fetch Join
SELECT p FROM Portfolio p
  JOIN FETCH p.positions
  JOIN FETCH p.trades   -- creates Cartesian product
```

If a portfolio has 10 positions and 5 trades, this returns 50 rows. Hibernate deduplicates in memory — but first it loads all 50 into the persistence context, multiplying memory usage and potentially causing `QueryException: MultipleBagFetchException`.

The fix is to use `Set` instead of `List` for one of the collections, which Hibernate can deduplicate during SQL execution:

```java
@OneToMany(mappedBy = "portfolio")
@OrderColumn(name = "position_idx")
private Set<Position> positions = new LinkedHashSet<>();
```

Or split the fetch into two queries — one per collection:

```java
List<Portfolio> portfolios = entityManager
    .createQuery("SELECT DISTINCT p FROM Portfolio p LEFT JOIN FETCH p.positions", Portfolio.class)
    .getResultList();

entityManager
    .createQuery("SELECT DISTINCT p FROM Portfolio p LEFT JOIN FETCH p.trades WHERE p IN :portfolios", Portfolio.class)
    .setParameter("portfolios", portfolios)
    .getResultList();
```

This is the **two-query fetch plan** — a well-known pattern for avoiding the Cartesian product while still loading two collections efficiently.

## Fetch Join and Pagination

Pagination with Fetch Join requires particular care. Hibernate applies the `JOIN` first, then paginates the result — which means pagination operates on the *expanded* (post-join) row set, not the original entity count:

```java
// Returns up to 20 Portfolio entities — but may return far more rows in the join
List<Portfolio> portfolios = entityManager
    .createQuery("SELECT p FROM Portfolio p LEFT JOIN FETCH p.positions", Portfolio.class)
    .setFirstResult(0)
    .setMaxResults(20)
    .getResultList();
```

If each portfolio has 10 positions, the database returns 200 rows. Hibernate collapses them back to 20 Portfolio instances — but the query fetched 200 rows worth of data.

For large collections, this defeats the purpose of pagination. The better pattern is to paginate on the parent query first, then fetch collections in a second query:

```java
// Page of portfolio IDs
List<Long> portfolioIds = entityManager
    .createQuery("SELECT p.id FROM Portfolio p ORDER BY p.id", Long.class)
    .setFirstResult(0)
    .setMaxResults(20)
    .getResultList();

// Then fetch portfolios with their positions
List<Portfolio> portfolios = entityManager
    .createQuery(
        "SELECT p FROM Portfolio p LEFT JOIN FETCH p.positions WHERE p.id IN :ids",
        Portfolio.class
    )
    .setParameter("ids", portfolioIds)
    .getResultList();
```

This produces exactly 20 portfolio entities with their positions loaded — no Cartesian explosion, no row count surprises.

## Using Fetch Join with Projections

Fetch Join works naturally when returning entities. When you project onto a DTO, you lose the persistence context benefit — but you still gain from the single-query fetch:

```java
public class PortfolioSummary {
    private final String name;
    private final int positionCount;
    private final BigDecimal totalValue;

    public PortfolioSummary(String name, int positionCount, BigDecimal totalValue) {
        this.name = name;
        this.positionCount = positionCount;
        this.totalValue = totalValue;
    }
}

List<PortfolioSummary> summaries = entityManager
    .createQuery(
        "SELECT new com.example.PortfolioSummary(" +
        "  p.name, " +
        "  SIZE(p.positions), " +
        "  COALESCE(SUM(pos.marketValue), 0) " +
        ") " +
        "FROM Portfolio p " +
        "LEFT JOIN p.positions pos " +
        "GROUP BY p.id, p.name",
        PortfolioSummary.class
    )
    .getResultList();
```

`SIZE(p.positions)` is Hibernate's function for counting collection elements without triggering a fetch — but it still requires a collection load in most Hibernate versions. For counting, prefer the explicit `LEFT JOIN` with `GROUP BY` shown above.

## Common Mistakes

**Fetching a bag (`List`) with multiple Fetch Joins.** Hibernate cannot dedupe a Cartesian product from two `List` associations. Use `Set` for at least one side, or use two queries.

**Assuming Fetch Join disables lazy loading globally.** Fetch Join is query-scoped. It does not change the entity's default fetch type. Outside this specific query, the collection remains lazy.

**Using Fetch Join inside a `@Query` annotation without `LEFT`.** An implicit `INNER JOIN` silently drops entities with empty collections — often not the intended behaviour.

**Paginating after a Cartesian-producing Fetch Join.** The `setMaxResults` applies to SQL rows, not entities. Always validate the actual row count with SQL logging before assuming pagination works correctly.

## Why This Matters in Production

In a trading system, Fetch Join is most valuable on read-heavy services where related data is almost always needed together:

- A **position service** that loads a portfolio and almost always needs its positions for risk calculation.
- A **trade blotter** that loads trades with their associated counterparty details.
- An **instrument reference service** that loads an instrument with its exchange and pricing source metadata.

The discipline is: if you know the access pattern at query time, use Fetch Join. If you need global load-when-accessed behaviour, use entity graphs or batch fetching. Fetch Join is a deliberate, query-level decision — not a default.

## Conclusion

Fetch Join is the most precise tool for solving N+1 at the query level. Used correctly, it replaces N+1 queries with a single `JOIN`, with full control over which collections are loaded and how. The sharp edges — Cartesian products with multiple bags, pagination surprises, and implicit `INNER` semantics — are all avoidable once the mechanics are understood.

The interview answer is straightforward: *a Fetch Join is a JPQL JOIN with the FETCH keyword that tells Hibernate to load the associated collection in the same query rather than returning a proxy. Use LEFT FETCH JOIN to avoid dropping entities with empty collections, avoid joining two bags simultaneously, and never paginate directly on a multi-collection Fetch Join result.*

A candidate who can explain both how Fetch Join works and when not to use it has moved beyond Hibernate fundamentals into production-grade ORM mastery.