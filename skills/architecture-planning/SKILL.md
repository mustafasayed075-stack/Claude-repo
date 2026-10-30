---
name: architecture-planning
description: Plan before building - use before a large or risky engineering change such as a new feature spanning several modules, a new service or subsystem, a data-model or public API change, a significant refactor, or choosing an architecture for a new project. Produces a right-sized design covering requirements, constraints, current structure, files and responsibilities, dependencies, tests, and risks. Not for single-file fixes or small, well-understood edits.
---

# Architecture & Planning

Goal: a plan sized to the change. A small feature gets a short plan; a new
system gets a fuller one. Do not produce ceremony for its own sake.

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

For large or high-risk plans, wait for the user's approval before
implementing. For moderate plans within a clearly scoped task, proceed and
follow `engineering-standards` while implementing.
