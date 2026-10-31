#!/usr/bin/env python3
"""EXPERIMENT: PreToolUse/PostToolUse gate for the skill-router decision.

Enforces one technical invariant from structured hook payloads only:
after `skill-router` is loaded, no other skill is loaded and no file-edit
tool runs until the Router decision has been recorded as an inert tool call:

    echo 'Router: <project type> | <task type> | skills: <list or none> - <reason>'

It never classifies tasks, chooses skills, or reads assistant prose or the
transcript. State per conversation lives in a small file. Any unexpected
input or internal error fails open (exit 0, no output), so a broken gate
never blocks unrelated work.

Usage (from settings.json): esk-router-gate.py pre | post
"""
import hashlib
import json
import os
import re
import sys

ROUTER = "skill-router"
PROTECTED = {"Skill", "Edit", "Write", "NotebookEdit", "MultiEdit"}
# Single-quoted, nothing else on the line: inert, so safe to auto-allow.
DECISION = re.compile(r"^echo '(Router: [^'|\n]+\|[^'|\n]+\| ?skills: [^'\n]+)'$")
STATE_DIR = os.environ.get("ESK_GATE_STATE_DIR") or os.path.join(
    os.path.expanduser("~"), ".claude", "esk-router-gate")


def state_file(p):
    # session_id alone is not unique (headless child sessions can inherit
    # it), so key on session_id + transcript_path.
    key = f"{p.get('session_id', '')}|{p.get('transcript_path', '')}"
    if key == "|":
        return None
    return os.path.join(STATE_DIR, hashlib.sha256(key.encode()).hexdigest()[:32])


def read_state(path):
    try:
        with open(path) as f:
            return f.read().strip()
    except OSError:
        return ""


def write_state(path, value):
    os.makedirs(STATE_DIR, exist_ok=True)
    tmp = f"{path}.{os.getpid()}"
    with open(tmp, "w") as f:
        f.write(value)
    os.replace(tmp, path)


def emit(decision, reason):
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": decision,
        "permissionDecisionReason": reason}}))


def main():
    phase = sys.argv[1] if len(sys.argv) > 1 else ""
    p = json.load(sys.stdin)
    if not isinstance(p, dict):
        return
    tool = p.get("tool_name")
    ti = p.get("tool_input") if isinstance(p.get("tool_input"), dict) else {}
    path = state_file(p)
    if path is None:
        return
    skill = str(ti.get("skill", "")).split(":")[-1]
    command = str(ti.get("command", "")).strip()

    if phase == "post":
        if tool == "Skill" and skill == ROUTER:
            write_state(path, "PENDING")
        elif tool == "Bash" and DECISION.match(command):
            write_state(path, "DECIDED")
        return

    if phase != "pre":
        return
    if tool == "Bash" and DECISION.match(command):
        emit("allow", "Router decision recorded.")
        return
    if tool not in PROTECTED or (tool == "Skill" and skill in (ROUTER, "")):
        return  # unreadable Skill input fails open like any malformed payload
    if read_state(path) == "PENDING":
        emit("deny",
             "skill-router ran but its decision is not recorded yet. Record it first "
             "with exactly one Bash call (single quotes, nothing else on the line): "
             "echo 'Router: <project type> | <task type> | skills: <list or none> - <reason>' "
             "-- then retry this call.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass  # fail open
    sys.exit(0)
