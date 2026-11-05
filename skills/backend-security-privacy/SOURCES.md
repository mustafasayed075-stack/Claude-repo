# Sources (backend-security-privacy)

Condensed ledger for this skill, drawn from the build research reports (Batch
B: security and data protection, verified November 2026) and the reviewed
backend skills spec (source ledger and verification pass). The full per-claim
ledgers with URLs live in those reports, which are not part of this
repository. Payment providers are cited generically.

Type: primary (official docs, standards, advisories), secondary (blogs,
summaries, recalled text) or law firm. Status: V verified, P partly verified,
T judgement or secondary.

| Source | Adopted | Ignored and why | Type | Status |
|---|---|---|---|---|
| NIST SP 800-63B-4 (final, July 2025; read) | Password policy, salt and stored parameters, pepper, AAL rules, session lifetimes, syncable-passkey limits, recovery codes | Federal-only requirements; the Rev 4 throttling cap is unconfirmed | Primary | V |
| OWASP Password Storage Cheat Sheet (retrieved Nov 2026) | Argon2id floor and equivalents, scrypt, bcrypt, PBKDF2 figure | An older mirror with a different memory value | Primary practitioner | V (PBKDF2 count P) |
| RFC 9700 (OAuth security BCP, Jan 2025; read) | PKCE, exact redirects, no implicit or password grant, refresh-token rules, audience restriction | Vendor token-lifetime claims | Primary | V |
| RFC 8725 and the 8725bis draft | JWT validation rules, case-variant `alg` bypasses | JWE detail | Primary (draft P) | V |
| RFC 9110 §15.5.4 | Answering 404 to hide a forbidden resource | Nothing material | Primary | V |
| OWASP API Security Top 10 (2023) | The ten risks and countermeasures | Vendor marketing | Primary | V |
| OWASP ASVS 5.0.0 (via summaries) | Release date, requirement counts, new token and OAuth chapters | A "14 chapters" claim; chapter titles reconstructed | Secondary | P |
| OWASP CSRF Cheat Sheet and SameSite page | Fetch Metadata with fallback, Lax-by-default caveat, 2-minute Lax window | Nothing material | Primary | V |
| 2025 CWE Top 25 (MITRE/CISA, via reporting) | Rankings for missing authorization, SQL injection, CSRF, path traversal, uploads, deserialization, resource allocation | Commentary | Secondary of official | V |
| An identity provider's security advisory (1 Nov 2024) | bcrypt 72-byte cache-key bypass and dates | Nothing material | Primary | V |
| GitHub advisory for tj-actions/changed-files (CVE-2025-30066) and write-ups | Dates, tags affected, repository count, secret-dumping mechanism | Repository-count variance across write-ups | Primary + secondary | V |
| CISA alert (23 Sep 2025), a security vendor's lab, Socket, CNCF | Shai-Hulud waves and npm's response | Unverified package counts; the April 2026 OIDC wave rests on one source | Primary + vendor | P |
| GitHub docs (OIDC, Actions hardening, token formats) | OIDC trust, read-only token, SHA pinning, lockfile-style control, ID-based `sub` default after 15 Jul 2026, prefix + checksum key format | The lockfile feature's name is unconfirmed | Primary + secondary | V (lockfile P) |
| npm docs | `--ignore-scripts` and the explicit `npm run` caveat | Nothing material | Primary | V |
| AWS IMDS docs and posts | `HttpTokens=required`, PUT token, `X-Forwarded-For` rejection, hop limit 1 (2 on ImdsSupport v2.0 AMIs), SCP enforcement | Older conflicting container-host advice | Primary | V |
| PCI SSC documents and FAQ (and summaries) | v4.0.1 status, 31 March 2025 deadline, SAQ A eligibility change | Sales advice; the standard text itself not read; a successor version not checked | Primary | P |
| Andersen Egypt unofficial English translation of Law 151/2020 (read in full) | Articles, rights, duties, deadlines, transfers, penalties | Not the Arabic official text, which governs | Primary translated | P |
| Law-firm summaries of the Executive Regulations (Kennedys, Al Tamimi, Baker McKenzie, CMS, Shand, Amereller, Soliman Hashish) | Decree 816/2025 dates, grace period, portal, consent guidelines, DPO detail, children's age bands, logging duties | Speculation; "approx." fines; single-source fee waivers kept as reported only | Law firm | P |
| GDPR articles (recalled) | Bases, rights, breach rules, DPIA, transfers, fines, territorial scope | Not re-fetched from EUR-Lex | Primary (recalled) | P |
| Twitter owner's post (18 Feb 2023) and coverage | SMS-pumping cost claim | Treated as a single-party claim; the figure is unverified | Claim | T |
| Spec verification pass (Opus) | Corrections: IMDSv2 hop limit, GitHub lockfile and `sub` change, npm caveat, SAQ A wording, PDPL dates and transfer licence | Points it could not settle stay "verify" | Review | V/P |
| Practitioner conventions (token lifetimes, reset windows, clock skew, webhook replay window) | Defaults stated as conventions | No standard sets these numbers | Secondary | T |
| Payment providers' webhook and hosted-field docs | Not adopted as named facts; rules kept generic | Gateway names are excluded from the skills | n/a | n/a |
| Central Bank of Egypt rules | Not adopted | Not researched (`TODO(verify)` in `references/egypt-pdpl-gdpr-pci.md`) | n/a | n/a |

## Notes on SKILL.md rows

Source commentary kept out of the decision table:

- SEC-2: the 2024 login bypass is an identity provider's advisory (bcrypt over
  userId + username + password); see `references/auth-and-sessions.md`.
- SEC-7: RFC 9700 (January 2025).
- SEC-8: RFC 8725.
- SEC-10: BOLA is ranked #1 in the OWASP API Security Top 10 (2023).
- SEC-13: the IMDSv2 default hop limit is 1, and 2 on AMIs with ImdsSupport
  v2.0.
- SEC-18: tj-actions/changed-files, March 2025, printed secrets into logs via
  mutable tags. GitHub's docs also mention a newer lockfile-style control that
  may supersede SHA pinning; use it only once available and confirmed.
  Repositories created after 15 July 2026 get an ID-based default OIDC `sub`
  claim; check every existing trust policy.
- SEC-19: Shai-Hulud (September 2025) spread through install scripts; package
  counts and mechanism rest on secondary sources. An explicit `npm run` still
  runs its target.
- SEC-22: since 2025, SAQ A requires the merchant to confirm the page is not
  susceptible to script attacks (6.4.3 and 11.6.1 moved into that test).
- SEC-23: Egypt duties are reported by translations and law-firm summaries;
  translations differ on "working" days; verify against the Arabic text.
