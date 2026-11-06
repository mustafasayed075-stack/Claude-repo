# SLOs and incident lessons (backend-reliability-performance)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

Burn-rate alerting, paging, on-call and post-mortem templates belong to
backend-testing-delivery. This file covers choosing SLIs and SLOs, error
budgets, what a target implies for the design, and outage lessons.

## Contents
1. SLIs, SLOs and error budgets
2. A small team's SLIs
3. What a target implies for the design
4. Measuring for mobile users
5. Incident lessons
6. Lessons in one list

## 1. SLIs, SLOs and error budgets
- Product sets an SLO, monitoring measures actual performance, and the
  difference is the error budget. Releases go ahead while budget remains and
  slow or stop when it is spent; once it is spent, freeze everything except
  urgent fixes. Use quarterly budgets for SLOs above 99.99%. (V)

## 2. A small team's SLIs
- Track 2–4 SLIs on user journeys (T):
  - availability: non-5xx responses / total;
  - latency: the share of requests under a threshold;
  - job freshness: the age of the oldest unprocessed message.
- Set SLOs at 99.5–99.9%. 99.9% per month allows about 43 minutes of downtime
  (arithmetic). If budget is healthy, ship features; if it is burned, do
  reliability work. (T)

## 3. What a target implies for the design
- A 99.9% SLO does not justify multi-region active-active for a three-person
  team. Multi-AZ, good backups and a tested restore usually meet it. (T)
- Large organisations targeting 99.99%+ need cells, multiple regions and
  automated rollback. (T)
- Failover, replication or backup configuration changes are a High trigger in
  this skill.

## 4. Measuring for mobile users
- Measure SLIs at the client or edge: server-side metrics hide last-mile
  failures, which dominate for users on unreliable mobile networks. (T)

## 5. Incident lessons

| Incident | What happened | Lesson | Status |
|---|---|---|---|
| GitLab.com, 31 Jan 2017 | Spam load caused replication lag; while re-syncing, an engineer deleted the data directory on the primary instead of the replica. About 6 hours of database data was lost. Its notes: "out of 5 backup/replication techniques deployed none are working reliably or set up in the first place"; there was no WAL archiving | Automate restore tests; use WAL archiving and PITR; make destructive-command targets obvious; alert on backup size and age | V (the ~18 h restore and the pg_dump version mismatch: P) |
| AWS us-east-1, 19–20 Oct 2025 | A race condition in DynamoDB's DNS automation left an empty DNS record for the regional endpoint. DynamoDB errors ran 11:48 PM–2:40 AM PDT, but EC2 and NLB recovery ran to 2:20 PM PDT: EC2's lease manager "had entered a state of congestive collapse"; engineers throttled incoming work and restarted hosts selectively. AWS disabled the automation worldwide | Hidden dependencies; recovery is its own overload event; single-region dependence | V |
| Cloudflare, 18 Nov 2025 | A database permissions change made a query return duplicate rows; a generated feature file grew past a hard-coded limit and the proxy crashed network-wide. Failures began at 11:20 UTC, errors resolved starting 14:30, and all systems were normal by 17:06 | Validate generated config; keep a last-known-good version; never panic on bad input | V (times); P (the 200-feature limit) |
| GitHub, 21 Oct 2018 | During routine equipment replacement, connectivity between sites "was restored in 43 seconds, but this brief outage triggered a chain of events that led to 24 hours and 11 minutes of service degradation", through a cross-coast database failover | Automated cross-region failover can be worse than waiting | V |
| Google Cloud, 12 Jun 2025 | A code path without error handling or a feature flag hit blank-field policy data that was replicated globally within seconds; a null-pointer crash-looped binaries. Total duration 7 h 27 min | Flag new paths; validate data; propagate progressively | V |

Release-side controls for config and data pushes (validation, rings, kill
switches): backend-testing-delivery. Global config or flag pushes are a High
trigger in this skill.

## 6. Lessons in one list
- Untested backups are not backups. (V)
- Fast, global propagation of an unvalidated config or data change is a leading
  cause of modern outages. (V)
- Recovery from an outage can be harder than the trigger: plan admission
  control and backlog draining (`health-shutdown.md`). (V)
- Automation that "repairs" state can create impossible states; guard it. (V)
- Keep observability and access independent of the system they watch
  (backend-testing-delivery). (T)
