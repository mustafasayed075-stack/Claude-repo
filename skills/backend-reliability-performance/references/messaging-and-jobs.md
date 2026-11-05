# Messaging and jobs (backend-reliability-performance)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Broker defaults are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

This file owns webhook delivery, retries and dedupe. Webhook signing and
verification: `backend-security-privacy/references/authorization-and-abuse.md`.
The webhook payload contract and API idempotency keys:
`backend-api-data/references/api-contracts.md`. Redis eviction:
`backend-api-data/references/caching-and-files.md`.

## Contents
1. Delivery semantics
2. Consumer dedupe and side effects
3. Transactional outbox
4. Sagas vs two-phase commit
5. Queue settings (SQS) and DLQs
6. Poison messages and the redrive runbook
7. Ordering and lag
8. Scheduled jobs on several replicas
9. Postgres or Redis as a queue
10. Job libraries
11. Webhooks: receiving and sending
12. Email, SMS and push delivery
13. GraphQL subscriptions and WebSockets

## 1. Delivery semantics
- Networks give at-most-once or at-least-once delivery. Exactly-once holds only
  inside one bounded system. (V)
- Kafka's idempotent producer dedupes per partition and is the default since
  3.0 (`enable.idempotence` true). Transactional exactly-once in Kafka also
  needs `transactional.id` plus `read_committed` consumers. Side effects
  outside Kafka still need consumer-side dedupe. (V; `read_committed` P)

## 2. Consumer dedupe and side effects
- Every consumer dedupes with a unique message id inserted in the same
  transaction as its effect, when the effect is in your own database. (V)
- External effects (a charge, an SMS) cannot share that transaction: record the
  intent first, pass an idempotency key to the provider, and reconcile
  afterwards. (T)
- Or make writes naturally idempotent: upserts, `UPDATE ... WHERE version = n`.
  (T)

## 3. Transactional outbox
- Never commit and then publish as two independent steps. Write the event to an
  outbox table in the same transaction. microservices.io lists the benefits: no
  2PC, messages "sent if and only if the database transaction commits", and
  ordering preserved. (V)
- Relay options: a polling publisher works with any SQL database, though
  ordering is tricky; transaction-log tailing (CDC, for example Postgres WAL via
  Debezium) needs database-specific tooling and makes it "tricky to avoid
  duplicate publishing". (V)
- Default: a polling publisher using `FOR UPDATE SKIP LOCKED`; move to CDC only
  if you already run Kafka Connect or need lower latency. (P)
- Ordering: several relays using `SKIP LOCKED` break event order. When order
  matters, run a single relay, or take one lock per partition key. (T)
- The relay delivers at least once, so consumers must dedupe (section 2). (V)
- Outbox or consumer changes on critical flows are a High trigger in this
  skill.

## 4. Sagas vs two-phase commit
- Avoid 2PC/XA across services and brokers: the coordinator blocks and
  availability suffers. Use sagas with compensating actions and idempotent
  steps when data truly spans systems. (T)
- If all the data lives in one Postgres, a local transaction beats any saga.
  Do not split the database and then need sagas. (T)

## 5. Queue settings (SQS) and DLQs
- SQS visibility timeout: default 30 s, range 0 s to 12 h; extending it does
  not reset the 12-hour cap. Set it above worst-case processing time, or
  heartbeat with `ChangeMessageVisibility`. Duplicates are still possible
  within the window. (V)
- SQS retention: default 4 days, configurable from 1 minute to 14 days. (V)
- DLQ `maxReceiveCount` is commonly 3–5; a value of 1 dead-letters healthy
  messages after a single transient failure. (P)
- On standard queues, DLQ messages keep their original enqueue time (FIFO
  queues reset it), so make DLQ retention longer than the source queue's. (P)
- DLQ or visibility changes on critical queues are a High trigger in this
  skill.

## 6. Poison messages and the redrive runbook
- Send permanent errors (validation, schema) straight to the DLQ; retry
  transient ones with backoff. (T)
- Alert when DLQ depth is above 0, and keep a redrive runbook: inspect a
  sample, fix the cause, redrive in small batches, watch the consumer. (T)

## 7. Ordering and lag
- Standard SQS does not guarantee order. Kafka orders within a partition only,
  so key by the entity whose order matters (for example `account_id`). Global
  ordering kills parallelism. (T)
- Alert on the age of the oldest message, not just queue depth. (T)

## 8. Scheduled jobs on several replicas
A scheduler inside every pod runs the job N times. Options, in order of
preference:
1. A job-run row with a unique constraint on (job, scheduled_time): the insert
   that wins runs the job. (T)
2. A dedicated scheduler, for example a Kubernetes CronJob with
   `concurrencyPolicy: Forbid`. (T)
3. A transaction-level advisory lock (`pg_try_advisory_xact_lock`) taken inside
   a transaction or on a direct connection. Never a session-level advisory lock
   behind PgBouncer transaction mode. (T)

The job itself must stay idempotent, because leaders can overlap during
failover. Add consistent per-host jitter to schedules. (V for jitter, T for the
rest)

## 9. Postgres or Redis as a queue
- Postgres as a queue is fine at small to medium scale with `SKIP LOCKED`. The
  docs: it "provides an inconsistent view of the data, so this is not suitable
  for general purpose work, but can be used to avoid lock contention with
  multiple consumers accessing a queue-like table". Watch dead-tuple bloat on
  the jobs table. (V)
- Redis-backed job queues use `noeviction` on a Redis instance separate from
  the cache. (P)

## 10. Job libraries
- BullMQ, Sidekiq and Celery are Redis-backed job queues: they inherit the
  separate `noeviction` instance rule (section 9). (P)
- Whatever the library: idempotent handlers, a bounded retry count with backoff
  (attempts = 1 + retries; `timeouts-retries-overload.md`), a DLQ or dead set
  with an alert, actor and tenant carried in the job and authorization
  re-checked when it runs (backend-security-privacy). (T)
- TODO(verify): library-specific defaults (retry counts, visibility or
  acknowledgement behavior, at-least-once guarantees) are not covered by the
  research reports; check each library's documentation.

## 11. Webhooks: receiving and sending
- Receiving: verify the signature (backend-security-privacy), store the event
  id under a UNIQUE constraint in the same transaction as its effect, return
  2xx fast, and process asynchronously; keep the handler idempotent. (T)
- Events arrive duplicated and out of order: large providers commonly do not
  guarantee order and retry failed deliveries for days. Treat events as hints:
  re-fetch the object or compare versions, and reconcile periodically. (P)
- Sending: a transactional outbox, at-least-once delivery, a per-endpoint
  circuit breaker, a DLQ and a replay API; auto-disable failing endpoints and
  apply per-tenant delivery quotas. (T)
- Mistake: sending inside the database transaction, or after commit without an
  outbox: you announce rollbacks or lose events. (T)

## 12. Email, SMS and push delivery
- Treat sends as external effects: record the intent in the outbox, send from a
  worker, dedupe by message id, retry only transient failures (section 2). (T)
- Monitor delivery failures as alerts: GitLab's 2017 backup-failure emails were
  rejected by DMARC, so nobody saw them. (P)
- OTP and verification sends are also an abuse surface (SMS pumping, spend
  caps): backend-security-privacy. (T)
- TODO(verify): SPF, DKIM and DMARC setup, email provider failover, push
  device-token lifecycle (expiry, invalidation), and OTP delivery over
  messaging apps are not covered by the research reports.

## 13. GraphQL subscriptions and WebSockets
- TODO(verify): the reports have no facts on delivery guarantees, backpressure
  or reconnection for GraphQL subscriptions or WebSocket push. Apply the
  at-least-once and dedupe rules above to anything the client must not miss,
  and keep a polling or delta-sync fallback (`performance-and-scaling.md`). (T)
