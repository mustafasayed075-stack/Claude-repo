# Data protection and logging (backend-security-privacy)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

This file owns the never-log list; other skills point here. Legal duties and
deadlines: `egypt-pdpl-gdpr-pci.md`. Retention and erasure mechanics in the
database: backend-api-data.

## Contents
1. Choosing a primitive
2. Nonces, randomness and comparison
3. Envelope encryption and key rotation
4. Encryption layers
5. What not to encrypt
6. TLS
7. Never log (owned here)
8. Security events to log
9. Audit trail vs app log
10. Log retention, correlation and alerts
11. Minimisation, data map and purpose
12. Pseudonymisation
13. Consent records
14. Data-subject requests
15. Breach runbook
16. Sensitive identifiers and phone numbers

## 1. Choosing a primitive
- HMAC-SHA-256 for shared-secret integrity; AEAD (AES-256-GCM,
  ChaCha20-Poly1305, XChaCha20-Poly1305 for random nonces at volume) for
  confidentiality; signatures (Ed25519; ECDSA P-256 with RFC 6979; RSA-PSS
  ≥3072) for third-party verification; a KDF for passwords
  (`auth-and-sessions.md`). (T)
- Use libsodium, Tink or KMS SDKs; never roll your own. New crypto schemes or
  keys are a High trigger in this skill. (T)

## 2. Nonces, randomness and comparison
- GCM nonce reuse leaks the plaintext XOR and enables forgery. Random 96-bit
  nonces are limited to about 2^32 messages per key (from memory in the source
  report; verify). Rotate by volume or use XChaCha or AES-GCM-SIV. Put tenant,
  record id and column in the associated data to stop ciphertext swapping. (T)
- CSPRNG only; constant-time comparison (`hmac.compare_digest`,
  `timingSafeEqual`). (T)

## 3. Envelope encryption and key rotation
- KMS/HSM root → key-encryption key → per-tenant data keys. Store the wrapped
  key and its version with the ciphertext. Rotate by writing with new versions
  and lazily re-encrypting. Per-tenant keys enable crypto-shredding. Key
  lifecycle per NIST SP 800-57 (not re-fetched). (T)

## 4. Encryption layers
- Disk / TDE encryption stops lost media and snapshots, but not SQL injection,
  app compromise or DBAs. (T)
- Field-level encryption for national id, health and bank data costs
  queryability and needs a rotation plan. (T)
- Blind index: `HMAC(k_index, normalized value)` with a separate key;
  low-entropy fields are brute-forceable if that key leaks. (T)

## 5. What not to encrypt
- Passwords (hash them), data you should not hold (delete it), range-searched
  values (redesign), join keys. One app key in an env var adds no boundary. (T)

## 6. TLS
- TLS 1.3 preferred, 1.2 minimum with AEAD ciphers. HSTS `max-age` ≥31536000
  with `includeSubDomains`; preload only when every subdomain is ready. ACME
  automation, expiry and CT monitoring. Internal traffic encrypted (mesh mTLS).
  Disabling TLS verification is a High trigger. (T)

## 7. Never log (owned here)
Never write these to logs, error trackers, traces or analytics:
- passwords, including failed attempts;
- session ids, access and refresh tokens, API keys;
- OTP and reset codes;
- card data (PAN, CVV), private keys;
- full national ids, health data;
- request bodies of authentication and payment routes;
- `Authorization` and `Cookie` headers.

(T) Enforce it with a redaction layer and tests that fail if a value from this
list reaches a log line; watch query strings in access logs. Changes to
logging redaction are High in backend-testing-delivery.

## 8. Security events to log
- Authentication success and failure; MFA, recovery, password and email
  changes; token issuance, revocation and refresh reuse; authorization denials
  (404/403 bursts on sequential ids indicate BOLA probing); admin and
  impersonation actions; role changes; exports and bulk reads; API key use (id
  only); config changes; webhook signature failures; rate-limit trips. (T)
- Egypt's Executive Regulations reportedly require logs of data-subject
  requests and the actions taken, of consents and of breach incidents (one
  law-firm summary). (P)

## 9. Audit trail vs app log
- An audit trail is append-only business evidence (who, what, which record,
  when, where, why), retained per policy and queryable for data-subject
  requests. Ship it off-host to a separate account; WORM / object lock;
  hash-chain where needed; deletion only via break-glass. (T)
- App logs are disposable debugging data and must not double as the audit
  trail. The audit table design itself: backend-api-data. (T)

## 10. Log retention, correlation and alerts
- Logs are personal data: justify the retention period (a common choice is 90
  days hot, 1 year archived). PCI DSS reportedly needs at least 12 months with 3
  months immediately available (from memory; verify Req. 10.5.1). (T)
- Correlate with W3C `traceparent` and return the id in error bodies. (T)
- Alert on credential-stuffing spikes, impossible travel, refresh-family
  revocations, authorization-denial spikes, unusual exports, new admin grants,
  and credentials used from unexpected IPs. Detection speed decides whether a
  72-hour breach clock can be met. Alert design belongs to
  backend-testing-delivery. (T)

## 11. Minimisation, data map and purpose
- Every field needs a purpose, readers and a deletion date; prefer coarse
  values; no national id "just in case". (T)
- Keep a machine-readable data map (column → category, purpose, legal basis,
  retention, processors, location), generated from schema annotations. It
  doubles as the GDPR Art. 30 / PDPL Art. 4 processing record. (T)
- Tag data with its purpose and block reuse without a new basis. A new purpose
  for existing data, or new sensitive data, is a High trigger. (T)
- Anonymise only if re-identification is not reasonably possible: exact
  timestamps, location and device ids keep rows personal. (T)

## 12. Pseudonymisation
- Pseudonymised is not anonymous: unkeyed hashes of emails or phone numbers
  are reversible; use HMAC with a protected key. (T)

## 13. Consent records
- Append-only: who, text version, purpose, time, channel, withdrawal.
  Withdrawal as easy as giving; propagate it to processors. (T)
- Egypt's PDPC Consent Guidelines (v1.1, Jan 2026, via secondary sources):
  explicit, specific, informed, freely given, separate from terms, affirmative,
  Arabic as the primary language, recorded; written consent for sensitive data.
  (P)

## 14. Data-subject requests
- Build them as product features: self-service export (JSON / CSV), access,
  rectification, erasure, restriction, objection; SLA timers; an action audit
  log; risk-proportional identity checks (never send an export to an
  unverified new address). The Egyptian deadline is far shorter than GDPR's
  (`egypt-pdpl-gdpr-pci.md`), so automate. (T)
- Log staff reads of sensitive records and alert on bulk access. (T)

## 15. Breach runbook
- A "personal data breach?" decision step, a clock that starts at awareness,
  templates (nature, categories, approximate subjects and records,
  consequences, measures, DPO contact) and pre-collected contact channels. (T)
- Deadlines: `egypt-pdpl-gdpr-pci.md`.

## 16. Sensitive identifiers and phone numbers
- The Egyptian 14-digit national id reportedly encodes birth date, governorate
  and gender (from memory in the source report; verify). Collect it only for
  KYC; field-level encryption; a keyed blind index; masked display; never a
  primary key or part of a URL. (T)
- Phone numbers: E.164, keyed-hash lookup, never the sole recovery channel for
  high-value accounts (storage format: backend-api-data). (T)
- Health-like data: a segregated store and keys, access logging, kept out of
  analytics, a DPIA (GDPR) and a licence check (PDPL Art. 12). (T)
- Transfers: know where every processor and sub-processor stores data; prefer
  in-country or adequate regions for Egyptian data. The first time a processor
  or region outside Egypt is introduced is a High trigger. (T)
