# Observability (backend-testing-delivery)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Version facts are as of Nov 2026: check your version.
Sources: `../SOURCES.md`.

What must never be logged:
`backend-security-privacy/references/data-protection-logging.md`. Choosing
SLIs, SLOs and error budgets, and outage lessons:
`backend-reliability-performance/references/slo-and-incidents.md`. Health
endpoints: `backend-reliability-performance/references/health-shutdown.md`.

## Contents
1. Signals and OpenTelemetry
2. Sampling
3. What to instrument by default
4. Structured logging
5. Metrics and cardinality
6. Version facts
7. Alerting on SLO burn
8. Page vs ticket, and the runbook template
9. Dead man's switches and synthetic checks
10. Error tracking and release markers
11. Incident response for a small team
12. Post-mortem template
13. Observability that prolonged outages
14. Cost-aware observability

## 1. Signals and OpenTelemetry
- Default: the OpenTelemetry SDK plus auto-instrumentation for the HTTP
  framework, database driver, Redis and queue clients, exporting OTLP to an
  OpenTelemetry Collector that handles sampling, personal-data scrubbing and
  batching; vendor-neutral, so you can switch backends without
  re-instrumenting. (T)
- Metrics for cheap, aggregatable alerting and trends (low cardinality only);
  traces for where time went per request (high cardinality is fine); logs for
  discrete events and the "why". Put `trace_id` in every log line. (T)
- Propagation: W3C Trace Context (`traceparent` / `tracestate`) is the
  OpenTelemetry default. Put `traceparent` in queue message headers:
  auto-instrumentation often misses custom consumers, breaking traces at async
  boundaries. (T)
- When the default is wrong: a tiny monolith may need only logs, RED metrics and
  an error tracker; tracing pays off at more than one hop or with async work.
  (T)

## 2. Sampling
- OpenTelemetry: with head sampling "you cannot ensure that all traces with an
  error within them are sampled". (V)
- Start with `OTEL_TRACES_SAMPLER=parentbased_traceidratio` plus
  `OTEL_TRACES_SAMPLER_ARG`; ParentBased keeps traces complete. (P)
- Add collector tail sampling only if errors and slow requests must always be
  kept. With more than one collector it needs trace-id-affine routing, and it
  buffers spans for a decision wait (30 s is commonly cited; verify). A trace
  dropped at the head never reaches the tail policy. (P)
- Under about 50 requests per second, sample 100%. (T)

## 3. What to instrument by default
- HTTP server duration histogram by method, route template and status;
  outbound HTTP duration, status and host; database query duration, pool
  in-use, waiters and timeouts (never raw SQL with literals); queue publish
  count, consume duration, failures, lag or depth, oldest-message age, DLQ
  depth; job last-success timestamp and duration; runtime (GC, heap,
  event-loop lag, goroutines, thread pools); a `build_info{version,commit}`
  gauge. (T)

## 4. Structured logging
- JSON to stdout, one event per line, with an RFC 3339 UTC `timestamp`,
  `level`, `service`, `version`, `env`, `trace_id`, `span_id`, `request_id`,
  `tenant_id` (if not sensitive), an event name and a message. The platform
  ships logs; containers never write log files. (T)
- Levels: ERROR = someone may need to act (also sent to the error tracker);
  WARN = degraded but handled; INFO = business state transitions; DEBUG off in
  production, switchable at runtime. Do not log and rethrow at every layer. (T)
- Volume: sample 1–10% of INFO access logs, keep 100% of WARN and ERROR, no
  logging in tight loops; app logs 7–30 days hot. Audit logs follow their own
  retention (`backend-security-privacy/references/data-protection-logging.md`).
  (T)
- Enforce the never-log list with an allow-list request serializer plus
  collector-side redaction. Logging-redaction changes are a High trigger in
  this skill. (T)

## 5. Metrics and cardinality
- RED for request-driven services, USE for resources (pools, CPU, disks,
  queues), or the four golden signals. Measure error latency separately. (T)
- Histograms by default: they aggregate across instances and summary quantiles
  do not. Prometheus: "it is highly recommended to use histograms over
  summaries whenever possible", and "averaging the quantiles yields
  statistically nonsensical values". Aggregate buckets:
  `histogram_quantile(0.9, sum by (le) (rate(x_bucket[10m])))`. (V)
- Put a bucket boundary at the SLO threshold. p99 on low traffic is noise;
  prefer "fraction of requests under X ms" as the SLI. (T)
- Cardinality is the main way metrics fail. Labels multiply (2 methods × 50
  routes × 5 statuses = 500 series per bucket set; add `user_id` and it
  explodes). Use route templates, watch `prometheus_tsdb_head_series`, keep
  per-user analysis in traces, logs or a warehouse. A bounded `tenant_tier` is
  fine. Prometheus advises keeping most metrics' cardinality below 10 and
  investigating anything that may grow past 100 (seen only as a search
  snippet; verify). (P)
- Naming: snake_case, base units (`_seconds`, `_bytes`), `_total` for counters;
  recording rules named `level:metric:operations`. (T)

## 6. Version facts
- Prometheus native histograms are stable since v3.8 but need
  `scrape_native_histograms: true`. (V)
- While migrating from classic to native histograms, the
  `always_scrape_classic_histograms` setting keeps the classic series scraped
  as well (named in the spec's review; verify its exact behaviour on your
  version). (P)
- The OpenTelemetry HTTP server duration metric is
  `http.server.request.duration` (stable, in seconds). (V)
- TODO(verify): OpenTelemetry's default histogram buckets are not covered by
  the reports.

## 7. Alerting on SLO burn
- The SRE Workbook calls multiwindow, multi-burn-rate alerting "the most
  appropriate approach to defending your application's SLOs": 2% of a 30-day
  budget consumed in 1 h (burn rate 14.4) or 5% in 6 h (burn rate 6) pages; 10%
  in 3 days (burn rate 1) opens a ticket. (V)
- Short windows are 1/12 of the long ones (5 m, 30 m, 6 h). Rule shape:
  `(1h > 14.4× AND 5m > 14.4×) OR (6h > 6× AND 30m > 6×)` pages;
  `3d > 1× AND 6h > 1×` tickets. Generators: Sloth, Pyrra, Grafana SLO, Google
  Cloud SLO alerting. (P)
- Burn-rate math for a 99.9% SLO: burn rate 1 = 0.1% errors (the budget lasts
  30 days); 10 = 1% (3 days); 1,000 = 100% (43 minutes). (V)
- Do not use `for:` durations for SLO paging: the workbook does "not recommend
  using durations as part of your SLO-based alerting criteria"; 100% error
  spikes every 10 minutes never fire a 10-minute `for:` alert yet consume 35% of
  the budget. (V)
- Low traffic: alerting is "particularly sensitive to nonsignificant events
  during low-traffic periods". Use synthetic traffic, a minimum event count
  (`and sum(increase(errors[1h])) > 5`), longer windows or aggregated services.
  (V for the warning, T for the fixes)

## 8. Page vs ticket, and the runbook template
- Page only on user-visible symptoms tied to an SLO. Cause signals (CPU, disk at
  80%, pod restarts, queue depth) become tickets or dashboards. Exceptions that
  page directly: imminent irreversible harm (disk full in under 4 h, a
  certificate expiring in under 7 days, failing backups) and dead man's
  switches. (T)
- A page must be urgent, actionable, user-impacting and novel. Demote any alert
  that fired twice without action. A target from the SRE book: at most about 2
  incidents per 12-hour shift (from memory; verify). (T)
- Runbook template, linked from every page: what the alert means; dashboards
  to open; first checks; safe mitigations; escalation. (T)

## 9. Dead man's switches and synthetic checks
- Check jobs and the alert pipeline for absence: `absent()`,
  `time() - last_success_timestamp > X`, and an always-firing Watchdog routed
  to an external heartbeat service. Knight Capital's 97 pre-open emails "weren't
  designed as system alerts"; GitLab's backup-failure emails were rejected by
  DMARC. (P)
- Synthetic probes from a different provider every 1–5 minutes: login, one
  read, one sandbox-tenant write, TLS expiry. Keep monitoring independent of
  what it monitors: in Cloudflare's November 2025 outage an unrelated
  status-page outage reinforced a wrong DDoS hypothesis. (T for the probes, P
  for the incident)

## 10. Error tracking and release markers
- An error tracker from day one: tag `release` and `environment`, upload
  source maps or symbols, scrub personal data in `before_send` and server-side,
  alert on new issue types after deploys. (T)
- Emit deploy events (a dashboard annotation, an error-tracker release, a
  `build_info` change): "did it start at the deploy?" is the first incident
  question. (T)

## 11. Incident response for a small team
- Severities by user impact: SEV1 full outage, data loss or breach (status page
  within 15–30 minutes); SEV2 major degradation; SEV3 minor with a workaround.
  (T)
- Roles: incident commander, fixer, comms. Solo, the incident commander posts
  updates on a fixed 30-minute cadence. (T)
- Mitigate first (roll back, flip the flag, fail over); find the root cause
  later. (T)
- A personal-data breach also starts legal clocks:
  `backend-security-privacy/references/egypt-pdpl-gdpr-pci.md`. (T)
- On-call: at least 2 people, or business-hours paging with an SLO that admits
  it; a weekly handoff and page review. (T)

## 12. Post-mortem template
- Blameless, within 5 business days for SEV1/2: impact; a UTC timeline; the
  detection gap; contributing factors; what went well; owned and dated actions.
  (T)

## 13. Observability that prolonged outages
- Cloudflare (Nov 2025): the cause was visible in proxy module logs, not in the
  traffic shape. (P)
- Facebook (Oct 2021): losing DNS broke the internal tools needed to
  investigate, and an audit tool bug "prevented it from properly stopping the
  command". (V)
- AWS (Oct 2025): DynamoDB DNS was identified as the source by 12:38 AM, but EC2
  recovery ran long after (`backend-reliability-performance/references/slo-and-incidents.md`).
  (V)
- Keep out-of-band access, observability independent of the production data
  plane, and dependency-health dashboards. (T)

## 14. Cost-aware observability
- Small teams (1–10 engineers): managed first, for example a hosted Prometheus,
  Loki and Tempo tier, a hosted tracing service, an error tracker, and an
  external uptime or heartbeat service. (T)
- Self-host only when data residency requires it (Egypt PDPL cross-border rules
  may push towards in-country hosting), when SaaS cost exceeds roughly 0.5–1
  engineer-day of operations a month, or when you already run Kubernetes and
  kube-prometheus-stack. Telemetry sent to a foreign-hosted service may be a
  cross-border transfer (backend-security-privacy). (T)
- Cost levers: drop unused metrics with `metric_relabel_configs`, sample traces
  and INFO logs, shorten retention, avoid per-pod high-cardinality labels, and
  watch NAT and cross-AZ egress for telemetry export. (T; `metric_relabel_configs`
  P)
