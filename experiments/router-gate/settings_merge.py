#!/usr/bin/env python3
"""EXPERIMENT: add or remove the router-gate hooks in a settings.json.

    settings_merge.py install   <settings.json> <gate-script>
    settings_merge.py uninstall <settings.json>

Only appends its own matcher groups to hooks.PreToolUse and
hooks.PostToolUse; every other key, hook and ordering is left as it was.
Its entries are recognised by the gate script's file name in the command.
Refuses to touch a file that is not valid JSON or not a JSON object. Writes
atomically, through symlinks, keeping the file mode; no write if unchanged.
"""
import json
import os
import sys
import tempfile

MARK = "esk-router-gate"
MATCHER = "Skill|Edit|Write|NotebookEdit|MultiEdit|Bash"


def ours(group):
    return any(MARK in str(h.get("command", "")) for h in group.get("hooks", []) if isinstance(h, dict))


def load(path):
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError("settings is not a JSON object")
    return data


def save(path, data, before):
    text = json.dumps(data, indent=2) + "\n"
    if text == before:
        return
    real = os.path.realpath(path)
    mode = os.stat(real).st_mode & 0o7777 if os.path.exists(real) else 0o644
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(real), prefix=".settings.")
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.chmod(tmp, mode)
    os.replace(tmp, real)


def main():
    action, path = sys.argv[1], sys.argv[2]
    before = open(path).read() if os.path.exists(path) else None
    data = load(path)
    hooks = data.get("hooks")
    if hooks is not None and not isinstance(hooks, dict):
        raise ValueError("hooks is not an object")
    if action == "install":
        cmd = os.path.abspath(sys.argv[3])
        hooks = data.setdefault("hooks", {})
        for event, phase in (("PreToolUse", "pre"), ("PostToolUse", "post")):
            groups = hooks.setdefault(event, [])
            if not any(ours(g) for g in groups if isinstance(g, dict)):
                groups.append({"matcher": MATCHER, "hooks": [
                    {"type": "command", "command": f"'{cmd}' {phase}", "timeout": 10}]})
    elif action == "uninstall":
        for event in ("PreToolUse", "PostToolUse"):
            if hooks and isinstance(hooks.get(event), list):
                kept = [g for g in hooks[event] if not (isinstance(g, dict) and ours(g))]
                if kept:
                    hooks[event] = kept
                else:
                    del hooks[event]
        # Empty containers left behind are removed (a user's own empty
        # "hooks": {} would be removed too).
        if hooks == {}:
            del data["hooks"]
    else:
        raise SystemExit("usage: settings_merge.py install|uninstall <settings.json> [gate-script]")
    save(path, data, before)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, json.JSONDecodeError) as e:
        print(f"ERROR: {e}. Nothing was changed.", file=sys.stderr)
        sys.exit(1)
