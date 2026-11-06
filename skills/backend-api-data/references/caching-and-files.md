# Caching and files (backend-api-data)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Vendor defaults are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

## Contents
1. Cache pattern and TTLs
2. Stampede protection
3. What never to cache
4. Cache key design
5. Redis eviction (owned here)
6. Redis persistence
7. Object storage layout
8. Presigned URLs
9. Multipart uploads
10. File states and scanning flow
11. Versioning, lifecycle and erasure

## 1. Cache pattern and TTLs
- Default: cache-aside with a TTL. Invalidate by deleting the key on write, not
  by updating it, to avoid stale-set races. Use write-through only where the
  cache is the read path for data that must be fresh. (T)
- Every key gets a TTL as a safety net, with ±10–20% jitter. AWS adds jitter to
  "all timers, periodic jobs, and other delayed work". (T for the numbers, V for
  the principle)
- Caching to hide a bad query is an anti-pattern: run EXPLAIN and fix the index
  first (`postgres-operations.md`). (T)
- Adding a cache layer is a measured decision (SKILL.md API-24). (T)

## 2. Stampede protection
- Per-key single-flight (request coalescing), stale-while-revalidate, or
  probabilistic early refresh. (T)
- Cold caches plus a retry backlog after an outage are the classic force that
  keeps a metastable failure going; recovery tactics live in
  backend-reliability-performance. (V for the concept)

## 3. What never to cache
- Authorization decisions keyed without principal + resource + policy version.
  (T)
- Per-user data under keys that lack the user or tenant id. (T)
- `Set-Cookie` responses in shared caches. (T)
- Responses that vary by `Authorization` or locale without a matching `Vary`.
  (T)

## 4. Cache key design
- `{env}:{service}:{tenant_id}:{entity}:{id}:v{n}`. The version suffix allows
  mass invalidation; the tenant id prevents cross-tenant leaks. (T)
- Every dimension the response varies on is part of the key. (T)

## 5. Redis eviction (owned here)
This file owns the eviction rule; other skills point here.
- OSS Redis defaults to `noeviction` (writes fail with OOM when full) with
  `maxmemory 0` (unlimited). ElastiCache defaults to `volatile-lru`; Redis
  Software and GCP Memorystore also default to `volatile-lru`. Set the policy
  explicitly. (V)
- Pure cache: set `maxmemory` and `allkeys-lru` or `allkeys-lfu`. (V)
- Queues, locks and sessions: `noeviction` on a separate instance. Eviction of
  non-cache data is silent data loss. (V)
- `volatile-*` policies act like `noeviction` when keys have no TTL. (P)
- Changing eviction or persistence on non-cache data is a High trigger in this
  skill.

## 6. Redis persistence
- RDB loses everything since the last snapshot; AOF with `everysec` loses about
  1 s; replication is asynchronous. (T)
- Default: Redis holds only data you can regenerate or afford to lose. (T)
- TTL locks in Redis are advisory only. (T)

## 7. Object storage layout
- Bytes go in object storage. Metadata goes in Postgres: key, size, type,
  checksum, owner, tenant, scan status. Never store large files as `bytea` by
  default. (T)
- Clients upload directly via presigned URLs; the API never proxies large
  bodies. (T)
- Generate the exact object key on the server, under a per-tenant prefix; never
  let the client choose the path. (T)
- Maximum S3 object size is now 48.8 TiB. (V)

## 8. Presigned URLs
- S3 presigned URLs last at most 7 days with IAM-user SigV4 credentials. URLs
  signed with role or temporary credentials die when that session does, even
  if you asked for longer; EC2 instance-profile credentials typically last
  about 6 hours. (V)
- Default lifetimes: minutes for uploads, minutes to hours for downloads. (T)
- A presigned PUT cannot cap the upload size. Use a presigned POST with a
  `content-length-range` condition (and a Content-Type condition) when size
  matters. The exact `content-length-range` wording is unsettled: verify
  against current S3 docs. (P)
- Use `s3:signatureAge` bucket policies where needed. (V)
- Whatever the URL allowed, check size, type and checksum (a HEAD request on
  the object, or the scan) before marking the file ready (section 10). (T)
- TODO(verify): presigned-URL limits for object stores other than S3 are not
  covered by the research reports.

## 9. Multipart uploads
- Use multipart for large or resumable mobile uploads. Parts must be at least
  5 MiB except the last, up to 10,000 parts (from memory in the source report;
  verify). (T)
- Add a lifecycle rule to abort incomplete multipart uploads: orphaned parts
  are billed. (T)

## 10. File states and scanning flow
- Flow: upload into a quarantine location → a scanner runs on the upload event
  → move to the clean location and update the database row → serve. Never
  serve unscanned files. (T)
- Keep a status column (for example `pending`, `scanned`, `ready`,
  `rejected`) so readers only see `ready` files. (T)
- The checks themselves (magic-byte type detection, size and archive limits,
  image handling) and how files are served (separate cookie-less domain,
  `nosniff`, `Content-Disposition: attachment`) are owned by
  backend-security-privacy.

## 11. Versioning, lifecycle and erasure
- Turn on versioning for user data, and use lifecycle rules to expire
  noncurrent versions. (T)
- Erasure must delete all versions of an object. (T)
- Retention and erasure mechanics across stores:
  `tenancy-and-data-lifecycle.md`.
