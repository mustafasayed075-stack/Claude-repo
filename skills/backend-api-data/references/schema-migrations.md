# Schema migrations (backend-api-data)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author synthesis. Word P and T items as "verify in
your environment". PostgreSQL facts are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

## Contents
1. Expand / contract
2. Why a one-step rename or drop breaks rolling deploys
3. Lock levels (table)
4. Lock-queue pile-ups and `lock_timeout`
5. Adding columns, defaults and NOT NULL (PG 11 and PG 18 notes)
6. Indexes concurrently, and invalid indexes
7. Foreign keys and CHECKs without long locks
8. Type changes on large tables
9. Backfills
10. Dual writes and verification
11. Ordering with app deploys
12. The migration role and timeouts
13. Rollback
14. Tooling and CI lint
15. Cross-version compatibility and feature flags
16. Rehearsal at production scale
17. Lock impact (input to the risk tier)

## 1. Expand / contract
1. Expand: add nullable columns, new tables, `CONCURRENTLY` indexes. Old code
   ignores them.
2. Deploy code that dual-writes and still reads the old shape.
3. Backfill in batches (section 9).
4. Verify with counts, checksums and shadow reads.
5. Switch reads, behind a flag.
6. Stop writing the old shape.
7. Contract: drop the old structures in a later deploy.

A large payments company's engineering blog ("Online migrations at scale",
2017) uses this four-step shape: dual-write, change reads, change writes,
remove old data; it compared read paths before cutover. GitLab's migration
docs document the same discipline. (V)

## 2. Why a one-step rename or drop breaks rolling deploys
- Old and new pods run at the same time. Rename `a` → `b` and old pods'
  `SELECT a` fails; run the migration after the deploy and new pods fail. (V)
- ORMs cache column lists: GitLab warns that dropping a column before the model
  ignores it makes instances fail "until the Rails schema cache expires". (V)
- GitLab's drop procedure: release M ignores the column; release M+1 drops it
  in a post-deployment migration. Rename: a new column plus sync triggers, then
  post-deploy cleanup, with batched background migrations for large tables.
  (V)

## 3. Lock levels (table)
Only ACCESS EXCLUSIVE blocks a plain SELECT. ALTER TABLE takes ACCESS
EXCLUSIVE unless the docs note otherwise, and a multi-subcommand ALTER takes the
strictest lock any part needs. (V)

| Statement | Lock | Rewrite or scan | Safe pattern |
|---|---|---|---|
| `ADD COLUMN` with no default or a non-volatile default (PG 11+) | ACCESS EXCLUSIVE, brief | Neither: metadata only | Fine with `lock_timeout` |
| `ADD COLUMN` with a volatile default, identity, or stored generated column | ACCESS EXCLUSIVE | Rewrite | Add nullable, backfill, then constrain |
| `ALTER COLUMN ... SET NOT NULL` | ACCESS EXCLUSIVE | Full scan (skipped on PG 12+ if a valid CHECK proves no NULLs) | CHECK `NOT VALID` → VALIDATE → SET NOT NULL |
| `CREATE INDEX` | SHARE (blocks writes) | Build | `CREATE INDEX CONCURRENTLY` |
| `CREATE INDEX CONCURRENTLY` | Does not block writes | Two scans, waits for older snapshots | Outside a transaction |
| `ADD FOREIGN KEY` | SHARE ROW EXCLUSIVE on both tables (blocks writes) | Scan unless `NOT VALID` | `NOT VALID`, then VALIDATE |
| `VALIDATE CONSTRAINT` | SHARE UPDATE EXCLUSIVE (ROW SHARE on the referenced table) | Scan; reads and writes continue | Separate statement |
| `ALTER COLUMN TYPE` | ACCESS EXCLUSIVE | Rewrite of table and indexes unless binary-coercible | New column + sync + backfill + swap |
| `VACUUM FULL` | ACCESS EXCLUSIVE | Rewrite | `pg_repack` online |

All rows V (PostgreSQL docs), except the `pg_repack` suggestion (T).

## 4. Lock-queue pile-ups and `lock_timeout`
- The outage is rarely the DDL itself. A migration waits behind a long query
  holding ACCESS SHARE, and every new query queues behind the migration, so a
  millisecond ALTER takes the table offline for as long as the long query
  runs. Practitioners document this consistently; the PostgreSQL docs say
  waiters wait "indefinitely" but do not describe the queue effect. (P)
- Prevention: `SET lock_timeout` to a few seconds (it applies to each lock
  acquisition), retry with backoff, set `statement_timeout`, check
  `pg_stat_activity` for long transactions first, and run one risky DDL per
  transaction (locks are held until COMMIT). (V for the settings, P for the
  "few seconds" value)

## 5. Adding columns, defaults and NOT NULL
- PG 11+: `ADD COLUMN ... DEFAULT <non-volatile>` (even with NOT NULL) is
  metadata only; the value lives in `pg_attribute.attmissingval`. `now()` is
  stable, so it is fast. Volatile defaults (`gen_random_uuid()`,
  `clock_timestamp()`), identity columns and stored generated columns still
  rewrite the table under ACCESS EXCLUSIVE. PG 10 and earlier rewrote the table
  for any non-null default. (V)
- So a NOT NULL column with a constant default on a large table is safe in one
  step on PG 11+. Without a usable constant default: expand, batched backfill,
  then the constraint sequence below. (V)
- NOT NULL on an existing column, safe sequence:
  1. `ADD CONSTRAINT c CHECK (col IS NOT NULL) NOT VALID`
  2. `VALIDATE CONSTRAINT c` (SHARE UPDATE EXCLUSIVE; reads and writes continue)
  3. `SET NOT NULL` (PG 12+ skips the scan when a valid CHECK proves no NULLs)
  4. Drop the CHECK in a separate statement. Combining `SET NOT NULL, DROP
     CONSTRAINT` in one ALTER drops the constraint first and forces the full
     scan. (V)
- PG 18: NOT NULL constraints themselves can be added `NOT VALID` and validated
  later. (V)

## 6. Indexes concurrently, and invalid indexes
- Plain `CREATE INDEX` takes SHARE and blocks writes for the whole build. `CREATE
  INDEX CONCURRENTLY` (CIC) does two scans in separate transactions and waits
  for older snapshots. It cannot run in a transaction block (disable the
  framework's transaction wrapper for that migration), and only one CIC per
  table runs at a time. (V)
- A failed CIC (duplicate key, cancel, timeout, deadlock) leaves an INVALID
  index. The planner ignores it, but it is still maintained on every write; a
  unique build that fails in the second scan keeps enforcing uniqueness. (V)
- Recovery: `DROP INDEX CONCURRENTLY` and retry, or `REINDEX INDEX
  CONCURRENTLY` (PG 12+). Check `pg_index.indisvalid` after every migration.
  `CREATE INDEX CONCURRENTLY IF NOT EXISTS` silently skips an existing invalid
  index. (V)
- One idle-in-transaction session can stall CIC indefinitely. (T)
- Partitioned tables: CIC is not supported on the partitioned parent; build on
  each partition, then on the parent. (P)

## 7. Foreign keys and CHECKs without long locks
- `ADD FOREIGN KEY` takes SHARE ROW EXCLUSIVE on both the table and the
  referenced table and scans unless `NOT VALID` is given. Pattern: `ADD
  CONSTRAINT fk ... NOT VALID` (brief; enforced for new rows), then `VALIDATE
  CONSTRAINT fk` separately. CHECK follows the same pattern. (V)
- Index FK columns: PostgreSQL does not index them automatically
  (`postgres-operations.md`). (V)

## 8. Type changes on large tables
- `ALTER COLUMN TYPE` normally rewrites the table and indexes under ACCESS
  EXCLUSIVE, needs up to 2× disk, and rewrites are not MVCC-safe. The exception
  is a binary-coercible change whose `USING` keeps the contents (`text` ↔
  `varchar` is the documented case). (V)
- Whether widening `varchar(n)` avoids a rewrite is inferred from that rule:
  test it on your version. (P)
- Real changes (int → bigint, text → jsonb): new column + sync trigger +
  batched backfill + CIC indexes, then a short swap transaction (renames,
  defaults and sequences, PK via an existing unique index), with the old column
  dropped later. GitLab documents this as a concurrent column-type change. (V)
- `int4` primary keys run out: GitLab keeps a dedicated procedure for migrating
  integer PKs to bigint. (V)

## 9. Backfills
- Batch by PK range (keyset), not OFFSET and not an unindexed
  `WHERE new_col IS NULL LIMIT n`. Commit per batch, throttle on replica lag,
  make the job idempotent and resumable (recompute from source, checkpoint),
  and run it as a background job, not in the deploy. (T)
- GitLab uses batched background migrations to "spread the work / load over a
  longer time period, without slowing down deployments". (V)
- Anti-pattern: one giant UPDATE. It causes a long transaction, bloat and
  replica lag, and a cancel rolls everything back. (T)
- A backfill that rewrites data is a High trigger in this skill.

## 10. Dual writes and verification
- App-level dual writes are simple but drift on partial failure. Triggers are
  atomic in the same transaction (GitLab's concurrent rename uses them). CDC or
  an outbox is decoupled but asynchronous. (T)
- Default for same-database changes: triggers or same-transaction writes, then
  reconciliation queries and shadow reads; switch only after a clean period.
  (T)

## 11. Ordering with app deploys
- Expand migrations run before the code that needs them. Contract migrations
  run after no running code uses the old shape. GitLab splits "regular"
  (pre-deploy) from "post-deployment" migrations. Every app version must work
  on schema N and N+1. (V)
- Anti-pattern: migrations run at app startup on every pod, which races and
  blocks readiness. (T)

## 12. The migration role and timeouts
- Run migrations as a separate role with its own `statement_timeout` and
  `lock_timeout`, so the per-role timeouts set for web traffic
  (`postgres-operations.md`) do not kill long migrations. (T)
- `transaction_timeout` exists in current PostgreSQL docs (which version
  introduced it is unconfirmed; verify). Like the other timeouts it defaults to
  0 (off). (P)
- Migration runners that take an advisory lock need a direct, unpooled
  connection, not transaction-mode PgBouncer. (T)

## 13. Rollback
- Expand steps are reversible. Contract steps (DROP COLUMN, lossy type changes,
  deletes) can only be undone from backup; a `down` that re-adds a column
  restores the shape, not the data. `DROP COLUMN` only hides the column until
  rows are rewritten, but it still cannot be reversed in SQL. (V)
- Default: roll forward; keep old structures until the new path is proven;
  keep code rollback independent of schema rollback; confirm a fresh backup and
  PITR coverage before destructive steps. (T)
- Destructive or irreversible migrations are a High trigger in this skill.

## 14. Tooling and CI lint
- Ordered, recorded migrations; a per-migration transaction opt-out (for
  CONCURRENTLY); a single-runner advisory lock (so a direct connection); a
  committed schema dump; separate pre- and post-deploy phases. (T)
- CI lint for dangerous DDL: missing CONCURRENTLY, volatile defaults, type
  changes, bare SET NOT NULL, missing `lock_timeout`. (T)

## 15. Cross-version compatibility and feature flags
- For N-1 code on the N schema: new columns are nullable or have defaults; no
  removed or renamed objects or enum values that old code reads; strict ORM
  mappings tolerate extra columns; triggers handle old-code writes. GitLab also
  requires a multi-release process for changing column defaults. (V)
- Flags gate the read/write switch, not the schema's existence. The schema must
  exist everywhere before the flag turns on, and the flag must be fully off
  before contract. Tie flag removal to the contract migration. (T)
- Migration tests (empty schema, previous schema, previous app version): owned
  by backend-testing-delivery.

## 16. Rehearsal at production scale
- Restore an anonymized snapshot, or generate production cardinalities. Time
  each migration and backfill batch, capture `pg_locks`, run under synthetic
  concurrent load to expose lock queues, and check for invalid indexes
  afterwards. (T)
- Never use real production personal data in lower environments without the
  controls in backend-security-privacy.

## 17. Lock impact (input to the risk tier)
This is not a separate tier scale: the tier comes from engineering-standards
and the High triggers in `../SKILL.md`. Use lock impact to decide whether a
statement is "destructive or irreversible" or "rewrites data". (T)
- Small: new table; nullable column; non-volatile default (PG 11+); `NOT VALID`
  constraints; CIC on a modest table.
- Medium: CIC on huge or hot tables; `VALIDATE` scans; backfills; tables with
  long-running readers.
- Large: rewrites or scans under ACCESS EXCLUSIVE (type change, volatile
  default, bare `SET NOT NULL`, `VACUUM FULL`); renames and drops; PK changes;
  partitioned parents; anything irreversible.
- Inputs: table size, write rate, lock level and duration, rewrite or scan,
  reversibility, readers' blast radius, replica and CDC impact, old-code
  compatibility.
- For large-impact steps: a written plan, a production-scale rehearsal,
  `lock_timeout`, an operator on watch, a quiet window and a fresh backup.
