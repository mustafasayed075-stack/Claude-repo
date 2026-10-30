---
name: engineering-standards
description: Baseline engineering rules for writing or changing code in any project - understand before editing, right-size the architecture, separation of concerns, error handling and security, tests and verification, Definition of Done, and when to act autonomously versus escalate. Use when implementing a feature, fixing a bug, refactoring, or reviewing your own change before calling it done. Not needed for pure questions, explanations, or non-code tasks.
---

# Engineering Standards

Project rules win. If the repo has a CLAUDE.md, CONTRIBUTING, lint config, or an
established pattern, follow it over anything here.

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
- For changes that add a module, cross several layers, or alter data models
  or public APIs, use the `architecture-planning` skill first.

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
Classify every change before the first edit. The highest matching tier wins.

**Low - implement autonomously.** Local, reversible edits inside the task's
scope: code changes on the working branch, adding/updating tests, read-only
commands, builds, test runs, lint fixes, small in-scope refactors.

**Medium - short plan, then implement autonomously.** Multi-file features
or refactors that stay within the existing architecture, and new modules,
endpoints, options, or fields that add behavior without breaking existing
callers or stored data. State a short plan (`architecture-planning`) and
continue without waiting.

**High - analyze, present the plan and risks, then stop and wait for
approval.** At least:
- Database schema changes and migrations, including writing migration files.
- Core architecture changes: restructuring into new layers or patterns,
  new service/process boundaries, swapping a framework, state manager, or
  other major dependency, wide refactors beyond the task.
- Authentication, authorization, permissions, crypto, secrets, or other
  security controls.
- API, CLI, event, or data-contract changes that can break compatibility.
- Moving, deleting, or transforming data in ways that are hard to undo.
- Infrastructure changes that can cause downtime or data loss.
- Also: destructive git (force-push, history rewrite, branch deletion),
  deploy, publish, send, or anything that costs money; requirements that
  conflict or are too unclear to choose a safe design.

**Approval rules for High risk**
- A request to make a high-risk change approves the goal, not the
  implementation. It is never approval of a plan the user has not seen, no
  matter how explicitly the change was requested.
- Approval means an explicit yes from the user after the plan was shown.
  Until then, do read-only work only (read code, reproduce, analyze); do
  not create or edit files, including migrations and new modules.
- Approval covers the plan as shown. If implementation needs a material
  change to it, or uncovers a new high-risk element, stop and ask again.
- When stopping, give the options, your recommendation, the risk of each,
  and end with a direct request for approval of the plan.
