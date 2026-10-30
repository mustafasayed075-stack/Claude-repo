---
name: skill-router
description: Decide how to approach a software engineering task in any repository - detect the project type from its files, classify the task (feature, bug, refactor, architecture, testing, release), and pick which skills to load, or decide none are needed. Use at the start of a coding task in a repo, especially an unfamiliar one, or when unsure which engineering skills apply. Skip for pure questions, chit-chat, or non-code work.
---

# Skill Router

A short decision step, not a process. Spend a few tool calls at most, then
work. Recommend only skills that appear in this session's available-skills
list; if a mapped skill is missing, skip it and say so.

## 1. Detect project type (quick look at the root and one level down)

| Signals | Project type |
|---|---|
| `package.json` with react/vue/svelte/angular/next/nuxt/vite/astro; `index.html`; `src/app`, `pages/` | Web frontend / full-stack web |
| express/fastify/nest/koa; `manage.py`, django/flask/fastapi; `go.mod` + net/http/gin; spring `pom.xml`/`build.gradle`; `Cargo.toml` + axum/actix; `routes/`, `controllers/`, OpenAPI file | Backend / API |
| `pubspec.yaml` (Flutter); react-native/expo in package.json; `android/` + `ios/`; `*.xcodeproj`; `AndroidManifest.xml` | Mobile app |
| `ProjectSettings/` + `Assets/` (Unity); `project.godot`; `*.uproject`; phaser/pixi/three/bevy/pygame deps | Game |
| anthropic/openai/langchain/llamaindex/transformers/torch deps; prompts/, agents/, evals/, embeddings, vector DB clients | AI / LLM app |
| `Dockerfile`, `docker-compose*`, `*.tf`, `k8s/`, `helm/`, `.github/workflows` only | Infra / DevOps |
| `setup.py`/`pyproject` or package.json with `main`/`exports` and no app entry | Library / package |
| Only a few scripts | Script / tool |

Projects can match several rows (e.g. AI + Backend). Record all matches.
An empty repo has no type yet: treat a request to "build X" as Architecture.

## 2. Classify the task

| Task | Typical wording |
|---|---|
| Feature | add, implement, support, new screen/endpoint |
| Bug | fix, broken, error, crash, regression, wrong output |
| Refactor | clean up, restructure, extract, rename, simplify (no behavior change) |
| Architecture | design, new project/service/module, choose stack, migrate framework |
| Testing | add tests, coverage, flaky test, test strategy |
| Release | version bump, changelog, build/publish, deploy prep, PR readiness |

## 3. Select skills

| Task | Load | Also consider (if available) |
|---|---|---|
| Feature (small, 1-2 files) | `engineering-standards` | - |
| Feature (multi-module, new data/API) | `architecture-planning`, then `engineering-standards` | `code-review` on the diff before finishing |
| Bug | `engineering-standards` (reproduce, regression test) | `run` to reproduce/verify runtime bugs |
| Refactor | `engineering-standards`; add `architecture-planning` if it spans modules | `simplify`, `code-review` |
| Architecture | `architecture-planning` | - |
| Testing | `engineering-standards` (section 5) | `run` |
| Release | `engineering-standards` (Definition of Done) | `code-review`; `security-review` if auth/secrets/permissions changed |

Project-type add-ons (only if present in the session):
- AI app calling Claude/Anthropic: `claude-api`. Not for other providers.
- Repo with no CLAUDE.md and the user wants one: `init`.
- Web/Mobile/Game/DevOps domain skills: not built yet; apply the generic
  skills above plus the framework's own conventions.

## 4. When no extra skill is needed
- Questions, explanations, code reading, or reviews the user asked to just
  discuss.
- Trivial edits: typo, one-line config/text change, renaming a variable in
  one file, running a command the user specified.
- The user already named the skill or approach to use.
In these cases do the work directly; the standards still apply implicitly.

## 5. Output
State the decision in one line before starting, then proceed:

`Router: <project type(s)> | <task type> | skills: <list or "none"> - <short reason>`
