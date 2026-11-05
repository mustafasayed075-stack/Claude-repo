#!/usr/bin/env python3
"""TEST-ONLY tool-event logger for disposable Claude Code sandboxes.

Registered as PreToolUse/PostToolUse hooks in a sandbox HOME's
settings.json by tests/harness/sandbox.sh. Never install it in a real
~/.claude. For every event it appends one JSON line with the tool call and
the working-tree state, so the analyzer can find the first real
modification whatever tool made it (Edit, Write, Bash, a generator, git).

    logger.py <log.jsonl> pre|post      hook mode (payload on stdin)
    logger.py state <dir>               print the state hash of <dir>

State = HEAD commit + porcelain status (untracked included) + the content of
every changed or untracked file. Fails open: any error writes nothing and
exits 0, so it can never block a session.
"""
import hashlib
import json
import os
import subprocess
import sys
import time


def git(cwd, *args):
    return subprocess.run(["git", "-C", cwd, *args], capture_output=True, timeout=30)


def state(cwd):
    head = git(cwd, "rev-parse", "HEAD")
    st = git(cwd, "status", "--porcelain=v1", "-uall", "-z")
    if st.returncode != 0:
        return {"hash": None, "files": []}
    h = hashlib.sha256(head.stdout)
    entries = [e for e in st.stdout.decode(errors="replace").split("\0") if e]
    for entry in entries:
        h.update(entry.encode())
        path = os.path.join(cwd, entry[3:] if len(entry) > 3 and entry[2] == " " else entry)
        if os.path.isfile(path):
            with open(path, "rb") as f:
                h.update(hashlib.sha256(f.read()).digest())
    return {"hash": h.hexdigest(), "files": entries[:50]}


def trim(v, n=400):
    if isinstance(v, str):
        return v if len(v) <= n else v[:n] + f"...[+{len(v) - n}]"
    if isinstance(v, dict):
        return {k: trim(x, n) for k, x in v.items()}
    if isinstance(v, list):
        return [trim(x, n) for x in v[:20]]
    return v


def main():
    if sys.argv[1] == "state":
        print(state(sys.argv[2])["hash"])
        return
    log, phase = sys.argv[1], sys.argv[2]
    p = json.load(sys.stdin)
    cwd = p.get("cwd") or os.getcwd()
    s = state(cwd)
    rec = {"ts": time.time(), "phase": phase, "tool": p.get("tool_name"),
           "tool_use_id": p.get("tool_use_id"), "input": trim(p.get("tool_input")),
           "cwd": cwd, "transcript": p.get("transcript_path"),
           "session": p.get("session_id"), "state": s["hash"], "files": s["files"]}
    with open(log, "a") as f:
        f.write(json.dumps(rec) + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
    sys.exit(0)
