---
name: skill-router
description: Decide how to approach a software engineering task in any repository - detect the project type from its files, classify the task (feature, bug, refactor, architecture, testing, release), and pick which skills to load, or decide none are needed. Use at the start of a coding task in a repo, especially an unfamiliar one, or when unsure which engineering skills apply. Skip for pure questions, chit-chat, or non-code work.
---

# Skill Router

A quick decision, not a process. Do exactly this:

1. **Look** at the file listing (root and one level down) and the main
   manifest (package.json, pyproject.toml, pubspec.yaml, ...). Do not read
   source files to route; that belongs to the task itself.
2. **Decide** project type, task type, and skills using the tables below.
3. **Write one line** as your next text output, before loading any other
   skill or reading further:

   `Router: <project type> | <task type> | skills: <list or none> - <reason, max ~12 words>`

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

| Situation | Load |
|---|---|
| Small change (1-2 files, existing patterns) - any task type | `engineering-standards` |
| Touches 3+ files or several modules, adds a module/dependency, or changes data, storage, or public API | `architecture-planning`, then `engineering-standards` |
| Architecture, or a "build X" request in an empty repo | `architecture-planning` |
| Release | `engineering-standards` (Definition of Done) |

Optional built-ins, only when they clearly add value:
- `run` - to reproduce or verify runtime/UI behavior.
- `code-review`, `simplify` - on a large diff before finishing.
- `security-review` - when auth, secrets, or permissions changed.
- `claude-api` - when the task changes code that calls Claude/Anthropic.

Web/Mobile/Game/Infra domain skills do not exist yet; follow the
framework's own conventions.

## No skill needed
Questions, explanations, code reading, trivial edits (typo, one-line
change, a command the user specified), or when the user named the
approach. Do the work directly; skip the Router line too.
