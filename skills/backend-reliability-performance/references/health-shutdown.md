# Health checks and shutdown (backend-reliability-performance)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Kubernetes defaults are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

Container entrypoints and signal handling inside images (exec form, an init
process): backend-testing-delivery.

## Contents
1. Probe semantics
2. Probe design
3. Shutdown sequence on SIGTERM
4. Warm-up
5. Rolling deploys
6. Thundering herds and stampedes
7. Metastable failures
8. Long-lived connections

## 1. Probe semantics
- A failed liveness probe restarts the container. A failed readiness probe
  removes the pod from Service endpoints. A startup probe delays both until it
  succeeds. (V)
- The Kubernetes docs warn: "Incorrect implementation of liveness probes can
  lead to cascading failures". (V)

## 2. Probe design
- Liveness checks only that the process can make progress: never the database
  or another shared dependency. (V)
- Readiness may check local readiness (config loaded, pools created, warm-up
  done). Checking a shared dependency in readiness turns a database blip into
  "all pods unready", which is a total outage. (P)
- A deep dependency check belongs to an external synthetic monitor, not to a
  probe (monitoring: backend-testing-delivery). (T)

## 3. Shutdown sequence on SIGTERM
1. Fail readiness.
2. Keep serving for a few seconds. Endpoint removal starts at the same time as
   shutdown, not before it, so traffic still arrives briefly.
3. Stop accepting new work (requests, queue messages, jobs).
4. Drain in-flight work within the grace period (`terminationGracePeriodSeconds`,
   default 30 s).
5. Nack in-flight messages you cannot finish, so they are redelivered.
6. Close pools and connections.

(V for the order and the default per the spec's verification pass)

## 4. Warm-up
- Pre-establish connections before taking traffic. Amazon's 20 ms timeout
  failed after every deploy because new connections needed a TLS handshake;
  pre-connecting at startup fixed it. (V)
- Use a startup probe for slow starts instead of loosening liveness. (T)

## 5. Rolling deploys
- Old and new versions run side by side, so schemas and message formats must
  be compatible in both directions (schemas:
  `backend-api-data/references/schema-migrations.md`). (T)
- Consumers must accept messages from both the old and the new producer
  version during the rollout. (T)

## 6. Thundering herds and stampedes
- Common triggers: synchronised cron, cache expiry, reconnect storms, mobile
  clients retrying on the minute. (T)
- Fixes: jitter on timers, TTLs and schedules; single-flight request
  coalescing; stale-while-revalidate; probabilistic early expiry; admission
  control while caches warm. (T; jitter V)
- Cache patterns themselves:
  `backend-api-data/references/caching-and-files.md`.

## 7. Metastable failures
- Bronson et al. (HotOS 2021) define them as failures in open systems where "a
  trigger causes the system to enter a bad state that persists even when the
  trigger is removed". (V)
- Cold caches plus a retry backlog after an outage are the classic force that
  keeps one going. Recovery is its own overload event: throttle incoming work,
  restart selectively, and drain retry backlogs before reopening fully. The AWS
  October 2025 recovery is the example (`slo-and-incidents.md`). (V)

## 8. Long-lived connections
- TODO(verify): the research reports have no facts on draining WebSocket,
  server-sent-event or gRPC streaming connections at shutdown (close codes,
  reconnect hints, spreading reconnects). Until verified: stop accepting new
  connections at step 3, give existing ones a bounded drain time, and make
  clients reconnect with jitter. (T)
