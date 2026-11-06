# Release and rollback (backend-testing-delivery)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

CI credentials and OIDC trust:
`backend-security-privacy/references/secrets-and-supply-chain.md`. Migration
order and expand/contract:
`backend-api-data/references/schema-migrations.md`. Outage lessons from
GitLab, AWS, Cloudflare, GitHub and Google Cloud:
`backend-reliability-performance/references/slo-and-incidents.md`.

## Contents
1. Pipeline shape and speed
2. Branching and protection
3. Feature flags
4. Versioning
5. Release strategies
6. Rollback safety
7. Separate deploy from release
8. Deployment consistency
9. DORA metrics
10. Config and generated-data rollout
11. Deployment safety
12. DR drills and game days
13. Incident lessons

## 1. Pipeline shape and speed
- Lint, types and secret scan → unit tests → build once (tag with the commit
  SHA, push by digest) → integration tests on that image → scan and SBOM →
  staging and smoke tests → progressive production rollout → post-deploy
  verification → automatic rollback. (T)
- Promote the same artifact by digest; never rebuild for production. (P)
- PR feedback under 10 minutes, merge-to-production under 30–60 minutes, using
  dependency caches, BuildKit cache mounts and test sharding. (T)
- Production pipeline or CD changes are a High trigger in this skill.

## 2. Branching and protection
- Branch protection: required checks, at least one review, no force-push to
  main, CODEOWNERS for infra, migrations and auth. (T)
- Trunk-based development with branches under 1–2 days is the DORA-associated
  default; GitFlow fits only versioned software with several supported
  releases. (T)
- Branch-protection or required-check changes are a High trigger in this
  skill.

## 3. Feature flags
- Types (Hodgson / Fowler): release (days to weeks), experiment (the length of
  the experiment), ops or kill switch (can be long-lived), permission
  (entitlements; really configuration). Fowler treats toggles as inventory with
  a carrying cost. (P)
- Every flag gets an owner, a type and an expiry; CI warns when one has
  expired. (P)
- Never reuse a flag's name or meaning. Knight Capital repurposed the "Power
  Peg" flag, and the one server without the new code ran Power Peg (quoting the
  SEC order). (P)
- A flag changes behaviour per cohort; config changes deployment parameters;
  both are audited production changes. Global config or flag pushes are a High
  trigger in this skill. (T)

## 4. Versioning
- SemVer for libraries and public APIs; date or commit versions for services;
  Keep a Changelog or Conventional Commits. (T)

## 5. Release strategies
- Rolling (the Kubernetes default) needs old and new versions to coexist.
  Blue/green gives instant rollback at double capacity, with a database that
  is still shared. Canary (1% → 10% → 50% → 100%) with automated baseline
  analysis (Argo Rollouts, Flagger or cloud canaries). Shadow traffic for
  read-path rewrites only, unless side effects are sandboxed. (T)
- The SRE Workbook (ch. 16) covers canarying; its content was not re-checked.
  (P)

## 6. Rollback safety
- Rollback-safe means: additive schema; no irreversible transform in the same
  release; new code tolerates old data and the other way round; flags default
  to the old behaviour; config versioned with code. (V)
- Migration order: expand before the code that uses it; contract only after the
  old code is gone and a soak period has passed; never in the same deploy. (T)
- If a release is not rollback-safe, say so and plan a forward fix. Rolling
  back code is not automatically safe (section 8). (V)
- Rollback runbook: one command; mark releases that are not rollback-safe. (T)

## 7. Separate deploy from release
- Ship dark; release by an audited flag change. (T)
- Google's June 2025 report: "If this had been flag protected, the issue would
  have been caught in staging". (V)

## 8. Deployment consistency
- Verify that every instance runs the same version before enabling anything.
  Knight Capital's manual deploy missed 1 of 8 servers; 212 parent orders
  produced 4 million executions in about 45 minutes, and rolling back the new
  code made it worse because the flag then woke Power Peg everywhere (quoting
  the SEC). The SEC press release says Knight "suffered a loss of more than $460
  million". (P)

## 9. DORA metrics
- Five metrics. Throughput: deployment frequency, change lead time, failed
  deployment recovery time. Instability: change fail rate, and deployment
  rework rate (unplanned, incident-driven deployments ÷ all deployments; added
  in the 2024 research, reported fully in 2025). "MTTR" became failed
  deployment recovery time. (P)
- The 2025 report replaced performance tiers with seven team archetypes and
  found AI adoption raises throughput but also instability. Its numeric
  benchmark bands could not be confirmed, so none are given here. (P)
- Compare the same application or service over time; never make DORA metrics
  individual targets. Small batches improve speed and stability together; with
  AI raising throughput, PR size is an early-warning metric. Lowering the change
  fail rate by deploying less shows up in the rework rate. (T)

## 10. Config and generated-data rollout
The 2024–2025 lesson: config and generated data need the safeguards code gets.
1. Validate generated config against a schema and size limits before
   publishing (Cloudflare's feature file exceeded a hard-coded limit;
   CrowdStrike's template had 21 input fields against 20 inputs). (V for
   CrowdStrike, P for the Cloudflare limit)
2. Roll out progressively by rings, never globally within seconds. (V)
3. Consumers keep the last-known-good version and reject bad input rather than
   crash (Cloudflare's proxy panicked; Google's binary crash-looped on a null).
   (V for Google, P for Cloudflare)
4. Keep a global kill switch and a fast "stop propagation, restore last good"
   path. (T)
5. Treat permission or schema changes to upstream data sources as config
   changes. (T)

## 11. Deployment safety
- Production deploys only from CI on protected branches or GitHub Environments
  with required reviewers; no standing human production credentials, only
  audited break-glass. (T)
- No long-lived cloud keys in CI: OIDC (trust rules:
  `backend-security-privacy/references/secrets-and-supply-chain.md`). (V)
- Record every deploy (who, commit and digest, when, where, approval) and
  mirror it as a release marker (`observability.md`). (T)
- Windows: "deploy when someone is awake and watching" (no Friday-evening
  High-risk deploys) rather than long freezes, which batch risk. Ramadan, Eid
  and month-end salary peaks are traffic events for consumer apps in Egypt and
  the region. (T)
- Post-deploy verification: smoke tests, new-issues-in-release checks, a 10–30
  minute canary SLI comparison, automatic rollback past a threshold. (T)
- Test the safeguards: Facebook's audit tool had a bug that failed to stop the
  command. Deploy guards, destroy checks and config validators need tests like
  features do. (V)

## 12. DR drills and game days
- Backups exist only if restored: an automated restore at least weekly into a
  scratch instance with row-count and checksum checks, alerting, and a named
  owner. (V for the principle, T for the cadence)
- Game days quarterly, 1–2 hours: kill the primary database, revoke a secret,
  break a dependency, restore a backup, roll back a bad deploy, timing each
  against RTO and RPO. (T)
- Multi-region is usually no for small teams: multi-AZ, cross-region backup
  copies and IaC that can rebuild elsewhere, with an RTO of hours. AWS us-east-1
  in October 2025 lasted about 15 hours, so "what if our region is down for 15
  hours?" needs a written business answer. (V for the duration, T for the
  advice)
- Backup, retention or DR changes are a High trigger in this skill.

## 13. Incident lessons

| Incident | What happened | Lesson | Status |
|---|---|---|---|
| Knight Capital, 1 Aug 2012 | A manual deploy missed 1 of 8 servers; a repurposed flag woke dead code; no reviewer or written procedure; about 45 minutes | Automate deploys, verify version consistency, delete dead code, never reuse flags | P |
| Facebook, 4 Oct 2021 | A maintenance command dropped all backbone links; an audit-tool bug did not stop it; DNS withdrew BGP; internal tools went down | Test safety tooling; keep out-of-band access | V |
| CrowdStrike, 19 Jul 2024 | A 21-field template against 20 inputs passed a validator with a logic error; a kernel out-of-bounds read; Microsoft estimated 8.5 million Windows devices affected | Content and config need staged rollout and bounds tests like code | V |

For GitLab 2017, AWS October 2025, Cloudflare November 2025, GitHub 2018 and
Google Cloud June 2025, see
`backend-reliability-performance/references/slo-and-incidents.md`.
