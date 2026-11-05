# Authentication and sessions (backend-security-privacy)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Standards are as of Nov 2026: check for newer revisions.
Sources: `../SOURCES.md`.

## Contents
1. Password hashing
2. Pepper
3. bcrypt's 72-byte limit
4. Rehash on login
5. Password policy (NIST SP 800-63B-4)
6. MFA
7. Recovery
8. Sessions vs bearer tokens vs JWT
9. Cookies and cross-site returns
10. Refresh tokens
11. OAuth / OIDC (RFC 9700)
12. JWT validation (RFC 8725)
13. API keys
14. Reset, verification, enumeration, brute force
15. Service-to-service identity
16. WebSockets

## 1. Password hashing
- Default: Argon2id at least m=19456 KiB, t=2, p=1. OWASP lists equivalents,
  all p=1: m=47104/t=1, m=12288/t=3, m=9216/t=4, m=7168/t=5. (V)
- Fallbacks: scrypt N=2^17, r=8, p=1; legacy bcrypt cost ≥10; PBKDF2-HMAC-SHA256
  at 600,000 iterations only where FIPS is required (figure via a secondary
  summary). (P)
- NIST SP 800-63B-4: salt ≥32 bits; store the scheme and cost with the hash.
  (V)
- Tune to your login latency budget and re-benchmark yearly; the values age.
  (T)
- Mistakes: fast hashes; no stored parameter version; high-memory Argon2 with
  unbounded login concurrency, so a credential-stuffing burst exhausts memory.
  (T)

## 2. Pepper
- NIST SP 800-63B-4: verifiers SHOULD add a keyed hash or encryption step with
  a secret stored separately, ideally in an HSM or TEE. (V)
- Default: encrypt the hash with a KMS key (rotatable by re-wrapping) or HMAC
  before the KDF; store a pepper version. (T)
- Mistake: the pepper in the same database or env file. Raw binary pre-hashes
  fed to bcrypt truncate at NUL bytes, so base64 them first. (T)

## 3. bcrypt's 72-byte limit
- Reject or pre-hash inputs over 72 UTF-8 bytes (after NFC). Never use a
  password KDF as a cache key or MAC. (V)
- Incident: an identity provider's delegated-auth cache computed
  bcrypt(userId + username + password); with usernames of 52+ characters the
  password fell past byte 72, so any password matched a cached entry when the
  directory agent was unavailable and MFA was off. Introduced 23 July 2024,
  fixed 30 October 2024 by switching to PBKDF2 (vendor advisory, 1 Nov 2024).
  (V)

## 4. Rehash on login
- Rehash the just-verified plaintext when the stored parameters are below
  policy. (T)
- Dormant accounts on broken schemes (unsalted MD5/SHA-1): wrap now as
  Argon2id(old_hash), so the weak form leaves storage immediately, including
  future backups. (T)

## 5. Password policy (NIST SP 800-63B-4)
- 15-character minimum when the password is the only factor, 8 within MFA;
  allow at least 64; accept ASCII, space and Unicode (count a code point as one
  character; NFC before hashing). (V)
- SHALL NOT impose composition rules or periodic changes; SHALL force a change
  on evidence of compromise. No hints or security questions; verify the whole
  password. (V)
- Blocklist of breached, common and context words (service name, username),
  with the reason shown. Allow password managers and autofill; SHOULD allow
  paste; rate-limit failures. Use a k-anonymity breached-password API or a
  local bloom filter. (V for the rules, T for the tooling)
- Mistakes: blocking paste, truncation, 90-day rotation. PCI DSS has separate
  rules for in-scope system accounts. (T)
- Treat July 2025 as the publication date (the page shows a 26 Aug 2025 build
  stamp). (P)

## 6. MFA
- Synced passkeys are phishing-resistant but exportable: allowed up to AAL2,
  SHALL NOT be used at AAL3; device-bound keys meet AAL3. TOTP and SMS are not
  phishing-resistant; SMS adds SIM-swap and cost risk. (V)
- Default: passkeys for all; phishing-resistant MFA required for staff, admin
  and support; TOTP fallback; SMS last. (T)
- Push approval must make the user transfer a secret (number matching): NIST
  rejects "compare and approve" (fatigue attacks). Email SHALL NOT be an
  out-of-band authenticator. AAL2 verifiers must offer a phishing-resistant
  option. (V)

## 7. Recovery
- Recovery must be as strong as login: recovery codes at MFA enrolment (CSPRNG,
  ≥6 digits per NIST, hashed, single-use, rate-limited), notification on all
  channels, revocation of all sessions. (V for the code rules)
- A cooling-off delay before email, MFA or payout changes. (T)
- Mistakes: "forgot password" resets MFA; support changes email or phone on a
  call; recovery links log the user in directly. (T)

## 8. Sessions vs bearer tokens vs JWT
- Opaque sessions revoke instantly and leak nothing, at the cost of a cheap
  lookup. JWTs avoid the lookup but cannot be revoked before `exp` without a
  denylist (state again), expose claims, and carry validation pitfalls. (T)
- Default: first-party web uses an opaque id in a cookie (section 9);
  delegated access uses OAuth tokens; services use short-lived
  audience-scoped tokens or mTLS. (T)
- JWT is wrong when logout or ban must be immediate, when it would live in
  localStorage (XSS), when it would carry personal data or fast-changing roles,
  or when it is a monolith's session. (T)
- NIST session lifetimes: AAL2 overall ≤24 h, inactivity ≤1 h; AAL3 overall
  ≤12 h (SHALL), inactivity ≤15 min; AAL1 ≤30 days. (V)
- Regenerate the session id on login and privilege change; invalidate it
  server-side on logout. (T)

## 9. Cookies and cross-site returns
- `__Host-` prefix, `Secure; HttpOnly; SameSite; Path=/`, no `Domain`.
  SameSite=Lax by default, Strict for admin. RFC 6265bis is still a draft (not
  re-verified). (T)
- Payment-return and OAuth `form_post` endpoints receive a cross-site POST, and
  SameSite drops the session cookie on it. Do not rely on the session there:
  look up the flow by the `state` value or the order id. (T)
- CSRF defences for cookie-authenticated endpoints:
  `authorization-and-abuse.md`.

## 10. Refresh tokens
- Access tokens of about 5–15 minutes are a convention; RFC 9700 gives no
  numbers. (T)
- Public-client refresh tokens MUST be sender-constrained (DPoP RFC 9449 or
  mTLS RFC 8705) or rotated (RFC 9700 §2.2.2). (V)
- With rotation, track token families and revoke the family on reuse; store
  them hashed; allow a few seconds of grace for concurrent refresh by the same
  client. (T)

## 11. OAuth / OIDC (RFC 9700)
- Authorization code + PKCE S256: MUST for public clients, RECOMMENDED for
  confidential ones; the authorization server MUST support PKCE and prevent
  downgrade. (V)
- Exact string redirect matching (only loopback ports vary); no http except
  loopback; no open redirectors. (V)
- CSRF via PKCE, the OIDC nonce or a one-time state; mix-up defence with `iss`
  (RFC 9207). Implicit SHOULD NOT be used; the password grant MUST NOT. (V)
- Prefer `private_key_jwt` or mTLS client authentication. Audience-restrict
  tokens; resource servers MUST reject tokens not meant for them. No CORS on the
  authorization endpoint. (V)
- Confused deputy: an API that accepts any token from "our IdP" without an
  `aud` check lets app A's token work at API B. Never accept ID tokens as
  access tokens. (V)

## 12. JWT validation (RFC 8725)
- Pin an algorithm allow-list and never trust `alg`; one key, one algorithm
  (defeats RS256 → HS256 confusion); reject `none`. (V)
- Check `iss`, `aud`, `exp` / `nbf` (about 60 s clock skew is a practitioner
  convention) and an explicit `typ` (for example `at+jwt`, RFC 9068). (V for
  the checks, T for the skew)
- `kid`, `jku` and `x5u` are untrusted: look up `kid` in a configured JWKS;
  never fetch token-supplied URLs. The 8725bis draft adds case-variant bypasses
  ("noNE") and prefers allow-lists. (V; the draft is P)
- Mistakes: decoding without verifying; skipping `aud` / `iss`; one shared HMAC
  secret across services. (T)

## 13. API keys
- At least 128 bits from a CSPRNG; format = prefix + body + checksum. GitHub
  uses a prefix (`ghp_` and others) + 30 base62 characters + a 6-character
  base62 CRC32, and its partner program asks for a unique prefix, high entropy
  and a 32-bit checksum. GitHub expected the prefix alone to cut secret-scanning
  false positives to about 0.5%; the checksum is not a security control. (P)
- Store only a SHA-256 hash plus a display prefix. Scope keys by tenant,
  permission and environment (separate live and test prefixes). Allow two active
  keys for rotation. Register your prefix with GitHub secret scanning. (T)

## 14. Reset, verification, enumeration, brute force
- Reset tokens: ≥128-bit CSPRNG, hashed, single-use, valid 15–60 minutes
  (practitioner), purpose-bound; on use, kill other tokens and sessions. (T)
- Build links from a configured base URL, never from `Host` or
  `X-Forwarded-Host` (host-header poisoning). Serve the reset page with
  `Referrer-Policy: no-referrer`. (T)
- Email change: re-authenticate, confirm the new address, notify the old one
  with a revert link. (T)
- Enumeration: identical body, status and timing for known and unknown users
  (dummy-hash for the unknown user; send an "account exists" email on
  registration). (T)
- Brute force: prefer throttling (per-account backoff, IP or ASN limits,
  breached-password checks, risk signals) over hard lockout, which lets
  attackers lock out any known user. NIST requires effective rate limiting; the
  Rev 4 cap on consecutive failures is unconfirmed (Rev 3 capped it at 100). (V
  for the requirement, P for the number)
- Rate-limit design for login, OTP and reset: `authorization-and-abuse.md`.

## 15. Service-to-service identity
- Default: short-lived platform identity (mesh mTLS / SPIFFE, cloud workload
  identity, projected Kubernetes tokens with an audience), audience-scoped; no
  static keys internally. (T)
- Signed requests (HMAC over method, path, body hash and timestamp) for
  webhooks and cross-organisation calls. (T)

## 16. WebSockets
- TODO(verify): the research reports have no facts on WebSocket
  authentication (handshake auth, token expiry on long-lived connections,
  origin checks, re-authorization per message). Treat a WebSocket endpoint as
  an authenticated, authorized endpoint like any other until this is
  researched.
