---
name: engineering-standards
description: Baseline engineering rules for writing or changing code in any project - understand before editing, right-size the architecture, separation of concerns, error handling and security, tests and verification, Definition of Done, and when to act autonomously versus escalate. Use when implementing a feature, fixing a bug, refactoring, or reviewing your own change before calling it done. Not needed for pure questions, explanations, or non-code tasks.
---

# Engineering Standards

Routing check: if `skill-router` ran for this task and you have not yet
written its `Router:` line, write it now, before anything else.

Project rules win. If the repo has a CLAUDE.md, CONTRIBUTING, lint config, or an
established pattern, follow it over anything here.

## Risk, scope and planning (read first; applies to every change)
This section is the single source of truth for risk classification, scope,
planning, shell writes and continuation. Other skills refer to it.

**Classify first.** Before the first modification of the working tree, by
any tool, write one line:

`Risk: Low|Medium|High - <reason in a few words>`

Low needs nothing more. Medium adds the short plan below. High adds the
complete plan and stops for explicit approval before any modification.

**Scope.** Judge a change by its impact and reversibility, not by how many
files it touches.
- *Implementation files* change what the software does: source code,
  configuration, schemas, build and deploy files.
- *Supporting files* are tests, docs, changelogs, fixtures and generated
  lockfiles. They never raise the tier by themselves.

**Tiers.** Check them in this order; the first that matches wins, regardless
of size: a one-line change can be High.
- **High - complete plan, then stop for explicit approval** (approval rules:
  section 7) when any of these High triggers applies:
  - database schema changes and migrations, including writing migration files;
  - core architecture changes: new layers or patterns, new service or
    process boundaries, swapping a framework, state manager or other major
    dependency, wide refactors beyond the task;
  - authentication, authorization, permissions, crypto, secrets or other
    security controls;
  - API, CLI, event or data-contract changes that can break compatibility;
  - moving, deleting or transforming data in ways that are hard to undo;
  - infrastructure changes that can cause downtime or data loss;
  - destructive git (force-push, history rewrite, branch deletion), deploy,
    publish, send, or anything that costs money;
  - requirements that conflict or are too unclear to choose a safe design.
- **Medium - short plan, then implement without waiting** for any new or
  changed behavior that no High trigger covers: additive, non-breaking
  changes such as a new CLI option or command, query parameter, endpoint,
  field or config key; a new internal module; changes spread across several
  implementation files or modules; mechanical changes across many files. A
  new runtime dependency is Medium by default, and High only when a High
  trigger applies (security-sensitive behavior, critical runtime or
  deployment impact, major architecture consequences, data or system
  changes that are hard to undo).
- **Low - implement directly:** changes that add no new or changed
  behavior: bug fixes that restore intended behavior and keep every
  contract, internal refactors within existing patterns, and tests-only or
  docs-only changes.

When uncertain between Low and Medium, classify as Medium. When uncertain
between Medium and High, check the High triggers above: High if any
applies, otherwise Medium.

**`architecture-planning` is independent of the risk tier.** Use it, at any
tier, when a change affects module or service boundaries or the
architecture, storage or data models, infrastructure, build or deploy,
breaking contracts, or introduces a meaningful architectural dependency (a
framework, ORM, datastore, messaging system or anything that shapes how the
code is structured). A library used inside one existing module is not
architectural. Its plan replaces the short Medium plan.

**Medium plan.** After the Risk line and before the first modification of
the working tree, by any tool, write:

```
Plan (Medium): <goal in one line>
- Scope: <implementation files>; supporting: <tests/docs>
- Approach: <how, in one or two lines>
- Validation: <tests and checks you will run>
```

Then implement without waiting for approval.

**Modifications and shell writes.** A modification is any change to the
working tree, whatever tool makes it; the tiers, plans and approvals above
apply to all of them.
- Use the Edit/Write tools for hand-written changes to tracked source, test,
  doc and config files. Several edits are several Edit calls.
- Shell commands may modify files when a tool does the work: generators,
  formatters, lockfile updates by the package manager, and mechanical
  transformations across many files (e.g. a sed rename). State the command,
  then show `git diff --stat`.
- Never use a shell heredoc or inline script to apply hand-written edits to
  tracked files, and never use the shell to get around a plan, an approval,
  a permission prompt or a risk control.

**Continuing a conversation.** The routing decision persists for the task it
was made for. For a new coding request in the same conversation, write a
fresh Router line; call `skill-router` again (and any skill whose rules you
need) only when its content is no longer in context, for example after
compaction.

## 1. Understand before editing
- Read the files you will touch and their direct callers/callees. Find how the
  project already solves the same kind of problem and reuse that pattern.
- Identify the build, test, and lint commands (package.json scripts, Makefile,
  pyproject, CI workflow) before changing code, so you can verify afterwards.
- Reproduce a bug before fixing it. If you cannot reproduce, say so.
- State assumptions explicitly when requirements are ambiguous; ask only when
  the answer changes what you build.

## 2. Right-size the architecture
- Match the structure to the project's size and lifespan. A script stays a
  script; a small app does not need layers, DI containers, or repositories.
- Prefer the existing architecture over a "better" one. No pattern
  (Clean/Hexagonal/MVC/ECS/...) is a default; adopt one only when a concrete
  problem calls for it.
- Add abstraction when there is a second real use, not in anticipation.
- Use `architecture-planning` when "Risk, scope and planning" (top of this
  skill) requires it.

## 3. Separation of concerns and maintainability
- One responsibility per function/module; keep I/O, business logic, and
  presentation apart where the project already does.
- Match surrounding naming, idiom, formatting, and comment density.
- Keep diffs minimal and on-task. No drive-by reformatting or unrelated
  refactors; note them as follow-ups instead.
- No dead code, commented-out blocks, or debug prints left behind.

## 4. Errors and security
- Handle errors at the boundary where you can act on them; never swallow
  exceptions silently. Error messages must help diagnose without leaking
  secrets or internals to end users.
- Validate untrusted input at system boundaries (HTTP, CLI, files, IPC, LLM
  output). Use parameterized queries, output encoding, and safe path handling.
- Never hard-code secrets; never log tokens, passwords, or personal data.
- Least privilege for credentials, permissions, and network access.
- Adding or upgrading a dependency is a decision: check it is maintained,
  needed, and license-compatible.
- For auth, crypto, payments, or permission changes, run the built-in
  `security-review` skill (if available) on the diff.

## 5. Tests and verification
- Run the existing tests that cover what you changed. Add or update tests for
  new behavior and for every bug fix (a test that fails before the fix).
- Test behavior through public interfaces, not implementation details.
- Run lint/type-check/build if the project has them.
- For UI or runtime behavior, actually run the app (built-in `run` skill if
  available) rather than inferring it works.
- Report results faithfully: what ran, what passed, what failed, what you
  could not run and why.

## 6. Definition of Done
A change is done only when all applicable items hold:
- [ ] Requirement met, including the edge cases discussed.
- [ ] Code follows project conventions; diff contains only intended changes.
- [ ] Builds; lint and type-check pass (or pre-existing failures noted).
- [ ] Relevant tests added/updated and passing; output was actually observed.
- [ ] Errors handled; no secrets or sensitive data exposed.
- [ ] Docs/config/migrations updated if behavior or setup changed.
- [ ] Summary given: what changed, how it was verified, known gaps.

## 7. Autonomy and escalation: autonomous by default, escalate by risk
The tiers are defined in "Risk, scope and planning" at the top of this
skill. Low and Medium proceed without waiting; High presents the complete
plan and stops for explicit approval under these rules.

**Approval rules for High risk**
- A request to make a high-risk change approves the goal, not the
  implementation. It is never approval of a plan the user has not seen, no
  matter how explicitly the change was requested.
- Approval means an explicit yes from the user after the plan was shown.
  Until then, do read-only work only (read code, reproduce, analyze); do
  not create or edit files, including migrations and new modules.
- Approval covers the plan as shown, and only that plan. After approval,
  first re-verify the plan against the current code: check what changed
  since the plan (`git status`, `git log`), and re-read the files the plan
  will touch and what they depend on (e.g. the latest migration, shared
  modules). Code can change between the plan and the approval. If
  something changed that the plan did not account for, treat it as a
  material deviation. Then implement within the plan:
  - **Material deviation - stop before making it, and ask for approval of
    the revised plan:** a different approach or design; scope beyond the
    plan; a component, service, data store, or interface the plan did not
    list as affected; a data operation the plan did not describe (e.g. a
    drop, rewrite, or backfill strategy that differs); a higher risk level;
    or a new external dependency. Keep work already done if it is still
    valid under the approved plan, and say what remains.
  - **Routine detail - proceed without asking:** naming, helper functions,
    file layout inside the listed components, extra test cases, and fixes
    to mistakes you made along the way, as long as the plan's approach and
    scope stay the same.
  - Every file you change outside the plan's affected components must be
    either a routine detail, which you list in the conformance line, or a
    reason to stop.
  - End the implementation report with
    `Plan conformance: followed approved plan` (only if every changed file
    was in the plan) or
    `Plan conformance: deviations - <each unplanned change and why>`.
- When stopping, give the options, your recommendation, the risk of each,
  and end with a direct request for approval of the plan.
