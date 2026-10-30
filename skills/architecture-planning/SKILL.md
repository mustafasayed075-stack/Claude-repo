---
name: architecture-planning
description: Plan before building - use before a large or risky engineering change such as a feature touching 3+ files or several modules, a new service or subsystem, a data-model, storage, or public API change, a significant refactor, or starting a new project in an empty repo. Produces a right-sized plan (requirements, design, files, dependencies, tests, risks) and decides whether to proceed or stop for approval. Not for single-file fixes or small, well-understood edits.
---

# Architecture & Planning

Goal: a plan sized to the change. A small feature gets a short plan; a new
system gets a fuller one. Do not produce ceremony for its own sake.

Use it when the change touches 3+ files or several modules, adds a module,
service, or dependency, changes data models, storage, or public APIs, or
starts a new project. Skip it for small, well-understood edits.

## Step 1 - Requirements and constraints
- Restate the goal in one or two sentences and list explicit requirements.
- Note non-functional needs only where they matter: performance, scale,
  security, offline, platform limits, budget, deadlines.
- List unknowns. Ask the user only for those that change the design;
  otherwise state the assumption and continue.

## Step 2 - Inspect the current structure
- Map the relevant entry points, modules, data flow, and boundaries.
- Identify the existing architectural style and conventions, and the test
  setup. Read real code; do not infer structure from directory names alone.
- Note constraints the codebase imposes (framework lifecycle, ORM, state
  management, build system).

## Step 3 - Propose a design that fits
- Default to extending the existing structure. Introduce a new pattern or
  layer only with a concrete reason it solves.
- Size guide (adjust to the project):
  - Script / prototype: functions in few files; no layers.
  - Small app: modules by feature; keep framework conventions.
  - Medium/large or long-lived: clear boundaries between domain logic, I/O,
    and UI; interfaces only at boundaries that actually vary.
- If two approaches are viable, compare them briefly (complexity, risk,
  reversibility) and recommend one.

## Step 4 - Files, responsibilities, dependencies
- List files to create/modify, each with its one-line responsibility.
- Show dependency direction between modules; avoid new cycles.
- Call out new external dependencies and why existing ones do not suffice.
- Note data-model, API-contract, config, or migration changes.

## Step 5 - Tests and risks
- Which tests to add or change (unit, integration, e2e) and how the result
  will be verified end to end.
- Risks with mitigations: breaking changes, data loss, performance,
  security, rollout. Mark any item that needs user approval per the
  `engineering-standards` escalation rules.

## Step 6 - Sequence
- Break the work into small, independently verifiable steps, each leaving
  the code buildable. Put risky or uncertain steps early.

## Refactoring guardrail
Do not start a broad restructuring unless there is a clear, stated reason
(a blocking defect, a requirement the current structure cannot meet, or an
explicit user request). Prefer incremental refactors tied to the task.
A sweeping rewrite needs explicit user approval.

## Output format
Present the plan as:

```
Goal:
Assumptions / open questions:
Current structure (relevant parts):
Proposed design (and alternative considered):
Files & responsibilities:
Dependencies / contract changes:
Tests & verification:
Risks & approvals needed:
Steps:
```

Keep each field to a few lines; omit fields that do not apply.

## Proceed or stop
Classify the plan with the risk tiers in `engineering-standards` section 7:
- **Low / Medium:** show the plan, then implement it in the same turn. Do
  not ask for approval.
- **High** (changes architecture, data/storage semantics, security, public
  contracts, or is irreversible), or a new project in an empty repo where
  the stack is not specified: show the plan, mark the decisions that need
  approval, and stop before editing files. Reading code and reproducing
  issues is still fine.
