#!/bin/bash
# Cloud containers start with a fresh ~/.claude, so re-deploy the Engineering
# Skill System at session start. Local machines install once by hand instead.
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
cd "${CLAUDE_PROJECT_DIR:-"$(dirname "$0")/../.."}" || exit 0
./scripts/install.sh --check >/dev/null 2>&1 && exit 0
if err="$(./scripts/install.sh 2>&1 >/dev/null)"; then
  # ~/.claude/CLAUDE.md was read before this hook ran, so this session would
  # miss the routing instructions; SessionStart stdout adds them to context.
  cat global/CLAUDE.md
else
  echo "Engineering Skill System was not installed: ${err:-unknown error}"
fi
exit 0
