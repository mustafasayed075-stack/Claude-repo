#!/usr/bin/env bash
# P1b held-out live validation (HANDOFF_P1b section 7). Scenarios, expectations,
# models, n and thresholds are fixed in tests/p1b_scenarios.json before any run.
#
#   tests/p1b_validation.sh [--dry-run] OUT_DIR
#   env: MODELS="claude-opus-5-5 claude-sonnet-5-5"  ONLY="NM1 NT5"  REPS=<n>  PARALLEL=3
#
# Isolation (every run): a fresh disposable HOME with only the sandbox test
# logger and sandbox allow rules, the skills and global block installed from
# the committed HEAD (git archive, not the working tree), a fixture repository,
# and the claude CLI started with `env -i` plus an explicit allow-list of
# variables (PATH, HOME, proxy and CA settings, ANTHROPIC_BASE_URL). Nothing is
# taken from the real ~/.claude, whose fingerprint is recorded before and after.
# Headless only (TUI is excluded from P1b acceptance).
#
# --dry-run replaces the CLI with tests/harness/stub_claude.py: no model calls,
# same sandboxes, same commands; used by tests/p1b_test.sh to prove isolation.
# Live mode refuses a dirty tracked working tree, so every result maps to HEAD.
# D8: a run with an Invalid or Inconclusive turn gets exactly one labelled
# replacement (<run>-r); the original is kept and marked replaced_by.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/.." && pwd)"
DRY=0; [[ "${1:-}" == --dry-run ]] && { DRY=1; shift; }
OUT="${1:-}"
[[ -n "$OUT" ]] || { echo "usage: $0 [--dry-run] OUT_DIR" >&2; exit 2; }
if [[ -e "$OUT" ]] && [[ -n "$(ls -A "$OUT" | grep -v '^stub_control\.json$')" ]]; then
  echo "refusing: $OUT is not empty" >&2; exit 2
fi
if [[ $DRY -eq 0 ]]; then
  [[ ! -e "$OUT/stub_control.json" ]] || { echo "refusing: stub_control.json in a live run" >&2; exit 2; }
  if [[ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]]; then
    echo "refusing: tracked files differ from HEAD; commit first so results map to a commit" >&2; exit 2
  fi
fi
mkdir -p "$OUT" || exit 1; OUT="$(cd "$OUT" && pwd -P)"
SC="$HERE/p1b_scenarios.json"
source "$HERE/harness/sandbox.sh"
source "$HERE/fixtures.sh"

if [[ $DRY -eq 1 ]]; then
  CLAUDE_BIN="$HERE/harness/stub_claude.py"; echo "DRY RUN: stub claude $CLAUDE_BIN (no model calls)"
  PARALLEL="${PARALLEL:-8}"
else
  CLAUDE_BIN="$(command -v claude)" || { echo "claude CLI not found" >&2; exit 2; }
  echo "LIVE: real claude $CLAUDE_BIN ($("$CLAUDE_BIN" --version 2>/dev/null | head -1))"
  PARALLEL="${PARALLEL:-3}"
fi
BASEPATH="/usr/local/bin:/usr/bin:/bin"
CPATH="$(dirname "$CLAUDE_BIN"):$BASEPATH"
PASSTHRU=(HTTPS_PROXY https_proxy NO_PROXY no_proxy NODE_EXTRA_CA_CERTS SSL_CERT_FILE NODE_OPTIONS ANTHROPIC_BASE_URL)
CENV=(env -i "PATH=$CPATH")
for v in "${PASSTHRU[@]}"; do [[ -n "${!v+x}" ]] && CENV+=("$v=${!v}"); done
REAL_CLAUDE="$HOME/.claude"
TURN_TIMEOUT="${TURN_TIMEOUT:-900}"

fingerprint() { # the parts of the real ~/.claude a session could read as configuration
  python3 -B - "$REAL_CLAUDE" <<'PY'
import hashlib, os, sys
root = sys.argv[1]; h = hashlib.sha256()
for rel in ("CLAUDE.md", "settings.json", "settings.local.json", "skills", "agents", "commands", "hooks"):
    p = os.path.join(root, rel)
    paths = [p] if os.path.isfile(p) else sorted(os.path.join(d, f) for d, _, fs in os.walk(p) for f in fs)
    for f in paths:
        h.update(f.encode()); h.update(open(f, "rb").read())
print(h.hexdigest())
PY
}

# Source of the installed rules: the committed HEAD, never the working tree.
SRC="$OUT/src"; mkdir -p "$SRC"
git -C "$REPO" archive HEAD skills global scripts | tar -x -C "$SRC" || { echo "git archive failed" >&2; exit 1; }
COMMIT="$(git -C "$REPO" rev-parse HEAD)"
cp "$SC" "$OUT/scenarios.json"
python3 -B - "$OUT" "$COMMIT" "$DRY" "$CLAUDE_BIN" "$(fingerprint)" \
  "$([[ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]] && echo 1 || echo 0)" <<'PY'
import json, sys, time
out, commit, dry, binp, fp, dirty = sys.argv[1:7]
json.dump({"commit": commit, "dirty_tracked_files": dirty == "1", "dry_run": dry == "1", "claude_bin": binp,
           "started": time.time(), "real_claude_before": fp}, open(f"{out}/manifest.json", "w"), indent=2)
PY

# plan: one line per run, "model|scenario|rep"
PLAN="$(python3 -B - "$SC" "${MODELS:-}" "${ONLY:-}" "${REPS:-}" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); models = sys.argv[2].split() or list(s["models"]); only = sys.argv[3].split(); reps = sys.argv[4]
for m in models:
    for sc in s["scenarios"]:
        if only and sc["id"] not in only: continue
        for r in range(1, (int(reps) if reps else s["models"][m]) + 1):
            print(f"{m}|{sc['id']}|{r}")
PY
)"

run_one() { # model scenario rep replacement(0|1) -> prints REPLACE if a replacement is due
  local model="$1" sid="$2" rep="$3" repl="$4" name="$2-$3"
  [[ "$repl" == 1 ]] && name="$name-r"
  local r="$OUT/runs/$model/$name"; mkdir -p "$r"
  sb_home "$r/home" "$r/events.jsonl"
  env -i "PATH=$BASEPATH" "HOME=$r/home" bash "$SRC/scripts/install.sh" > "$r/install.log" 2>&1 \
    || { echo "install failed: $model $name" >&2; echo '{"install_failed": true}' > "$r/install_failed.json"; }
  local fx; fx="$(python3 -B -c 'import json,sys; print(next(x["fixture"] for x in json.load(open(sys.argv[1]))["scenarios"] if x["id"] == sys.argv[2]))' "$SC" "$sid")"
  make_fixture "$fx" "$r/project" > /dev/null 2>&1
  local proj; proj="$(cd "$r/project" && pwd -P)"
  python3 -B - "$SC" "$r" "$sid" "$rep" "$repl" "$model" "$COMMIT" "$r/home" "$proj" "$(sb_state "$r/project")" "$name" <<'PY'
import json, sys, time
sc, run, sid, rep, repl, model, commit, home, proj, base, name = sys.argv[1:12]
s = json.load(open(sc)); x = next(v for v in s["scenarios"] if v["id"] == sid)
turns = []
for t in x["turns"]:
    exp = {**s["templates"].get(t.get("template"), {}), **t.get("extra", {}), **(t.get("expect") or {})}
    turns.append({"prompt": t["prompt"], "expect": exp, "mode": t.get("mode", "fresh"),
                  **({"compact_before": True} if t.get("compact_before") else {})})
json.dump({"id": name, "scenario": sid, "group": x["group"], "rep": int(rep), "replacement": repl == "1", "model": model,
           "commit": commit, "home": home, "project": proj, "baseline": base, "started": time.time(),
           "stream": bool(x.get("stream")), "turns": turns}, open(f"{run}/meta.json", "w"), indent=2)
PY
  local common=(--model "$model" --output-format stream-json --verbose --max-turns 30 --permission-mode acceptEdits)
  local cenv=("${CENV[@]}" "HOME=$r/home")
  if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["stream"])' "$r/meta.json")" == True ]]; then
    python3 -B -c 'import json,sys; m=json.load(open(sys.argv[1]))["turns"]; json.dump([m[0]["prompt"], "/compact", m[1]["prompt"]], open(sys.argv[2], "w"))' \
      "$r/meta.json" "$r/messages.json"
    (cd "$r/project" && python3 -B "$HERE/harness/stream_turns.py" "$r" "$r/messages.json" "$TURN_TIMEOUT" -- \
       "${cenv[@]}" "$CLAUDE_BIN" -p --input-format stream-json "${common[@]}")
  else
    local n=0 sidx=""
    while IFS=$'\t' read -r mode prompt; do
      n=$((n + 1))
      if [[ $n -eq 1 ]]; then
        (cd "$r/project" && timeout "$TURN_TIMEOUT" "${cenv[@]}" "$CLAUDE_BIN" -p "$prompt" "${common[@]}" \
           < /dev/null > "$r/turn1.jsonl" 2> "$r/turn1.err")
        sidx="$(python3 -B -c 'import json,sys
for l in open(sys.argv[1]):
    try: e = json.loads(l)
    except ValueError: continue
    if e.get("session_id"): print(e["session_id"]); break' "$r/turn1.jsonl")"
      else
        [[ -n "$sidx" ]] || { echo "no session id after turn 1: $model $name" >&2; break; }
        (cd "$r/project" && timeout "$TURN_TIMEOUT" "${cenv[@]}" "$CLAUDE_BIN" -p "$prompt" --resume "$sidx" "${common[@]}" \
           < /dev/null > "$r/turn$n.jsonl" 2> "$r/turn$n.err")
      fi
    done < <(python3 -B -c 'import json,sys
for t in json.load(open(sys.argv[1]))["turns"]: print(t["mode"] + "\t" + t["prompt"])' "$r/meta.json")
  fi
  ANALYZE_OUT="$r/analysis.json" python3 -B "$HERE/harness/analyze.py" "$r" > "$r/analysis.txt" 2>&1
  # isolation check: a skill that exists only in the real ~/.claude must never be listed
  python3 -B - "$r" <<'PY'
import glob, json, sys
run = sys.argv[1]
leak = any("session-start-hook" in open(f, errors="replace").read()
           for f in glob.glob(f"{run}/home/.claude/projects/*/*.jsonl"))
m = json.load(open(f"{run}/meta.json")); m["isolation_leak"] = leak
json.dump(m, open(f"{run}/meta.json", "w"), indent=2)
a = json.load(open(f"{run}/analysis.json"))
if any(t["verdict"] in ("Invalid", "Inconclusive") for r in a for t in r["turns"]):
    print("REPLACE")
PY
}

run_with_replacement() { # model scenario rep
  local verdict; verdict="$(run_one "$1" "$2" "$3" 0)"
  if [[ "$verdict" == REPLACE ]]; then
    python3 -B -c 'import json,sys; p=sys.argv[1]; m=json.load(open(p)); m["replaced_by"]=sys.argv[2]; json.dump(m, open(p,"w"), indent=2)' \
      "$OUT/runs/$1/$2-$3/meta.json" "$2-$3-r"
    run_one "$1" "$2" "$3" 1 > /dev/null
  fi
  echo "done $1 $2-$3"
}

jobs=0
while IFS='|' read -r model sid rep; do
  [[ -n "$model" ]] || continue
  run_with_replacement "$model" "$sid" "$rep" &
  jobs=$((jobs + 1)); if (( jobs >= PARALLEL )); then wait -n; jobs=$((jobs - 1)); fi
done <<< "$PLAN"
wait

python3 -B - "$OUT" "$(fingerprint)" <<'PY'
import json, sys, time
out, fp = sys.argv[1:3]; m = json.load(open(f"{out}/manifest.json"))
m["real_claude_after"] = fp; m["finished"] = time.time()
json.dump(m, open(f"{out}/manifest.json", "w"), indent=2)
if m["real_claude_before"] != fp:
    print("WARNING: the real ~/.claude changed during the run", file=sys.stderr)
PY
python3 -B "$HERE/p1b_report.py" "$OUT" > "$OUT/report.md"
echo "report: $OUT/report.md"
