#!/usr/bin/env bash
# TEST-ONLY helpers (source this file) to build disposable sandboxes for live
# Claude Code tests. Everything goes under a directory the caller created
# with mktemp; the real ~/.claude, its settings and permissions are never
# read or written.
HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HARNESS/../.." && pwd)"

# sb_home <home-dir> <event-log> [--skills-dir]
# A fresh HOME whose ~/.claude/settings.json registers the event logger and
# allows read-only and test commands, so interactive runs need no permission
# prompts. With --skills-dir, an empty ~/.claude/skills/ exists beforehand.
sb_home() {
  local h="$1" log="$2"
  mkdir -p "$h/.claude"
  [[ "${3:-}" == --skills-dir ]] && mkdir -p "$h/.claude/skills"
  python3 - "$h/.claude/settings.json" "$HARNESS/logger.py" "$log" <<'PY'
import json, os, sys
path, logger, log = sys.argv[1:4]
hook = lambda phase: [{"matcher": "", "hooks": [{"type": "command",
        "command": f"python3 '{logger}' '{log}' {phase}", "timeout": 30}]}]
allow = ["Read", "Glob", "Grep"] + [f"Bash({c}:*)" for c in (
    "ls", "cat", "head", "tail", "wc", "grep", "find", "sed -n", "bash -n",
    "git status", "git log", "git diff", "git show",
    "./scripts/install.sh", "scripts/install.sh", "bash scripts/install.sh",
    "tests/installer_test.sh", "bash tests/installer_test.sh")]
# Reading the sandbox's own ~/.claude is harmless and avoids prompts.
extra = [os.path.join(os.path.dirname(path))]
json.dump({"permissions": {"allow": allow, "additionalDirectories": extra},
           "hooks": {"PreToolUse": hook("pre"), "PostToolUse": hook("post")}},
          open(path, "w"), indent=2)
PY
}

# sb_project <dir>: a copy of this repository's working tree (uncommitted
# changes included) to run sessions in. Edits land only in the copy.
sb_project() {
  mkdir -p "$(dirname "$1")"
  cp -a "$REPO_ROOT" "$1"
}

# sb_state <dir>: working-tree state hash (same function the logger uses).
sb_state() { python3 "$HARNESS/logger.py" state "$1"; }
