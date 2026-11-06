# Governance: ADRs, C4, fitness functions and AI-agent guardrails (architect)

Status tags: V verified on a primary page, P partly verified or
experience-based, T judgement (verify in your environment). Sources:
`../SOURCES.md`.

## Contents
1. ARCH-21 ADRs
2. ARCH-48 What needs an ADR
3. ARCH-22 C4 diagrams
4. ARCH-23 Fitness functions
5. ARCH-24 and ARCH-44 Tech debt
6. ARCH-25 Evolution
7. ARCH-26 Module map in CLAUDE.md
8. ARCH-27 Structural risk
9. ARCH-28 Slice-end checks

## 1. ARCH-21 ADRs (V; MADR 4.0.0)
- One short ADR per hard-to-reverse decision (the list is in ARCH-48), in
  `docs/decisions/NNNN-title-with-dashes.md`.
- Sections: context, options, decision, consequences, and a Confirmation line
  that names the fitness function or test that checks the decision.
- Template: `../templates/ADR.md`.

## 2. ARCH-48 What needs an ADR (T)
An ADR is required for:
- the core data model;
- any data store;
- the hosting region and provider;
- the identity provider;
- the shape of a public interface;
- module boundaries;
- any vendor with no export path.

Everything else is a line in the brief.

## 3. ARCH-22 C4 diagrams (P)
- Keep the C4 context and container diagrams in `docs/c4.md`; context and
  container are enough for most teams (V).
- A change that adds or removes a container, store, external system or trust
  boundary edits `docs/c4.md` in the same change (T). This is reviewable in the
  diff.
- Use a plain Mermaid flowchart with labelled trust boundaries; Mermaid's C4
  syntax was not verified. Template: `../templates/c4.md`.

## 4. ARCH-23 Fitness functions (P)
- Always two structural checks:
  1. module and layer import rules (ARCH-7, ARCH-8);
  2. no module cycles (ARCH-10).
- Add 1 to 3 more, chosen from the top-ranked quality attributes. Write each as:
  attribute, check, tool-agnostic pass condition, where it runs.
- Examples (more in `../templates/fitness-functions.md`):
  - each module's queries or database role reach only its own tables;
  - the public API is unchanged without a version bump (Rules:
    `backend-api-data/references/api-contracts.md`);
  - an APK or bundle size budget for mobile;
  - a per-request AI cost ceiling in an eval run.
- On an existing codebase, start with a ratchet: record today's violation
  count, CI fails if it rises, then tighten over time.
- Why: an unchecked rule decays, and checks that are too strict too early block
  exploration.
- This skill decides which checks exist and why. How they run in CI: Rules:
  `backend-testing-delivery/references/testing-strategy.md` (section "Static
  analysis"; the tenant-isolation test is its section "Two-tenant
  authorization").
- Not fitness functions here: latency budgets (Rules:
  `backend-reliability-performance/references/slo-and-incidents.md`) and
  dependency or vulnerability scans (Rules:
  `backend-security-privacy/references/secrets-and-supply-chain.md`).

## 5. ARCH-24 and ARCH-44 Tech debt (T)
- Keep a tech-debt register: item, cost of delay, owner (ARCH-24).
- Review it at each slice end. Schedule an item when it blocks a fitness
  function, is touched again in two consecutive slices, or touches a trust
  boundary (ARCH-44).
- Deliberate debt gets an ADR line with its payback trigger.

## 6. ARCH-25 Evolution (T)
- Evolve by strangler and by expand, migrate, contract; no big-bang rewrite.
- Rules: `backend-api-data/references/schema-migrations.md`.

## 7. ARCH-26 Module map in CLAUDE.md (P)
- Put the module map (with each module's class from ARCH-8), the dependency
  rules and a do-not list in CLAUDE.md. This is the architectural seed that
  agent plans are checked against, and CI enforces it, not the agent's memory.
- Why: DORA 2025 reports that AI raises throughput but still lowers delivery
  stability, so automated checks are what protect you.
- Do not present "agents drift over time" as an established finding: the
  supporting papers are preprints, and one is a position paper with no data.

## 8. ARCH-27 Structural risk (T)
Use the foundation tier names exactly; define no new tiers. The tier test
stays in engineering-standards ("Risk, scope and planning"); the items below
are this skill's additional High triggers for structural work, in the same way
the backend skills list theirs. They add no tier and lower nothing.
- High (plan first via `architecture-planning`): the change touches a trust
  boundary, authentication or authorization, a personal-data flow, or adds a
  new external dependency or store. Why: each is hard to undo or widens what
  can leak.
- High: a public-interface change that can break compatibility (the
  foundation's API and data-contract trigger).
- Medium: the change crosses a module boundary, or changes a public interface
  in a non-breaking, additive way. Why: new behavior with no High trigger is
  Medium under the foundation.

## 9. ARCH-28 Slice-end checks (T)
At the end of each slice, check for:
- code duplicated across modules;
- new abstractions with a single implementation that nobody asked for;
- logic for one feature scattered over several modules (files split, logic
  still scattered);
- new imports that break the module map.

This skill owns these structural checks. The verification routine (failing
test first, never weaken tests, a Not-verified list): Rules:
`backend-testing-delivery/references/ai-agent-verification.md`.
