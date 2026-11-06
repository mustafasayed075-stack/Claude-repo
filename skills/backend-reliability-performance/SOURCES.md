# Sources (backend-reliability-performance)

Condensed ledger for this skill, drawn mainly from the build research report
Batch C (reliability, storage and performance), with incident details from
Batch D and API items from Batch A, plus the reviewed backend skills spec
(source ledger and verification pass). The full per-claim ledgers with URLs
live in those reports, which are not part of this repository. Payment
providers are cited generically.

Type: primary (official docs, papers, vendor post-mortems) or secondary
(blogs, summaries, author knowledge). Status: V verified, P partly verified,
T judgement or secondary.

| Source | Adopted | Ignored and why | Type | Status |
|---|---|---|---|---|
| AWS Builders' Library, "Timeouts, retries, and backoff with jitter" (fetched) | Timeout method and pitfalls, single retry layer, 243× amplification, token-bucket retry limits, breaker caution, jitter on all timers, pre-connecting | Nothing material | Primary | V |
| Google SRE book ch. 22, "Addressing Cascading Failures" (fetched) | Overload chain, queue sizing, load shedding, 64× retry math, 60-per-minute budget, deadlines, bimodal latency example, rate-limit placement | Google-internal tooling | Primary | V (19.6% figure P) |
| Google SRE book ch. 21, "Handling Overload" | Three attempts per request, 10% retry ratio, 1.1× growth | The throttling formula | Primary | V |
| Google SRE book ch. 3 and Appendix B | Error budgets, release freeze, quarterly budgets above 99.99% | Organisational process | Primary | V |
| Bronson et al., HotOS 2021; Huang et al., OSDI 2022 | Metastable-failure definition | Taxonomy details | Primary | V |
| AWS DynamoDB us-east-1 summary (Oct 2025) | Root cause, timeline, congestive collapse, automation disabled | Third-party cost estimates | Primary | V |
| GitLab 2017 database outage post-mortem | Data loss, wrong host, no WAL archiving, failed backups, DMARC-rejected alert emails | Restore-time and pg_dump version details (secondary) | Primary | V |
| Cloudflare post-mortem (18 Nov 2025) and summaries | Cause chain and timings | The 200-feature limit appears only in summaries; "% of internet" claims | Primary + secondary | V (limit P) |
| GitHub post-incident analysis (Oct 2018) | 43-second partition, 24 h 11 min degradation, failover lesson | Nothing material | Primary | V |
| Google Cloud incident report (Jun 2025), quoted | Unflagged code path, global replication, null-pointer crash loop, 7 h 27 min total | Blame percentages | Secondary quoting primary | V |
| Kubernetes probes docs | Probe semantics, the cascading-failure warning | Blog threshold recipes | Primary | V |
| Amazon SQS docs | Visibility timeout default and range, retention | DLQ enqueue-time behavior (secondary only) | Primary | V (DLQ P) |
| Kafka KafkaProducer javadoc (3.6) | Idempotence default since 3.0, `transactional.id` | `read_committed` detail (secondary) | Primary | V |
| microservices.io (outbox, polling publisher, log tailing) | Outbox benefits, relay trade-offs | Product promotion | Primary catalogue | V |
| PostgreSQL docs (SELECT locking, client settings) | `SKIP LOCKED` quote, per-role `statement_timeout` advice | Settings owned by the api-data skill | Primary | V |
| redis.io, cloud Redis docs | `noeviction` for queues on a separate instance | Eviction defaults (owned by the api-data skill) | Primary + secondary | P |
| IETF Idempotency-Key draft | Idempotent retries rely on keys (owned by the api-data skill) | Draft status | Primary, non-final | P |
| Spec verification pass (Opus) and adversarial review | Node fetch default of 300 s, Python free-threading from 3.14, JDK 24 pinning fix, libuv pool 4, DLQ enqueue time on standard queues only, single-relay ordering rule, job-run row preferred, attempts convention, container-aware GOMAXPROCS (Go 1.25), shutdown timing | Points it could not settle stay "verify" | Review | V/P |
| Practitioner posts (HikariCP sizing, autovacuum, DLQ counts, backoff starting values) | Corroboration only | Low-quality posts ignored | Secondary | P |
| Author knowledge in the reports (tool timeouts, bulkheads, rate-limit algorithms, profilers, coordinated omission, HTTP tuning) | Defaults marked T | Not re-checked against primary sources | Secondary | T |
| Payment providers' webhook docs | Not adopted as named facts; rules kept generic | Gateway names are excluded from the skills | n/a | n/a |

## Notes on SKILL.md rows

Source commentary kept out of the decision table:

- REL-1: the p99.9 / false-timeout-rate method is from the AWS Builders'
  Library.
- REL-2: the tool defaults are tool notes (status T); the Node `fetch` value
  was corrected to 300 s in the spec's review.
- REL-4: Google's example: with 5% of requests hanging for 100 s, only about
  20% of requests succeed; the exact figure is P.
- REL-6: three retries at each of three layers give 64 database attempts; five
  layers with three retries give 243×.
- REL-7: breakers add modal behavior (AWS Builders' Library).
- REL-10: Kafka's idempotent producer is on by default since 3.0.
- REL-14: the DLQ keeps the original enqueue time on standard queues only.
- REL-19: metastable failure (Bronson et al., HotOS 2021).
- REL-23: Python free-threading from 3.14; virtual-thread pinning fixed in
  JDK 24; container-aware GOMAXPROCS from Go 1.25 (verify).
