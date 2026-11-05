---
name: backend-api-data
description: In a backend/server codebase, use when designing or changing HTTP/JSON APIs, request and response shapes, error formats, pagination, idempotency, database schemas, queries, migrations, caching, file or object storage, or multi-tenant data in a PostgreSQL-backed service. Gives senior defaults with reasons. Not for auth or user-data privacy (backend-security-privacy), retries, queues or scaling (backend-reliability-performance), or tests and deploys (backend-testing-delivery).
---

# Backend API and Data

Senior defaults for API contracts, PostgreSQL data design, zero-downtime
migrations, caching, file storage and tenancy. This skill sits under the
foundation: the `Router:` line and engineering-standards' "Risk, scope and
planning" set the risk tier first, and a High change goes through
`architecture-planning` before any default here is used to edit code.

The root cause of most expensive mistakes here is treating a contract (an API
shape, a schema, a lock) as if only the current code version depends on it.
Old and new app versions, retrying clients, replicas, poolers and the
migration itself all use it at the same time. Design for that overlap first.

## Decisions

Name the rule (for example "API-3") when you apply it. Status: V verified
against a primary source, P partly verified, T judgement or secondary source;
word P and T items as "verify in your environment" or "judgement".

| # | Default | Why | Status |
|---|---|---|---|
| API-1 | Design every change for old and new versions running together: additive only; never remove, rename or retype a field in place | Mobile clients can't be forced to update, and rolling deploys mix versions | V |
| API-2 | Paginate every list endpoint from day one with opaque cursors and a server-side max page size; a page may hold fewer items than asked; an oversized `page_size` is coerced down, not rejected | Adding pagination later breaks every client (AIP-158) | V |
| API-3 | Side-effect or money-moving POST/PATCH accepts `Idempotency-Key`: store key, request fingerprint, state (in-flight / done) and response. Effects in your own database: write the key in the same transaction as the effect. External effects (a charge, an SMS): record the intent first, pass the key to the provider, reconcile afterwards. Same key + different payload = 422; in-flight = 409 | Flaky mobile networks make retries certain. The IETF draft is still a draft: check Datatracker for the current revision (P). Choose and document your own key retention, longer than the client's retry horizon (for example 24 h or more) | V |
| API-4 | Errors use RFC 9457 `application/problem+json` with a stable machine `type`; no stack traces or SQL | Clients branch on `type`; leaks help attackers | V |
| API-5 | Objects the caller may not know exist: follow backend-security-privacy (authorization), which owns the 404-vs-403 rule | One owner per fact | T |
| API-6 | Money = integer minor units + ISO 4217 currency, with the currency's exponent stored, not assumed to be 2; timestamps = `timestamptz` in UTC; store the IANA zone for scheduling | Floats and fixed offsets corrupt silently. Some currencies use 0 or 3 decimals. Egypt's DST is back, so never hard-code +02:00 | V |
| API-7 | Primary keys: bigint internally; UUIDv7 when ids are public or client-generated (PG 18 has `uuidv7()`; earlier versions generate in the app) | UUIDv4 scatters index inserts; v7 is time-ordered but leaks creation time | V |
| API-8 | Breaking-change check for every contract change: OpenAPI kept as a source artefact and diffed in CI with a breaking-change detector | Old mobile clients break silently otherwise | T |
| API-9 | Migrations follow expand, then migrate, then contract, in separate deploys; each step safe with the previous app version | Old code runs during rollout | V |
| API-10 | DDL sets a short `lock_timeout` and retries | A waiting ALTER blocks everything queued behind it (pile-up wording from practitioners; the docs only define the timeout) | P |
| API-11 | `CREATE INDEX CONCURRENTLY` outside a transaction; a failed build leaves an INVALID index to drop or `REINDEX`. FK/CHECK: `ADD ... NOT VALID`, then `VALIDATE CONSTRAINT` (SHARE UPDATE EXCLUSIVE) | Plain builds block writes | V |
| API-12 | Backfills run in small keyset batches with throttling, never one giant UPDATE | Long transactions block vacuum and lag replicas | T |
| API-13 | Migrations run as a separate migration role with its own timeouts, over a direct (unpooled) connection | Per-role web timeouts would kill long migrations; runner locks need session state | T |
| API-14 | Behind PgBouncer transaction mode: no `SET` (use `SET LOCAL`), no session advisory locks, `LISTEN` or temp tables. Prepared statements work only at protocol level, with `max_prepared_statements` (default 200 since 1.24.0); SQL-level `PREPARE` still breaks | Session state does not survive pooling | V |
| API-15 | Set `statement_timeout` per role, `idle_in_transaction_session_timeout` and `lock_timeout` (`transaction_timeout` also exists); all default to 0 (off) | A stuck query or idle transaction holds locks and blocks vacuum | V |
| API-16 | Connection budget: replicas × workers × pool size at max autoscale stays under `max_connections` (typically 100) minus a reserve | Classic exhaustion bug. Small pools beat big ones; the wiki/HikariCP formula is a starting point, measure | P |
| API-17 | Keyset pagination, not deep `OFFSET`; index FK columns; check new queries with `EXPLAIN (ANALYZE, BUFFERS)` (wrap DML in `BEGIN ... ROLLBACK`) | Offset cost grows and pages shift under inserts | V |
| API-18 | Autovacuum: default trigger is 50 + 20% of rows; tune per table (0.01 to 0.05) on large hot tables; PG 18 adds a 100,000,000-tuple cap | Bloat comes from long transactions or abandoned replication slots | V |
| API-19 | Default isolation Read Committed + atomic `UPDATE ... WHERE balance >= x` or `FOR UPDATE`; Serializable only with a retry loop around the whole transaction; `SKIP LOCKED` for queue-like tables | Lost updates and write skew | V |
| API-20 | Cache-aside with TTL (±10–20% jitter); invalidate by deleting the key on write; keys carry tenant and user; never cache authorization decisions without principal + resource + policy version | Stale-set races and cross-tenant leaks | T |
| API-21 | Redis: pure cache = `maxmemory` + `allkeys-lru`; queues, locks and sessions on a separate `noeviction` instance. Set the policy explicitly: OSS defaults to `noeviction`, ElastiCache to `volatile-lru` | Eviction of non-cache data is silent data loss | V |
| API-22 | Files: bytes in object storage, metadata (key, size, type, checksum, owner, tenant, scan status) in Postgres; clients upload via short-lived presigned URLs on server-generated keys; a presigned PUT cannot cap size, so use presigned POST with `content-length-range`, and mark a file ready only after its size, type and checksum are checked; multipart for large or mobile uploads with an abort-incomplete lifecycle rule. Upload validation itself: backend-security-privacy | API never proxies large bodies | P |
| API-23 | Tenancy model: shared schema + `tenant_id` + Row Level Security as a backstop; schema- or database-per-tenant only for few large or regulated tenants. RLS setup rules are in `references/tenancy-and-data-lifecycle.md`; enforcing isolation in the request path belongs to backend-security-privacy, two-tenant tests to backend-testing-delivery | Cheapest isolation that still fails closed | T |
| API-24 | Datastore choice: Postgres first. Add Redis, a queue, search or Kafka only with a measured need written down | Each store adds backup, monitoring, failover and on-call cost | T |
| API-25 | Retention and erasure per data class: batched deletes or dropped partitions; audit tables append-only; backups age out and a deletion ledger is replayed after any restore. Legal duties: backend-security-privacy | Erasure vs backups | T |

## Ask before choosing

Ask the user only what changes the design; otherwise state the assumption.

- Who consumes this API (public, partner, internal, slow-upgrading mobile), and how long must old clients keep working?
- Can the collection grow without bound? What are the sort order, the unique tiebreaker, and the default and maximum page size?
- What happens when a client retries after a timeout? What are the idempotency key's scope and retention?
- Which state transitions have side effects and deserve explicit actions instead of a PATCH on `status`?
- How are money, time and ids represented, and do public ids leak volume or creation time?
- Which invariants must hold, and which can be database constraints?
- What are the top queries, filters and sorts, which indexes serve them, and at what write cost?
- What is the row count and growth over 1 and 3 years? Is retention or partitioning needed?
- Does any transaction span a network call or user think-time?
- Is this read safe on a lagging replica?
- What is the tenancy model, and is the tenant predicate enforced structurally (composite FKs, RLS)?
- For a migration: will old and new app versions run against this schema at the same time? What lock does each statement take, for how long, and does it rewrite or scan? Is the table large or write-hot? Is there a fresh backup before any irreversible step?

## High-risk triggers (this skill's additions)

On top of the foundation's High triggers, these are High:

- destructive or irreversible migrations, and backfills that rewrite data;
- money or ledger schema;
- tenant isolation changes;
- retention or deletion jobs;
- partitioning an existing table;
- a new datastore;
- Redis eviction or persistence changes on non-cache data.

If a trigger above applies, write a `Risk: High` line before editing. Use the
format engineering-standards defines (`Risk: High - high trigger: <trigger>;
change: <new|restores|preserves>; <reason>`), load `architecture-planning`,
write its complete plan and stop for explicit approval. Any other new or
changed behavior here is Medium under engineering-standards: write
`Plan (Medium):` before the first edit.

## References: when to open each

Open a reference only when the task needs it.

- `references/api-contracts.md`: designing or changing an endpoint, status codes, error bodies, pagination, filtering, versioning and breaking changes, idempotency keys, PATCH semantics, bulk calls, long-running operations, rate-limit headers, webhook payload contracts, conditional requests, money/time/id formats, deprecation, OpenAPI.
- `references/schema-migrations.md`: any schema change on a live table: lock levels, adding columns and NOT NULL, indexes, FKs and CHECKs, renames, drops, type changes, backfills, ordering with deploys, the migration role, rollback.
- `references/postgres-operations.md`: connection pools and PgBouncer, the connection budget, timeouts, autovacuum and bloat, index types, reading EXPLAIN, N+1, isolation and locks, replicas and read-your-writes, failover, backups and PITR, key and data types.
- `references/caching-and-files.md`: adding or changing a cache, Redis eviction and persistence, object storage, presigned URLs, multipart uploads, file states, versioning and lifecycle.
- `references/tenancy-and-data-lifecycle.md`: choosing a tenancy model, RLS setup, noisy neighbours, soft delete, audit tables, ledgers, JSONB, in-database search, a new datastore, partitioning, retention and erasure mechanics.
- `references/i18n-arabic-text.md`: storing, validating, searching or showing Arabic text, Egyptian phone numbers, digits, or localized error messages.
- `SOURCES.md`: where each default comes from and how strong the source is.

## Backend common rules

These rules are the same, word for word, in all four backend skills.

- **Risk tiers.** Use the foundation's tiers exactly as engineering-standards defines them ("Risk, scope and planning"); a backend skill adds no tiers. Low restores an established contract (with evidence), refactors without changing behavior, or touches only tests or docs; any new or changed behavior is Medium and gets a `Plan (Medium):`; High goes through architecture-planning and stops for explicit approval. A trigger in a backend skill's High list makes the change High. When unsure, rate up.
- **Explain every decision.** When you apply a rule, name it and give its reason in one line. When you depart from a default, say why and what the alternative was.
- **Honest status.** V = verified against a primary source; P = partly verified; T = judgement or secondary source. Word every P and T item as "verify in your environment" or "judgement", never as settled fact. Version-sensitive facts are "as of Nov 2026; check your version".
- **Egypt first.** Assume users in Egypt on unreliable mobile networks, personal data under Egypt Law 151/2020, EGP money, Cairo time with DST (store IANA zones, never a fixed offset), and cloud regions that may be far away (measure latency; do not assume). Treat any foreign-hosted service as a possible cross-border transfer.
- **No named payment gateways.** Keep payment rules generic; for gateway-specific behavior, check the chosen gateway's own documentation.
- **Not verified.** End every task with a short "Not verified" list (for example production-scale data, real third parties, load, multiple instances, failure injection).
