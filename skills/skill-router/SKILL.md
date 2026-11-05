---
name: skill-router
description: Decide how to approach a software engineering task in any repository - detect the project type from its files, classify the task (feature, bug, refactor, architecture, testing, release), set its risk tier (Low, Medium, High) in the Router line, and pick which skills to load, or decide none are needed. Use at the start of a coding task in a repo, especially an unfamiliar one, or when unsure which engineering skills apply. Skip for pure questions, chit-chat, or non-code work.
---

# Skill Router

A quick decision, not a process. Do exactly this:

1. **Look** at the file listing (root and one level down) and the main
   manifest (package.json, pyproject.toml, pubspec.yaml, ...). Do not read
   source files to route; that belongs to the task itself.
2. **Decide** project type, task type, the risk tier, and skills using the
   tables below.
3. **Write one line of text**, then act on it. Write it in the same
   response as your next tool call (text first), whatever that call is: loading
   a skill, reading a file, or running a command. Never skip it because you
   are about to call a tool:

   `Router: <project type> | <task type> | risk: <Low|Medium|High> (<High trigger or none>; <new|restores|preserves>) | skills: <list or none> - <reason, max ~12 words>`

   The risk: field is the classification: answer it with the two questions
   of engineering-standards ("Risk, scope and planning"): does a High
   trigger apply (name it, or write none), and what kind of change is it;
   the tier test in your global instructions lists those triggers in
   engineering-standards' words. For a fix that restores the established
   contract write `restores (evidence: <where>)`. When uncertain between Low
   and Medium, write Medium.

   The reason says what the task is, never its risk, size or difficulty: no
   risk or size verdict about the task (for example 'low-risk', 'small
   change' or 'trivial fix'); task words such as 'high score' or 'risk
   scoring' are fine.

   When the tier is Medium, write `Plan (Medium):` (engineering-standards)
   in this same response, before any tool call other than loading skills.

Then load the chosen skills and do the task. No other routing report.

Recommend only skills in this session's available-skills list; if a mapped
skill is missing, skip it.

## Project type

| Signals | Type |
|---|---|
| package.json with react/vue/svelte/angular/next/nuxt/vite/astro; index.html; src/app, pages/ | Web |
| express/fastify/nest/koa; django/flask/fastapi; go.mod + net/http/gin; spring; axum/actix; routes/, controllers/, OpenAPI | Backend/API |
| pubspec.yaml; react-native/expo; android/ + ios/; *.xcodeproj | Mobile |
| Unity (ProjectSettings/ + Assets/); project.godot; *.uproject; phaser/pixi/three/bevy/pygame | Game |
| anthropic/openai/langchain/llamaindex/transformers/torch; prompts/, agents/, evals/, vector DB | AI/LLM |
| Dockerfile, compose, *.tf, k8s/, helm/, CI workflows only | Infra |
| package/pyproject exporting a library, no app entry | Library |
| no source files (empty or README only) | Empty - a "build X" request is Architecture |

Several types can match (e.g. AI + Backend); list them all.

## Task type

| Task | Typical wording |
|---|---|
| Feature | add, implement, support, new screen/endpoint |
| Bug | fix, broken, error, crash, regression, wrong output |
| Refactor | clean up, restructure, split, extract (no behavior change) |
| Architecture | design, new project/service, choose stack, migrate framework |
| Testing | add tests, coverage, flaky test, test strategy |
| Release | version bump, changelog, build/publish, deploy prep |

## Skills

Risk tiers, scope and when `architecture-planning` is needed are defined in
engineering-standards, section "Risk, scope and planning". Route by those
definitions, not by file count. The tier is the one you state in the risk:
field of the Router line; a re-check after reading the code can raise it.

| Situation (first matching row wins) | Load |
|---|---|
| risk: High (every High change needs it), or architectural impact visible in the request, as defined there | `architecture-planning`, then `engineering-standards` - both before any edit |
| Architecture, or a "build X" request in an empty repo | `architecture-planning` |
| Release | `engineering-standards` (Definition of Done) |
| Any other change, any task type | `engineering-standards`; a High classification there loads `architecture-planning` before the plan (every High change needs it) |

Optional built-ins, only when they clearly add value:
- `run` - to reproduce or verify runtime/UI behavior.
- `code-review`, `simplify` - on a large diff before finishing.
- `security-review` - when auth, secrets, or permissions changed.
- `claude-api` - when the task changes code that calls Claude/Anthropic.

Web/Mobile/Game/Infra domain skills do not exist yet; follow the
framework's own conventions.

## Continuing a conversation
Your routing decision persists for its task. For a new coding request, write a fresh Router line; call this skill again only if its content is no longer in context (e.g. after compaction).

## No skill needed
Questions, explanations, code reading, and edits that cannot execute:
comments, documentation files and prose, whitespace the toolchain ignores.
Do the work directly; skip the Router line too. Every code, config, schema,
build or deploy edit is routed, including docstrings and user-visible
strings, whatever its size and even when the user dictated the change.

**Before your next tool call: have you written the `Router:` line? If not,
write it now.**
