# Testing strategy (backend-testing-delivery)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

This file owns two-tenant tests and migration tests; other skills point here.
What counts as a breaking API change: `backend-api-data/references/api-contracts.md`.
Abuse cases from threat modelling:
`backend-security-privacy/references/authorization-and-abuse.md`.

## Contents
1. Principle and default shape
2. Test sizes
3. Test doubles
4. Test types
5. Migrations
6. Idempotency
7. Concurrency
8. Two-tenant authorization
9. Time
10. Jobs and queues
11. Webhooks
12. Payments
13. Test data and isolation
14. Determinism
15. Flaky tests
16. Coverage, mutation and property testing
17. What passing tests do not prove
18. Static analysis

## 1. Principle and default shape
- Spend effort where bugs live and where confidence per CI minute is highest.
  (T)
- Backend default ("honeycomb" or "trophy" shape): fast unit tests for pure
  logic (pricing, state machines, parsers); most weight on service-level
  integration tests with real Postgres, Redis and queue through the handler
  layer; a handful of end-to-end journeys; contract tests at service
  boundaries. (T)
- The Testing Trophy was scoped to JavaScript applications; the transferable
  idea is "mostly integration". The Test Pyramid: tests of different
  granularity, fewer at high levels. (P)
- When the default is wrong: CPU-bound pure libraries should be unit-heavy;
  legacy suites with 30-minute database setup should invest in template
  databases or snapshots first. (T)

## 2. Test sizes
- Google test sizes: small (no network or database, 60 s), medium (localhost,
  database allowed, 300 s), large (external systems, 900 s+). (V)

## 3. Test doubles
- Stub = canned answers; mock = verifies interactions; fake = a working
  lightweight implementation (in-memory repository, MinIO, a fake payment
  gateway). (V for the definitions)
- Prefer real, then fake, then stub, then mock. *Software Engineering at
  Google*: "A real implementation should be preferred over a test double";
  mock-heavy tests "required constant effort to maintain while rarely finding
  bugs". (V)
- Mock only third-party HTTP APIs (WireMock, MSW, `responses`, nock), time,
  randomness and UUIDs, and hard-to-provoke failures. (T)
- Never mock the database when testing database code. H2 or SQLite miss
  constraints, locking, JSONB, `ON CONFLICT`, isolation and migration
  behaviour; Testcontainers: in-memory substitutes "may not have all the
  features of your production service" and "delay the feedback cycle". (V)

## 4. Test types

| Type | Purpose | Small-team default |
|---|---|---|
| Unit | Pure logic, edge cases | Always; milliseconds each |
| Integration (real dependencies) | SQL, transactions, serialization, handlers | Always for database and queue code |
| Contract (Pact) | Independently deployed consumer and provider you own | Only with two or more such services |
| OpenAPI / property (Schemathesis, Dredd) | Provider matches spec; fuzzed inputs | Yes for public or partner APIs |
| End-to-end | Critical journeys | 5–20 tests |
| Smoke | Post-deploy "alive and wired" | After every deploy |
| Load | Capacity | Before launches or big changes |
| Chaos / game day | Failure handling, runbooks | Quarterly |

(V for Pact's fit; T for the rest)
- Pact docs: "most valuable… where you… control the development of both the
  consumer and the provider"; not good for public APIs, performance and load
  testing, or functional testing of the provider. For public APIs combine
  OpenAPI conformance and property-based fuzzing. (V)
- Load-test method: `backend-reliability-performance/references/performance-and-scaling.md`.

## 5. Migrations
This file owns migration tests; the migration rules themselves are in
`backend-api-data/references/schema-migrations.md`.
- Apply migrations from an empty database and from the previous release's
  schema. (T)
- Run the previous app version's tests against the new schema. (T)
- Prefer forward fixes over down-migrations; time each migration on a
  production-sized synthetic or masked dataset; lint with squawk or
  strong_migrations. (T)

## 6. Idempotency
- Same key twice, sequentially and concurrently → one side effect and an
  identical response. (T)
- Kill the worker mid-processing and assert that redelivery does not
  double-charge. (T)

## 7. Concurrency
- Real parallelism against the real database, with a barrier so workers start
  together; assert invariants (no oversell, the unique constraint holds), not
  timing. Repeat 50–100× in a dedicated job; inject pause hooks to force
  interleavings; run `go test -race` or ThreadSanitizer. (T)
- Races in automation escape normal tests: the October 2025 AWS outage came
  from a race between two DNS automation workers. (V)

## 8. Two-tenant authorization
This file owns two-tenant tests.
- For every resource endpoint: tenant A requesting tenant B's id must get 404
  (or 403 where existence is not secret; the rule is in
  `backend-security-privacy/references/authorization-and-abuse.md`). (T)
- Generate these tests from the route table, so a new endpoint cannot skip
  them. (T)

## 9. Time
- Inject a clock (`java.time.Clock`, freezegun or time-machine, Jest fake
  timers, a Go `func() time.Time`). (T)
- Test DST changes (Egypt reinstated DST in 2023; verify the current rules),
  month-end, leap day, and expiry ±1 s. (T)

## 10. Jobs and queues
- Test the handler as a function, plus one real-broker test of ack/nack,
  backoff, poison → DLQ and the visibility timeout. Queue rules:
  `backend-reliability-performance/references/messaging-and-jobs.md`. (T)

## 11. Webhooks
- Signature valid, invalid, expired and replayed; duplicate and out-of-order
  delivery; fast 2xx then asynchronous processing. (T)

## 12. Payments
- A small sandbox contract suite against the chosen gateway's test mode,
  recorded webhook fixtures, and never live keys in CI. (T)
- TODO(verify): how faithful any gateway's sandbox is (for example whether its
  test mode sends webhooks like production) is not established; check the
  chosen gateway's documentation before relying on sandbox results.

## 13. Test data and isolation
- Factories over fixtures (factory_boy, Fishery, Instancio, builders). (T)
- Isolation options: transaction rollback (fastest; breaks when code commits or
  uses several connections), truncation (simple, slower), or a database per
  worker / `CREATE DATABASE ... TEMPLATE` (best for parallel runs). Default: a
  database per worker plus rollback, truncation for tests that commit. (T)
- Parallelism: each worker gets its own database, Redis index or prefix, and
  queue names; isolated per-pipeline services prevent data conflicts. (V for
  the Testcontainers point, T for the rest)
- Never use production data in dev or CI: only synthetic or irreversibly masked
  data under a documented PDPL / GDPR basis. (T)

## 14. Determinism
- Logged fixed seeds; randomized order (pytest-randomly, `go test
  -shuffle=on`); no real network; no sleeps (poll with a timeout). (T)

## 15. Flaky tests
- Google (May 2016): "about 1.5% of all test runs reporting a 'flaky' result";
  "about 84% of the transitions we observe from pass to fail involve a flaky
  test"; "It is quite common to ignore legitimate failures in flaky tests".
  Causes: "concurrency, relying on non-deterministic or undefined behaviors,
  flaky third party code, infrastructure problems", plus order dependence,
  shared state, real clocks and sleeps. (V; extra causes P)
- Policy: detect pass-and-fail on the same commit; quarantine within 24 h with
  a ticket and an owner; fix or delete within about two weeks; never quarantine
  security, money or data-integrity tests without approval. (T; Google's
  auto-quarantine V)
- Retries hide bugs: many "flakes" are real races, and a test that fails 2% of
  the time shows up in 50 runs only about 64% of the time. (P)

## 16. Coverage, mutation and property testing
- Coverage shows what executed, not what was checked. Ratchet it; a practical
  gate is ≥80% line coverage on changed code, with exemptions. (T)
- Mutation testing (mutmut, Stryker, PIT) nightly or on critical modules
  (pricing, authorization). (T)
- Property-based testing (Hypothesis, fast-check, jqwik, `go test -fuzz`) for
  parsers, serializers, money math and state machines. (T)

## 17. What passing tests do not prove
- Correctness at production volume or concurrency, with real third parties,
  real config or old clients; the absence of security flaws; or that the
  assertions are meaningful. List these under "Not verified". (T)

## 18. Static analysis
- Pre-commit (under about 10 s, advisory because `--no-verify` bypasses it):
  formatter (ruff/black, prettier, gofmt, spotless), a fast linter, gitleaks,
  large-file checks. (T)
- CI (authoritative, required): full lint; type checks (mypy or pyright strict
  on new code, `tsc --noEmit` with `strict: true`, `go vet` + staticcheck,
  ErrorProne / SpotBugs / NullAway); SAST (Semgrep, CodeQL); dependency audit
  (pip-audit, osv-scanner, govulncheck); IaC scan (tflint, checkov, trivy
  config); hadolint; migration lint; the OpenAPI breaking-change diff. (T)
- Google's June 2025 null-pointer outage argues for null-safety tooling on
  critical paths (cause V; the conclusion is an inference). (P)
