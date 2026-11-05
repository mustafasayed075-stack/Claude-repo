---
name: backend-testing-delivery
description: In a backend/server codebase, use when writing or changing tests, fixing a backend bug, setting up CI/CD, Docker, Kubernetes or Terraform, changing config or feature flags, adding logs, metrics or alerts, planning a release or rollback, or deciding whether a backend task is done. Gives senior defaults with reasons and a verification routine for AI-written changes. Not for schema design, security rules or retry policy (see the other three backend skills).
---

# Backend Testing and Delivery

Senior defaults for tests, verification of AI-written changes, observability,
CI/CD, releases, config, containers and infrastructure as code. This skill
sits under the foundation: the `Router:` line and engineering-standards'
"Risk, scope and planning" set the risk tier first, and a High change goes
through `architecture-planning` before any default here is used to edit code.

The 2024–2025 mega-outages share one shape: a config or data change spread
globally in seconds without the safeguards code gets. Treat config, flags and
generated data like code.

## Decisions

Name the rule (for example "TST-2") when you apply it. Status: V verified
against a primary source, P partly verified, T judgement or secondary source;
word P and T items as "verify in your environment" or "judgement".

| # | Default | Why | Status |
|---|---|---|---|
| TST-1 | Bug fix: write the reproducing test first and watch it fail for the right reason, then fix. New behaviour: break or revert the implementation and confirm the new tests fail | A test that never failed proves nothing | T |
| TST-2 | Never delete, skip, `xfail`, loosen, add retries to, raise timeouts on, or mass-regenerate snapshots of a test to get green; if truly needed, say so and get approval (High) | It hides regressions | T |
| TST-3 | Run the full relevant suite (unit, integration, migrations, lint, types) and report the exact commands and results, plus a "Not verified" list | The person cannot judge the code from the summary | T |
| TST-4 | Avoid the known AI-test failure modes (`references/ai-agent-verification.md`) | Those tests pass and prove nothing | T |
| TST-5 | Weight tests toward service-level integration tests against real Postgres, Redis and queue in containers; a thin unit layer for pure logic; a few end-to-end journeys | In-memory substitutes miss constraints, locking and JSONB | V |
| TST-6 | Contract tests only where you own both sides and run two or more deployed services | Elsewhere they cost more than they find | V |
| TST-7 | Mock only what you do not own or control (third-party HTTP, time, randomness); prefer real, then fake, then stub, then mock | Mock-heavy suites cost upkeep and find few bugs | V |
| TST-8 | Hard cases get dedicated tests: migrations, idempotency, concurrency, injectable clocks, webhooks | That is where production bugs live | T |
| TST-9 | Every resource endpoint gets a two-tenant test: tenant A's token with tenant B's ids must not see the object | BOLA is the top API vulnerability | T |
| TST-10 | Test data: factories over fixtures; one database per parallel worker; never production data in dev or CI | Determinism, and Egypt PDPL / GDPR | T |
| TST-11 | Flaky tests: quarantine within a day with an owner, fix or delete within about two weeks; never quarantine security, money or data-integrity tests without approval | Retries hide real races | V |
| TST-12 | Coverage shows what ran, not what was checked: gate on changed code; mutation testing on critical modules | Prevents false confidence | T |
| TST-13 | Pre-commit checks are advisory; CI is authoritative (types, SAST, dependency audit, IaC and Dockerfile lint, migration lint, OpenAPI breaking-change diff) | `--no-verify` bypasses hooks | T |
| TST-14 | Observability: OpenTelemetry to a collector; JSON logs with `trace_id`; RED metrics with route templates and no unbounded labels; histograms; trace context through queues | Cardinality and averaged quantiles break monitoring | V |
| TST-15 | Page only on user-visible SLO burn with multiwindow, multi-burn-rate rules; add heartbeat alerts for jobs and an external uptime probe | Fewer pages, faster detection | V |
| TST-16 | Small teams: managed observability first; self-host only for data residency or when cost exceeds the ops time | Operability | T |
| TST-17 | Pipeline: build once, test that image, promote the same digest; progressive rollout; post-deploy verification; automatic rollback | Never ship untested bits | T |
| TST-18 | Trunk-based development with short branches; branch protection with required checks; CODEOWNERS on infra, migrations and auth | Small batches are safer | T |
| TST-19 | Separate deploy from release: ship dark behind a flag; every flag has an owner, type and expiry; never reuse a flag's name or meaning | Kill switch, and no dead-code surprises | V/T |
| TST-20 | Rollback-safe releases: additive schema, no irreversible transform in the same release, flags default to old behaviour; otherwise say so and plan a forward fix | Rolling back code is not always safe | V |
| TST-21 | Config and generated data: schema and size validation before publishing, progressive rollout by rings, last-known-good fallback, a global kill switch | Global config pushes caused the biggest recent outages | V |
| TST-22 | Validate config at startup with a typed schema before readiness passes | Bad config never takes traffic | T |
| TST-23 | Containers: multi-stage, non-root, digest-pinned base images with automated bumps, no secrets in layers, signals reach the app | Supply chain and clean shutdown | P |
| TST-24 | Kubernetes only if someone owns it; otherwise a PaaS, serverless containers, or a VM with compose and managed Postgres | A small team without a platform owner pays heavily | T |
| TST-25 | Infrastructure as code: locked remote state per environment; review the plan and apply only the saved plan; protect stateful resources from destroy | A destructive plan applied unseen loses data | V |
| TST-26 | Production deploys only from CI on protected branches with required reviewers; every deploy recorded (who, commit, digest, when) | A leaked key or a manual deploy is how outages start | P |
| TST-27 | Backups count only when restored: an automated restore at least weekly with checks and an owner; game days quarterly | Untested backups are not backups | V |
| TST-28 | Cost basics: budget alerts at 50/80/100%, enforced tags, watch the usual surprises | Small teams get bitten by bills | T |
| TST-29 | Payment gateways: none is named; gateway sandbox behaviour is added later, once a gateway is chosen and its docs are checked | Gateway behaviour could not be verified | P |

## AI-agent verification routine

Follow it for every change you write; details and examples are in
`references/ai-agent-verification.md`.

1. Bug fix: write a reproducing test first, confirm it fails for the right reason, then fix and confirm it passes.
2. New behaviour: break or revert the implementation and confirm the new tests fail; a test that never failed proves nothing.
3. Run the full relevant suite (unit, integration, migrations, linters, type checker) and report the exact commands and results.
4. Never delete, skip, `xfail`, loosen assertions, raise timeouts, add retries or regenerate snapshots to get green without stating it and getting approval (High).
5. End with what was and was not verified, and the assumptions made.

## Definition of Done: backend additions to the engineering-standards checklist

Check these on top of engineering-standards' Definition of Done.

- [ ] Requirements and assumptions restated.
- [ ] A failing-first test shown for the fix or the new behaviour.
- [ ] Unit and real-dependency tests pass; the commands and results are reported.
- [ ] Lint, types, security and dependency checks clean, or each exception explained.
- [ ] No test, alert or check weakened without approval.
- [ ] Migrations safe forward and with the previous app version.
- [ ] Two-tenant tests for new access paths.
- [ ] Idempotency and concurrency covered for writes, handlers and webhooks.
- [ ] Bounded metrics, structured logs with trace id, and an alert with a runbook for user-facing paths.
- [ ] No secrets or personal data in logs, fixtures or snapshots.
- [ ] Config validated at startup.
- [ ] Flag, rollback plan and rollback-safety stated.
- [ ] Docs and changelog updated.
- [ ] Risk tier declared, and High-tier approval obtained before work.
- [ ] A "Not verified" list: production-scale data, real third parties, load, multiple instances, failure injection.

## Ask before choosing

Ask the user only what changes the design; otherwise state the assumption.

- Which behaviour must the test prove, and how will you show it failing first?
- Which real dependencies (database, Redis, queue) can the tests run against?
- Which third parties need fakes or sandboxes, and what failure cases (timeouts, 5xx, malformed bodies)?
- How is this released: flag, canary, rolling? Is it rollback-safe, and if not, what is the forward fix?
- Does the change touch generated config or data that propagates on its own?
- Which SLI shows it is healthy, and which alert pages if it is not?
- Who deploys, from where, with which credentials?
- What would a destroy or replace in the infrastructure plan delete?

## High-risk triggers (this skill's additions)

On top of the foundation's High triggers, these are High:

- production pipeline or CD changes;
- branch protection or required-check changes;
- IaC destroy, replace, state moves or imports;
- production config, secrets, IAM or OIDC trust;
- disabling, skipping, deleting or loosening any test, alert, scanner or check;
- destructive migrations, backfills or data deletion;
- irreversible format or key changes;
- backup, retention or DR changes;
- logging redaction changes;
- global config or flag pushes;
- anything that can take production down or lose data.

If a trigger above applies, write a `Risk: High` line before editing. Use the
format engineering-standards defines (`Risk: High - high trigger: <trigger>;
change: <new|restores|preserves>; <reason>`), load `architecture-planning`,
write its complete plan and stop for explicit approval. Any other new or
changed behavior here is Medium under engineering-standards: write
`Plan (Medium):` before the first edit.

## References: when to open each

Open a reference only when the task needs it.

- `references/testing-strategy.md`: choosing test types and doubles, migration, idempotency, concurrency, two-tenant, time and webhook tests, test data and isolation, flaky tests, coverage and mutation testing, static checks.
- `references/ai-agent-verification.md`: verifying your own change, reporting results, and the failure modes of AI-written tests, with examples.
- `references/observability.md`: instrumentation, logs, metrics and cardinality, alert math, dead man's switches, synthetic checks, error tracking, incident roles, runbook and post-mortem templates, observability cost, version facts.
- `references/release-and-rollback.md`: pipeline shape, branch protection, feature flags, release strategies, rollback safety, DORA metrics, config rollout, deployment safety, DR drills and game days, incident lessons.
- `references/containers-iac-config.md`: Dockerfiles, image scanning, Kubernetes essentials and when not to use it, Terraform or OpenTofu state and plans, config at startup, staging, cost basics.
- `SOURCES.md`: where each default comes from and how strong the source is.

## Backend common rules

These rules are the same, word for word, in all four backend skills.

- **Risk tiers.** Use the foundation's tiers exactly as engineering-standards defines them ("Risk, scope and planning"); a backend skill adds no tiers. Low restores an established contract (with evidence), refactors without changing behavior, or touches only tests or docs; any new or changed behavior is Medium and gets a `Plan (Medium):`; High goes through architecture-planning and stops for explicit approval. A trigger in a backend skill's High list makes the change High. When unsure, rate up.
- **Explain every decision.** When you apply a rule, name it and give its reason in one line. When you depart from a default, say why and what the alternative was.
- **Honest status.** V = verified against a primary source; P = partly verified; T = judgement or secondary source. Word every P and T item as "verify in your environment" or "judgement", never as settled fact. Version-sensitive facts are "as of Nov 2026; check your version".
- **Egypt first.** Assume users in Egypt on unreliable mobile networks, personal data under Egypt Law 151/2020, EGP money, Cairo time with DST (store IANA zones, never a fixed offset), and cloud regions that may be far away (measure latency; do not assume). Treat any foreign-hosted service as a possible cross-border transfer.
- **No named payment gateways.** Keep payment rules generic; for gateway-specific behavior, check the chosen gateway's own documentation.
- **Not verified.** End every task with a short "Not verified" list (for example production-scale data, real third parties, load, multiple instances, failure injection).
