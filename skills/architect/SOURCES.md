# Sources (architect)

From the architect final spec (v1, 6 Nov 2026), which merges the research
spec with an adversarial review and source verification. Quotes are kept under
15 words per source; everything else is paraphrase. Status tags: V verified on
a primary page, P partly verified or experience-based, T judgement. Never
upgrade a tag without reading the primary page.

| Source | Adopted | Ignored | Tag |
|---|---|---|---|
| martinfowler.com MonolithFirst, MicroservicePremium (read) | Monolith first, modular at the API and data layer, the premium costs | Treating it as measured evidence (the author calls it anecdotal) | P |
| milanjovanovic.tech modular monolith (read, Jul 2026) | Extraction drivers, separate data per module | .NET specifics | T |
| InfoQ and Network World on Prime Video (read; original blog blocked) | One service cut infrastructure cost over 90% | Reading it as a verdict against services (Cockcroft: one service refactored) | P |
| quality.arc42.org hexagonal (read) | Dependency rule, composition root, CI import test, skip for thin CRUD | Full layering everywhere | V |
| InfoQ Team Topologies review (read) | Align module ownership with team and context | Team prescriptions | T |
| InfoQ fitness functions 2025; Thoughtworks Radar 2018 (read) | Few automated checks in the pipeline, tolerated ranges tightened later | Large metric catalogues; "too many checks" wording (the source warns about too much direction too early) | P |
| adr.github.io/madr 4.0.0 (read) | Short ADR, naming, Confirmation section | Heavy templates | V |
| c4model.com and /diagrams (read) | Context and container are enough for most teams | Component and code levels by default | V |
| iso.org 78176 (read); genorma catalogue | The 2023 edition exists, with nine characteristics | Reproducing lists; the 2011 withdrawal rests on a secondary catalogue | P |
| threatmodelingmanifesto.org (read) | Four questions, start early, small slices, no hero modeler | Tooling | V |
| MDN threat modeling frameworks (read) | STRIDE as a prompt list | Mandatory STRIDE | P |
| CISA alert 16 Oct 2023, archived announcement, April 2023 joint guide (read; main CISA pages blocked, Oct 2023 PDF not read) | Secure by default, ownership of outcomes | Obligations written for large vendors | V |
| AWS REL11-BP05, Well-Architected definitions, Builders' Library static stability and avoiding fallback (read) | Static stability; avoid an unexercised fallback; pillar trade-offs | AWS-product detail | V |
| Google SRE ch. 22 (read) | Load test to the breaking point; unused paths do not work | Other chapters (not read) | V |
| 12factor.net processes (read) | Stateless processes | Other factors | V |
| DORA 2025 blog and the first third of the PDF (read) | AI raises throughput but still lowers stability; one case study on coupling | Presenting the coupling case study as a general finding | P |
| arXiv 2605.02741 (preprint) | A distinct smell profile; scattered logic across split files | Claims about correct code degrading; not Claude Code | P/T |
| arXiv 2604.16323 (position paper, no data) | The idea of an architectural seed checked against agent plans | Evidence weight | T |
| skills.cat | Dropped (arc42 says the same on a better source) | | |

## Pointers to the backend skills

Every backend pointer in this skill is written as
`<backend-skill>/references/<file>.md` and was checked against the real files in
`skills/`. Two spec pointers named a skill but no file; they resolve to:
- ARCH-40 cost metrics: `backend-reliability-performance/references/performance-and-scaling.md` (its cost section).
- ARCH-45 recovery mechanics: `backend-reliability-performance/references/slo-and-incidents.md` (recovery level and failover lessons), next to `backend-api-data/references/postgres-operations.md`.

## Not verified

- The Prime Video original post (figures rest on InfoQ and Network World).
- The CISA October 2023 PDF.
- The ISO/IEC 25010:2011 withdrawal (secondary only).
- The DORA 2025 PDF beyond its first third.
- OWASP threat modeling and LLM guidance.
- Google SRE chapters other than 22.
- The ARCH-17 order and the ARCH-3 thresholds.
- Every shape-table item.
- The Android store-policy and OEM background-kill items.
- Mermaid C4 syntax.
- Every Egypt item: connectivity, foreign-currency hosting cost, data
  residency, and Law 151/2020 duties
  (not legal advice; the Arabic text governs).
