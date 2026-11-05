# Performance and scaling (backend-reliability-performance)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Runtime facts are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

Query-level work (indexes, EXPLAIN, N+1, keyset pagination) and the connection
budget: `backend-api-data/references/postgres-operations.md`. Load-test
placement in the pipeline: backend-testing-delivery.

## Contents
1. Measure first
2. Load testing
3. Models and laws
4. Profilers
5. Bottlenecks in likelihood order
6. Runtime notes
7. HTTP level
8. Mobile-friendly reliability
9. Region and CDN
10. Scaling decisions
11. Cost
12. Anti-patterns

## 1. Measure first
- Report p50, p95, p99 and max per endpoint, never averages: averages hide
  bimodal tails, and in Google's 5%-hanging example the tail consumes all
  capacity (`timeouts-retries-overload.md`). (V)
- Keep a before/after baseline (p95/p99, query counts) for every performance
  change. (T)

## 2. Load testing
- Coordinated omission: closed-loop load generators stop sending while the
  system stalls, so they under-report the tail. Use open-model tools such as
  wrk2 or k6's constant-arrival-rate executor. (T)
- Use production-like volumes (a 1,000-row table hides every missing index), a
  realistic request mix, and exclude warm-up. Ramp up to find the knee, then
  soak for hours to catch leaks. (T)
- Test behaviour under overload: Google calls load testing to failure the most
  important overload exercise. (V)
- Tools: k6, Locust, wrk2, Gatling. (T)

## 3. Models and laws
- USE (Utilization, Saturation, Errors) for resources; RED (Rate, Errors,
  Duration) for services. (T)
- Little's law: L = λ × W. Google's example: 1,000 QPS × 0.1 s = 100 busy
  threads. Use it to size pools and to explain why latency spikes exhaust them.
  (V)
- Amdahl's law: parallelising 20% of the work gives at most 1.25×. (T)

## 4. Profilers
- Go pprof; Java async-profiler or JFR; Python py-spy; Node `--cpu-prof` or
  clinic.js. Flame graphs for CPU, allocation profiles for GC. (T)

## 5. Bottlenecks in likelihood order
Judgement; fix the likeliest first. (T)
1. Missing or wrong indexes and bad plans: stale statistics, functions on
   indexed columns, implicit casts.
2. ORM N+1 queries.
3. Unbounded result sets.
4. Pool exhaustion, including connections held during remote calls. Never keep
   a database transaction open across an HTTP call.
5. Chatty sequential service calls.
6. Synchronous work that belongs in a queue: email, PDFs, webhooks, images.
7. Hot-row lock contention (for example counters): use sharded or async
   aggregation.
8. Serialization cost: big JSON, hydrating unused ORM columns.
9. Memory and GC: unbounded in-process caches, leaked listeners, the JVM GC
   death spiral (a verified example in Google's SRE book).

## 6. Runtime notes
- Node: CPU work (huge JSON, crypto, regex backtracking, sync fs) blocks the
  event loop for every request. Monitor event-loop lag and use worker threads.
  The libuv threadpool defaults to 4 (`UV_THREADPOOL_SIZE`). (P)
- Python: the GIL serialises CPU-bound bytecode. Use processes for CPU work and
  async for I/O; each worker process has its own pool. Free-threaded builds are
  supported from 3.14. (P)
- Java: virtual threads (21) remove thread scarcity but not database-connection
  scarcity; bound concurrency with semaphores. They pin on `synchronized`, which
  is fixed in JDK 24, so prefer JDK 24 or later for virtual threads. (P)
- Go: bound goroutine fan-out with worker pools and set `GOMEMLIMIT` in
  containers. GOMAXPROCS is container-aware from Go 1.25 (spec review note;
  verify on your version). (P)

## 7. HTTP level
- Compression: gzip or brotli for text responses over about 1 KB; never for
  images or video. Watch for BREACH when secrets share a compressed response
  with attacker-controlled input. (T)
- Caching headers (RFC 9111): `Cache-Control: private` for per-user data;
  `public, max-age` with hashed filenames for static assets; `no-store` for
  sensitive data; `ETag` + `If-None-Match` → 304 to save mobile bandwidth;
  `stale-while-revalidate` (RFC 5861); a correct `Vary`. Private data never goes
  into shared caches. (T)
- Connections: reuse pooled keep-alive clients; creating a client per request
  burns TLS handshakes. HTTP/2 multiplexes requests, and HTTP/3 (QUIC) helps on
  lossy mobile links. (T)
- Limits: cap body size, page size and array lengths, and set slow-client
  timeouts. (T)

## 8. Mobile-friendly reliability
For users in Egypt on unreliable mobile networks: (T)
- idempotency keys on every mutation (backend-api-data);
- client backoff with jitter that honours `Retry-After`;
- small payloads via field selection, pagination and compression;
- delta sync (`?since=cursor`);
- resumable uploads (multipart: backend-api-data);
- client-generated UUIDv7 for offline creation;
- version numbers with 409 on stale writes;
- duplicates treated as normal, not exceptional.

## 9. Region and CDN
- In-region edge PoPs cut TLS round-trips. (T)
- Choosing between Middle East regions (for example AWS me-central-1 in the UAE
  or me-south-1 in Bahrain) and Europe (Frankfurt, Milan) is a latency, cost
  and residency trade-off: measure latency from your users; do not assume.
  Any region outside Egypt may be a cross-border transfer
  (backend-security-privacy). (P)

## 10. Scaling decisions
- Scale vertically first: a bigger Postgres plus an index fix outruns most
  re-architectures. (T)
- Stateless services: no local sessions, local-disk uploads,
  coherence-dependent in-memory caches or unelected in-process schedulers.
  Sticky sessions hide these problems and then break on deploys. (T)
- Read scaling path: cache → lag-aware replicas → materialised views or read
  models. (T)
- Sharding: delay it; exhaust vertical scaling, partitioning, archiving and
  replicas first. If you must shard, shard by tenant. Sharding or partitioning
  existing tables is a High trigger in this skill. (T)
- Autoscaling signals: CPU for CPU-bound services; in-flight concurrency or
  queue age for I/O-bound ones. Max replicas × pool size must stay within the
  database connection budget (backend-api-data). Autoscaling limits are a High
  trigger in this skill. (T)

## 11. Cost
- For small teams, egress, NAT gateways, cross-AZ traffic and idle capacity
  often outweigh compute. Budget alerts and tagging: backend-testing-delivery.
  (T)

## 12. Anti-patterns
- Premature microservices (distributed monoliths and dual writes); Kafka for a
  CRUD app; caching to hide a bad query; premature sharding; never inspecting
  ORM-generated SQL; optimising without a profile; retries without timeouts or
  budgets; third-party calls inside request paths and database transactions.
  (T)
