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
tests/router-scenarios.md  discovery and routing test record
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

**Cloud sessions:** `~/.claude` in a claude.ai/code container is reset when
the container is reclaimed. Re-run `./scripts/install.sh` in each new session,
or add it to the environment's setup script.
