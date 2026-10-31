#!/usr/bin/env python3
"""TEST-ONLY tmux driver for interactive Claude Code sessions in sandboxes.

    tui.py start  <name> <cwd> <home> <driver-log>
    tui.py prompt <name> <driver-log> <text> [timeout-seconds]
    tui.py exit   <name>

It answers only first-run onboarding screens and the workspace-trust
dialog. It never approves a permission prompt: one that appears is rejected
with Escape and logged, and the analyzer then treats the turn as
inconclusive. A question dialog (AskUserQuestion) ends the turn: the model
is waiting for the user. Usage-limit screens are logged so the run counts
as blocked.
"""
import json
import re
import subprocess
import sys
import time

DONE = re.compile(r"· done \d")
LIMIT = re.compile(r"hit your .{0,20}limit", re.I)


def tmux(*args):
    return subprocess.run(["tmux", *args], capture_output=True, text=True)


def screen(name, history=False):
    args = ["capture-pane", "-p", "-t", name] + (["-S", "-50000"] if history else [])
    return tmux(*args).stdout


def log(path, **rec):
    rec["ts"] = time.time()
    with open(path, "a") as f:
        f.write(json.dumps(rec) + "\n")


def keys(name, *k):
    tmux("send-keys", "-t", name, *k)


def start(name, cwd, home, dlog):
    tmux("set-option", "-g", "history-limit", "50000")
    cmd = (f"cd '{cwd}' && env HOME='{home}' CLAUDE_CODE_REMOTE=true "
           f"claude --permission-mode acceptEdits; sleep 3600")
    tmux("new-session", "-d", "-s", name, "-x", "220", "-y", "50", cmd)
    deadline = time.time() + 180
    while time.time() < deadline:
        time.sleep(3)
        s = screen(name)
        if "Choose the text style" in s:
            keys(name, "Enter"); log(dlog, type="onboarding", step="theme")
        elif "Press Enter to continue" in s:
            keys(name, "Enter"); log(dlog, type="onboarding", step="security-notes")
        elif "trust this folder" in s:
            keys(name, "Down"); time.sleep(0.5); keys(name, "Enter")
            log(dlog, type="trust_accepted")
        elif "accept edits on" in s and "Do you want" not in s:
            log(dlog, type="ready")
            return 0
    log(dlog, type="blocked", reason="session never became ready", screen=screen(name)[-1500:])
    return 1


def prompt(name, dlog, text, timeout=900):
    before = len(DONE.findall(screen(name, True)))
    keys(name, "-l", text); time.sleep(1); keys(name, "Enter")
    log(dlog, type="sent", prompt=text)
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(5)
        s = screen(name)
        if LIMIT.search(s):
            log(dlog, type="blocked", reason="usage limit", screen=s[-800:]); return 2
        if "Do you want to proceed?" in s or "Do you want to make this edit" in s:
            log(dlog, type="permission_prompt_rejected", screen=s[-1200:])
            keys(name, "Escape"); continue
        if "Enter to select" in s and "Esc to cancel" in s:
            log(dlog, type="question_asked", screen=s[-1500:]); return 0
        full = screen(name, True)
        if len(DONE.findall(full)) > before or (text.startswith("/compact") and "Compacted" in full):
            log(dlog, type="turn_done", prompt=text); return 0
    log(dlog, type="timeout", prompt=text, screen=screen(name)[-1500:])
    return 3


def main():
    cmd = sys.argv[1]
    if cmd == "start":
        sys.exit(start(*sys.argv[2:6]))
    if cmd == "prompt":
        t = int(sys.argv[5]) if len(sys.argv) > 5 else 900
        sys.exit(prompt(sys.argv[2], sys.argv[3], sys.argv[4], t))
    if cmd == "exit":
        keys(sys.argv[2], "C-u"); keys(sys.argv[2], "-l", "/exit"); keys(sys.argv[2], "Enter")
        time.sleep(3); tmux("kill-session", "-t", sys.argv[2])


if __name__ == "__main__":
    main()
