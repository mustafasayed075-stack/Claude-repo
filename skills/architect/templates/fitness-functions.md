# Fitness functions: <!-- system name -->

<!-- Template (architect skill, ARCH-23). Always the two structural checks, plus 1 to 3
     chosen from the top-ranked quality attributes. Each row: attribute, check,
     tool-agnostic pass condition, where it runs. On an existing codebase, start with a
     ratchet: record today's count; CI fails if it rises. How checks run in CI is owned by
     backend-testing-delivery. -->

| # | Attribute | Check | Pass condition | Where it runs |
|---|---|---|---|---|
| 1 | Changeability | Module and layer import rules (ARCH-7, ARCH-8) | No core-to-framework import in rule-heavy modules; no import of another module's internals or tables | CI, every change |
| 2 | Changeability | No module cycles (ARCH-10) | Zero cycles in the module dependency graph | CI, every change |
| 3 | Security / data ownership | Table ownership | Each module's queries (or its database role) reach only its own tables | CI, every change |
| 4 | Compatibility | Public API contract | The public API is unchanged unless the version is bumped | CI, every change |
| 5 | Mobile performance | APK or bundle size budget | Size stays under the stated budget | CI, release builds |
| 6 | Cost | Per-request AI cost ceiling | The eval run's cost per request stays under the stated ceiling | CI, on prompt or model change |

<!-- Pick rows 3 to 6 only if they match the ranked attributes. Not fitness functions here:
     latency budgets (SLOs) and dependency or vulnerability scans (supply chain). -->

## Ratchet baseline (existing codebase)

| Check | Baseline count | Date | Target |
|---|---|---|---|
| | | | |
