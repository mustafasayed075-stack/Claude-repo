---
name: backend-reliability-performance
description: In a backend/server codebase, use when code calls other services or APIs, retries or times out, uses queues, background jobs, schedulers or webhooks, handles shutdown or health checks, defines SLOs, or when an endpoint is slow or must scale. Gives senior defaults with reasons for PostgreSQL, Redis and queue based services used over unreliable mobile networks. Not for schema design (backend-api-data) or tests and deploys (backend-testing-delivery).
---

# Backend Reliability and Performance

Senior defaults for timeouts, retries, overload, messaging, background jobs,
health checks, shutdown, SLOs and performance work. This skill sits under the
foundation: the `Router:` line and engineering-standards' "Risk, scope and
planning" set the risk tier first, and a High change goes through
`architecture-planning` before any default here is used to edit code.

Most production pain comes from unbounded work: calls without timeouts,
retries at every layer, and consumers that cannot tolerate duplicates.

## Decisions

Name the rule (for example "REL-6") when you apply it. Status: V verified
against a primary source, P partly verified, T judgement or secondary source;
word P and T items as "verify in your environment" or "judgement".

| # | Default | Why | Status |
|---|---|---|---|
| REL-1 | Every remote call (HTTP, DB, Redis, queue, DNS, TLS) has an explicit connect timeout and request timeout, set from the downstream p99.9 at an accepted false-timeout rate | A slow dependency holds threads and connections until you run out | V |
| REL-2 | Never rely on client defaults: Python `requests` and Go's default `http.Client` have no timeout; Node `fetch` (undici) waits 300 s | Missing or far too long | T |
| REL-3 | Set an absolute deadline at the edge and pass the remaining budget downstream | Servers stop working for callers who gave up | V |
| REL-4 | Prefer short timeouts on a partly failing dependency | Hung requests eat all capacity | V |
| REL-5 | Retry only transient failures (network errors, 503, 429 with `Retry-After`) and only idempotent or idempotency-keyed operations | Retrying 4xx or bare POSTs adds load and double charges | V |
| REL-6 | Retry at one layer only, with capped exponential backoff, jitter and a budget: at most 3 attempts (attempts = 1 + retries), retries under about 10% of requests | Retries multiply per layer | V |
| REL-7 | Circuit breakers only around slow-failing dependencies with a cheap fallback; timeouts and retry budgets come first | Breakers add hard-to-test modal behaviour | V |
| REL-8 | Shed load early and cheaply (503 at an in-flight limit); keep queues short; exercise degraded paths and alert when they activate | Overload turns into crash loops | V |
| REL-9 | Enforce rate limits at the proxy or gateway; with several replicas, use a shared store | Per-instance limits multiply by replica count | T |
| REL-10 | Assume at-least-once delivery: every consumer dedupes by a unique message id or writes idempotently | End-to-end exactly-once does not exist | V |
| REL-11 | Never commit and then publish as two steps: write an outbox row in the same transaction and relay it; one relay (or one lock per partition key) when order matters | Dual writes lose or duplicate events | V/T |
| REL-12 | Webhook receivers: verify (backend-security-privacy), store the event id under a unique constraint, return 2xx fast, process asynchronously | Senders retry, duplicate and reorder | T |
| REL-13 | Avoid two-phase commit; idempotent saga steps with compensations only when data truly spans systems | Blocking coordinators hurt availability | T |
| REL-14 | Queues: visibility timeout above worst-case processing (or a heartbeat); a DLQ after about 3–5 receives; permanent errors straight to the DLQ; alert on DLQ depth and oldest-message age | Duplicates and silent poison messages | P |
| REL-15 | Key ordered work by the entity whose order matters | Global ordering kills parallelism | T |
| REL-16 | Scheduled jobs run once across replicas: a dedicated scheduler or a job-run row unique on (job, scheduled time); the job stays idempotent | Cron inside every pod runs N times | T |
| REL-17 | Postgres as a queue is fine at small to medium scale with `SKIP LOCKED`; Redis-backed queues get their own `noeviction` instance | Fewer moving parts | V/T |
| REL-18 | Liveness checks the process only, never the database; readiness checks local readiness; shut down gracefully on SIGTERM | A database blip must not restart or unready the fleet | V |
| REL-19 | Fight herds with jitter on timers and TTLs, single-flight, stale-while-revalidate and admission control after outages | Cold caches plus retry backlogs keep failures going | V |
| REL-20 | SLOs: 2 to 4 SLIs on user journeys, measured at the client or edge for mobile users | Server-side metrics hide last-mile failures | T |
| REL-21 | Measure first: p50/p95/p99 per endpoint, never averages; open-model load tests on production-like data, then a soak run | Closed-loop tools under-report the tail | V/T |
| REL-22 | Fix bottlenecks in likelihood order, starting with indexes and query plans | Fix the likeliest first | T |
| REL-23 | Keep CPU work off Node's event loop and Python's GIL path; bound database concurrency under Java virtual threads; bound Go fan-out | Runtime-specific stalls | P |
| REL-24 | HTTP and mobile: compress text, correct caching headers, small payloads, delta sync, resumable uploads, client backoff with jitter that honours `Retry-After` | Mobile bandwidth and flaky links | T |
| REL-25 | Scale vertically first; keep services stateless; shard last and by tenant; autoscale I/O-bound services on in-flight requests or queue age | Cheapest real capacity | T |
| REL-26 | No premature microservices, no Kafka for a CRUD app, no third-party calls inside request paths or database transactions | Complexity without evidence | T |

## Ask before choosing

Ask the user only what changes the design; otherwise state the assumption.

- What happens if this runs twice, zero times, or half-way?
- What is the end-to-end latency budget, and what is each dependency's p99?
- Is the operation idempotent, or does it carry an idempotency key?
- Where are retries already happening (client SDK, gateway, service, worker)?
- What RPO and RTO does this data need?
- What data volume is expected in 12 months, and how big is the largest tenant?
- Who else shares this resource (database connections, Redis, the queue)?
- Which SLI or alert tells us it is broken, and how do we roll back?
- Is this complexity justified by a measurement, or by scale we may never reach?

## High-risk triggers (this skill's additions)

On top of the foundation's High triggers, these are High:

- retry or timeout policy in shared clients or gateways;
- idempotency logic for payments or orders;
- outbox or consumer changes on critical flows;
- DLQ or visibility changes on critical queues;
- failover, replication or backup configuration;
- sharding or partitioning existing tables;
- autoscaling limits;
- global config or flag pushes.

If a trigger above applies, write a `Risk: High` line before editing. Use the
format engineering-standards defines (`Risk: High - high trigger: <trigger>;
change: <new|restores|preserves>; <reason>`), load `architecture-planning`,
write its complete plan and stop for explicit approval. Any other new or
changed behavior here is Medium under engineering-standards: write
`Plan (Medium):` before the first edit.

## References: when to open each

Open a reference only when the task needs it.

- `references/timeouts-retries-overload.md`: choosing timeouts and deadlines, client timeout settings, retry policy and budgets, hedged requests, circuit breakers, bulkheads, load shedding, degradation, rate-limit enforcement and algorithms.
- `references/messaging-and-jobs.md`: delivery semantics, consumer dedupe, the outbox and its relay, sagas, queue settings and DLQs, ordering, schedulers and cron, Postgres or Redis as a queue, job libraries, webhook delivery, email/SMS/push delivery.
- `references/health-shutdown.md`: liveness, readiness and startup probes, the SIGTERM sequence, warm-up, rolling deploys, thundering herds and metastable failures, long-lived connections.
- `references/performance-and-scaling.md`: measuring (percentiles, load tests, Little's law, profilers), the bottleneck checklist, runtime notes, HTTP and mobile tuning, scaling decisions, cost, anti-patterns.
- `references/slo-and-incidents.md`: choosing SLIs and SLOs, error budgets, what a target implies for the design, and lessons from major outages.
- `SOURCES.md`: where each default comes from and how strong the source is.

## Backend common rules

These rules are the same, word for word, in all four backend skills.

- **Risk tiers.** Use the foundation's tiers exactly as engineering-standards defines them ("Risk, scope and planning"); a backend skill adds no tiers. Low restores an established contract (with evidence), refactors without changing behavior, or touches only tests or docs; any new or changed behavior is Medium and gets a `Plan (Medium):`; High goes through architecture-planning and stops for explicit approval. A trigger in a backend skill's High list makes the change High. When unsure, rate up.
- **Explain every decision.** When you apply a rule, name it and give its reason in one line. When you depart from a default, say why and what the alternative was.
- **Honest status.** V = verified against a primary source; P = partly verified; T = judgement or secondary source. Word every P and T item as "verify in your environment" or "judgement", never as settled fact. Version-sensitive facts are "as of Nov 2026; check your version".
- **Egypt first.** Assume users in Egypt on unreliable mobile networks, personal data under Egypt Law 151/2020, EGP money, Cairo time with DST (store IANA zones, never a fixed offset), and cloud regions that may be far away (measure latency; do not assume). Treat any foreign-hosted service as a possible cross-border transfer.
- **No named payment gateways.** Keep payment rules generic; for gateway-specific behavior, check the chosen gateway's own documentation.
- **Not verified.** End every task with a short "Not verified" list (for example production-scale data, real third parties, load, multiple instances, failure injection).
