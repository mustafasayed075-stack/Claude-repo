#!/bin/bash
# Cloud containers start with a fresh ~/.claude, so re-deploy the Engineering
# Skill System at session start. Local machines install once by hand instead.
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
./scripts/install.sh --check >/dev/null 2>&1 && exit 0
if ./scripts/install.sh >/dev/null 2>&1; then
  # ~/.claude/CLAUDE.md was read before this hook ran, so this session would
  # miss the routing instructions; SessionStart stdout adds them to context.
  cat global/CLAUDE.md
else
  echo "Engineering Skill System failed to install; run ./scripts/install.sh to see why."
fi
exit 0
