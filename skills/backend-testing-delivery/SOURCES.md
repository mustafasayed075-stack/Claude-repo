# Sources (backend-testing-delivery)

Condensed ledger for this skill, drawn mainly from the build research report
Batch D (observability, testing and release engineering), plus the reviewed
backend skills spec (source ledger, verification pass and adversarial review).
The full per-claim ledgers with URLs live in those reports, which are not part
of this repository. Payment providers are cited generically.

Type: primary (official docs, vendor post-mortems, regulators) or secondary
(blogs, summaries, author knowledge). Status: V verified, P partly verified,
T judgement or secondary.

| Source | Adopted | Ignored and why | Type | Status |
|---|---|---|---|---|
| Google SRE Workbook ch. 5, "Alerting on SLOs" | Multiwindow multi-burn-rate rules and numbers, burn-rate math, the critique of `for:` durations, the low-traffic caveat | Later tables truncated; the short-window pairing seen only in secondaries | Primary | V (pairing P) |
| Prometheus docs (histograms and summaries, query functions, metric types, instrumentation) | Histograms over summaries, no averaging of quantiles, `histogram_quantile` with `le` | The cardinality wording was seen only as a search snippet | Primary | V (wording P) |
| Prometheus 3.8 notes (via the spec's verification pass) | Native histograms stable, `scrape_native_histograms` flag; `always_scrape_classic_histograms` for migration | Exact migration-flag behavior not confirmed | Primary via review | V (flag P) |
| OpenTelemetry docs (sampling; HTTP semantic conventions via the spec) | Head-sampling limits; `http.server.request.duration` (stable, seconds) | Default histogram buckets not covered | Primary | V |
| Datadog, Uptrace and similar sampling write-ups | Tail-sampling routing and buffering | Product pitches | Secondary | P |
| Google Testing Blog: "Flaky Tests at Google and How We Mitigate Them" (2016, mirror) | 1.5% flaky runs, 84% of pass→fail transitions, causes, quarantine | Nothing material | Primary (mirror) | V |
| Google Testing Blog: "Test Sizes" (2010) | Small, medium and large definitions and limits | Nothing material | Primary | V |
| *Software Engineering at Google*, ch. 13 (test doubles) | Real over doubles, fakes, mock overuse | Nothing material | Primary | V |
| Pact docs, "When to use Pact" | Fit and non-fit criteria | Nothing material | Primary | V |
| Testcontainers introduction | Real dependencies over in-memory substitutes; isolated services per pipeline | Nothing material | Primary | V |
| Testing Trophy and Practical Test Pyramid articles | Test shapes; the JavaScript scoping | Full texts not read | Secondary | P |
| DORA 2025 report (snippets) and DORA summaries | Five metrics, rework-rate definition, archetypes, AI raises throughput and instability | Numeric benchmark bands (sources conflict, so none are given) | Primary snippet + secondary | P |
| Feature-toggle articles (Hodgson / Fowler) | Four toggle types, carrying cost, owner and expiry | Article not fetched | Secondary | P |
| SEC order and press release on Knight Capital (via quotes) | Missed server, repurposed flag, rollback made it worse, loss figure | Varying loss figures; the order itself not read | Secondary quoting primary | P |
| CrowdStrike root-cause analysis (Aug 2024) | 21-vs-20 input mismatch, validator error, staged rollout lesson; Microsoft's 8.5 million estimate | Mitigation dates (secondary) | Primary | V |
| Google Cloud incident report (Jun 2025), quoted | No feature flag, global replication, null crash, "If this had been flag protected…" | Blame percentages | Secondary quoting primary | V |
| Meta engineering post on the 4 Oct 2021 outage | Audit-tool bug, BGP withdrawal, internal tools down | Nothing material | Primary | V |
| AWS DynamoDB us-east-1 summary (Oct 2025) | Race condition, ~15 h duration, identification time | Cost estimates | Primary | V |
| Cloudflare 18 Nov 2025 post-mortem summaries | Cause visible in module logs; status-page red herring; feature-file limit | "2.4 billion users" claim | Secondary | P |
| GitLab 2017 post-mortem | Untested backups, DMARC-rejected failure emails | Restore-time details (secondary) | Primary snippet + secondary | P |
| GitHub OIDC docs | No long-lived cloud keys in CI | Trust-rule detail (owned by the security skill) | Primary | V |
| HashiCorp S3 backend docs; AWS Terraform backend guidance | `use_lockfile` GA in 1.11, DynamoDB deprecation, migration steps | Nothing material | Primary | V |
| Spec verification pass (Opus) | OpenTofu keeps DynamoDB locking; write-only arguments and ephemeral resources (Terraform 1.11+); Google Cloud June 2025 total 7 h 27 min; no DORA bands | Points it could not settle stay "verify" | Review | V/P |
| Kubernetes docs (resources, PDB) | Scheduling rule, PDB semantics | An old unofficial PDB default | Primary excerpts | V |
| Docker hardening blogs | Digest pinning, multi-stage, non-root, secret mounts | Vendor pushes; Docker's official page not fetched | Secondary | P |
| Author knowledge in the report (tool lists, policies, cadences, templates) | Defaults marked T | Not re-checked against primary sources | Secondary | T |
| Payment providers' sandbox docs | Not adopted; sandbox behavior could not be verified | Gateway names are excluded from the skills | n/a | n/a |

## Notes on SKILL.md rows

Source commentary kept out of the decision table:

- TST-5, TST-7: Testcontainers and *Software Engineering at Google* ch. 13.
- TST-6: the Pact docs' "when to use" page.
- TST-11: Google measured about 1.5% flaky runs (2016).
- TST-14: Prometheus histogram guidance; version facts in
  `references/observability.md`.
- TST-15: SRE Workbook ch. 5 burn-rate numbers (2% in 1 h, 5% in 6 h page; 10%
  in 3 days tickets).
- TST-19: Knight Capital (2012) reused a flag's meaning.
- TST-20: rollback made Knight Capital's incident worse.
- TST-21: CrowdStrike (Jul 2024), Google Cloud (Jun 2025), Cloudflare (Nov
  2025).
- TST-23, TST-25: version facts (BuildKit secret mounts, Terraform 1.11
  `use_lockfile`, OpenTofu, write-only arguments) are in
  `references/containers-iac-config.md`.
- TST-26: OIDC trust rules, including GitHub's July 2026 `sub` change, are
  owned by backend-security-privacy.
- TST-27: GitLab (2017) had five backup methods and none worked.
- TST-29: one gateway's webhook test mode and another's developer portal could
  not be verified during research.
