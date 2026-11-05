# Arabic text and Egyptian formats (backend-api-data)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

The research reports cover this area only thinly. Every item marked
`TODO(verify)` has no supporting fact in the reports yet: do not present it as
settled, and confirm it before relying on it.

## Contents
1. Unicode normalization
2. Digits (Arabic-Indic and Latin)
3. Phone numbers (E.164, +20)
4. Sorting and collation
5. Search over Arabic text
6. Localized error messages
7. Dates, times and DST
8. Related rules owned elsewhere

## 1. Unicode normalization
- Normalize text to NFC before hashing or comparing values that must match
  exactly; the research applies this to passwords (count a code point as one
  character, NFC before hashing) and to keyed blind indexes
  (`HMAC(key, normalized value)`). (P)
- Store the user's text as entered and keep a normalized form only where you
  compare, index or hash. (T)
- TODO(verify): Arabic-specific normalization (letter variants, diacritics,
  tatweel) for matching and search is not covered by the reports; decide it
  with tests on real data.

## 2. Digits (Arabic-Indic and Latin)
- TODO(verify): the reports have no facts on accepting Arabic-Indic digits in
  numeric input (phone numbers, amounts, codes) or on converting them before
  validation and storage. Until this is verified, decide explicitly per field,
  write the rule down, and test input with both digit sets.

## 3. Phone numbers (E.164, +20)
- Store phone numbers in E.164 form; for Egypt that means the +20 country code.
  Look them up through a keyed hash rather than the raw number, and never make
  a phone number the sole recovery channel for high-value accounts. (P)
- Phone numbers are personal data: logging and retention rules are owned by
  backend-security-privacy; SMS abuse and spend caps likewise.
- TODO(verify): validation rules for Egyptian mobile and landline formats are
  not in the reports.

## 4. Sorting and collation
- TODO(verify): the reports have no facts on PostgreSQL collations for Arabic
  (ICU vs libc, version differences, index impact when a collation changes).
  Test ordering on your PostgreSQL version and data before relying on
  `ORDER BY` for Arabic names.

## 5. Search over Arabic text
- In-database search (`tsvector` + GIN, `pg_trgm`) covers simple keyword and
  fuzzy matching; a dedicated search engine is the option when you need Arabic
  analysers beyond PostgreSQL full-text search and `pg_trgm`. The size at which
  that becomes necessary is not settled. (P)
- TODO(verify): which PostgreSQL text-search configuration to use for Arabic is
  not covered by the reports.

## 6. Localized error messages
- RFC 9457 problem bodies keep a stable machine `type`; `title` and `detail`
  are human text and can be localized because clients never parse `detail`
  (`api-contracts.md`). (V for RFC 9457, T for the localization practice)
- Select the language from the request (for example `Accept-Language`) and keep
  the `type` and any `errors[].code` identical across languages. (T)
- Consent text shown to users in Egypt has its own language requirements
  (Arabic as primary, per secondary sources on the PDPC Consent Guidelines);
  those are owned by backend-security-privacy. (P)

## 7. Dates, times and DST
- `timestamptz` in UTC for storage, RFC 3339 UTC on the wire, and the user's
  IANA zone stored for scheduling; Egypt reinstated DST, so never hard-code
  +02:00. Confirm current rules in the IANA tz database. (V for the storage
  rule; the DST date is from memory in the reports, so verify)
- TODO(verify): the exact IANA zone identifier for Egypt is not named in the
  reports; take it from the tz database.
- TODO(verify): Hijri calendar display and Ramadan-specific scheduling are not
  covered by the reports.

## 8. Related rules owned elsewhere
- National ID numbers (structure, encryption, masking): backend-security-privacy.
- Money in EGP and currency exponents: `api-contracts.md`.
- Payloads and bandwidth for mobile networks: backend-reliability-performance.
