# Containers, IaC and config (backend-testing-delivery)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Version facts are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

Secrets storage and the secrets manager:
`backend-security-privacy/references/secrets-and-supply-chain.md`. Shutdown
sequence and probes:
`backend-reliability-performance/references/health-shutdown.md`.

## Contents
1. Dockerfile defaults
2. Image scanning, SBOM and signing
3. Kubernetes essentials
4. When Kubernetes is not right
5. Infrastructure as code: tool and state
6. Plan before apply
7. Drift, secrets in state, tags
8. Staging
9. Configuration at startup
10. Cost basics

## 1. Dockerfile defaults
- Multi-stage, with a slim or distroless final stage. (P)
- Base images pinned by digest (`FROM image:tag@sha256:...`) and paired with
  automated bumps (Renovate or Dependabot), so pinning does not freeze known
  CVEs. (P)
- `.dockerignore` (`.git`, `.env`, `node_modules`, tests); copy manifests
  before source; a non-root numeric `USER`; pinned toolchains and lockfiles. (P)
- Secrets via BuildKit `--mount=type=secret`, never `ARG`, `ENV` or a layer. (P)
- Exec-form `ENTRYPOINT`, so SIGTERM reaches the app. If the app runs as PID 1
  and does not handle signals, run it with `--init` or tini. Drain within the
  grace period (Kubernetes default 30 s; the sequence is in
  `backend-reliability-performance/references/health-shutdown.md`). (P)
- `HEALTHCHECK` is for Docker and Compose only; Kubernetes uses probes. (T)
- Docker's official best-practices page was not fetched; these defaults come
  from secondary sources. (P)

## 2. Image scanning, SBOM and signing
- Scan with trivy or grype and fail on fixable HIGH or CRITICAL findings; SBOM
  with syft or `buildx --sbom`; sign with cosign; a private registry with
  immutable tags. Supply-chain rules: `backend-security-privacy/references/secrets-and-supply-chain.md`.
  (T)

## 3. Kubernetes essentials
- The scheduler keeps "the sum of the resource requests of the scheduled
  containers… less than the capacity of the node". A LimitRange injects
  namespace defaults. (V)
- A PodDisruptionBudget takes only one of `minAvailable` / `maxUnavailable`,
  "can only protect against voluntary evictions", and `maxUnavailable: 0` "may
  block node drains entirely". No PDB exists by default (verify). (V; the
  default P)
- Common practice: CPU requests; memory request = limit; CPU limits avoided for
  latency-sensitive services (debated); HPA minimum of 2 for user-facing
  services; secrets from a cloud secret manager via External Secrets or CSI
  (Kubernetes Secrets are only base64 unless etcd encryption is on); namespaces
  are not a security boundary on their own. (T)
- TODO(verify): whether a request defaults to the limit when unset, and how PDB
  percentages round, are not confirmed in the reports.

## 4. When Kubernetes is not right
- Teams of 1–10 engineers without a platform owner should prefer a PaaS,
  serverless containers, or a VM with docker-compose or systemd plus managed
  Postgres. (T)
- Adopt Kubernetes when you need many services or custom scheduling and
  someone owns upgrades (a minor release about every 4 months; verify). (T)

## 5. Infrastructure as code: tool and state
- Terraform or OpenTofu (the fork after HashiCorp's 2023 licence change);
  Pulumi if the team prefers a general-purpose language. (T)
- State in S3 with `use_lockfile = true` (GA since Terraform 1.11), versioning
  and encryption. HashiCorp: "DynamoDB-based locking is deprecated and will be
  removed in a future minor version"; advice that still says "S3 plus a
  DynamoDB lock table" is outdated. OpenTofu supports `use_lockfile` and keeps
  DynamoDB locking without deprecating it. (V)
- Migrating: set both, run `init -reconfigure`, then remove DynamoDB once every
  runner is on 1.11 or later; a runner on an older version can bypass the new
  lock. (V; the bypass risk P)
- GCS and azurerm backends lock natively. (T)
- Separate state per environment and per blast-radius unit (network, data,
  app). (T)
- IaC state moves or imports are a High trigger in this skill.

## 6. Plan before apply
- CI posts the plan on the PR, a human reviews it, and only the saved plan from
  that commit is applied. (T)
- Block or require explicit approval for `destroy` or `-/+ replace` on stateful
  resources; set `prevent_destroy` and deletion protection on databases and
  buckets. IaC destroy or replace is a High trigger in this skill. (T)

## 7. Drift, secrets in state, tags
- Drift: a nightly `plan -detailed-exitcode` with alerts. (T)
- Secrets in state: keep them out where possible with write-only arguments or
  ephemeral resources (Terraform 1.11 and later); otherwise keep state in an
  encrypted, access-restricted backend, or use OpenTofu state encryption.
  Reference secret-manager ids rather than values. (V)
- Tags: `owner`, `env`, `service`, `cost-center` via `default_tags`. (T)

## 8. Staging
- Same artifacts, IaC modules, config schema and migration path as
  production, smaller and with synthetic data. Not a week-long manual QA gate,
  not a holder of production data, not a hand-tweaked "almost production". (T)
- Data and config changes must go through staging too: Google's June 2025 bug
  passed a region-by-region rollout because it needed a policy change to
  trigger. (V)
- Production personal data in staging is a High trigger in
  backend-security-privacy. (T)

## 9. Configuration at startup
- Twelve-factor: non-secret config from env vars or mounted files versioned in
  git; secrets fetched from a secrets manager at runtime, never baked into
  images. (T)
- Validate config at startup with a typed schema (pydantic-settings,
  zod / envalid, Spring `@Validated @ConfigurationProperties`, Go envconfig)
  and fail fast before readiness passes, so bad config never takes traffic.
  (T)
- Production config changes are a High trigger in this skill; the rollout
  rules for generated config are in `release-and-rollback.md`. (T)

## 10. Cost basics
- Budgets with 50/80/100% alerts plus anomaly detection from day one;
  policy-enforced tags with weekly untagged-resource reports; monthly
  right-sizing; savings plans only for stable baseline load. (T)
- Common surprises: NAT gateway processing (use S3 and DynamoDB VPC endpoints),
  cross-AZ traffic, internet and CDN egress, idle load balancers, unattached
  volumes, public IPv4 (now billed on AWS), log ingestion, oversized database
  instances. (T)
