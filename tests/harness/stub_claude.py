#!/usr/bin/env python3
"""TEST-ONLY stand-in for the claude CLI, used by tests/p1b_validation.sh --dry-run.

It never contacts a model. For every call it records what the real CLI
would have received (environment, argv, working directory, stdin messages)
in <run>/stub_calls.jsonl, writes a minimal transcript into $HOME/.claude so
the analyzer can find the turn, and prints stream-json events.

The working directory is <OUT>/runs/<model>/<run>/project. An optional
<OUT>/stub_control.json maps "<run>@<model>" to a behaviour:
  limit         this run answers with a usage-limit message (is_error result)
  apierror      this run ends with a non-limit API error (is_error result)
  <x>_always    behaviour <x> for this run and for its replacement
  leak          the transcript's skill listing shows a skill that exists only
                in the real ~/.claude (session-start-hook)
  slow          answers after 3 seconds (a run still in flight)
"""
import datetime
import json
import os
import re
import sys
import time
import uuid


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def main():
    argv = sys.argv[1:]
    cwd = os.getcwd()
    run_dir = os.path.dirname(cwd)
    run, model_dir = os.path.basename(run_dir), os.path.basename(os.path.dirname(run_dir))
    out = os.path.dirname(os.path.dirname(os.path.dirname(run_dir)))
    model = argv[argv.index("--model") + 1] if "--model" in argv else "unknown"
    sid = argv[argv.index("--resume") + 1] if "--resume" in argv else str(uuid.uuid4())
    control = {}
    cpath = os.path.join(out, "stub_control.json")
    if os.path.exists(cpath):
        control = json.load(open(cpath))
    behaviour = control.get(f"{run}@{model}")
    if not behaviour and str(control.get(f"{re.sub(r'-r$', '', run)}@{model}", "")).endswith("_always"):
        behaviour = control[f"{re.sub(r'-r$', '', run)}@{model}"]  # also applies to the replacement run
    behaviour = (behaviour or "").replace("_always", "") or None

    stream = "--input-format" in argv
    home = os.environ.get("HOME", "")
    tdir = os.path.join(home, ".claude", "projects", re.sub(r"[^A-Za-z0-9]", "-", cwd))
    os.makedirs(tdir, exist_ok=True)
    listing = "- skill-router: route\n- engineering-standards: std\n- architecture-planning: plan\n"
    if behaviour == "leak":
        listing += "- session-start-hook: only in the real ~/.claude\n"
    print(json.dumps({"type": "system", "subtype": "init", "session_id": sid, "model": model}), flush=True)
    t = open(os.path.join(tdir, f"{sid}.jsonl"), "a")
    t.write(json.dumps({"type": "attachment", "timestamp": now(), "attachment": {"type": "skill_listing", "content": listing}}) + "\n")
    t.flush()

    def answer(m):
        if behaviour == "slow":
            time.sleep(3)
        if m == "/compact":
            print(json.dumps({"type": "result", "subtype": "success", "is_error": False, "session_id": sid, "result": "compacted"}), flush=True)
            return
        text = ("You've hit your 5-hour limit" if behaviour == "limit" else
                "API Error: 500 Internal server error" if behaviour == "apierror" else "(stub) no model was called")
        t.write(json.dumps({"type": "user", "timestamp": now(), "message": {"content": m}}) + "\n")
        t.write(json.dumps({"type": "assistant", "timestamp": now(),
                            "message": {"model": model, "content": [{"type": "text", "text": text}]}}) + "\n")
        t.flush()
        print(json.dumps({"type": "assistant", "message": {"model": model, "content": [{"type": "text", "text": text}]}}), flush=True)
        print(json.dumps({"type": "result", "subtype": "success", "is_error": behaviour in ("limit", "apierror"),
                          "session_id": sid, "result": text}), flush=True)

    msgs = []
    if stream:  # answer each message as it arrives, as the real CLI does
        for line in sys.stdin:
            line = line.strip()
            if line:
                msgs.append(json.loads(line)["message"]["content"])
                answer(msgs[-1])
    else:
        msgs = [argv[argv.index("-p") + 1]]
        answer(msgs[0])
    t.close()
    # The environment the process was started with. os.environ is not used:
    # Python adds LC_CTYPE to it at startup (PEP 538), which the real CLI never sees.
    raw = open("/proc/self/environ", "rb").read().split(b"\0")
    env = dict(x.decode(errors="replace").split("=", 1) for x in raw if b"=" in x)
    with open(os.path.join(run_dir, "stub_calls.jsonl"), "a") as f:
        f.write(json.dumps({"env": env, "argv": argv, "cwd": cwd, "session_id": sid,
                            "stdin_messages": msgs if stream else None}) + "\n")


if __name__ == "__main__":
    main()
