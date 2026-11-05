# Egypt Law 151/2020, GDPR and PCI DSS (backend-security-privacy)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

**Read this first.** Everything about Egypt's Personal Data Protection Law
(No. 151/2020) and its Executive Regulations in this file is reported by an
unofficial English translation and by law-firm summaries; verify it against
the Arabic text. This is not legal advice: the Arabic official text governs,
and an Egyptian lawyer should review before launch. Never turn these notes into
a legal conclusion. Treat the duties as likely in force now.

## Contents
1. Status and open points
2. Scope
3. Data-subject rights and deadlines
4. Lawful bases and controller duties
5. Processor duties
6. Breach notification
7. DPO
8. Sensitive data and children
9. Cross-border transfers
10. Marketing
11. Licences, fees and penalties
12. Egypt vs GDPR
13. GDPR for engineers
14. PCI DSS and card data

## 1. Status and open points
- The Executive Regulations were issued by Decree 816/2025, dated 1 November
  2025 and made public around 25 December 2025. A one-year grace period
  followed. (P)
- The PDPC launched a licensing portal in early October 2026; login needs a
  Digital Egypt account (national ID), so foreign entities apply through a
  local representative. No licence grants or enforcement had been reported.
  Decisions are due within 90 days of a complete application, and silence
  counts as rejection (Art. 27). (P)
- Open points: do not state these as fact.
  - The exact end of the grace period: 31 October or 1 November 2026, and
    whether enforcement has started.
  - Whether consent is needed for every cross-border transfer.
  - The age rules for children.
  - Whether the DPO may be outsourced.
  - Who issued Decree 816/2025 (sources name the Minister of Communications
    and IT or the Prime Minister).
- Also reported by law-firm summaries, to be verified: a registered DPO; a
  PDPC licence or permit for controllers and processors (not only for
  transfers); prior consent for electronic marketing.

## 2. Scope
- Electronically processed personal data of natural persons, including
  processing abroad that relates to people in Egypt. Foreign controllers and
  processors need a local representative (Arts. 4–5). (P)

## 3. Data-subject rights and deadlines
- Rights (Art. 2): to know, access and obtain data; withdraw consent; correct,
  delete and update; restrict processing; be told of a breach; object. A fee
  set by the Center, up to EGP 20,000, may apply (not for breach
  notification). (P)
- Answer requests within 6 working days (Art. 32; silence on access counts as
  refusal, Art. 10). Translations differ on "working" days. This is far tighter
  than GDPR's one month, so automate requests
  (`data-protection-logging.md`). (P)
- The Regulations reportedly require approved request mechanisms and logged
  requests. (P)

## 4. Lawful bases and controller duties
- Conditions (Art. 3): legitimate, specific, declared purposes; accuracy and
  security; no retention beyond the purpose. (P)
- Bases (Art. 6): explicit consent; a contract or legal claims; a legal
  obligation, investigative order or judgment; a legitimate interest that does
  not override the person's rights. The Consent Guidelines reportedly count six
  bases. (P)
- Controller duties (Art. 4): lawful collection, accuracy, written processor
  contracts, security, erasure (or de-identification) at the end of the
  purpose, a processing record that includes transfers abroad, a licence or
  permit, a local representative, demonstrable compliance. The Regulations
  reportedly add a secure electronic register of consents, categories, scope,
  retention and measures. (P)

## 5. Processor duties
- Art. 5: documented instructions, scope and duration; delete or return data at
  the end; no disclosure; security; its own processing record; a licence; a
  representative. (P)

## 6. Breach notification
- Notify the Personal Data Protection Center within 72 hours of becoming aware
  (immediately if national security is involved), via its portal or hotline,
  with the nature, cause, estimated size and records, DPO contact,
  consequences, measures and documentation. There is no risk threshold. (P)
- Notify affected data subjects within 3 working days of that notification
  (translations differ on "working"). (P)
- The breach runbook: `data-protection-logging.md`.

## 7. DPO
- Legal entities appoint and register a qualified DPO (Arts. 8–9). The law says
  an employee; one secondary source says the Regulations allow contracted or
  shared DPOs: an open point (section 1). (P)
- Duties: assessments, liaison with the Center, rights handling, breach
  notification, complaints, records, training; reportedly also a "DPO code" and
  annual reports. (P)

## 8. Sensitive data and children
- Sensitive (Arts. 1, 12): health, biometric, financial, religious, political
  and security-status data, and children's data always. Processing needs a
  licence plus written explicit consent (a guardian's for children). Fintech
  and payments land in the top tier by default because financial data is
  sensitive. (P)
- The law sets no child age. The Regulations reportedly distinguish under 15
  (guardian consent) and 15 to 18 (conditional rules) and restrict child
  profiling. Children's age rules are an open point. (P)

## 9. Cross-border transfers
- Prohibited unless the destination's protection is "not less than" Egypt's
  and the transfer is licensed or authorised (Art. 14). Art. 15 allows
  non-adequate destinations with explicit consent in listed cases (vital or
  medical interests, legal claims, a contract for the person's benefit,
  judicial cooperation, legal requirement or public interest, financial
  transfers, treaties). Art. 16 covers sharing with foreign controllers under
  licence. Whether consent and a listed case are both required is ambiguous.
  (P)
- Per law-firm summaries, the Regulations require licence, adequacy and
  consent, and a licence update for each new destination country. (P)
- Engineering consequence: treat every foreign-hosted SaaS, cloud region,
  email or analytics service, error tracker or LLM API as a possible
  cross-border transfer, and confirm with Egyptian counsel. The first time a
  processor or region outside Egypt is introduced is a High trigger. (T)

## 10. Marketing
- Arts. 17–18: a separate licence, prior explicit consent, sender
  identification, an opt-out, and logs. (P)

## 11. Licences, fees and penalties
- Fee caps: EGP 2M for a licence, EGP 500k for a permit (Art. 26). The
  Regulations reportedly waive fees up to 100,000 records (licences) and 25,000
  (permits); single secondary source. (P)
- Penalties are decided by the Economic Courts:

| Article | Conduct | Penalty |
|---|---|---|
| 36 | Unlawful processing | EGP 100k–1M; for gain or harm, ≥6 months and/or EGP 200k–2M |
| 37 | Refusing rights; collecting outside Art. 3 | EGP 100k–1M; EGP 200k–2M |
| 38 | Breaching Arts. 4, 5 or 7 | EGP 300k–3M |
| 39 | No DPO | EGP 200k–2M |
| 40 | DPO failures | EGP 200k–2M (50k–500k if negligent) |
| 41 | Sensitive data | ≥3 months and/or EGP 500k–5M |
| 42 | Unlawful transfer | ≥3 months and/or EGP 500k–5M |
| 43 | Marketing | EGP 200k–2M |
| 45 | Licensing | EGP 500k–5M |

(P) Art. 47 makes aware managers liable; Art. 48 doubles penalties for repeat
offences; Art. 49 allows settlement. (P)

## 12. Egypt vs GDPR

| Topic | GDPR | Egypt PDPL (reported) |
|---|---|---|
| Breach to the regulator | 72 h unless unlikely to result in risk | 72 h, no threshold; immediate if national security |
| Breach to individuals | Without undue delay if high risk | 3 working days after notifying the Center |
| Data-subject requests | 1 month (+2) | 6 working days |
| Financial data | Not a special category | Sensitive |
| Transfers | Adequacy, SCCs, BCRs | Licence + adequacy (+ consent per the Regulations) |
| Sanctions | Administrative fines | Criminal fines and imprisonment |

(P)

## 13. GDPR for engineers
Recalled from the articles in the source report, not re-fetched; high
confidence. (P)
- Bases (Art. 6): consent, contract, legal obligation, vital interests, public
  task, legitimate interests (with a documented balancing test). Special
  categories (Art. 9) need an extra condition.
- Rights (Arts. 13–22): information, access, rectification, erasure,
  restriction, portability, objection, safeguards on automated decisions;
  respond within one month, extendable by two (Art. 12(3)).
- Art. 25: data protection by design and default. Art. 32 names
  pseudonymisation and encryption.
- Breaches: the authority within 72 h unless unlikely to result in risk (Art.
  33); individuals without undue delay if high risk (Art. 34); document all.
- DPIA (Art. 35) for high-risk processing (large-scale profiling, special
  categories, systematic monitoring, new technology); per EDPB guidance, two or
  more criteria usually trigger one.
- Transfers: adequacy, SCCs plus a transfer impact assessment, or BCRs. Fines up
  to €20M or 4% of turnover.
- Applies to an Egyptian product only if it targets or monitors people in the
  EU (Art. 3(2)).

## 14. PCI DSS and card data
- Aim for card numbers never touching your servers. (T)
- PCI DSS v4.0.1 (June 2024) is the active version. Its 51 future-dated
  requirements became mandatory on 31 March 2025, including 6.4.3 (payment-page
  script inventory, authorization and integrity) and 11.6.1 (change and tamper
  detection). (V)
- Since 2025, SAQ A requires the merchant to confirm the payment page is not
  susceptible to script attacks: 6.4.3 and 11.6.1 moved into that eligibility
  test (with 12.3.1), and PCI SSC says the requirements are "not removed or
  diminished". How strictly the related FAQ applies is unsettled. A version
  after v4.0.1 was not checked. (P)
- Scope reduction: a hosted payment page or the gateway's hosted fields or
  iframes plus tokens aim at SAQ A; a self-built card form moves toward
  SAQ A-EP. Rules stay gateway-agnostic: check the chosen gateway's own
  documentation. (P)
- Never log payment request bodies (`data-protection-logging.md`). Protect the
  page that embeds the payment form with CSP, SRI and a minimal script
  inventory (the 2018 British Airways skimming pattern, from memory in the
  source report). (T)
- Never store CVV, track data or PIN blocks after authorization; display at
  most the first 6 and last 4 digits (from memory; verify). (T)
- Financial data is also sensitive under the PDPL (section 8). Central Bank of
  Egypt rules were not researched. TODO(verify): Central Bank of Egypt
  requirements for payment and fintech products.
