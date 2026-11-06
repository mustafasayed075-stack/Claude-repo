#!/usr/bin/env bash
# P1b held-out live validation (HANDOFF_P1b section 7). Scenarios, expectations,
# models, n and thresholds are fixed in tests/p1b_scenarios.json before any run.
# 23 scenarios x (3 Opus + 2 Sonnet) = 115 sessions, 130 CLI calls (3 scenarios
# resume a second turn), plus up to one replacement per Invalid/Inconclusive run.
#
#   tests/p1b_validation.sh [--dry-run] OUT_DIR
#   env: MODELS="claude-opus-5-5 claude-sonnet-5-5"  ONLY="NM1 NT5"  REPS=<n>  PARALLEL=1
#
# Batches: every invocation is one batch into the same OUT_DIR. A run that
# finished has <run>/done.json and is skipped next time; a run interrupted
# half-way is moved to OUT_DIR/aborted/ (kept) and run again. Resuming is
# refused when HEAD differs from the commit of the earlier batches. ONLY and
# MODELS select a subset, so the set can be run over several days.
#
# Usage limits: after the first run that shows a usage-limit / rate-limit
# signal, no new run is launched, runs still in flight are marked Invalid when
# they end, no replacement is spent, and what remains is printed and written
# to OUT_DIR/remaining.json. Replacements (D8: one per Invalid/Inconclusive
# run, labelled <run>-r) run after the base runs of a batch, only when that
# batch saw no limit.
#
# Isolation (every run): a fresh disposable HOME with only the sandbox test
# logger and sandbox allow rules, the skills and global block installed from
# the committed HEAD (git archive, not the working tree), a fixture repository,
# and the claude CLI started with `env -i` plus an explicit allow-list of
# variables (PATH, HOME, proxy and CA settings, ANTHROPIC_BASE_URL). Nothing is
# taken from the real ~/.claude. Its fingerprint is taken before and after
# every batch; a change between batches that matches this repo's rules is
# labelled as a reinstall by the P0 SessionStart hook (a session restart),
# anything else is flagged. Headless only (TUI is excluded from P1b acceptance).
#
# Live mode: refuses a dirty tracked working tree (results must map to HEAD),
# PARALLEL defaults to 1 and may be at most 2. --dry-run replaces the CLI with
# tests/harness/stub_claude.py (no model calls); used by tests/p1b_test.sh.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/.." && pwd)"
DRY=0; [[ "${1:-}" == --dry-run ]] && { DRY=1; shift; }
OUT="${1:-}"
[[ -n "$OUT" ]] || { echo "usage: $0 [--dry-run] OUT_DIR" >&2; exit 2; }
if [[ $DRY -eq 0 ]]; then
  PARALLEL="${PARALLEL:-1}"
  [[ "$PARALLEL" =~ ^[12]$ ]] || { echo "refusing: PARALLEL=$PARALLEL (live runs allow 1 or 2)" >&2; exit 2; }
  [[ ! -e "$OUT/stub_control.json" ]] || { echo "refusing: stub_control.json in a live run" >&2; exit 2; }
  if [[ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]]; then
    echo "refusing: tracked files differ from HEAD; commit first so results map to a commit" >&2; exit 2
  fi
else
  PARALLEL="${PARALLEL:-8}"
fi
COMMIT="$(git -C "$REPO" rev-parse HEAD)"
RESUME=0
if [[ -e "$OUT/manifest.json" ]]; then
  RESUME=1
  prev="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$OUT/manifest.json")"
  [[ "$prev" == "$COMMIT" ]] || { echo "refusing: HEAD $COMMIT differs from the commit of the earlier runs $prev" >&2; exit 2; }
  prevdry="$(python3 -B -c 'import json,sys; print(int(json.load(open(sys.argv[1]))["dry_run"]))' "$OUT/manifest.json")"
  [[ "$prevdry" == "$DRY" ]] || { echo "refusing: cannot mix dry-run and live batches in one OUT_DIR" >&2; exit 2; }
elif [[ -e "$OUT" ]] && [[ -n "$(ls -A "$OUT" | grep -v '^stub_control\.json$')" ]]; then
  echo "refusing: $OUT is not empty and holds no earlier batch" >&2; exit 2
fi
mkdir -p "$OUT" || exit 1; OUT="$(cd "$OUT" && pwd -P)"
SC="${SCENARIOS:-$HERE/p1b_scenarios.json}"   # SCENARIOS=tests/d7_scenarios.json selects the D7 set
SC="$(cd "$(dirname "$SC")" && pwd)/$(basename "$SC")"
source "$HERE/harness/sandbox.sh"
source "$HERE/fixtures.sh"

if [[ $DRY -eq 1 ]]; then
  CLAUDE_BIN="$HERE/harness/stub_claude.py"; echo "DRY RUN: stub claude $CLAUDE_BIN (no model calls), parallel $PARALLEL"
else
  CLAUDE_BIN="$(command -v claude)" || { echo "claude CLI not found" >&2; exit 2; }
  echo "LIVE: real claude $CLAUDE_BIN ($("$CLAUDE_BIN" --version 2>/dev/null | head -1)), parallel $PARALLEL"
fi
BASEPATH="/usr/local/bin:/usr/bin:/bin"
CPATH="$(dirname "$CLAUDE_BIN"):$BASEPATH"
PASSTHRU=(HTTPS_PROXY https_proxy NO_PROXY no_proxy NODE_EXTRA_CA_CERTS SSL_CERT_FILE NODE_OPTIONS ANTHROPIC_BASE_URL)
CENV=(env -i "PATH=$CPATH")
for v in "${PASSTHRU[@]}"; do [[ -n "${!v+x}" ]] && CENV+=("$v=${!v}"); done
REAL_CLAUDE="$HOME/.claude"
TURN_TIMEOUT="${TURN_TIMEOUT:-900}"
LIMIT="$OUT/limit_hit.json"

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
p0_reinstall() { # does the real ~/.claude now hold exactly this repo's rules (what the P0 hook installs)?
  python3 -B - "$REAL_CLAUDE" "$SRC" <<'PY'
import os, sys
real, src = sys.argv[1:3]
# every skill folder in the installed source is managed (the installer copies whole folders, references/ included)
skills = sorted(s for s in os.listdir(f"{src}/skills") if os.path.isdir(f"{src}/skills/{s}"))
def files(root):
    return {os.path.relpath(os.path.join(d, f), root) for d, _, fs in os.walk(root) for f in fs}
ok = True
extra = []
for s in skills:
    want, have = f"{src}/skills/{s}", f"{real}/skills/{s}"
    if not os.path.isdir(have):
        ok = False
        continue
    wf, hf = files(want), files(have) - {".engineering-skill-system"}
    ok = ok and wf <= hf and all(open(f"{want}/{f}", "rb").read() == open(f"{have}/{f}", "rb").read() for f in wf)
    extra += [f"{s}/{f}" for f in sorted(hf - wf)]
md = f"{real}/CLAUDE.md"
ok = ok and os.path.exists(md) and open(f"{src}/global/CLAUDE.md").read().strip() in open(md).read()
if os.path.isdir(f"{real}/skills"):
    extra += [s for s in os.listdir(f"{real}/skills") if s not in skills and s != "session-start-hook"]
print("yes" if ok and not extra else "no")
PY
}

# Source of the installed rules: the committed HEAD, never the working tree.
SRC="$OUT/src"
if [[ $RESUME -eq 0 ]]; then
  mkdir -p "$SRC"
  git -C "$REPO" archive HEAD skills global scripts | tar -x -C "$SRC" || { echo "git archive failed" >&2; exit 1; }
  cp "$SC" "$OUT/scenarios.json"
fi
cmp -s "$SC" "$OUT/scenarios.json" || { echo "refusing: the scenario file $SC differs from the one of the earlier batches" >&2; exit 2; }
rm -f "$LIMIT"
FP_BEFORE="$(fingerprint)"
python3 -B - "$OUT" "$COMMIT" "$DRY" "$CLAUDE_BIN" "$FP_BEFORE" "${ONLY:-}" "${MODELS:-}" "$PARALLEL" "$(p0_reinstall)" \
  "$([[ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]] && echo 1 || echo 0)" <<'PY'
import json, os, sys, time
out, commit, dry, binp, fp, only, models, par, p0, dirty = sys.argv[1:11]
p = f"{out}/manifest.json"
m = json.load(open(p)) if os.path.exists(p) else {
    "commit": commit, "dirty_tracked_files": dirty == "1", "dry_run": dry == "1", "claude_bin": binp,
    "started": time.time(), "real_claude_before": fp, "batches": []}
prev = m["batches"][-1]["real_claude_after"] if m["batches"] else fp
note = "unchanged" if prev == fp else ("changed before this batch: P0 reinstall (the real ~/.claude now holds this repo's rules; not a leak)"
                                       if p0 == "yes" else "changed before this batch: NOT a P0 reinstall - investigate")
m["batches"].append({"n": len(m["batches"]) + 1, "started": time.time(), "only": only, "models": models, "parallel": int(par),
                     "real_claude_before": fp, "fingerprint_note": note})
json.dump(m, open(p, "w"), indent=2)
print(f"batch {len(m['batches'])}: real ~/.claude {note}")
PY

# plan: one line per base run, "model|scenario|rep" (the selected subset)
plan() { # models only reps(""=approved n)
  python3 -B - "$SC" "$1" "$2" "$3" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); models = sys.argv[2].split() or list(s["models"]); only = sys.argv[3].split(); reps = sys.argv[4]
for m in models:
    for sc in s["scenarios"]:
        if only and sc["id"] not in only: continue
        for r in range(1, (int(reps) if reps else s["models"][m]) + 1):
            print(f"{m}|{sc['id']}|{r}")
PY
}
PLAN="$(plan "${MODELS:-}" "${ONLY:-}" "${REPS:-}")"
printf '%s\n' "$PLAN" > "$OUT/plan_batch.txt"   # this batch's selection
plan "" "" "" > "$OUT/plan_all.txt"              # the full approved plan (approved n, never REPS)

run_one() { # model scenario rep replacement(0|1)
  local model="$1" sid="$2" rep="$3" repl="$4" name="$2-$3"
  [[ "$repl" == 1 ]] && name="$name-r"
  local r="$OUT/runs/$model/$name"
  if [[ -d "$r" ]]; then  # interrupted earlier: keep it as evidence, start clean
    mkdir -p "$OUT/aborted/$model"; mv "$r" "$OUT/aborted/$model/$name.$(date +%s%N)"
  fi
  mkdir -p "$r"
  local t_start; t_start="$(python3 -B -c 'import time; print(time.time())')"
  sb_home "$r/home" "$r/events.jsonl"
  env -i "PATH=$BASEPATH" "HOME=$r/home" bash "$SRC/scripts/install.sh" > "$r/install.log" 2>&1 \
    || { echo "install failed: $model $name" >&2; echo '{"install_failed": true}' > "$r/install_failed.json"; }
  local fx; fx="$(python3 -B -c 'import json,sys; print(next(x["fixture"] for x in json.load(open(sys.argv[1]))["scenarios"] if x["id"] == sys.argv[2]))' "$SC" "$sid")"
  make_fixture "$fx" "$r/project" > /dev/null 2>&1
  local proj; proj="$(cd "$r/project" && pwd -P)"
  python3 -B - "$SC" "$r" "$sid" "$rep" "$repl" "$model" "$COMMIT" "$r/home" "$proj" "$(sb_state "$r/project")" "$name" "$t_start" <<'PY'
import json, sys
sc, run, sid, rep, repl, model, commit, home, proj, base, name, started = sys.argv[1:13]
s = json.load(open(sc)); x = next(v for v in s["scenarios"] if v["id"] == sid)
turns = []
for t in x["turns"]:
    exp = {**s["templates"].get(t.get("template"), {}), **t.get("extra", {}), **(t.get("expect") or {})}
    turns.append({"prompt": t["prompt"], "expect": exp, "mode": t.get("mode", "fresh"),
                  **({"compact_before": True} if t.get("compact_before") else {})})
json.dump({"id": name, "scenario": sid, "group": x["group"], "rep": int(rep), "replacement": repl == "1", "model": model,
           "commit": commit, "home": home, "project": proj, "baseline": base, "started": float(started),
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
  # isolation check, limit signal, invalidation of in-flight runs, then done.json (written last)
  python3 -B - "$r" "$LIMIT" "$model" "$name" <<'PY'
import glob, json, os, re, sys, time
run, limit, model, name = sys.argv[1:5]
m = json.load(open(f"{run}/meta.json"))
m["isolation_leak"] = any("session-start-hook" in open(f, errors="replace").read()
                          for f in glob.glob(f"{run}/home/.claude/projects/*/*.jsonl"))
a = json.load(open(f"{run}/analysis.json"))
turns = [t for r in a for t in r["turns"]]
SIGNAL = re.compile(r"usage limit|hit your .{0,20}limit|rate.?limit|\b429\b|\b529\b|overloaded|quota", re.I)
hit = any(t["verdict"] == "Invalid" and SIGNAL.search(t.get("why") or "") for t in turns)
if hit and not os.path.exists(limit):
    json.dump({"run": f"{model} {name}", "time": time.time()}, open(limit, "w"))
elif os.path.exists(limit) and not hit:
    # this run was in flight when another run hit the limit: its evidence is not trustworthy
    m["invalidated_by_limit"] = True
ORDER = ["Blocked", "Invalid", "Inconclusive", "Failed", "Passed"]
verdict = min([t["verdict"] for t in turns] or ["Blocked"], key=ORDER.index)
if m.get("invalidated_by_limit"):
    verdict = "Invalid"
json.dump(m, open(f"{run}/meta.json", "w"), indent=2)
json.dump({"verdict": verdict, "limit_signal": hit, "invalidated_by_limit": bool(m.get("invalidated_by_limit")),
           "finished": time.time()}, open(f"{run}/done.json", "w"))
PY
  echo "done $model $name"
}

launch() { # run lines "model|scenario|rep|repl" from stdin, at most PARALLEL at once; stop on a limit
  local jobs=0 model sid rep repl
  while IFS='|' read -r model sid rep repl; do
    [[ -n "$model" ]] || continue
    [[ -e "$LIMIT" ]] && break
    run_one "$model" "$sid" "$rep" "$repl" < /dev/null &
    jobs=$((jobs + 1)); if (( jobs >= PARALLEL )); then wait -n; jobs=$((jobs - 1)); fi
  done
  wait
}

# base runs of this batch: skip finished ones
todo=""
while IFS='|' read -r model sid rep; do
  [[ -n "$model" ]] || continue
  if [[ -f "$OUT/runs/$model/$sid-$rep/done.json" ]]; then echo "skip done $model $sid-$rep"; continue; fi
  todo+="$model|$sid|$rep|0"$'\n'
done <<< "$PLAN"
launch <<< "$todo"

# replacements (D8): one per Invalid/Inconclusive base run, only when this batch saw no limit
due() {
  python3 -B - "$OUT" "$OUT/plan_batch.txt" <<'PY'
import json, os, sys
out = sys.argv[1]
for line in open(sys.argv[2]):
    if not line.strip(): continue
    model, sid, rep = line.strip().split("|")
    base = f"{out}/runs/{model}/{sid}-{rep}"
    if not os.path.exists(f"{base}/done.json"): continue
    if json.load(open(f"{base}/done.json"))["verdict"] not in ("Invalid", "Inconclusive"): continue
    if os.path.exists(f"{base}-r/done.json"): continue
    print(f"{model}|{sid}|{rep}|1")
PY
}
if [[ ! -e "$LIMIT" ]]; then
  launch <<< "$(due)"
  # mark originals whose replacement finished
  while IFS='|' read -r model sid rep; do
    [[ -n "$model" && -f "$OUT/runs/$model/$sid-$rep-r/done.json" ]] || continue
    python3 -B -c 'import json,sys; p=sys.argv[1]; m=json.load(open(p)); m["replaced_by"]=sys.argv[2]; json.dump(m, open(p,"w"), indent=2)' \
      "$OUT/runs/$model/$sid-$rep/meta.json" "$sid-$rep-r"
  done <<< "$PLAN"
fi

# what remains, against the full approved plan (all models, all scenarios)
FP_AFTER="$(fingerprint)"
python3 -B - "$OUT" "$LIMIT" "$FP_AFTER" "$(p0_reinstall)" "$OUT/plan_all.txt" "$OUT/plan_batch.txt" <<'PY'
import json, os, sys, time
out, limit, fp, p0 = sys.argv[1:5]
def remaining(path):
    base, repl = [], []
    for line in open(path):
        if not line.strip(): continue
        model, sid, rep = line.strip().split("|"); d = f"{out}/runs/{model}/{sid}-{rep}"
        if not os.path.exists(f"{d}/done.json"):
            base.append(f"{model} {sid}-{rep}")
        elif json.load(open(f"{d}/done.json"))["verdict"] in ("Invalid", "Inconclusive") and not os.path.exists(f"{d}-r/done.json"):
            repl.append(f"{model} {sid}-{rep}")
    return base, repl
base, repl = remaining(sys.argv[5])
bbase, brepl = remaining(sys.argv[6])
lim = json.load(open(limit)) if os.path.exists(limit) else None
json.dump({"base": base, "replacements_due": repl, "batch_base": bbase, "batch_replacements_due": brepl, "limit_hit": lim},
          open(f"{out}/remaining.json", "w"), indent=2)
m = json.load(open(f"{out}/manifest.json")); b = m["batches"][-1]
b.update({"finished": time.time(), "real_claude_after": fp, "limit_hit": lim})
if fp != b["real_claude_before"]:
    b["fingerprint_note"] = ("changed during this batch: P0 reinstall (the real ~/.claude now holds this repo's rules; not a leak)"
                             if p0 == "yes" else "changed during this batch: NOT a P0 reinstall - investigate")
m["real_claude_after"] = fp; m["finished"] = time.time()
json.dump(m, open(f"{out}/manifest.json", "w"), indent=2)
if lim:
    print(f"USAGE LIMIT hit by {lim['run']}: no new runs were launched, runs in flight were marked Invalid, no replacement was spent.")
print(f"remaining base runs in this batch's selection: {len(bbase)}" + (": " + ", ".join(bbase) if bbase else ""))
print(f"remaining base runs in the full approved plan: {len(base)}" + (": " + ", ".join(base) if base else ""))
print("replacements due: " + (", ".join(repl) if repl else "none"))
if "NOT a P0" in b["fingerprint_note"]:
    print("WARNING: " + b["fingerprint_note"], file=sys.stderr)
PY
python3 -B "$HERE/p1b_report.py" "$OUT" > "$OUT/report.md"
echo "report: $OUT/report.md"
