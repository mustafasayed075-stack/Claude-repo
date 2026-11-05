---
name: backend-security-privacy
description: In a backend/server codebase, use when building or changing login, sessions, tokens, permissions, tenant isolation, secrets, CI credentials, uploads, URL fetching, webhooks, rate limits, payments, or anything that stores, logs or sends user personal data (including Egypt Law 151/2020 and GDPR duties). Gives senior defaults with reasons and flags High-risk changes. Not for general performance or tests.
---

# Backend Security and Privacy

Senior defaults for authentication, authorization, input handling, secrets,
supply chain, logging of user data, privacy duties and payments. This skill
sits under the foundation: the `Router:` line and engineering-standards' "Risk,
scope and planning" set the risk tier first, and a High change goes through
`architecture-planning` before any default here is used to edit code.

Authorization gaps, long-lived credentials, the wrong crypto primitive and
missing privacy duties cause most expensive breaches, so this skill leads with
them.

## Decisions

Name the rule (for example "SEC-9") when you apply it. Status: V verified
against a primary source, P partly verified, T judgement or secondary source;
word P and T items as "verify in your environment" or "judgement".

| # | Default | Why | Status |
|---|---|---|---|
| SEC-1 | Passwords: Argon2id, at least m=19456 KiB, t=2, p=1, plus a pepper held outside the database; store scheme and cost; rehash on login | Memory-hard; the numbers age, so re-benchmark yearly | V |
| SEC-2 | Never feed bcrypt more than 72 bytes; never use a password hash as a cache key | Both caused a real login bypass in 2024 | V |
| SEC-3 | Password policy per NIST SP 800-63B-4: 15+ characters single-factor, 8 with MFA, accept 64+, no composition rules, no periodic change, breached-password blocklist, allow paste | Forced complexity and rotation make weaker passwords | V |
| SEC-4 | MFA: passkeys by default; phishing-resistant MFA for staff and admins; TOTP fallback; SMS last; recovery as strong as login | Recovery is the weakest link | V/T |
| SEC-5 | First-party web: opaque server-side session in a `__Host-` cookie (`Secure; HttpOnly; SameSite=Lax`, Strict for admin); JWT sessions only if instant revocation is not needed | JWTs cannot be revoked before expiry without state | T |
| SEC-6 | Payment-return and OAuth `form_post` endpoints look up by state or order id, not the session cookie | SameSite drops the cookie on cross-site POST | T |
| SEC-7 | OAuth: authorization code + PKCE (S256), exact redirect matching, no implicit or password grant, refresh tokens rotated or sender-constrained | Redirect and token theft | V |
| SEC-8 | JWT validation: pinned algorithm allow-list; check `iss`, `aud`, `exp`, `typ`; look up `kid` in a configured JWKS; never fetch token-supplied URLs | Algorithm confusion and confused-deputy bypasses | V |
| SEC-9 | API keys: 128+ bits from a CSPRNG, prefix + checksum, store only a SHA-256 hash, allow two active keys | Leak detection and zero-downtime rotation | T |
| SEC-10 | Authorization deny-by-default; every query scoped by tenant or owner in the data layer; out-of-scope returns 404; RLS as backstop; per-operation input allow-lists | BOLA is OWASP API #1; fix it structurally | V |
| SEC-11 | Re-check authorization in jobs, exports, search, GraphQL resolvers and caches | That is where checks get forgotten | T |
| SEC-12 | Parameterize every SQL value; identifiers via allow-lists; no shell; no deserialization of untrusted data; strip CR/LF from logs and headers | Injection classes stay in the top 10 | T |
| SEC-13 | SSRF: allow-list hosts, or resolve DNS yourself and block private, loopback and link-local ranges, connect to the validated IP, no redirects; enforce IMDSv2; least-privilege roles | IMDSv2 is defence in depth, not a fix | V |
| SEC-14 | CSRF on cookie-authenticated endpoints: Fetch Metadata with an Origin/Referer fallback, plus tokens; set `SameSite` explicitly | Lax-by-default is not universal | V |
| SEC-15 | CORS off by default; if needed, an exact origin list; never reflect arbitrary origins with credentials | CORS is not access control | T |
| SEC-16 | Uploads: server-generated keys, magic-byte type check, size and archive limits, served with `nosniff` and `attachment` from a separate cookie-less domain | Malware and stored XSS | T |
| SEC-17 | Secrets in a secrets manager; short-lived credentials; rotate add → deploy → confirm old idle → revoke; after a leak, rotate first | Deleting the commit is not remediation | T |
| SEC-18 | CI: read-only `GITHUB_TOKEN` with per-job grants; Actions pinned to a full commit SHA; OIDC to the cloud restricted to repo and environment | Mutable tags leaked CI secrets in 2025 | P |
| SEC-19 | npm: `--ignore-scripts` by default with explicit exceptions; keep a lockfile; consider a short cooldown before new versions | Install-script worms spread in 2025 | P |
| SEC-20 | Never log secrets or sensitive personal data (list in `references/data-protection-logging.md`); redaction layer with tests; audit trails append-only and separate | Logs are copied widely and are personal data | T |
| SEC-21 | Rate-limit login, OTP and reset per account, destination and IP; SMS country allow-lists and spend caps; sign outgoing webhooks, verify incoming ones exactly as the sender documents | Abuse costs money | T |
| SEC-22 | Payments: card numbers never touch your servers (hosted fields); never store CVV after authorization; follow PCI DSS v4.0.1 | Scope reduction | P |
| SEC-23 | Egypt Law 151/2020: keep a machine-readable data map; plan for the reported 72-hour breach notice, 6-working-day data-subject requests and licensed transfers abroad | Criminal penalties apply | P |
| SEC-24 | Before a High-risk feature, run a 10-minute STRIDE pass and write three abuse cases as tests | Cheap, and catches design flaws | T |

## Egypt handling rule

State Egypt Law 151/2020 duties as engineering defaults only, and add: "not
legal advice; the Arabic official text governs; have an Egyptian lawyer review
before launch". Never give a legal conclusion. The open points and the details
reported by translations and law-firm summaries are in
`references/egypt-pdpl-gdpr-pci.md`; treat the duties as likely in force now.

## Ask before choosing

Ask the user only what changes the design; otherwise state the assumption.

- Authentication: who are the principals and what assurance level? Sessions or tokens, and must revocation be instant? Is recovery as strong as login? Are reset and OTP tokens hashed, single-use and expiring, with links not built from request headers?
- Authorization: for every id, where is ownership enforced? Which fields may each role read and write? Do jobs, exports, search, GraphQL and caches re-check?
- Input: any dynamic identifiers, raw ORM calls, shell, templates, XML, deserialization, file paths, URL fetches or redirects? Is every input bounded?
- Secrets and supply chain: any new credential, dependency, action or plugin? Where is it stored, who reads it, how is it rotated, could it reach logs, URLs, images or bundles?
- Web: are credentials auto-attached by the browser (CSRF)? Is CORS really needed? Cookie flags? Debug off?
- Abuse: what is the costliest or most automatable request, and what limits and spend caps apply?
- Logging: which security events are logged, what is redacted, where is the audit trail?
- Privacy: which data categories (sensitive, children, financial)? Purpose and basis? Does data leave Egypt, and to which processor or region? Can a data-subject request be answered in 6 working days and a breach sized within 72 hours?
- Payments: can card numbers, CVV or national ids reach your servers or logs?
- Threat model: what are the trust boundaries and the top three abuse cases?

## High-risk triggers (this skill's additions)

On top of the foundation's High triggers, these are High:

- authentication, sessions, tokens, MFA, recovery, or email and phone changes;
- the authorization model, roles or tenant isolation;
- bulk, export or search over sensitive data (plain id-based access is Medium with a two-tenant test);
- new crypto schemes or keys;
- new secret types, CI permissions, GitHub Actions, plugins or skills;
- user-supplied URL fetches, uploads, deserialization, shell calls;
- new sensitive data, or a new purpose for existing data;
- the first time a processor or region outside Egypt is introduced;
- retention or deletion changes;
- production data in staging;
- IAM widening or public exposure;
- relaxing any security control, even temporarily.

When unsure, rate up.

If a trigger above applies, write a `Risk: High` line before editing. Use the
format engineering-standards defines (`Risk: High - high trigger: <trigger>;
change: <new|restores|preserves>; <reason>`), load `architecture-planning`,
write its complete plan and stop for explicit approval. Any other new or
changed behavior here is Medium under engineering-standards: write
`Plan (Medium):` before the first edit.

## References: when to open each

Open a reference only when the task needs it.

- `references/auth-and-sessions.md`: password storage and policy, MFA, recovery and reset flows, sessions and cookies, JWTs and refresh tokens, OAuth/OIDC, API keys, service-to-service identity.
- `references/authorization-and-abuse.md`: object and property authorization (BOLA, 404 vs 403), roles, impersonation, injection, SSRF, uploads, CSRF, CORS, security headers, rate-limit design, business-flow abuse, SMS pumping, webhook signatures, GraphQL limits, threat modelling.
- `references/secrets-and-supply-chain.md`: secrets storage and rotation, leak response, CI permissions and OIDC trust, dependency pinning and install scripts, vetting packages and plugins, IAM, database accounts and container basics.
- `references/data-protection-logging.md`: encryption choices and key management, what never to log, security events and audit trails, log retention, data minimisation, data maps, consent records, data-subject requests, breach runbook, national ids.
- `references/egypt-pdpl-gdpr-pci.md`: Egypt Law 151/2020 duties and penalties, the open points, GDPR comparison, PCI DSS scope and card data.
- `SOURCES.md`: where each default comes from and how strong the source is.

## Backend common rules

These rules are the same, word for word, in all four backend skills.

- **Risk tiers.** Use the foundation's tiers exactly as engineering-standards defines them ("Risk, scope and planning"); a backend skill adds no tiers. Low restores an established contract (with evidence), refactors without changing behavior, or touches only tests or docs; any new or changed behavior is Medium and gets a `Plan (Medium):`; High goes through architecture-planning and stops for explicit approval. A trigger in a backend skill's High list makes the change High. When unsure, rate up.
- **Explain every decision.** When you apply a rule, name it and give its reason in one line. When you depart from a default, say why and what the alternative was.
- **Honest status.** V = verified against a primary source; P = partly verified; T = judgement or secondary source. Word every P and T item as "verify in your environment" or "judgement", never as settled fact. Version-sensitive facts are "as of Nov 2026; check your version".
- **Egypt first.** Assume users in Egypt on unreliable mobile networks, personal data under Egypt Law 151/2020, EGP money, Cairo time with DST (store IANA zones, never a fixed offset), and cloud regions that may be far away (measure latency; do not assume). Treat any foreign-hosted service as a possible cross-border transfer.
- **No named payment gateways.** Keep payment rules generic; for gateway-specific behavior, check the chosen gateway's own documentation.
- **Not verified.** End every task with a short "Not verified" list (for example production-scale data, real third parties, load, multiple instances, failure injection).
