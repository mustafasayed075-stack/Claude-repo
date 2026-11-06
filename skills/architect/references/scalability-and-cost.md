# Scalability, resilience and cost (architect)

Status tags: V verified on a primary page, P partly verified or
experience-based, T judgement (verify in your environment). Sources:
`../SOURCES.md`.

The mechanics (timeouts, retries, queues, scaling, SLO targets) belong to
backend-reliability-performance; this file covers the architectural
decisions.

## Contents
1. ARCH-16 Stateless processes
2. ARCH-17 Scaling order
3. ARCH-18 Async by default for slow work
4. ARCH-19 Static stability
5. ARCH-20 Capacity estimate
6. ARCH-40 Cost in EGP
7. ARCH-43 Failure-mode table
8. ARCH-45 Recovery level
9. ARCH-46 Observability by design

## 1. ARCH-16 Stateless processes (V)
- App processes are stateless (Twelve-Factor processes page). State lives in
  the database, cache or object storage; no sticky sessions.
- Exception: real-time connections are process-bound. Their state must be
  recoverable (the client reconnects and resumes from the last seen id) and is
  never the source of truth.

## 2. ARCH-17 Scaling order (T; the "load test to the breaking point" part is V)
Measure first and find the bottleneck (profile, slow-query log, load test).
Then, in the usual order, skipping steps the measurement does not point to:
1. Fix queries, indexes and N+1.
2. Scale the machine up.
3. Add stateless app copies.
4. Move slow work async (ARCH-18).
5. Cache specific hot reads, with a stated TTL and an invalidation owner.
6. Add a read replica for read-heavy or reporting paths that tolerate lag.
7. Partition or shard only with an ADR.

The order is by typical cost and blast radius for a small team, not a law.
Mechanics: Rules:
`backend-reliability-performance/references/performance-and-scaling.md` and
`backend-reliability-performance/references/timeouts-retries-overload.md`.

## 3. ARCH-18 Async by default for slow work (T)
- Slow or unreliable work (email, SMS, files, third parties) goes async;
  synchronous only for what the user waits on.
- Rules: `backend-reliability-performance/references/messaging-and-jobs.md`.

## 4. ARCH-19 Static stability (V)
- Design so that recovery needs no new resources or control-plane action:
  cache last-known-good config and credentials and keep serving on failure.
- No fallback path that does not run in normal operation. If two paths are
  needed, run both continuously.
- Cross-check: an offline mode (ARCH-29) or a second AI provider is a fallback.
  It is allowed only if it is the normal path (local-first writes) or is
  exercised continuously.

## 5. ARCH-20 Capacity estimate (T)
- The brief has a one-paragraph capacity estimate: requests per second and
  data growth. Cost: ARCH-40.

## 6. ARCH-40 Cost in EGP (T; the cost pillar itself is V)
The brief states:
- a monthly cost range in EGP;
- the foreign-currency share of that cost;
- the unit cost per active user (and per AI request);
- a budget alarm;
- a review trigger (an FX move or 2× growth).

Metrics detail: Rules:
`backend-reliability-performance/references/performance-and-scaling.md`.

## 7. ARCH-43 Failure-mode table (P)
One row per container and external dependency:

| Component | What fails | User impact | How detected | Response (fail fast, queue, degrade) | Runs in normal operation? (ARCH-19) |
|---|---|---|---|---|---|
| Example: SMS provider | Timeouts | OTP not delivered | Delivery-failure rate | Queue and retry; show "resend" | Yes: the queue is the normal path |

## 8. ARCH-45 Recovery level (T)
- The brief states the target data-loss window and recovery time per store.
- Default: a single region with automated backups and a restore that is
  actually tested.
- Mechanics: Rules: `backend-api-data/references/postgres-operations.md` and
  `backend-reliability-performance/references/slo-and-incidents.md`.

## 9. ARCH-46 Observability by design (T; boundary only)
Per container, name:
- the 1 to 3 user-facing signals that mean "working";
- where the request id is created and how it is propagated;
- which events are business-auditable.

Instrumentation: Rules: `backend-testing-delivery/references/observability.md`.
Targets and alerts: Rules:
`backend-reliability-performance/references/slo-and-incidents.md`. Log
content: Rules: `backend-security-privacy/references/data-protection-logging.md`.
