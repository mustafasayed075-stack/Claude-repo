# Timeouts, retries and overload (backend-reliability-performance)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Client defaults are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

This file owns retry policy and where rate limits are enforced. Which limits
exist and how they are keyed (rate-limit design):
`backend-security-privacy/references/authorization-and-abuse.md`. Rate-limit
response headers: `backend-api-data/references/api-contracts.md`.

## Contents
1. Timeouts on every remote call
2. Choosing the value
3. Client and database defaults
4. Deadline propagation
5. The bimodal latency trap
6. When a total timeout is wrong
7. Retries: what and where
8. Backoff, jitter and budgets (attempts = 1 + retries)
9. Retry failure modes
10. Hedged requests and transactions
11. Circuit breakers and bulkheads
12. Load shedding, queues and degradation
13. Rate-limit enforcement and algorithms

## 1. Timeouts on every remote call
- Every remote call needs an explicit connect timeout and an overall request
  timeout: HTTP, gRPC, database, Redis, queue, DNS and TLS. Amazon: "set a
  timeout on any remote call, and generally on any call across processes even
  on the same box". (V)
- Why: a slow dependency otherwise holds threads, connections, memory and
  ports until the caller runs out of them. (V)

## 2. Choosing the value
- Pick an acceptable false-timeout rate (for example 0.1%) and use the
  matching downstream percentile (p99.9). (V)
- Over the internet, add worst-case network latency: critical for clients in
  Egypt reaching EU or US regions. When p99.9 is close to p50, add padding.
  Some timeout settings do not cover DNS or TLS; prefer the timeouts built into
  well-tested clients. (V for the AWS pitfalls, T for the Egypt note)
- A 20 ms timeout failed after every deploy because new connections needed a
  TLS handshake; Amazon fixed it by pre-connecting at startup
  (`health-shutdown.md`). (V)

## 3. Client and database defaults
- Python `requests` and Go's default `http.Client` have no default timeout;
  Node `fetch` (undici) defaults to 300 s, which is far too long. Set them
  explicitly: an explicit `timeout=`, `context.WithTimeout`, and
  `AbortSignal.timeout()` respectively. (T)
- Java: set connect and read timeouts, and HikariCP `connectionTimeout`. (T)
- Postgres: set timeouts per role or session; the docs say setting
  `statement_timeout` in postgresql.conf "is not recommended because it would
  affect all sessions" (V). The timeout settings themselves:
  `backend-api-data/references/postgres-operations.md`.

## 4. Deadline propagation
- Set an absolute deadline at the edge and pass the remaining budget
  downstream, minus a few hundred milliseconds for transit. Check the remaining
  time before each stage and propagate cancellation. Google's example: A sets
  30 s and spends 7 s, so B gets 23 s. (V)

## 5. The bimodal latency trap
- With 1,000 threads and 5% of requests hanging until a 100-second deadline,
  the frontend can serve only 19.6% of requests (an 80.4% error rate). Long
  timeouts on a partly failing dependency are worse than short ones. The exact
  figure comes from Google's SRE book and could not be re-confirmed: verify.
  (P)

## 6. When a total timeout is wrong
- Uploads, streams and exports need idle or progress timeouts rather than a
  total timeout. (T)
- Checkpointing work may continue past a deadline once it has written its
  checkpoint. (V)

## 7. Retries: what and where
- Retry only transient failures: network errors, 503, and 429 (honour
  `Retry-After`). Never retry 4xx validation errors. (V)
- Retry only idempotent or idempotency-keyed operations (key design:
  `backend-api-data/references/api-contracts.md`). (V)
- Retry at exactly one layer. Amazon: "for low-cost control-plane and
  data-plane operations, our best practice is to retry at a single point in the
  stack". (V)
- Why: three retries at each of three layers turn one user action into 64
  database attempts (4³); a five-deep stack with three retries per layer
  multiplies database load 243×. (V)
- Retry policy in shared clients or gateways is a High trigger in this skill.

## 8. Backoff, jitter and budgets (attempts = 1 + retries)
Convention used in all backend skills: attempts = 1 + retries. "At most 3
attempts" means the first try plus up to 2 retries. (T)
- Capped exponential backoff with jitter. Amazon also adds jitter "to all
  timers, periodic jobs, and other delayed work". (V)
- Google SRE: a per-request budget of up to three attempts, and a per-client
  retry ratio below 10%, which "reduces the growth to just 1.1x in the general
  case". A server-wide example: "only allow 60 retries per minute in a
  process". (V)
- AWS SDKs have limited retries with a local token bucket since 2016. (V)
- A common, unstandardised starting point: 3 attempts, 100 ms base, 30 s cap.
  (P)

## 9. Retry failure modes
- Retry storms keep a backend overloaded after load drops; to recover "you must
  dramatically reduce or eliminate the load on the frontends until the retries
  stop". (V)
- Retrying 4xx; retrying non-idempotent POSTs (double orders or charges);
  offline-sync clients that retry aggressively (Google names these as a cascade
  contributor); retries without jitter that line up in time. (V for the Google
  items, T for the rest)
- Mobile clients should back off with jitter and honour `Retry-After`
  (`performance-and-scaling.md`). (T)

## 10. Hedged requests and transactions
- Hedged requests suit read-only, latency-critical fan-out, provided
  cancellation is in place. (V)
- Never retry a single statement inside a lock-holding transaction; retry the
  whole transaction (serialization failures must be retried as a whole:
  `backend-api-data/references/postgres-operations.md`). (V)

## 11. Circuit breakers and bulkheads
- Amazon: breakers "introduce modal behavior into systems that can be difficult
  to test, and can introduce significant addition time to recovery"; it prefers
  local token-bucket retry limits. Default: timeouts and retry budgets first;
  add a breaker only around a dependency that fails slowly and has a cheap
  fallback. (V)
- Bulkheads: give each dependency or criticality level its own pool (threads,
  connections, consumers), so one slow dependency cannot drain a shared pool.
  (T)

## 12. Load shedding, queues and degradation
- Reject early and cheaply, for example a 503 when in-flight requests exceed a
  limit. (V)
- Keep request queues at "50% or less" of thread-pool size for steady traffic.
  LIFO or CoDel queueing avoids serving requests whose clients have already
  given up. (V)
- Graceful degradation serves cheaper results under load, but "the code path
  you never use is the code path that (often) doesn't work": exercise it
  regularly and alert when it activates. (V)
- Rate limiting alone cannot stop a cascade that has already started. (V)

## 13. Rate-limit enforcement and algorithms
- Where to enforce (Google SRE): at the reverse proxy, per IP or abusive
  client; at the load balancer, for global overload, dropping low-priority work
  such as background sync first; and per task. (V)
- With several replicas, enforce limits in a shared store (Redis with atomic
  Lua or `INCR` + TTL) or at the gateway: per-instance in-memory limits
  multiply by the replica count. Do not count rejected retries against the
  quota. (T)
- Algorithms: token bucket (bursts up to the bucket size plus a sustained rate;
  the usual default for API quotas); leaky bucket (smooth output); fixed
  window (cheap, but allows 2× bursts at window edges); sliding log (exact,
  memory-heavy); sliding window counter (a good approximation); concurrency
  limits (the best protection for expensive endpoints). (T)
