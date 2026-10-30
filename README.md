# Claude-repo — Engineering Skill System

Reusable, project-agnostic engineering skills for Claude Code. This repo is the
**source of truth**; the skills are **deployed** to the user-level Claude Code
directory so they apply in every project.

## Phase 1 (Foundation)

| Skill | Purpose |
|---|---|
| `skill-router` | Detect project type and task type; choose which skills to load, or none. |
| `engineering-standards` | Baseline rules: understand first, right-size architecture, errors/security, tests, Definition of Done, autonomy vs. escalation. |
| `architecture-planning` | Right-sized design before large or risky changes. |

Existing built-in skills (`code-review`, `security-review`, `simplify`, `run`,
`init`, `claude-api`) are referenced by the router and not duplicated.
Domain skills (Web, Mobile, Games, AI, DevOps) are a later phase.

## Layout

```
skills/<name>/SKILL.md   source of each skill (official Claude Code skill format)
global/CLAUDE.md         instruction block that tells Claude to use the router
scripts/install.sh       deploys skills + instruction block to ~/.claude
tests/                   test harness (run.sh, score.py) and RESULTS.md
.claude/                 SessionStart hook that redeploys in cloud sessions
```

## Deploy

```bash
./scripts/install.sh            # install or update
./scripts/install.sh --check    # verify deployed copy matches source
./scripts/install.sh --uninstall
```

The installer:
- copies each skill to `~/.claude/skills/<name>/` (user-level, all projects);
- inserts or replaces a marked block in `~/.claude/CLAUDE.md`, leaving
  any other content in that file alone;
- refuses to overwrite a same-named skill it did not install;
- honors `CLAUDE_HOME` to target another directory.

Start a new Claude Code session after installing. Edit the files under `skills/`
in this repo and re-run the installer. Don't edit the deployed copies.

## Persistence between sessions

`~/.claude` on a local machine persists, so run the installer once there.
In claude.ai/code cloud sessions the container, and its `~/.claude`, is
rebuilt for each session. Here is how each case is covered:

| Where the session runs | Mechanism | Status |
|---|---|---|
| Cloud session on **this repo** | `.claude/settings.json` SessionStart hook runs `.claude/hooks/session-start.sh`, which installs if `--check` fails. It prints the routing instructions into the session's context, because `~/.claude/CLAUDE.md` was already read before the hook ran. | Automatic. Verified: fresh session with an empty `~/.claude` got the skills and the instructions. It runs from the branch the session checks out, so merge to `main` for it to apply by default. |
| Cloud session on **another repo** | Add to the environment's **Setup script** (environment settings → Edit):<br>`git clone --depth 1 https://github.com/mustafasayed075-stack/Claude-repo /opt/engineering-skills && /opt/engineering-skills/scripts/install.sh` | Configured by you in the environment settings. Not verified from here (no tool to edit environments). It clones `main`, so merge first. In the first session, run `--check` (below) to confirm. |
| Any session, fallback | Run the same two commands by hand. | Skills load immediately. The CLAUDE.md routing rule applies from the next session; until then, ask Claude to follow `skill-router`. |
| Local machine | `./scripts/install.sh` once. | Persistent. The hook does nothing outside the cloud (`CLAUDE_CODE_REMOTE`). |

Check a session: `./scripts/install.sh --check` (or
`/opt/engineering-skills/scripts/install.sh --check`).

## Tests

`tests/run.sh [REPEATS] [OUT_DIR]` builds throwaway fixture repos and runs
headless `claude -p` sessions against them, then `tests/score.py` reports:
- **Discovery** - a fresh session lists the skills and knows the routing rule.
- **Selection** - which skills each run invoked vs. what was expected.
- **Execution** - Router line, edits vs. stopping for approval, tests
  touched and run.

Latest results: `tests/RESULTS.md`.
