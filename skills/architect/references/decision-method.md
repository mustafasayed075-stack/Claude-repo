# Decision method (architect)

Status tags: V verified on a primary page, P partly verified or
experience-based, T judgement (verify in your environment). Sources:
`../SOURCES.md`.

## Contents
1. ARCH-37 Proportionality
2. ARCH-38 Ask before choosing
3. ARCH-32 Dominant shape
4. ARCH-33 Shape defaults (table)
5. ARCH-34 Comparing options
6. ARCH-35 Stress the biggest assumption
7. ARCH-39 Build vs buy
8. ARCH-47 Vendor exit
9. Egypt inputs (ARCH-29 to ARCH-31)
10. ARCH-36 Explaining the result

## 1. ARCH-37 Proportionality (T)
- Scale the artefacts to the stakes: a small tool gets a 10-line brief and no
  ADR; a product gets the full loop.
- State the chosen depth at the top of the brief.

## 2. ARCH-38 Ask before choosing (T)
Ask only what changes the answer:
- expected users in 6 to 12 months;
- monthly budget in EGP;
- team size and skills;
- deadline;
- data sensitivity;
- offline need;
- external integrations.

Unknown answers become written assumptions. In unattended runs do not stop:
write the assumptions and continue.

## 3. ARCH-32 Dominant shape (T)
Classify the idea by its dominant shape: the shape that drives the top-ranked
quality attribute, or the riskiest part. For a mixed idea, name the dominant
shape and treat the rest as modules.

Shapes: business app with roles; multi-tenant SaaS; mobile app with on-device
logic; integration or automation; AI-backed product; data and reporting heavy;
real-time or chat; content or static site; marketplace or e-commerce with
payments; internal admin tool.

## 4. ARCH-33 Shape defaults (table; every row T, verify)
Start from the shape default, then list what in this idea breaks it.

| Shape | Default | Watch for |
|---|---|---|
| Business app with roles | Modular monolith, one relational DB, a worker process for email, SMS, files | Too many layers for CRUD; an authorization point per module |
| Multi-tenant SaaS | Modular monolith, tenant id on every tenant table, central scoping, single DB first, database row policies as defence in depth (Rules: `backend-api-data/references/tenancy-and-data-lifecycle.md`) | Noisy neighbour (per-tenant quotas and rate limits); per-tenant export and deletion; a cross-tenant leak test as a fitness function |
| Mobile app, on-device logic (for example an Android content filter) | Logic and data on the device; no backend unless a feature needs one; rule or blocklist updates delivered as signed, versioned data separate from app releases; user browsing data never leaves the device by default | Battery and background-kill behaviour of popular Android OEMs; sensitive-permission and store-policy review for VPN or accessibility-style APIs (verify with current store policy); tamper and bypass by the user; low-end devices and minimum OS version; slow app updates (keep update channels in data, not code) |
| Integration or automation (for example n8n) | Use the workflow platform first; idempotent steps keyed by source event id; retries with backoff; a dead-letter or failed-runs list someone checks; webhook signature check; secrets in the platform credential store; complex rules and prompts versioned outside the flow | Third-party rate limits; duplicate events; human approval before customer-facing messages until quality is proven; platform messaging-policy limits (verify); an audit log of every automated action; replay of a failed run |
| AI-backed product | Thin gateway module behind a port (provider swappable); versioned prompts; per-user and per-tenant cost limits; async for long calls; an evaluation set run in CI on prompt or model change; structured output validated before use | Cost per request in foreign currency; personal data sent to a provider (classify first, ARCH-15; cross-border ADR, ARCH-30); prompt injection: model output never triggers a privileged action without normal authorization (OWASP LLM guidance not verified); a second provider is a fallback (ARCH-19) |
| Data and reporting heavy | Operational DB plus reporting tables or materialised views on a schedule, then a read replica, before any warehouse; exports as async jobs | Long queries blocking writes (statement timeouts on report roles); replica lag making reports inconsistent |
| Real-time or chat | Persist messages in the DB first, then push; connection layer as its own module; pub/sub fan-out across instances; a managed real-time service if the budget allows | Reconnect with resume from the last message id; at-least-once delivery with client de-duplication by message id; auth on socket connect and on each channel join; push notifications when the app is backgrounded; presence is ephemeral |
| Content or static site | Static or CMS-based, no backend by default | Forms and spam, hosting limits |
| Marketplace or e-commerce with payments | Modular monolith; payment provider behind a port; idempotent webhooks; no gateway named | Refund and dispute flows, reconciliation |
| Internal admin tool | Buy or low-code first | Access control, audit trail |

## 5. ARCH-34 Comparing options (T)
1. First eliminate options that fail a hard constraint (budget, team skill,
   law, deadline).
2. Compare the remaining 2 to 3 per ranked attribute (ARCH-5), with a one-line
   reason each.
3. Always include the plain baseline.
4. Scores are optional and never summed.
5. Write what would make us switch.

## 6. ARCH-35 Stress the biggest assumption (T)
Before finalizing, try:
- 10× users;
- an EGP devaluation raising foreign-currency hosting and AI API cost;
- the one developer being unavailable;
- a 2G-quality mobile network for a session;
- the hosting price doubling.

If the choice flips on a small change, mark it fragile in the ADR.

## 7. ARCH-39 Build vs buy (T)
- Buy or use managed services for non-differentiating parts (identity,
  email/SMS delivery, payments, file storage, real-time transport, workflow
  automation) when cost and exit are acceptable; build the core business logic.
- Record the choice and its exit cost.

## 8. ARCH-47 Vendor exit (T)
- Every external vendor sits behind a port or adapter in the module that uses
  it.
- The ADR records the data export path, the exit effort and the pricing
  currency.
- No vendor-specific feature in domain code without an ADR.

## 9. Egypt inputs (ARCH-29 to ARCH-31)
- **ARCH-29 Offline (T).** Decide per client whether it must work offline. If
  yes, local-first is the primary path (write locally, then sync), not a
  fallback. Mechanics: Rules: `backend-api-data/references/api-contracts.md`
  (idempotency), `backend-api-data/references/caching-and-files.md`, and
  `backend-reliability-performance/references/timeouts-retries-overload.md`.
- **ARCH-30 Data residency and Law 151/2020 (T).** Record the data-residency and
  Law 151/2020 decision as an ADR before choosing hosting: what is decided now,
  what is deferred to a legal review, and the trigger for that review. This is
  not legal advice; the Arabic text governs. The owner decided that the legal
  and compliance review waits until about the first 100 users; the ADR records
  that decision and does not override it. Rules:
  `backend-security-privacy/references/egypt-pdpl-gdpr-pci.md`.
- **ARCH-31 Arabic, RTL, Cairo time (T).** Arabic text, RTL and the Cairo time
  zone are data-model inputs. Rules:
  `backend-api-data/references/i18n-arabic-text.md`.

## 10. ARCH-36 Explaining the result (T)
- Explain the result in the user's language in about five lines:
  1. what we build;
  2. the shape chosen;
  3. why not the alternative;
  4. what it costs us;
  5. when we would change.
- For this owner the language is Egyptian Arabic with English technical terms
  kept in English.
- In Claude Code, the full rationale is also written into the brief and the
  ADR.
