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
- copies each skill to `~/.claude/skills/<name>/` (user-level, all projects)
  and marks it with an ownership file, `.engineering-skill-system`;
- only replaces or removes a skill directory that carries that marker, and
  refuses (install) or skips (uninstall) any other directory with the same name;
- changes `~/.claude/CLAUDE.md` only between its BEGIN/END markers, in
  place, and leaves the rest of the file alone. If the markers are damaged it
  stops and writes nothing. A symlinked CLAUDE.md keeps its link (the target
  file is updated) and the file mode is kept. The file is replaced
  atomically, only when its content changes, and is never deleted;
- runs every check before its first write, so a refusal leaves nothing
  half-installed;
- `--check` compares every skill file and the managed CLAUDE.md block
  against this repo and reports `STALE`, `MISSING`, `FOREIGN` or `DAMAGED`;
- honors `CLAUDE_HOME` to target another directory.

Start a new Claude Code session after installing. Edit the files under `skills/`
in this repo and re-run the installer. Don't edit the deployed copies.

## Persistence between sessions

`~/.claude` on a local machine persists, so run the installer once there.
In claude.ai/code cloud sessions the container, and its `~/.claude`, is
rebuilt for each session. Here is how each case is covered:

| Where the session runs | Mechanism | Status |
|---|---|---|
| Cloud session on **this repo** | `.claude/settings.json` SessionStart hook runs `.claude/hooks/session-start.sh`, which installs if `--check` fails and then prints one SessionStart JSON object: `additionalContext` carries the routing instructions (`~/.claude/CLAUDE.md` was already read before the hook ran) and `reloadSkills: true` makes Claude Code re-scan skills, because skill discovery runs before SessionStart hooks finish. | Automatic. Verified live (tests/p0_validation.sh): the three skills are listed before the first response in fresh interactive and headless sessions, after an upgrade, and after resume/compaction. It runs from the branch the session checks out, so merge to `main` for it to apply by default. |
| Cloud session on **another repo** | Add to the environment's **Setup script** (environment settings → Edit):<br>`git clone --depth 1 https://github.com/mustafasayed075-stack/Claude-repo /opt/engineering-skills && /opt/engineering-skills/scripts/install.sh` | Configured by you in the environment settings. Not verified from here (no tool to edit environments). It clones `main`, so merge first. In the first session, run `--check` (below) to confirm. |
| Any session, fallback | Run the same two commands by hand. | Skills load immediately. The CLAUDE.md routing rule applies from the next session; until then, ask Claude to follow `skill-router`. |
| Local machine | `./scripts/install.sh` once. | Persistent. The hook does nothing outside the cloud (`CLAUDE_CODE_REMOTE`). |

Check a session: `./scripts/install.sh --check` (or
`/opt/engineering-skills/scripts/install.sh --check`).

## Tests

Code tests (deterministic, executed):

- `tests/installer_test.sh`: installer, SessionStart hook and `run.sh`
  temp-dir guard, in a `mktemp` sandbox with its own `CLAUDE_HOME`/`HOME`.
  No network and no `claude` calls.
- `tests/setup_flow_test.sh`: the setup-script route for other repos, end
  to end, in a disposable sandbox: a fresh `HOME`, a local `file://` clone of
  the committed branch, and a throwaway project. Live `claude` checks can be
  skipped with `LIVE=0`. It verifies that the real `~/.claude` and this repo
  are unchanged.

Live-test harness (test-only, disposable sandboxes): `tests/harness/` holds
a tool-event logger that records the working-tree state per tool call (so
the first real modification is found whatever tool made it), a tmux driver
for interactive sessions that never approves permission prompts, and an
analyzer that reports Passed / Failed / Blocked / Inconclusive per turn.
`tests/harness_test.sh` tests it deterministically; `tests/p0_validation.sh`
runs the live P0 scenarios.

Behavior tests (`tests/run.sh [REPEATS] [OUT_DIR]`): headless `claude -p`
sessions against throwaway fixtures, scored by `tests/score.py` into
separate results:
- **Discovery**: a fresh session lists the skills, quotes the routing
  instruction verbatim, and rejects a decoy skill.
- **Selection**: which skills each run invoked vs. what was expected.
- **Behavior**: the Router line, autonomous edits for Low/Medium, and for High
  a plan with `Risk: High` and Rollback, an approval request, and no edits.
- **After approval**: a `--resume` turn approves the plan. Compliant runs
  must implement within the plan and print `Plan conformance:`. In the
  `conform-*` runs a teammate commit lands before approval, and the run must
  stop and ask for a revised plan.
- **Router-line probes**: short extra first turns for a larger sample.
- **Code execution**: test commands that actually ran, with their output,
  read from tool results. Behavior passes are not code verification.
- **INVALID**: turns that hit an API or usage-limit error are excluded and
  listed separately. Rerun them with `REPS="3" SKIP_DISCOVERY=1`.

`heldout-*`, `new-*` and `conform-refactor-deviation` were written after
the skill text they test and were not used to shape it.

Latest results: `tests/RESULTS.md`.
