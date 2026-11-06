# Style and boundaries (architect)

Status tags: V verified on a primary page, P partly verified or
experience-based, T judgement (verify in your environment). Sources:
`../SOURCES.md`.

## Contents
1. ARCH-1 Modular monolith first
2. ARCH-2 First vertical slice
3. ARCH-3 When to extract a module
4. ARCH-4 Technology choices
5. ARCH-5 Quality attributes
6. ARCH-6 Modules own their tables
7. ARCH-7 Dependency rule for rule-heavy modules
8. ARCH-8 Module classes, with examples
9. ARCH-9 Shared kernel
10. ARCH-10 No cycles
11. ARCH-11 Default stores
12. ARCH-41 Data ownership
13. ARCH-42 Public interfaces

## 1. ARCH-1 Modular monolith first (P)
- Start as a modular monolith: one deployable, strict internal module
  boundaries, unless an ARCH-3 trigger is already true.
- One deployable may run several process types (web, worker, scheduler) from
  the same codebase. That is not a split and needs no ADR.
- Why: services add network failure modes and operating cost, the
  "microservice premium" (automated deploy, monitoring, failure handling,
  eventual consistency). The evidence is experience-based: Fowler calls his
  own evidence anecdotal.

## 2. ARCH-2 First vertical slice (T)
- Build the first vertical slice end to end (one real user flow through every
  layer) before designing the full target system.

## 3. ARCH-3 When to extract a module (P for drivers, T for thresholds)
Keep a module in the monolith until one trigger is true and written in an ADR
with evidence:
- (a) a different runtime or hardware profile (for example a Python ML worker
  or GPU), or a resource share far larger than the rest, measured in
  production;
- (b) a different availability or security tier (for example a public webhook
  receiver isolated from admin data);
- (c) it must release on a cadence the rest cannot follow;
- (d) a separate team owns it;
- (e) legal or contractual isolation (T, verify).

Before extracting, the boundary must already hold inside the monolith (own
tables, public interface, no cycles: ARCH-6, ARCH-10) for a stated period.

Anti-triggers, never a reason on their own: "cleaner", "future scale", "to
learn microservices", "the framework suggests it".

## 4. ARCH-4 Technology choices (T)
- Every technology choice records its reason and one rejected alternative.
- Prefer boring technology the team can debug.

## 5. ARCH-5 Quality attributes (P)
- Rank 3 to 5 quality attributes before choosing: for example security,
  availability, cost, latency, changeability, operability.
- The trade-off principle is V (AWS Well-Architected definitions page); the
  number "3 to 5" is judgement.

## 6. ARCH-6 Modules own their tables (P)
- A module is a business capability that owns its tables. Other modules use
  its public interface or its events, never its tables.

## 7. ARCH-7 Dependency rule for rule-heavy modules (V)
Applies to modules that ARCH-8 classifies as rule-heavy.
- Domain code imports no framework, database or HTTP library.
- Adapters implement ports that the core defines.
- One composition root wires them together.
- An architecture test fails on any core-to-framework import.

## 8. ARCH-8 Module classes, with examples (V)
- Record each module's class (rule-heavy or thin CRUD) in the module map in
  CLAUDE.md.
- Thin CRUD modules use handler-service-repository. Their only enforced rule is
  "no imports from other modules' internals or tables".
- Ports and adapters everywhere is overhead.

Illustrations of the classification (examples, not sourced facts; T):

| Module | Class | Reason |
|---|---|---|
| Pricing and discounts | Rule-heavy | Business rules that change often and must be tested without a database |
| Order lifecycle (states, refunds) | Rule-heavy | Transitions and invariants |
| Content filter rules on a device | Rule-heavy | Matching logic independent of the platform |
| User profile edit | Thin CRUD | Read and write fields, little logic |
| Admin lookup tables (cities, categories) | Thin CRUD | Plain storage |
| Notification preferences | Thin CRUD | Settings stored and read back |

## 9. ARCH-9 Shared kernel (T)
- The shared kernel is small, listed by file in CLAUDE.md, and every addition
  is a reviewed change.
- No shared utils or common dumping module.
- Money representation: Rules: `backend-api-data/references/api-contracts.md`.

## 10. ARCH-10 No cycles (P)
- No circular dependencies between modules; CI fails on a cycle.

## 11. ARCH-11 Default stores (T)
- One relational database plus object storage for files by default.
- A cache or any other store needs a measured reason and an ADR (ARCH-48).
- Shape override: a mobile app with on-device logic stores on the device.

## 12. ARCH-41 Data ownership (P)
- Each entity has exactly one owning module (its system of record).
- Other modules read through its interface, or hold read-only copies with a
  stated staleness.

## 13. ARCH-42 Public interfaces (T)
- Decide which interfaces are public (mobile apps in the field, partners,
  webhooks) and their compatibility window. Mobile clients cannot be forced to
  update fast.
- Mechanics (versioning, breaking changes): Rules:
  `backend-api-data/references/api-contracts.md`.
