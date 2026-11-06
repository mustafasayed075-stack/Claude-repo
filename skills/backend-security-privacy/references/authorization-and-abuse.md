# Authorization and abuse (backend-security-privacy)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

This file owns: the 404-vs-403 rule, isolation enforcement in the request
path, upload validation, webhook signing and verification, and rate-limit
design. Tenancy model and RLS setup: backend-api-data. Two-tenant tests:
backend-testing-delivery. Where limits are enforced:
backend-reliability-performance.

## Contents
1. Deny-by-default
2. Object-level authorization (BOLA) and 404 vs 403
3. Property-level authorization
4. RBAC, ABAC, ReBAC
5. Where checks get forgotten (GraphQL included)
6. Impersonation
7. TOCTOU and escalation
8. OWASP API Top 10 (2023) and ASVS 5.0
9. Injection
10. SSRF and cloud metadata
11. Upload validation
12. Other unsafe input
13. CORS
14. CSRF
15. Security headers, errors and debug
16. Rate-limit design
17. Business flows and expensive endpoints
18. SMS and email pumping
19. Webhook signatures
20. Threat modelling

## 1. Deny-by-default
- Global authentication and authorization middleware with an explicit
  `@public` allow-list, plus a CI test that fails routes without a policy
  annotation. Opt-in decorators get forgotten (BFLA, OWASP API5). (T)

## 2. Object-level authorization (BOLA) and 404 vs 403
- OWASP API Security Top 10 2023 ranks BOLA #1; the 2025 CWE Top 25 has Missing
  Authorization (CWE-862) #4 and IDOR (CWE-639) #24. (V)
- For every id (path, query, body, header, GraphQL argument, nested object),
  ask where ownership is enforced. Default: scope in the query
  (`WHERE id = :id AND tenant_id = :ctx_tenant`) through repositories that
  require an authorization context. (V)
- Out-of-scope objects return 404, not 403. RFC 9110 §15.5.4 lets a server that
  wishes to hide a forbidden resource answer 404. Make the body and timing
  identical to a genuine miss, or you have built an enumeration oracle. Use 403
  only when the caller is authenticated, not allowed, and existence is not
  secret. (V for RFC 9110, T for the policy)
- UUIDs reduce enumeration but are not authorization. (T)
- Postgres RLS is the backstop (setup rules: backend-api-data). Every endpoint
  gets a two-tenant test (owned by backend-testing-delivery). (T)

## 3. Property-level authorization
- Per-operation input DTO allow-lists: never bind request bodies onto ORM
  entities. Per-role output serializers. (T)
- Server-set only: `role`, `is_admin`, `tenant_id`, `owner_id`, `price`,
  `status`, `email_verified`, `balance`. (T)
- OWASP API3:2023 combines 2019's Excessive Data Exposure and Mass Assignment.
  (P)

## 4. RBAC, ABAC, ReBAC
- RBAC fails on role explosion and per-object sharing; ABAC on policy sprawl
  and missing attributes; ReBAC (OpenFGA, SpiceDB) adds consistency ("new
  enemy") and operations cost. (T)
- Default: RBAC for coarse gates plus query-level ownership; move to OPA/Cedar
  or ReBAC when sharing, delegation or hierarchical organisations arrive.
  Centralise the decision, enforce it at every resource, log decisions. (T)

## 5. Where checks get forgotten (GraphQL included)
- Bulk: authorise every element. Exports: authorise at enqueue and at
  execution; deliver via short-lived presigned URLs. Search: put the tenant
  filter inside the index query (facets and counts leak). (T)
- Jobs and queues: carry actor and tenant and re-check; a "system" worker is an
  escalation path. Files: check before presigning. Caches: tenant and user in
  keys. (T)
- GraphQL: authorise per object and per field in resolvers or loaders; set
  depth, cost, alias and batch limits; use persisted queries; turn off
  playgrounds in production. (T)

## 6. Impersonation
- A separate, audited capability: reason or ticket, a time-boxed token marked
  `act` (RFC 8693 semantics), read-only by default, unable to change
  credentials, MFA or payouts, a visible banner, and an immutable log. (T)

## 7. TOCTOU and escalation
- Atomic conditional updates (`UPDATE ... WHERE balance >= :amt`), unique
  constraints or `FOR UPDATE`. (T)
- Watch invite flows that accept a role parameter, email-domain organisation
  auto-join, and role changes that do not revoke sessions. Re-evaluate
  authorization per request; do not freeze roles in long-lived JWTs. (T)

## 8. OWASP API Top 10 (2023) and ASVS 5.0

| ID | Risk | Countermeasures |
|---|---|---|
| API1 | BOLA | Query scoping, 404, cross-tenant tests, RLS |
| API2 | Broken Authentication | `auth-and-sessions.md`; rate-limit login, OTP, reset |
| API3 | Object Property Level Authorization | Input allow-lists, per-role serializers |
| API4 | Unrestricted Resource Consumption | Page and body caps, timeouts, GraphQL cost, spend caps |
| API5 | Function Level Authorization | Deny-by-default, separate admin router, route inventory |
| API6 | Sensitive Business Flows | Flow inventory, velocity limits, progressive friction |
| API7 | SSRF | Allow-lists, resolve and pin the IP, block metadata, no redirects |
| API8 | Security Misconfiguration | Hardened defaults, headers, CORS, no verbose errors |
| API9 | Improper Inventory | Track versions and hosts; retire old ones; no production data in non-production |
| API10 | Unsafe Consumption of APIs | Treat third-party responses as untrusted; schemas, timeouts |

(V for the list)

- ASVS 5.0.0 (30 May 2025): 345 requirements, 70 at L1, 17 chapters, levels
  L1–L3. For APIs prioritise API and web service, authentication, sessions,
  authorization, self-contained tokens (V9, new), OAuth/OIDC (V10, new),
  cryptography, communications, configuration, data protection, logging and
  errors, validation and business logic. Web Frontend (V3) and WebRTC (V17) can
  usually be scoped out for machine-to-machine APIs. Target L2 for personal
  data or money; record token lifetimes and rate limits in its "documented
  security decisions". Chapter titles were reconstructed from summaries: verify.
  (P)

## 9. Injection
- SQL (#2 in the 2025 CWE Top 25): parameterize every value; identifiers
  (ORDER BY, table names) through server-side allow-list maps; sort direction as
  an enum. LIKE: parameterize and escape `%` / `_` with `ESCAPE`. IN lists:
  generated placeholders or `= ANY($1)`, capped. Lint and gate ORM escape
  hatches (`raw`, `extra`, `literal`, `whereRaw`). Stored data is untrusted
  (second-order injection). (V for the ranking, T for the practice)
- Command injection (CWE-78): no shells; argument arrays; `--` before user
  positionals. (V for the ranking)
- NoSQL: reject objects where scalars are expected (`{"$ne": null}`); disable
  `$where`. (T)
- Server-side templates: never compile user templates; a logic-less sandbox if
  needed. (T)
- Deserialization (CWE-502): no Java serialization, BinaryFormatter, pickle,
  `unserialize`, Marshal or full YAML loaders on untrusted data. (V for the
  ranking, T for the list)

## 10. SSRF and cloud metadata
- Allow-list scheme, host and port. Otherwise resolve DNS yourself, reject
  private, loopback, link-local (169.254.169.254, fd00:ec2::254), CGNAT and
  IPv6-mapped forms, and connect to the validated IP (anti-rebinding). Disable
  or re-validate redirects; use one URL parser for validation and fetch; route
  through an egress proxy; cap time and size. (T)
- AWS IMDSv2 with `HttpTokens=required` needs a PUT-obtained token and rejects
  PUTs carrying `X-Forwarded-For`. The default hop limit is 1, which keeps
  tokens on the instance; it is 2 on AMIs with ImdsSupport v2.0. (V)
- IMDSv2 does not stop an SSRF that controls the method and headers, so
  least-privilege roles still matter; the 2019 Capital One breach paired SSRF
  with an over-privileged role. (V for IMDSv2, T for the incident detail)
- Enforce IMDSv2 account-wide: `secrets-and-supply-chain.md`.

## 11. Upload validation
This file owns upload validation; the storage layout (keys, metadata, file
states) belongs to backend-api-data.
- Server-generated keys; canonicalize and confine any name-derived path; size
  caps at the proxy and the app; type detection by magic bytes. (T)
- Serve private files with `Content-Disposition: attachment` and `nosniff` from
  a separate cookie-less sandbox domain; never serve user HTML or SVG inline
  from the app origin. (T)
- Presigned URLs: short expiry, content-type and length conditions, a
  per-tenant prefix. (T)
- Images: pixel limits, a restrictive ImageMagick / Ghostscript policy, strip
  EXIF and GPS, process in sandboxed workers. (T)
- Archives: cap uncompressed size and entry count (zip bombs); reject `..` or
  absolute entries (zip slip). CWE-22 is #6 and CWE-434 #12 in 2025. (V for the
  ranking, T for the controls)

## 12. Other unsafe input
- XXE: disable DTDs and external entities in every XML parser (defaults vary
  by version); watch SVG, Office files and SAML. (T)
- Open redirects: relative paths or allow-listed hosts; reject `//evil` and
  `/\evil`. RFC 9700 forbids open redirectors on OAuth parties. (V for RFC 9700)
- Request smuggling: CL/TE disagreement; normalise at the edge, reject
  ambiguity, prefer end-to-end HTTP/2. (T)
- ReDoS: no nested quantifiers on user input; RE2-style engines; length caps.
  (T)
- Header and log injection: strip CR/LF; structured JSON logs. (T)
- Prototype pollution: block `__proto__`, `constructor`, `prototype` in merges;
  `Object.create(null)` or `Map`. Analogues: Python format-string attribute
  access, Ruby `send`, Java EL, PHP loose comparison. (T)

## 13. CORS
- CORS relaxes reading responses; it is not access control and does not stop
  requests. Default: none. Otherwise an exact origin allow-list, reflect only
  listed origins, `Vary: Origin`, credentials only if required. Never reflect
  arbitrary origins with credentials, never allow `null`, no suffix regexes. No
  CORS on the OAuth authorization endpoint (RFC 9700). (T, V for RFC 9700)

## 14. CSRF
- CSRF matters whenever the browser auto-attaches credentials. Bearer-only APIs
  are not CSRF-prone, but JavaScript-readable tokens are XSS-stealable. (T)
- OWASP's current guidance: framework protection; Fetch Metadata as a primary
  signal (reject unsafe methods with `Sec-Fetch-Site: cross-site`) with a
  mandatory Origin/Referer fallback; synchronizer or signed double-submit
  tokens; SameSite as defence in depth only. (V)
- Traps: Lax-by-default is Chromium-only (OWASP, Dec 2024), so set SameSite
  explicitly; Lax-defaulted cookies may still be sent on cross-site top-level
  POSTs for about 2 minutes; Lax sends on top-level GET, so never change state
  on GET; sibling subdomains are same-site. CSRF is #3 in the 2025 CWE Top 25.
  (V)
- Reject unexpected request content types: `text/plain` skips the preflight,
  enabling CSRF on cookie-authenticated JSON endpoints. (T)

## 15. Security headers, errors and debug
- HSTS; `nosniff`; an explicit `Content-Type`; `Cache-Control: no-store` on
  authenticated responses; Referrer-Policy; for HTML, CSP
  `frame-ancestors 'none'`. (T)
- Errors: generic bodies with a correlation id; no stack traces, SQL or
  versions. Assert at startup that debug is off (Django DEBUG, the Flask
  debugger is RCE, Spring `/env` and `/heapdump`, GraphQL playgrounds); no
  default credentials. (T)

## 16. Rate-limit design
This file owns which limits exist and how they are keyed. The header format:
backend-api-data. Where they are enforced (proxy, gateway, shared store):
backend-reliability-performance.
- Login: per account (backoff) plus IP / ASN / device. Reset and OTP send: per
  account, destination, IP and country. OTP verify: a few tries per code.
  Signup: per device and domain. Key on account, destination and tenant, not
  just IP. No standard sets thresholds: document yours. (T)
- Limits on input: edge body size; a page-size cap (for example 100) with
  cursors; array caps; outbound timeouts; GraphQL depth, cost, alias and batch
  limits plus persisted queries. (T)

## 17. Business flows and expensive endpoints
- Inventory sensitive flows (OWASP API6): signup bonuses, referrals, coupons,
  limited stock, reviews, messaging, trials, payouts. Define human velocity and
  automation signals; add friction progressively. CAPTCHAs hurt accessibility
  and are farmed; risk scoring may need consent (`egypt-pdpl-gdpr-pci.md`). (T)
- Expensive endpoints (exports, search, LLM calls, image processing): async
  jobs with per-tenant concurrency and budget caps. (T)

## 18. SMS and email pumping
- Attackers trigger OTPs to number ranges whose termination revenue they share.
  On 18 February 2023 Twitter's owner claimed the company was losing $60M a
  year to fake 2FA SMS; SMS 2FA was then limited to paying subscribers. This is
  a single-party claim and the figure is unverified. (P)
- Controls: country allow-lists, per-prefix and per-country caps, daily spend
  caps with alerts, a humanity check before the first SMS, exponential resend
  delays, prefer TOTP or passkeys, the SMS provider's fraud guards. (T)

## 19. Webhook signatures
This file owns signing and verification; delivery, retries, outbox and dedupe
belong to backend-reliability-performance; the payload contract to
backend-api-data.
- Sending: sign with HMAC-SHA256 over `timestamp.raw_body`, include a delivery
  id for dedupe, support multiple active secrets for rotation, and use a replay
  window of about 5 minutes (practitioner convention). (T)
- Receiving: verify exactly as the sender documents. Keep the raw body, verify
  the raw bytes before parsing, compare in constant time, check the timestamp
  window, and defend against replay by event id. Re-fetch canonical state
  before moving money. (T)
- User-supplied callback URLs are SSRF (section 10); set per-tenant delivery
  quotas and auto-disable failing endpoints. CWE-770 is #25 in 2025. (V for the
  ranking, T for the rest)

## 20. Threat modelling
- A 10-minute STRIDE pass before a High-risk feature: sketch actors → entry
  points → services → stores → third parties, and mark trust boundaries
  (internet, tenant/tenant, user/admin, service/vendor, app/DB, CI/prod). At
  each crossing ask: Spoofing (who is the caller?), Tampering (can ids or prices
  change?), Repudiation (audited?), Information disclosure (what leaks?),
  DoS/cost (most expensive request?), Elevation (can a lower role or another
  tenant reach this?). (T)
- Write 3–5 abuse cases as tests and record the decisions (ASVS 5.0
  "documented security decisions"). (T)
- Testing layers: SAST with custom rules for raw-query APIs and authorization
  decorators; SCA and malicious-package checks; secret scanning; authenticated
  DAST with two tenants; fuzzing for parsers; pen tests scoped to business logic
  and multi-tenancy. The test strategy itself belongs to
  backend-testing-delivery. (T)
