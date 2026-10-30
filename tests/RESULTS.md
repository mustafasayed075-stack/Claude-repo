# Phase 1 verification record

Run on 2026-10-30, Claude Code 2.1.314, after `./scripts/install.sh`.
Each test was a fresh headless session (`claude -p`).

## 1. Discovery

Prompt (no tools allowed): list skills matching engineering/router/architecture
and quote any user-level instruction about skill-router.

Result: all three skills listed with correct descriptions; the
`~/.claude/CLAUDE.md` block was quoted verbatim. **Pass.**

## 2. Router scenarios

Fixtures: small git repos with only the signal files listed below.
Command per scenario:

```bash
claude -p "<prompt>" --output-format stream-json --verbose \
  --max-turns 10 --disallowedTools "Edit,Write,NotebookEdit"
```

The prompts never mention skills. Routing comes from the global CLAUDE.md
block and the skill descriptions.

| # | Fixture signals | Prompt | Skills invoked (in order) | Router line / reasoning |
|---|---|---|---|---|
| 1 Web App | `package.json` react+vite, `index.html`, `src/Header.jsx` | Add a dark mode toggle to the header that persists across reloads | skill-router → engineering-standards | "Web frontend (React + Vite) \| Feature, small \| engineering-standards": self-contained header change, so no planning |
| 2 Backend API | `pyproject.toml` fastapi, `app/routes/users.py`, `tests/` | GET /users/{id} returns 500 for missing user; should be 404 | skill-router → engineering-standards | "Backend/API (Python) \| Bug \| engineering-standards": reproduce plus a regression test. It added a failing test and then ran pytest |
| 3 Game | `project.godot`, `scripts/player.gd` | Split movement/health/inventory in player.gd | skill-router → engineering-standards | "Game (Godot) \| Refactor \| engineering-standards": refactor stays inside the player, so no architecture-planning |
| 4 Mobile App | `pubspec.yaml` flutter, `lib/`, `android/`, `ios/` | Add offline caching + sync when back online | skill-router → architecture-planning → engineering-standards | Correct choice (multi-module feature), but **no one-line Router decision was printed** |
| 5 AI App | `package.json` with `@anthropic-ai/sdk`, `CHANGELOG.md` | Prepare release 1.2.0 | skill-router → engineering-standards | "AI/LLM app + Library \| Release \| engineering-standards": `claude-api` skipped because no API code changes. It would not invent changelog entries and left tag/publish to the user |
| control | API fixture | What does app/db.py do? | none | Correctly skipped routing for a pure question |

## Known gaps / not tested

- Scenario 4 skipped the one-line `Router:` output that the skill asks for.
  The skill selection was still correct.
- Scenario 4 began implementing a large design without waiting for approval.
  There is no user in headless mode, so this is not conclusive.
- Headless runs blocked some shell writes pending approval, so implementation
  quality was not assessed. These tests cover discovery and routing only.
- Not tested: interactive (TTY) sessions, the claude.ai desktop/web clients,
  Testing and Architecture-from-empty-repo task types, and repeat runs
  for variance (each scenario was run once).
