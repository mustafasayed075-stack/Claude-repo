#!/usr/bin/env python3
"""TEST-ONLY: drive one headless claude process through several messages.

    stream_turns.py <run-dir> <messages.json> <timeout-per-turn> -- <command...>

<messages.json> is a JSON list of strings. Each one is sent as a stream-json
user message only after the previous turn's "result" event arrived. Events
for real prompts go to <run-dir>/turn<N>.jsonl (N counts real prompts), so
the analyzer's per-turn files line up with meta.json; events for "/compact"
go to <run-dir>/compact.jsonl. Used for the compaction scenario (OBS-C1).
"""
import json
import queue
import subprocess
import sys
import threading


def main():
    run, msgs_path, per_turn = sys.argv[1], sys.argv[2], float(sys.argv[3])
    cmd = sys.argv[sys.argv.index("--") + 1:]
    msgs = json.load(open(msgs_path))
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=open(f"{run}/stream.err", "w"), text=True)
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(l) for l in proc.stdout] and lines.put(None), daemon=True).start()
    n = 0
    status = 0
    for m in msgs:
        name = "compact" if m == "/compact" else f"turn{n + 1}"
        if m != "/compact":
            n += 1
        proc.stdin.write(json.dumps({"type": "user", "message": {"role": "user", "content": m}}) + "\n")
        proc.stdin.flush()
        with open(f"{run}/{name}.jsonl", "w") as f:
            while True:
                try:
                    line = lines.get(timeout=per_turn)
                except queue.Empty:
                    status = 124
                    break
                if line is None:
                    break
                f.write(line)
                try:
                    if json.loads(line).get("type") == "result":
                        break
                except ValueError:
                    pass
        if status:
            break
    try:
        proc.stdin.close()
    except OSError:
        pass
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:
        proc.kill()
        status = status or 124
    sys.exit(status)


if __name__ == "__main__":
    main()
