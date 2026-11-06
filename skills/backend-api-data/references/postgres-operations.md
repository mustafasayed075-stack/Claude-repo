# PostgreSQL operations (backend-api-data)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Version facts are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

## Contents
1. Connections, pooling and the connection budget
2. PgBouncer transaction mode
3. Timeouts
4. Autovacuum and bloat
5. Indexes
6. Reading EXPLAIN
7. N+1 queries
8. Keyset pagination
9. Isolation and retries
10. Locks
11. Optimistic vs pessimistic concurrency
12. Replicas and read-your-writes
13. Failover
14. Backups and PITR
15. Keys and data types
16. Constraints

## 1. Connections, pooling and the connection budget
This file owns the connection-budget rule; other skills point here.
- PostgreSQL is process-per-connection; `max_connections` is "typically 100",
  and resources are sized from it, so raising it costs shared memory. (V)
- Budget: replicas × processes per replica (gunicorn or Node cluster workers) ×
  pool size, at max autoscale, must stay under `max_connections` minus a
  reserve. Re-check it whenever autoscaling limits change. This multiplication
  is a classic exhaustion bug. (T)
- Starting point for sizing: the PostgreSQL wiki ("Number Of Database
  Connections") suggests active connections near
  `(core_count * 2) + effective_spindle_count`, where the spindle count is zero
  if the active data set is fully cached; HikariCP's docs cite the same
  formula. Use the database server's cores. Whether it is per pool or
  fleet-wide is not settled: treat it as a starting point and load-test. (P)
- A small saturated pool beats a large one. Little's law sizes it:
  500 QPS × 20 ms of database time ≈ 10 busy connections. (T)
- Exhaustion causes: long or idle-in-transaction sessions, HTTP calls inside
  transactions, leaks, autoscale surges. Prevent with checkout timeouts,
  statement and idle timeouts, separate pools for batch work, and direct
  unpooled connections for migrators and other advisory-lock users. (P)

## 2. PgBouncer transaction mode
- Modes: session (safe, little multiplexing), transaction (the usual choice),
  statement (no multi-statement transactions). (P)
- Breaks in transaction mode, mostly without errors: session `SET` / `RESET`
  (use `SET LOCAL`), `LISTEN`, session advisory locks, `WITH HOLD` cursors,
  temp tables, SQL-level `PREPARE`. (P)
- Prepared statements: protocol-level named prepared statements are supported
  from PgBouncer 1.21.0 via `max_prepared_statements` (0, off, at first). The
  official 1.24.0 changelog: "Enable prepared statement support by default,
  max_prepared_statements is now set to 200 by default." This covers
  protocol-level prepared statements only; SQL-level `PREPARE` / `EXECUTE`
  still breaks. Check the version you run. (V)

## 3. Timeouts
- `statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout`
  and `transaction_timeout` all default to 0 ("zero (the default) disables the
  timeout"). (V)
- Set them per role or session. The docs say setting `statement_timeout` in
  postgresql.conf "is not recommended because it would affect all sessions".
  (V)
- An open transaction "prevents vacuuming away recently-dead tuples" and "can
  contribute to table bloat": always set `idle_in_transaction_session_timeout`.
  (V)
- Typical values: `statement_timeout` 5–30 s for web roles, longer for
  reporting; a short `lock_timeout` for DDL with retry. (T)
- Migrations use their own role with their own timeouts:
  `schema-migrations.md`.

## 4. Autovacuum and bloat
- Trigger: `autovacuum_vacuum_threshold` (50) + `autovacuum_vacuum_scale_factor`
  (0.2) × reltuples, so a 10M-row table waits for about 2M dead tuples. (V)
- PG 18 caps this with `autovacuum_vacuum_max_threshold` = 100,000,000. (V)
- Default: set a per-table `autovacuum_vacuum_scale_factor` of 0.01–0.05 on
  large hot tables, rather than lowering the global value first. (P)
- Usual root cause of bloat: a long transaction, an abandoned replication slot,
  or `hot_standby_feedback` holding back the xmin horizon. XID wraparound is
  the catastrophic outcome. (T)
- `VACUUM FULL` takes ACCESS EXCLUSIVE; use `pg_repack` online. (P)
- Monitor `n_dead_tup`, the oldest `xact_start`, XID age and replication slot
  lag. (T)

## 5. Indexes
- B-tree by default. GIN for JSONB containment, arrays, full-text search and
  `pg_trgm`. GiST for ranges, geometry and exclusion constraints. BRIN for huge
  tables whose columns have "natural correlation with their physical location
  within the table": tiny and lossy. (V for BRIN, T for the rest)
- Composite indexes: equality columns first, then range or sort columns;
  `(tenant_id, status, created_at)` serves `WHERE tenant_id = ? AND status = ?
  ORDER BY created_at`. (T)
- Partial indexes (`WHERE status = 'pending'`, `WHERE deleted_at IS NULL`) keep
  hot indexes small; covering indexes (`INCLUDE`) enable index-only scans,
  which depend on the visibility map (vacuum). (T)
- Each index adds write and WAL cost and can defeat HOT updates. (T)
- Unused indexes: `pg_stat_user_indexes.idx_scan = 0` over a full business
  cycle, checked on the primary and every replica (stats are per node). Drop
  duplicate and prefix-redundant indexes. (T)
- Build indexes on live tables concurrently: `schema-migrations.md`.
- Mistake: single-column indexes where one composite is needed. (T)

## 6. Reading EXPLAIN
- Use `EXPLAIN (ANALYZE, BUFFERS)`. ANALYZE executes the statement, so wrap DML
  in `BEGIN ... ROLLBACK`. (V)
- Look at estimated vs actual rows (a gap of 10× or more means stale or
  correlated statistics; consider `CREATE STATISTICS`), `Rows Removed by
  Filter`, loops × time, sequential scans on selective filters, large nested
  loops, sorts spilling to disk, and shared reads vs hits. (T)
- Plans on tiny dev databases are meaningless: a 1,000-row table hides every
  missing index. (T)

## 7. N+1 queries
- Eager-load (JOIN, or `WHERE id = ANY($1)`), use dataloaders in GraphQL, and
  assert a query-count ceiling per request in tests. N+1 is round-trip-bound,
  so it worsens with each network hop (cross-AZ, pooler). (T)

## 8. Keyset pagination
- `WHERE (created_at, id) < ($1, $2) ORDER BY created_at DESC, id DESC LIMIT n`
  with a matching `(created_at, id)` index stays constant; `OFFSET 100000`
  reads and discards 100k rows, and pages shift under concurrent inserts. Cap
  the page size on the server. (T)
- Contract side (cursor tokens, page sizes): `api-contracts.md`.

## 9. Isolation and retries
- Read Committed is the default (a snapshot per statement). PostgreSQL's
  Repeatable Read is snapshot isolation and does not allow phantoms but still
  allows serialization anomalies such as write skew; Serializable uses SSI and
  blocks those too. (V)
- Under Repeatable Read and Serializable, applications "must be prepared to
  retry transactions" on serialization failure (40001); deadlocks (40P01) are
  also worth retrying. The entire transaction, including the app logic that
  chose the writes, must be re-run, which is why PostgreSQL has no automatic
  retry. (V)
- A unique violation (23505) can be an undetected serialization failure and is
  sometimes worth retrying, but may also be a persistent condition. (V)
- `SERIALIZABLE READ ONLY DEFERRABLE` lets long reports run without
  serialization-failure risk. (V)
- Lost update (read-modify-write under Read Committed): fix with an atomic
  `SET x = x + 1`, `FOR UPDATE`, or a version check. Write skew: Serializable,
  or lock a shared parent row. (V)
- Guarded atomic update for balances and stock:
  `UPDATE ... SET balance = balance - $1 WHERE balance >= $1` (zero rows
  updated means insufficient funds). (T)
- Default: Read Committed plus atomic updates or row locks on hot paths;
  Serializable plus a bounded, jittered retry wrapper for cross-row
  invariants. Never hold a transaction across network calls. (T)

## 10. Locks
- `FOR UPDATE` holds row locks until commit. `NOWAIT` fails fast. `SKIP LOCKED`
  "provides an inconsistent view of the data, so this is not suitable for
  general purpose work, but can be used to avoid lock contention with multiple
  consumers accessing a queue-like table". (V)
- Advisory locks come in session and transaction scope; session advisory locks
  are unsafe through transaction-mode PgBouncer, so prefer
  `pg_advisory_xact_lock` there. (P)
- Lock waiters "wait indefinitely" absent a deadlock, so "it is a bad idea for
  applications to hold transactions open for long periods of time". (V)
- Avoid deadlocks by locking in a consistent order (sort ids, parent before
  child) and retrying 40P01. PostgreSQL aborts one victim after
  `deadlock_timeout` (believed to default to 1 s; verify). (T)

## 11. Optimistic vs pessimistic concurrency
- Optimistic (a version column; 412 or retry) suits low-contention user edits
  and maps to `If-Match` (`api-contracts.md`). Pessimistic (`FOR UPDATE`) suits
  short, high-contention sections such as inventory and balances. (T)
- Default: optimistic at the API, atomic SQL or row locks inside short
  transactions. (T)

## 12. Replicas and read-your-writes
- Failure modes: read-your-own-writes violations, check-then-act against stale
  data, standby query cancellations from vacuum conflicts. (T)
- Default: send only staleness-tolerant reads to replicas; pin a session to the
  primary for a window after it writes, or route by LSN
  (`pg_current_wal_lsn()` vs `pg_last_wal_replay_lsn()`). Never run
  authorization, balance or other write-guarding checks on a replica. (T)

## 13. Failover
- Use managed HA (for example RDS Multi-AZ or Aurora, Cloud SQL HA, Azure
  Flexible HA) or Patroni. Asynchronous failover can lose recent commits, so
  RPO > 0. Apps must reconnect and retry, and DNS caching affects failover
  time. (T)
- Failover and replication configuration changes are High in
  backend-reliability-performance.

## 14. Backups and PITR
- Physical base backups + continuous WAL archiving (pgBackRest, WAL-G or a
  managed service) for point-in-time recovery, with logical dumps as a
  secondary. (T)
- Replicas are not backups: `DROP TABLE` replicates in milliseconds. (T)
- GitLab lost about 6 hours of production database data in January 2017; its
  notes say "out of 5 backup/replication techniques deployed none are working
  reliably or set up in the first place", and it had no WAL archiving. (V)
- "Tested restore" means a scheduled, automated restore to an isolated
  environment, replay to a chosen timestamp, data checks, and measured restore
  time against the RTO. The drill cadence and ownership live in
  backend-testing-delivery. (T)
- Keep immutable copies in a separate account or region; backup encryption and
  access: backend-security-privacy. (T)

## 15. Keys and data types
- Keys: `bigint GENERATED ALWAYS AS IDENTITY` (8 bytes, best locality, but
  leaks counts) or UUIDv7; UUIDv4 scatters inserts across the B-tree. PG 18
  adds `uuidv7()` and a `uuidv4()` alias; on PG 17 and earlier, generate v7 in
  the app. Never `int4` keys (GitLab migrates int PKs to bigint as a recurring
  procedure). Add UNIQUE on any natural key. (V)
- Public id exposure: `api-contracts.md`.
- Sequences have gaps by design; for gapless invoice numbers, use a locked
  counter row. (T)
- Money: `numeric(p, s)` or a bigint of minor units plus a currency column;
  avoid the `money` type (locale-dependent) and floats. (T)
- Time: `timestamptz` for events; plain `timestamp` is wall-clock time and
  usually a bug. (T)
- Text: `text` + `CHECK (length(x) <= n)` is more flexible than `varchar(n)`;
  `text` ↔ `varchar` needs no index rebuild. (V for the rebuild rule, T for the
  preference)
- Enums: PostgreSQL enums are easy to extend but hard to shrink; lookup tables
  or CHECK are more flexible, and API consumers must tolerate new values. (T)

## 16. Constraints
- Default to NOT NULL. UNIQUE for every business uniqueness rule (application
  "check then insert" races). CHECK for domain rules. (T)
- FKs with an explicit ON DELETE: RESTRICT / NO ACTION by default, CASCADE only
  for true composition and never across tenant or aggregate boundaries,
  SET NULL for optional links. Index FK columns: PostgreSQL does not index them
  automatically, so parent deletes scan the child tables. (T for the policy, V
  for the indexing fact)
- Mistake: app-only validation, soon bypassed by a backfill script or a second
  service. (T)
