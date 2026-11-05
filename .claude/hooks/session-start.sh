#!/bin/bash
# Cloud containers start with a fresh ~/.claude, so re-deploy the Engineering
# Skill System at session start. Local machines install once by hand instead.
#
# Output contract: nothing when the install is already current; otherwise
# exactly one SessionStart JSON object on stdout. Skill discovery runs before
# SessionStart hooks finish, so after installing we ask Claude Code to
# re-scan (reloadSkills), and we pass the routing block as additionalContext
# because ~/.claude/CLAUDE.md was read before this hook ran.
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
cd "${CLAUDE_PROJECT_DIR:-"$(dirname "$0")/../.."}" || exit 0
./scripts/install.sh --check >/dev/null 2>&1 && exit 0

# emit <context> <reload: true|false> [message for the user]
emit() {
  python3 - "$@" 2>/dev/null <<'PY' && return
import json, sys
out = {"hookSpecificOutput": {"hookEventName": "SessionStart",
                              "additionalContext": sys.argv[1]}}
if sys.argv[2] == "true":
    out["hookSpecificOutput"]["reloadSkills"] = True
if len(sys.argv) > 3:
    out["systemMessage"] = sys.argv[3]
print(json.dumps(out))
PY
  jq -cn --arg c "$1" --argjson r "$2" --arg m "${3:-}" \
    '{hookSpecificOutput: ({hookEventName: "SessionStart", additionalContext: $c}
       + (if $r then {reloadSkills: true} else {} end))}
     + (if $m != "" then {systemMessage: $m} else {} end)' 2>/dev/null && return
  # No JSON encoder: plain-text context still reaches the session; the
  # skills then load from the next session on.
  printf '%s\n' "$1"
}

if err="$(./scripts/install.sh 2>&1 >/dev/null)"; then
  emit "$(cat global/CLAUDE.md)" true
else
  msg="Engineering Skill System was not installed: ${err:-unknown error}"
  emit "$msg" false "$msg"
fi
exit 0
