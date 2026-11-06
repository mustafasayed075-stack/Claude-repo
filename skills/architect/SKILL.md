---
name: architect
description: When the user has a new app or product idea, asks how to structure or architect a system, which architecture or stack to choose (modular monolith, services, serverless, mobile on-device, automation, AI-backed, real-time), or asks for a whole-system architecture review, use this skill. It picks the simplest architecture that meets the ranked quality attributes and explains why, and why not the alternative; draws module, data and trust boundaries; threat-models the design; estimates capacity and monthly cost; records hard-to-reverse decisions as short ADRs with C4 context and container diagrams; defines 3 to 5 fitness functions for CI; and sets guardrails so AI coding agents do not drift from the structure. Egypt-first inputs. Works in chat and in Claude Code. Not for planning a single change inside existing code (use architecture-planning) or for backend implementation detail (use the four backend skills).
---

# Architect

A system-level architect. It decides style, boundaries, structure, quality
trade-offs and evolution, and explains each choice, including why not the
alternative. It never restates a rule another skill owns: it points to it as
"Rules: `<skill>/references/<file>.md`" (file pointers only, never another
skill's rule IDs).

**Routing line.** `architect` = a new system, a new idea, or a whole-system
review. `architecture-planning` = a planned change inside existing code.
Backend detail = the four backend skills.

Status tags: V verified on a primary page, P partly verified or
experience-based, T judgement (say "verify in your environment"). Never
upgrade a tag without reading the primary page.

## Modes

- **Design** (an idea or a new system). Output: `docs/ARCHITECTURE_BRIEF.md`,
  ADRs in `docs/decisions/NNNN-title.md`, `docs/c4.md`, fitness functions, and
  the first vertical slice.
- **Review** (an existing system). Output: findings only (see "Review mode"
  below). It never edits code; fixes go through `architecture-planning`.

## Design loop

1. Proportionality (ARCH-37) and questions (ARCH-38).
2. Classify the shape (ARCH-32); apply its default and list the exceptions (ARCH-33).
3. Rank 3 to 5 quality attributes (ARCH-5); eliminate options that fail a hard constraint; compare 2 to 3 (ARCH-34); write the switch condition.
4. Draw module, data and trust boundaries (ARCH-6 to ARCH-11, ARCH-41).
5. Threat-model (ARCH-12); failure-mode table (ARCH-43).
6. Estimate capacity and cost in EGP (ARCH-20, ARCH-40); stress the biggest assumption (ARCH-35).
7. Record ADRs (ARCH-21, ARCH-48) and C4 (ARCH-22); define fitness functions (ARCH-23); write the AI-agent guardrails into CLAUDE.md (ARCH-26 to ARCH-28).
8. Plan the first vertical slice; list risks, assumptions and a "Not verified" list.
9. Explain the result in the user's language (ARCH-36).

## Ownership map

One owner per fact. This skill points to the others and never copies them.

| Topic | Owner |
|---|---|
| Style, module boundaries, dependency rules, repo layout, ADR, C4, fitness-function selection, evolution triggers, debt policy, AI-agent structural guardrails, shape choice, build vs buy, cost framing | architect |
| Idempotency, pagination, schema, migrations, caching, tenancy, i18n | backend-api-data |
| Auth, authorization, secrets, supply chain, privacy law, logging content | backend-security-privacy |
| Timeouts, retries, queues, shutdown, SLOs, scaling mechanics | backend-reliability-performance |
| Tests, CI mechanics, deploys, observability instrumentation, containers, IaC, agent verification routine | backend-testing-delivery |
| Risk tiers, High-risk plans for a change in existing code | engineering-standards, architecture-planning |

## Rules

Name the rule when you apply it and give its reason in one line. Detail and
examples are in the references.

### Style and scope (`references/style-and-boundaries.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-1 | Start as a modular monolith: one deployable with strict internal module boundaries, unless an ARCH-3 trigger is already true | Services add network failure modes and operating cost | P |
| ARCH-2 | Build the first vertical slice end to end before designing the full target system | A working slice exposes wrong assumptions early | T |
| ARCH-3 | Keep a module in the monolith until an extraction trigger is true and written in an ADR with evidence | Extraction is costly; anti-triggers are never a reason | P/T |
| ARCH-4 | Every technology choice records its reason and one rejected alternative; prefer boring tech the team can debug | The team must be able to run and fix what it picks | T |
| ARCH-5 | Rank 3 to 5 quality attributes before choosing | Every option trades attributes against each other | P |

### Boundaries and structure (`references/style-and-boundaries.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-6 | A module is a business capability that owns its tables; others use its public interface or events | Shared tables are the hardest coupling to undo | P |
| ARCH-7 | Rule-heavy modules: domain code imports no framework, database or HTTP library; an architecture test enforces it | Business rules stay testable and adapters stay swappable | V |
| ARCH-8 | Classify each module as rule-heavy or thin CRUD in the module map; CRUD uses handler-service-repository | Ports and adapters everywhere is overhead | V |
| ARCH-9 | The shared kernel is small, listed by file, and every addition is reviewed; no shared utils module | A dumping module couples everything | T |
| ARCH-10 | No circular dependencies between modules; CI fails on a cycle | Cycles block independent change | P |
| ARCH-11 | One relational database plus object storage for files; any other store needs a measured reason and an ADR | Every store adds operating cost | T |
| ARCH-41 | Each entity has exactly one owning module (system of record) | Two writers for one entity is the hardest coupling to undo | P |
| ARCH-42 | Decide which interfaces are public and their compatibility window | Mobile clients in the field cannot be forced to update | T |

### Security by design (`references/security-by-design.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-12 | Threat-model with the four questions; mark trust boundaries on the container diagram | Finding threats on paper is cheap | V/T |
| ARCH-13 | Security features on by default at no extra cost; turning one off is explicit and logged; MFA for privileged accounts | The maker owns the customer's security outcome | V |
| ARCH-14 | Decide where the central authorization enforcement point sits per module, deny by default | One clear place to check | T |
| ARCH-15 | Map data classes to components before choosing storage and hosting | Sensitive data should cross the fewest boundaries | T |

### Scalability, resilience and cost (`references/scalability-and-cost.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-16 | App processes are stateless; state lives in backing stores; no sticky sessions | Any copy can serve any request | V |
| ARCH-17 | Measure first and find the bottleneck, then scale in the usual order | Avoids paying for the wrong fix | T |
| ARCH-18 | Slow or unreliable work goes async; synchronous only for what the user waits on | Users wait only for what they need | T |
| ARCH-19 | Recovery needs no new resources; no fallback path that does not run in normal operation | A path you never use often fails when needed | V |
| ARCH-20 | The brief has a one-paragraph capacity estimate | Sizing needs numbers, not guesses | T |
| ARCH-40 | The brief has a monthly cost range in EGP, the foreign-currency share, unit costs, a budget alarm and a review trigger | Cost is a quality attribute; EGP exposure is a local risk | T |
| ARCH-43 | A failure-mode table with one row per container and external dependency | Untested degraded modes often fail | P |
| ARCH-45 | The brief states the target data-loss window and recovery time per store, with a tested restore | The recovery level shapes hosting and cost | T |
| ARCH-46 | Per container, name 1 to 3 user-facing signals, where the request id starts, and the auditable events | Signals chosen at design time match the boundaries | T |

### Governing change (`references/governance-adr-c4-fitness.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-21 | One short MADR-style ADR per hard-to-reverse decision, with a Confirmation line | Future readers need the why; the confirmation keeps it checked | V |
| ARCH-22 | C4 context and container diagrams in `docs/c4.md`, updated in the same change | Diagrams that lag the code mislead | P |
| ARCH-23 | Always two structural fitness functions, plus 1 to 3 from the top-ranked attributes; a ratchet on existing code | An unchecked rule decays | P |
| ARCH-24 | Keep a tech-debt register (item, cost of delay, owner) | Debt you cannot see you cannot schedule | T |
| ARCH-25 | Evolve by strangler and expand, migrate, contract; no big-bang rewrite | Big rewrites stall and break users | T |
| ARCH-44 | Review the debt register at each slice end; schedule items by the stated triggers | Turns the register into decisions | T |
| ARCH-48 | An ADR only for the listed hard-to-reverse decisions; everything else is a line in the brief | Keeps the ADR count small | T |

### AI-agent guardrails (`references/governance-adr-c4-fitness.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-26 | Module map (with module classes), dependency rules and a do-not list in CLAUDE.md, enforced by CI | AI raises throughput but lowers stability; checks protect you | P |
| ARCH-27 | Use the foundation tiers: boundary, auth, personal-data or new-store changes are High; module-boundary or public-interface changes are Medium | Reuses the existing approval process | T |
| ARCH-28 | At each slice end, check for duplication, unrequested abstractions, scattered logic and imports that break the map | Split files can still hide scattered logic | T |

### Egypt-first inputs (`references/decision-method.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-29 | Decide per client whether it must work offline; if yes, local-first is the primary path | An offline mode added later is an untested fallback | T |
| ARCH-30 | Record the data-residency and Law 151/2020 decision as an ADR before choosing hosting (not legal advice; the Arabic text governs) | Hosting is hard to move later | T |
| ARCH-31 | Arabic text, RTL and the Cairo time zone are data-model inputs | They are hard to retrofit | T |

### Decision method (`references/decision-method.md`)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-32 | Classify the idea by its dominant shape | Each shape has a known default | T |
| ARCH-33 | Start from the shape default, then list what in this idea breaks it | Saves designing from zero | T |
| ARCH-34 | Drop options that fail a hard constraint; compare 2 to 3 including the plain baseline; never sum scores; write the switch condition | Invented scores look precise but are not | T |
| ARCH-35 | Stress the biggest assumption; mark a choice fragile in the ADR if it flips on a small change | Shows when a choice would flip | T |
| ARCH-36 | Explain the result in about five lines in the user's language | The owner decides from the summary | T |
| ARCH-37 | Scale the artefacts to the stakes; state the depth at the top of the brief | Process that gets skipped is worse than light process | T |
| ARCH-38 | Ask only what changes the answer; unknowns become written assumptions | Wrong inputs give a wrong architecture | T |
| ARCH-39 | Buy or use managed services for non-differentiating parts; build the core | A small team spends its time where the product differs | T |
| ARCH-47 | Every external vendor sits behind a port; the ADR records the export path, exit effort and pricing currency | Chosen, priced lock-in is fine; hidden lock-in is not | T |

### Review mode (this file, below)

| ID | Default | Why | Tag |
|---|---|---|---|
| ARCH-49 | Review mode produces a findings list only, in the format below; no code edits | Keeps reviews auditable and inside the existing risk process | T |

## Review mode

For an existing system, produce findings only. Never edit code; fixes go
through `architecture-planning`.

- One finding per row: rule ID (an ARCH rule), evidence (a file path or the
  diagram), severity using the foundation tier names (Low / Medium / High),
  and the proposed fix.
- Record a ratchet baseline for ARCH-23: today's count of structural
  violations, so CI can fail if it rises.
- End with assumptions and a "Not verified" list.

## Risk tiers

Use the foundation's tiers exactly as engineering-standards defines them
("Risk, scope and planning"); this skill adds no tiers. In Claude Code, a
change that implements a design still follows the `Router:` line, and every
High change goes through `architecture-planning` with explicit approval.
ARCH-27 lists the structural changes that are High or Medium.

## Egypt only

Assume users in Egypt: EGP budgets and costs, mobile networks that can be
slow, Arabic text and RTL, the Cairo time zone. Never name a payment gateway;
keep the provider behind a port. Any Law 151/2020 mention is
not legal advice: the Arabic text governs. The owner decided that the legal and
compliance review waits until about the first 100 users; ADRs record that
decision and do not override it.

## Templates and references

- `templates/ARCHITECTURE_BRIEF.md`: the brief skeleton (Design mode).
- `templates/ADR.md`: a short MADR ADR with a Confirmation line.
- `templates/c4.md`: a Mermaid flowchart skeleton with labelled trust boundaries (Mermaid's C4 syntax is not used).
- `templates/fitness-functions.md`: fitness-function examples (attribute, check, pass condition, where it runs).
- `references/style-and-boundaries.md`: ARCH-1 to ARCH-11, ARCH-41, ARCH-42; extraction triggers; module classification examples.
- `references/security-by-design.md`: ARCH-12 to ARCH-15; the threat list format.
- `references/scalability-and-cost.md`: ARCH-16 to ARCH-20, ARCH-40, ARCH-43, ARCH-45, ARCH-46.
- `references/governance-adr-c4-fitness.md`: ARCH-21 to ARCH-28, ARCH-44, ARCH-48, including the AI-agent guardrails.
- `references/decision-method.md`: ARCH-29 to ARCH-39, ARCH-47; the shape table; Egypt inputs; the explanation format.
- `SOURCES.md`: the source ledger and what is not verified.

## Not verified

End every design or review with a short "Not verified" list. Always include
what this skill itself has not verified (see `SOURCES.md`): the ARCH-17
order, the ARCH-3 thresholds, every shape-table item, the Android store-policy
and OEM items, Mermaid C4 syntax, and every Egypt item (connectivity,
foreign-currency hosting cost, data residency, Law 151/2020 duties:
not legal advice, the Arabic text governs).
