# Architecture brief: <!-- system name -->

<!-- Template for docs/ARCHITECTURE_BRIEF.md (architect skill, Design mode).
     Fill every section; delete a section only if the depth line says so.
     Keep each section short. Write "unknown: assumed X" where an answer is missing. -->

**Depth:** <!-- small tool (10-line brief, no ADR) | product (full loop) --> (ARCH-37)

## Goal
<!-- One or two sentences: what problem this solves and for whom. -->

## Scope and not building
- In scope:
- Not building (now):

## Users and main flows
<!-- Roles, expected users in 6 to 12 months, the 3 to 5 main flows. -->

## Shape and why
- Dominant shape (ARCH-32):
- Shape default applied, and what breaks it (ARCH-33):
- Ranked quality attributes, 3 to 5 (ARCH-5):

## Candidates
<!-- ARCH-34: options that failed a hard constraint are listed first with the reason.
     Then 2 to 3 options including the plain baseline; one line per attribute; no summed scores. -->

| Option | Attribute 1 | Attribute 2 | Attribute 3 | Notes |
|---|---|---|---|---|
| Plain baseline | | | | |
| Option B | | | | |

- Chosen:
- Why not the alternative:
- Switch condition (what would make us change):

## Boundaries
- Modules, class (rule-heavy or thin CRUD) and owned data (ARCH-6, ARCH-8, ARCH-41):
- Public interfaces and compatibility window (ARCH-42):
- Shared kernel files (ARCH-9):
- Diagram: `docs/c4.md` (ARCH-22)

## Data classes
<!-- ARCH-15: which components hold public, internal, personal and sensitive data. -->

## Threat list
<!-- ARCH-12: one table per trust boundary; threat, component, owner, mitigation pointer. -->

## Failure modes
<!-- ARCH-43: one row per container and external dependency. -->

## Capacity and cost
- Capacity estimate (requests per second, data growth) (ARCH-20):
- Monthly cost range in EGP, foreign-currency share, unit cost per active user (and per AI request), budget alarm, review trigger (ARCH-40):
- Recovery level: data-loss window and recovery time per store (ARCH-45):
- Signals per container, request-id origin, auditable events (ARCH-46):

## Data residency
<!-- ARCH-30: point to the ADR. Law 151/2020 notes here are not legal advice; the Arabic text governs. -->

## First slice
<!-- ARCH-2: one real flow end to end. -->

## Fitness functions
<!-- ARCH-23: the two structural checks plus 1 to 3 more; see templates/fitness-functions.md. -->

## Assumptions

## Not verified
