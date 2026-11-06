# Secrets and supply chain (backend-security-privacy)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Platform facts are as of Nov 2026: check current docs.
Sources: `../SOURCES.md`.

This file owns: the secrets-manager rule and the CI-to-cloud OIDC trust rules
(including GitHub's `sub` claim change). The deploy pipeline itself belongs to
backend-testing-delivery.

## Contents
1. Where secrets must never be
2. Storage and short-lived credentials
3. Zero-downtime rotation
4. After a leak
5. Secret scanning
6. CI permissions and OIDC trust
7. Pinning GitHub Actions and dependencies
8. Install scripts and package worms
9. Typosquatting and dependency confusion
10. Scanning, SBOM and provenance
11. Vetting packages, plugins and AI-agent skills
12. Infrastructure basics

## 1. Where secrets must never be
- Never in code, images (`docker history` exposes build args and ENV), CI logs,
  error messages, URLs, client bundles (`NEXT_PUBLIC_*` / `VITE_*` values are
  public), mobile apps or crash reports. (T)
- Environment variables leak via `/proc/*/environ`, child processes, `/env`
  debug endpoints, dumps and error trackers: fine for low-risk config, but
  fetch high-value secrets from a manager or mounted files. (T)

## 2. Storage and short-lived credentials
- Default: secrets live in a secrets manager. One credential per service per
  environment, least privilege. Prefer dynamic short-lived credentials (a
  Vault database engine, IAM roles, CI OIDC). (T)
- New secret types are a High trigger in this skill.

## 3. Zero-downtime rotation
- Add the new credential alongside the old → deploy consumers → confirm the old
  one is idle → revoke it. (T)
- Patterns: alternate two database users; publish old and new keys in the
  JWKS until the maximum token lifetime has passed; dual-sign webhooks or carry
  a key id. (T)

## 4. After a leak
- Rotate first (deleting the commit is not remediation) → audit usage during
  the exposure window → hunt for persistence (new IAM users or keys, OAuth
  apps, deploy keys) → scrub history last. (T)
- Secrets printed into public CI logs on 14–15 March 2025 in the
  tj-actions/changed-files compromise had to be treated as compromised. (V)

## 5. Secret scanning
- Pre-commit (gitleaks or trufflehog), CI, and push protection. Use prefix +
  checksum formats for your own keys so scanners can find them
  (`auth-and-sessions.md`). (T)

## 6. CI permissions and OIDC trust
- `GITHUB_TOKEN` read-only by default (`permissions: {}` at the top) with
  per-job grants. (T)
- Reach the cloud through OIDC, not stored keys. Restrict the cloud trust
  condition to the repository and environment (for example
  `repo:org/name:environment:production`). (P)
- GitHub changed the default OIDC `sub` claim: repositories created after
  15 July 2026 get an ID-based default. Check every existing cloud trust policy
  against the claim your repositories actually send. (V)
- Production environments with required reviewers. No secrets in
  `pull_request_target` workflows that check out untrusted code; no
  `${{ github.event.* }}` interpolated into `run:` (pass it through env); no
  self-hosted runners on public repositories; no cache sharing across trust
  levels. (T)
- New CI permissions or GitHub Actions are a High trigger in this skill.

## 7. Pinning GitHub Actions and dependencies
- Pin Actions to a full 40-character commit SHA, with Renovate or Dependabot
  bumping the pins. This is the default. GitHub's docs now also describe a
  lockfile-style control that may supersede SHA pinning; its name and status
  are unconfirmed, so use it only once it is available and confirmed. (P)
- Incident: tj-actions/changed-files (CVE-2025-30066, CVSS 8.6). A compromised
  bot token repointed tags v1–v45.0.7 on 14 March 2025 to code that dumped
  runner memory and printed secrets, double-base64-encoded, into logs; the
  GitHub advisory says it affected "over 23,000 repositories". Mutable tags were
  the delivery path. (V)
- Dependencies: commit lockfiles; `npm ci`, `--require-hashes`, `go.sum`;
  container images by digest. (T)

## 8. Install scripts and package worms
- `npm --ignore-scripts` by default, with allow-listed exceptions. An explicit
  `npm run` still runs its target script, so the flag does not cover scripts
  you call yourself. (V)
- Shai-Hulud (September 2025) ran via `postinstall`, harvested npm, GitHub and
  cloud tokens with TruffleHog, and republished itself into victims' packages;
  CISA's 23 September alert said it "has compromised over 500 packages".
  Shai-Hulud 2.0 (24 November 2025) backdoored 796 packages with over 20M
  weekly downloads (one security vendor's count). An April 2026 wave reportedly
  read OIDC tokens from runner memory (a single source), so short-lived tokens
  help but do not suffice. Package counts and mechanism rest on secondary
  sources. (P)
- npm responded with publish 2FA, granular short-lived tokens and trusted
  publishing. (P)

## 9. Typosquatting and dependency confusion
- A private registry with scoped namespaces; internal names never resolved
  from the public registry; reserve your names. (T)
- Consider a minimum-age cooldown before adopting brand-new versions (for
  example about 7 days; judgement). (T)

## 10. Scanning, SBOM and provenance
- SCA (OSV-Scanner, Dependabot, Trivy) plus malicious-package behaviour
  analysis: different risks, so you need both. (T)
- SBOMs in CycloneDX or SPDX; keyless signing with cosign; SLSA provenance;
  verify at deploy; npm provenance. (T)
- Images: distroless, non-root, multi-stage, rebuilt regularly. Dockerfile and
  pipeline details belong to backend-testing-delivery. (T)

## 11. Vetting packages, plugins and AI-agent skills
- Check maintainers and their 2FA, package age, install scripts, network or
  filesystem access at import time, transitive dependency count, repository
  provenance and open security issues. (T)
- AI-agent skills and plugins: read every file, look for env-var exfiltration
  or shell instructions, run them sandboxed without credentials, and pin the
  reviewed version. New plugins or skills are a High trigger in this skill. (T)

## 12. Infrastructure basics
- IAM: one role per service, resource-scoped, no `*:*`, trimmed with access
  analyzers. A role's breadth sets the blast radius (Capital One, 2019, from
  memory in the source report). IAM widening is a High trigger. (T)
- Network: databases in private subnets reachable only from app tiers; no
  public admin interfaces; restricted egress. (T)
- Database accounts: per-service users with minimal grants (no DDL, superuser
  or BYPASSRLS); a separate migrator role used only by migration jobs
  (backend-api-data); a masked read-only analytics role; SSO-brokered,
  time-bound human access. (T)
- Containers: non-root, read-only root filesystem, dropped capabilities,
  no-new-privileges, seccomp, resource limits, no Docker socket,
  NetworkPolicies, Pod Security "restricted". (T)
- Metadata service: enforce IMDSv2 account-wide or via an SCP on
  `ec2:MetadataHttpTokens`; block pod-to-node metadata access. (V)
- Backups: encrypted with keys in a separate account; immutable copies; tested
  restores; restricted, logged access; retention aligned with deletion. (T)
- Environments: separate accounts; no production personal data in lower
  environments without production-grade controls. Copying Egyptian users' data
  into a staging environment hosted abroad may itself be a cross-border
  transfer (`egypt-pdpl-gdpr-pci.md`). Production data in staging is a High
  trigger. (T)
