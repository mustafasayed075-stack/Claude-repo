# Sources (backend-api-data)

Condensed ledger for this skill, drawn from the build research reports (Batch
A: API, database and migrations; Batch C: reliability, storage and
performance; Batch B for a few security-adjacent items) and the reviewed
backend skills spec (source ledger and verification pass). The full per-claim
ledgers with URLs live in those reports, which are not part of this
repository. Payment providers are cited generically.

Type: primary (official docs, standards, vendor post-mortems) or secondary
(blogs, summaries, author knowledge). Status: V verified, P partly verified,
T judgement or secondary.

| Source | Adopted | Ignored and why | Type | Status |
|---|---|---|---|---|
| RFC 9457 (problem details) | Members, media type, registry, `about:blank`, batch caveat | Nothing material | Primary | V |
| RFC 9110 (HTTP semantics) | Idempotency definition, 401/409/412/422/429 semantics | Conditional-request detail came from author knowledge | Primary | V |
| RFC 5789, RFC 7396, RFC 6902 (PATCH, Merge Patch, JSON Patch) | PATCH semantics, null and array behavior | RFCs not fetched; consistent secondary summaries only | Secondary | P |
| RFC 9562 (UUIDs) | UUIDv7 layout, recommendation, optional monotonicity, time leak | RFC not fetched directly | Secondary | V (spec pass) |
| RFC 9745, RFC 8594 (Deprecation, Sunset) | Header format and ordering rule | Full texts not fetched | Primary via enrichment | P |
| Google AIP-158, 180, 181, 149, 151; AIP issue #1428 | Pagination rules, breaking-change list, stability levels, contested page-size coercion | Proto-only details de-emphasised | Primary | V |
| IETF Idempotency-Key draft | 400/409/422 mapping, fingerprint idea | An Internet-Draft, not a standard; the latest revision (-07 or -08) is unsettled | Primary, non-final | P |
| IETF RateLimit headers drafts | Field evolution, `Retry-After` alignment | Latest revision not fetched | Primary, non-final | P |
| A large payment provider's public API docs (idempotency) | Key retention of at least 24 h in its v1 API, replay rules, parameter comparison | Provider-specific behavior beyond the general pattern; the provider is not named in this skill | Primary vendor | V |
| A large payments company's engineering blog (online migrations, 2017) | Four-step dual-write migration shape | Retellings used only as confirmation | Recognised practitioner | V |
| PostgreSQL 16–18 docs (isolation, serialization failures, ALTER TABLE, explicit locking, CREATE INDEX, client settings, connections, vacuum, BRIN, SELECT locking) | Isolation and retry rules, lock levels, NOT VALID / VALIDATE, rewrites, CIC behavior, timeouts and their defaults, `max_connections`, autovacuum trigger and PG 18 cap, `SKIP LOCKED` quote | Tuning numbers that are not in the docs; no FIFO lock-queue statement found (flagged) | Primary | V |
| PostgreSQL commits and docs list (PG 11 fast default, PG 18 NOT NULL NOT VALID, combined-ALTER trap) | `attmissingval`, NOT NULL NOT VALID, separate DROP CONSTRAINT | Nothing material | Primary | V |
| PostgreSQL 18 release notes | `uuidv7()`, `uuidv4()` alias | Unrelated features | Primary | V |
| PostgreSQL wiki "Number Of Database Connections"; HikariCP docs | Pool-size starting formula | Whether it is per pool or fleet-wide is not stated | Community / secondary | P |
| PgBouncer changelog (1.21, 1.24.0) and issue #976 | Transaction-mode limits, protocol-level prepared statements, default of 200 | ORM anecdotes | Primary + secondary | V |
| GitLab migration docs (avoiding downtime, style guide) | Drop and rename procedures, post-deploy migrations, schema-cache failure, background migrations, bigint PK procedure | Third-party mirrors treated as copies | Primary practitioner | V |
| GitLab 2017 database outage post-mortem | Backups that were never restored | Restore-time figures (secondary only) | Primary | V |
| Amazon S3 docs (presigned URLs, limits) | 7-day maximum, role-credential expiry, ~6 h instance profiles, `s3:signatureAge`, 48.8 TiB objects | Multipart limits and presigned POST conditions came from memory | Primary | V (multipart, presigned POST: T/P) |
| redis.io (Redis Software eviction), GCP Memorystore, ElastiCache docs | Eviction policies and per-product defaults | OSS defaults seen in secondary sources, then confirmed in the spec pass | Primary | V |
| AWS Builders' Library (timeouts, retries, jitter) | Jitter on all timers and TTLs | Retry policy itself (owned by the reliability skill) | Primary | V |
| OWASP API Security Top 10 (2023) | API3 and API4 names and scope | Full text not fetched | Primary via enrichment | P |
| Batch B security research (NFC normalization, keyed blind index, E.164 phone storage) | Normalization before hashing, phone storage and lookup | Security rules themselves (owned by the security skill) | Mixed | P |
| PostgreSQL row-level security behavior | Owner bypass, FORCE, BYPASSRLS, `security_invoker`, `SET LOCAL` scope | From author knowledge; not fetched | Secondary | T |
| Practitioner posts (lock queues, fast defaults, CIC, autovacuum, sizing) | Corroboration only | Specific timeout numbers treated as heuristics; low-quality posts ignored | Secondary | P |
| MySQL 8.4 manual (online DDL) | Not adopted | This skill targets PostgreSQL | Primary | n/a |
| Microsoft REST API Guidelines | Not adopted | Not retrieved during research | n/a | n/a |
| Owner confirmation (review of stage 1) | Egypt's IANA zone id is `Africa/Cairo` | Not in the research reports | Owner | V |

## Notes on SKILL.md rows

Source commentary kept out of the decision table:

- API-2: the pagination rule follows Google AIP-158.
- API-3: the Idempotency-Key header spec is an IETF Internet-Draft, not an
  RFC; check Datatracker for the current revision (status P for the draft).
- API-10: the lock-queue pile-up is documented by practitioners; the
  PostgreSQL docs define `lock_timeout` but do not describe the queue effect.
- API-16: the PostgreSQL wiki / HikariCP pool formula is only a starting
  point; whether it applies per pool or fleet-wide is not settled.
