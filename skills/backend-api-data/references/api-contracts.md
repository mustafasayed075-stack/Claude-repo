# API contracts (backend-api-data)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author synthesis. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

## Contents
1. Resource modelling
2. Method semantics
3. Status codes
4. Error format (RFC 9457)
5. Pagination
6. Filtering, sorting, field selection
7. Versioning and breaking changes
8. Compatibility and the breaking-change check
9. Idempotency keys
10. Rate-limit headers
11. Input limits
12. PATCH semantics
13. Bulk operations
14. Long-running operations
15. Webhook payload contracts
16. Conditional requests
17. Money, time and identifiers
18. REST, GraphQL or gRPC
19. Deprecation and sunset

## 1. Resource modelling
- Model side-effecting transitions (cancel, capture, refund) as explicit POST
  actions or created sub-resources (`POST /orders/{id}:cancel`,
  `/orders/{id}/refunds`); keep PATCH for plain field edits. (T)
- Mistake: letting clients PATCH `status`, which skips the server-side
  workflow and the audit record. (T)

## 2. Method semantics
- RFC 9110: an idempotent method is one where repeated identical requests have
  the same intended effect. GET, HEAD, PUT and DELETE are idempotent; POST is
  not. (V)
- RFC 5789: PATCH is neither safe nor idempotent, though a particular PATCH can
  be designed to be. (P)
- Default: PUT is a full replacement keyed by a client-known id; every POST
  that creates something or moves money accepts `Idempotency-Key` (section 9);
  document whether a repeated DELETE returns 404 or 204. (T)
- Mistake: an increment or append exposed as PUT or PATCH, which double-applies
  on retry. (T)

## 3. Status codes
- 400 vs 422: 422 (RFC 9110) means valid syntax, invalid semantics. A
  defensible split is 400 for parse, type or shape errors and 422 for domain
  rule violations; some large public APIs use 400 for both. It is a convention:
  consistency matters more than the choice. (V for the definition, T for the
  split)
- 401 when credentials are missing or invalid; it must carry
  `WWW-Authenticate`. (V)
- 403 vs 404 for objects the caller may not know exist: owned by
  backend-security-privacy (authorization). Do not restate it here.
- 409: conflict with current state (duplicate business key, illegal
  transition, a concurrent request with the same idempotency key). 412: an
  `If-Match` precondition failed. 429: send `Retry-After`. (V)
- 500 for bugs, 503 for overload (with `Retry-After`). Never return 5xx for
  client input errors: SDKs retry 5xx automatically and multiply load during
  incidents. (V for the codes, T for the SDK behavior)

## 4. Error format (RFC 9457)
- RFC 9457 obsoletes RFC 7807 and keeps `application/problem+json`. Members:
  `type` (a URI that SHOULD resolve to documentation), `title`, `status`,
  `detail`, `instance`; extensions are allowed. `about:blank` means "nothing
  beyond the status code". It adds an IANA problem-type registry; a new type
  must document its URI and status code. Generic "batch" problem types are
  discouraged. (V)
- Default: clients branch on a stable `type`; field errors go in an `errors[]`
  extension (JSON Pointer, code, message); clients never parse `detail`. (T)
- No stack traces, SQL or versions in error bodies. (V)
- Error `type`s and codes are contract: never rename them casually. (T)

## 5. Pagination
- Offset: simple, random access, but cost grows linearly with depth and rows
  are skipped or duplicated when data changes. Keyset: stable and
  index-friendly, no random access. (V)
- Default: opaque cursor tokens backed by keyset pagination over a total order
  that ends in a unique column (`ORDER BY created_at, id`). (V)
- AIP-158: its example uses a default page size of 50 and a maximum of 1000,
  coerces values above the maximum down, and rejects negative values with
  INVALID_ARGUMENT. Coercion is contested (AIP issue #1428 proposes an error),
  so the default here follows the current AIP text. (V; the contest is P)
- AIP-158: tokens are limited to "an indication of where to continue"; the
  other request parameters must match across pages; a rule of thumb for token
  expiry is three days. A page may hold fewer items than asked. (V)
- Adding pagination later is breaking: a client that received all 75 items
  gets 50 and does not know to follow the token. Ship it on day one with a
  finite default, even if the implementation is in memory. (V)
- Totals (`COUNT(*)`) should be optional, approximate or capped. (T)
- Mistakes: sorting only on a non-unique column; transparent cursors (a base64
  offset) that clients start to depend on. (T)
- Query side (keyset SQL, OFFSET cost): `postgres-operations.md`.

## 6. Filtering, sorting, field selection
- Allow-list fields, and only fields an index supports. (T)
- Reject unknown filters with 400: a silently ignored filter returns a
  superset, which is dangerous in bulk or delete tools. (T)
- Field masks are a performance feature, not an authorization boundary. (T)
- Never expose a generic filter language that maps straight to SQL. (T)

## 7. Versioning and breaking changes
- AIP-180 lists as breaking: removing or renaming components; changing a
  field's type or format; changing default values or how defaults are
  serialized; changing resource names or the set of valid names; moving fields
  into a oneof; changing visible behavior that reasonable code relies on. (V)
- AIP-149: adding or removing proto `optional` is breaking. AIP-151: changing a
  long-running operation's response or metadata type is breaking. (V)
- Usually additive and safe: new optional request fields, new response fields,
  new methods. (V)
- Enum caveat: a new enum value breaks exhaustive switches and strict
  deserializers, so publish "clients must tolerate unknown values" from day
  one. (T)
- Default: a URL major version plus additive-only changes within it.
  Date-pinned versions only if you can maintain translation layers. (T)
- AIP-181: alpha may break freely; beta may break after a pre-declared
  deprecation period; an in-place breaking change to a stable API is an
  "extreme course of action". (V)

## 8. Compatibility and the breaking-change check
- Servers accept old clients' requests; clients ignore unknown fields and enum
  values (tolerant reader). (T)
- Rejecting unknown request fields catches typos but complicates staged
  rollouts; a common choice is strict on writes, tolerant on reads. (T)
- Treat the OpenAPI spec as a source artefact: lint it, diff it in CI with a
  breaking-change detector, contract-test responses against it, generate
  clients. Design-first for public APIs; code-first internally only if the
  generated spec is diffed. (T)
- The CI step itself lives in the pipeline owned by backend-testing-delivery;
  this file owns what counts as breaking.

## 9. Idempotency keys
- Storage: key scoped per account, a request fingerprint (method + path +
  canonical body hash), a state (`in_progress` / `completed`), the response
  status and body, and a timestamp. UNIQUE on `(account_id, key)`. (T)
- Effects inside your own database: claim the key with
  `INSERT ... ON CONFLICT DO NOTHING` in the same transaction as the business
  effect. (T)
- External effects (a gateway charge, an SMS): a database transaction cannot
  include them. Record the intent first, pass the key (or a derived key) to the
  provider, and reconcile afterwards. (T)
- Retention: choose a window longer than the client's maximum retry horizon
  and document it. One large payment provider prunes keys after they are at
  least 24 hours old in its v1 API, and treats a reuse after pruning as a new
  request. (V for that provider; the choice is yours)
- Mismatch and concurrency: the IETF Idempotency-Key draft recommends 422 for a
  reused key with a different payload, 409 for a retry while the original is
  still in flight, and 400 for a missing required key. It is an Internet-Draft,
  not an RFC; the latest revision is unsettled (-07 or -08), so check
  Datatracker. Some providers return 400 for a mismatch instead. (P)
- What to replay: one large provider stores the first result whether it
  succeeded or failed (including 500s), but not validation failures or
  concurrent-conflict rejections, so those can be retried. (V for that
  provider; decide and document your own rule)
- Mistakes: a crash leaves the key `in_progress` forever (add a lease and a
  recovery path); keys generated per HTTP attempt instead of per logical
  operation; assuming your idempotency covers downstream calls (propagate
  derived keys). (T)

## 10. Rate-limit headers
- The IETF `draft-ietf-httpapi-ratelimit-headers` evolved from
  `RateLimit-Limit` / `-Remaining` / `-Reset` to two structured fields:
  `RateLimit-Policy: "burst";q=100;w=60` and `RateLimit: "default";r=50;t=30`.
  It is still a draft; `X-RateLimit-*` remains the de-facto convention. When
  both are present, the reset value SHOULD match `Retry-After`. (P)
- The header format is contract and lives here. Limit design (keys, algorithms,
  thresholds) belongs to backend-security-privacy; where limits are enforced
  belongs to backend-reliability-performance.

## 11. Input limits
- Enforce maximums at the edge: body size, JSON depth, array length, string
  length, page size, batch size, timeouts. Validate at the boundary and again
  with database constraints. (T)
- OWASP API Security Top 10 (2023): API4:2023 "Unrestricted Resource
  Consumption"; API3:2023 "Broken Object Property Level Authorization", which
  combines 2019's Excessive Data Exposure and Mass Assignment. (P)
- Input allow-lists per operation (never binding bodies onto ORM models) are
  owned by backend-security-privacy.

## 12. PATCH semantics
- JSON Merge Patch (RFC 7396, `application/merge-patch+json`) looks like the
  document: `null` deletes a key (so you cannot set a field to null) and arrays
  are replaced wholesale. (P)
- JSON Patch (RFC 6902, `application/json-patch+json`) is an ordered list of
  add/remove/replace/move/copy/test operations on JSON Pointer paths; it can
  edit array elements and has an atomic `test`. (P)
- Default: Merge Patch for ordinary edits; JSON Patch for array or
  explicit-null edits; or an AIP-style `update_mask`. (T)
- Mistake: deserializing a partial body into a full struct, so absent fields
  overwrite data; typed languages need tri-state fields. (P)

## 13. Bulk operations
- All-or-nothing for small or interdependent batches. Otherwise return
  per-item results (200 with per-item status, or 207) with per-item
  idempotency and input correlation, and cap the batch size. (T)
- Mistake: a 500 after half the batch committed, so clients retry everything
  and duplicate the committed half. (T)

## 14. Long-running operations
- Return `202 Accepted` + `Location: /operations/{id}` (status, done, error,
  result), optionally with `Retry-After` as a polling hint. Webhooks are an
  optimisation; polling is the reliable fallback. (T)
- AIP-151: errors that prevent the start are returned synchronously; execution
  failures go in the operation's `error`. (V)
- Mistake: holding HTTP requests open past load balancer timeouts, which
  triggers duplicate client retries. (T)

## 15. Webhook payload contracts
- This file owns only the payload contract: a unique event id, an event type,
  a payload version, and the rule that receivers must tolerate duplicates and
  out-of-order delivery (re-fetch the object or compare versions; treat events
  as hints). Large providers commonly do not guarantee ordering. (P)
- Signing and verification: backend-security-privacy. Delivery, retries,
  outbox and dedupe: backend-reliability-performance.

## 16. Conditional requests
- Send a strong `ETag` on GET; `If-None-Match` → 304; `If-Match` → 412 on
  writes, implemented as `UPDATE ... WHERE id = $1 AND version = $2` (zero rows
  means 412). `If-Match` uses strong comparison, so weak ETags do not work for
  it. (P)
- Per-user responses: `Cache-Control: private` or `no-store`, with a correct
  `Vary`; a shared cache storing an auth-dependent body is a cross-user leak.
  HTTP caching and compression in general: backend-reliability-performance. (P)

## 17. Money, time and identifiers
- Time: RFC 3339 UTC on the wire, `timestamptz` in storage, a separate IANA
  zone id when civil time matters, date-only types for dates. (V)
- Money: never binary floats. Integer minor units + ISO 4217 currency, or a
  decimal string. Exponents vary (for example 0 or 3 decimals for some
  currencies), so store the exponent per currency and document rounding. (T)
- Ids: RFC 9562 (May 2024, obsoletes RFC 4122) defines UUIDv7 as a 48-bit Unix
  millisecond timestamp plus random bits and recommends it over v1/v6; its
  advantage over v4 is B-tree locality; monotonicity within a millisecond is
  optional, not guaranteed; v7 leaks creation time. (V)
- Default: bigint identity or UUIDv7 internally; v4 or prefixed opaque ids
  publicly. Never expose sequential ids (enumeration, business-volume
  leakage). (T)
- Storage-side key choices: `postgres-operations.md`.

## 18. REST, GraphQL or gRPC
- REST + OpenAPI: public or partner APIs and HTTP caching. (T)
- GraphQL: several client shapes over one graph (a BFF); needs query cost
  limits, persisted queries, field-level authorization and dataloaders; HTTP
  caching is weak. (T)
- gRPC: internal traffic, streaming, codegen; browsers need gRPC-Web or
  transcoding (AIP-127). (T)
- Default: REST externally, gRPC internally where latency or streaming matters,
  GraphQL only with a real aggregation need and an owner for cost control. (T)

## 19. Deprecation and sunset
- Mark the endpoint deprecated in OpenAPI and send the `Deprecation` header
  (RFC 9745, March 2025, Proposed Standard). Its value is a structured-field
  Date, for example `Deprecation: @1688169599`. Also send `Sunset`
  (RFC 8594); RFC 9745 says the Sunset date must not be earlier than the
  Deprecation date. (P)
- Then measure remaining callers per key, contact them, run scheduled
  brownouts, and remove. (T)
- Mistake: removal dates with no usage telemetry. (T)
