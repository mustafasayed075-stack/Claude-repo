# Tenancy and data lifecycle (backend-api-data)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`. Legal duties (Egypt Law
151/2020, GDPR): backend-security-privacy. Never-log rules:
backend-security-privacy.

## Contents
1. Tenancy models
2. Tenant rules in the schema
3. Row Level Security setup
4. Noisy neighbours
5. Choosing a datastore
6. Soft delete
7. Audit and history tables
8. Money and ledgers
9. JSONB columns
10. Search inside Postgres
11. Partitioning
12. Retention and erasure mechanics
13. Event sourcing

## 1. Tenancy models
This file owns the tenancy model. Enforcing isolation in the request path
(query scoping, re-checks in jobs and exports) belongs to
backend-security-privacy; two-tenant tests belong to backend-testing-delivery.

| Model | Pros | Cons | Default for |
|---|---|---|---|
| Shared schema + `tenant_id` + RLS | Cheapest; one migration; scales to many tenants | Every query must be scoped (one missing predicate leaks data); noisy neighbours | Most B2B SaaS |
| Schema-per-tenant | Logical isolation; easier per-tenant restore | Migrations run N times; catalog bloat at thousands of schemas | Tens to hundreds of mid-size tenants |
| Database-per-tenant | Strongest isolation; per-tenant region, residency and restore | Linear operational and connection cost; cross-tenant analytics are hard | Few large or regulated tenants |

(T) Default: shared schema + `tenant_id` + RLS as a backstop. Changing the
tenancy or isolation model is a High trigger in this skill.

## 2. Tenant rules in the schema
- `tenant_id` goes on every tenant-owned table, leads composite indexes, and
  appears in unique constraints and cache keys. (T)
- Composite FKs `(tenant_id, id)` block cross-tenant references. (T)

## 3. Row Level Security setup
RLS is defence in depth, not the only control. (T)
- Policies read the tenant from a setting such as
  `current_setting('app.tenant_id')`, set with `SET LOCAL` inside the
  transaction, which survives transaction pooling. `SET LOCAL` outside a
  transaction does nothing. (T)
- The app role must not own the tables: the table owner bypasses RLS unless
  `FORCE ROW LEVEL SECURITY` is set. Superusers and roles with `BYPASSRLS`
  always bypass it, so the app role has neither. (T)
- Views bypass RLS unless `security_invoker` is set. (T)
- The policy must fail closed when the tenant setting is unset: no rows, not
  all rows. (T)
- TODO(verify): RLS bypass rules come from author knowledge in the research
  reports, not a fetched PostgreSQL page; confirm on your version.

## 4. Noisy neighbours
- Contain them with per-tenant rate limits, fair job queues and statement
  timeouts. (T)
- Shard last, and by tenant, only after vertical scaling, partitioning,
  archiving and replicas are exhausted (backend-reliability-performance owns
  scaling). (T)

## 5. Choosing a datastore
Default: PostgreSQL is the system of record; it also covers JSONB documents,
modest full-text search, queue tables and advisory locks. Add another store
only with a written justification based on a measured bottleneck: every new
store adds backups, monitoring, failover, patching, a dual-write boundary and
on-call knowledge. A new datastore is a High trigger in this skill. (T)

| Add | When | Not when |
|---|---|---|
| Redis | Measured hot reads; rate limiting; ephemeral sessions; TTL locks (advisory only) | To hide a slow query you haven't EXPLAINed; as the only copy of business data |
| Managed queue | Async side effects (email, webhooks), retries, smoothing spikes | Postgres `SKIP LOCKED` covers your volume and you want transactional enqueue |
| Kafka | Many independent consumers, replay, high-throughput streams, CDC fan-out | A CRUD app with one consumer |
| Search engine | Relevance ranking, fuzzy or faceted search on large corpora, Arabic analysers beyond Postgres full-text search + `pg_trgm` | Exact filters and simple keyword search on modest tables (threshold unsure) |
| Object storage | Any file or blob | Never large files as `bytea` by default |
| Time-series store | High-ingest events with time-range aggregation | A few million rows; partitioning + BRIN is enough |
| Document/KV store | Known access patterns at massive scale, serverless | You need ad-hoc queries, joins, multi-entity transactions |

(T)

## 6. Soft delete
- It costs: a filter on every query (and a leak when one is forgotten), broken
  uniqueness, FKs that no longer mean "exists", bloat; and erasure still needs
  a hard delete. (T)
- Default: hard delete plus an audit or archive table, unless users genuinely
  need undo. (T)
- If soft delete is required: a partial unique index
  (`CREATE UNIQUE INDEX ... (email) WHERE deleted_at IS NULL`), indexes with
  the same predicate, and views or RLS to hide deleted rows. (T)

## 7. Audit and history tables
- Triggers capture every writer but add write cost; application audit records
  carry intent and actor; WAL-based CDC has no write overhead but is heavier to
  operate. (T)
- Default: an append-only audit table (actor, action, before/after JSONB,
  request id) written in the same transaction, with UPDATE and DELETE revoked:
  the app role gets INSERT only. Optionally hash-chain entries and ship them to
  separate storage. (T)
- Keep full personal-data payloads out of audit rows where you can; what may
  be logged at all is owned by backend-security-privacy. (T)

## 8. Money and ledgers
- Double-entry with immutable postings that sum to zero per currency per
  transaction. Derive balances, or cache them under `FOR UPDATE` on the account
  row. Corrections are reversing entries. Integer minor units + currency; key
  each ledger transaction by an idempotency key. (T)
- Anti-pattern: `UPDATE balance = balance - x` with no ledger, which cannot be
  reconciled. (T)
- Money or ledger schema is a High trigger in this skill.

## 9. JSONB columns
- Use JSONB for sparse attributes, verbatim external payloads and user-defined
  fields. (T)
- Avoid it for anything you filter, join, constrain or aggregate on routinely:
  you lose types, FKs and NOT NULL, and updates rewrite (TOASTed) values.
  Promote hot keys to columns (generated columns help). GIN indexes serve
  containment queries. (T)

## 10. Search inside Postgres
- A stored generated `tsvector` + GIN index + `ts_rank` handles in-app search
  well, and `pg_trgm` handles `ILIKE '%x%'` and fuzzy matching. (T)
- Move to a search engine when you need relevance tuning, typo tolerance,
  large-scale facets or cross-entity search. (T)
- Arabic text in search: `i18n-arabic-text.md`. (T)

## 11. Partitioning
- Partition when retention is by dropping or detaching partitions, queries
  prune by the key, or maintenance on one huge table has become unmanageable.
  Not a generic speed-up: queries without the key hit all partitions, unique
  constraints must include the key, and concurrent index builds go per
  partition, then on the parent. (P)
- Partitioning an existing table is a High trigger in this skill.

## 12. Retention and erasure mechanics
Legal duties and deadlines: backend-security-privacy. This file owns the
mechanics.
- Define retention per data class. Enforce it with batched deletes (1k–10k
  rows per transaction, keyset-driven, to avoid long transactions and replica
  lag) or by dropping partitions. (T)
- Erasure fans out: database, replicas, caches, search index, object versions,
  logs, analytics, warehouses, CDNs, backups and third parties. Run deletion as
  an idempotent job with a tombstone (deletion) ledger, and verify it. (T)
- Backups: you usually cannot surgically edit immutable backups. Document the
  backup retention period (for example 30–35 days), replay the deletion ledger
  after any restore, and let backups age out; per-tenant keys allow
  crypto-shredding. (T)
- Retention or deletion jobs are a High trigger in this skill.

## 13. Event sourcing
- Worth it when the history is the domain (ledgers, regulated replay) and the
  team accepts the cost of schema evolution and projection rebuilds. For CRUD,
  an audit table plus an outbox gets most of the value. (T)
