#!/usr/bin/env bash
# Deterministic tests for the TEST-ONLY live-test harness (tests/harness/):
# the logger's modification detection and the analyzer's verdicts. Runs in a
# mktemp sandbox; no claude calls, no network.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; H="$HERE/harness"
SB="$(mktemp -d)" || exit 1; trap 'rm -rf "$SB"' EXIT
PASS=0 FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
st() { python3 "$H/logger.py" state "$1"; }

echo "1. Logger: working-tree state detection"
R="$SB/repo"; mkdir -p "$R"; git -C "$R" init -q; echo a > "$R/f.txt"; echo k > "$R/keep.txt"
git -C "$R" add -A; git -C "$R" -c user.email=t@t -c user.name=t commit -qm init
b0=$(st "$R")
expect "state is stable when nothing changes" '[[ "$(st "$R")" == "$b0" && -n "$b0" ]]'
echo b > "$R/f.txt"; s1=$(st "$R")
expect "editing a tracked file changes the state" '[[ "$s1" != "$b0" ]]'
echo c > "$R/f.txt"; s2=$(st "$R")
expect "a second content-only edit changes it again" '[[ "$s2" != "$s1" ]]'
echo a > "$R/f.txt"
expect "reverting to the original content returns to the baseline" '[[ "$(st "$R")" == "$b0" ]]'
mkdir -p "$R/gen"; echo x > "$R/gen/out.txt"
expect "a generated untracked file changes the state" '[[ "$(st "$R")" != "$b0" ]]'
rm -rf "$R/gen"; rm "$R/keep.txt"
expect "deleting a tracked file changes the state" '[[ "$(st "$R")" != "$b0" ]]'
git -C "$R" checkout -q keep.txt; echo z > "$R/f.txt"; git -C "$R" -c user.email=t@t -c user.name=t commit -qam change
expect "a commit (clean status again) still differs from the baseline" '[[ "$(st "$R")" != "$b0" && -z "$(git -C "$R" status --porcelain)" ]]'
L="$SB/events.jsonl"
printf '{"cwd":"%s","tool_name":"Bash","tool_use_id":"t1","tool_input":{"command":"%s"},"transcript_path":"/x","session_id":"s"}' "$R" "$(printf 'x%.0s' {1..600})" | python3 "$H/logger.py" "$L" post
expect "hook mode appends one record with tool, phase, state and trimmed input" 'python3 -c "import json,sys; r=[json.loads(l) for l in open(sys.argv[1])]; e=r[0]; sys.exit(0 if len(r)==1 and e[\"tool\"]==\"Bash\" and e[\"phase\"]==\"post\" and e[\"state\"] and \"...[+\" in e[\"input\"][\"command\"] else 1)" "$L"'
printf 'not json' | python3 "$H/logger.py" "$L" pre; rc=$?
expect "malformed payload: exit 0 and nothing logged" '[[ $rc -eq 0 && $(wc -l < "$L") -eq 1 ]]'
mkdir -p "$SB/nogit"; printf '{"cwd":"%s","tool_name":"Read","tool_input":{}}' "$SB/nogit" | python3 "$H/logger.py" "$L" pre
expect "non-git directory: logged with state null (never mistaken for a change)" 'python3 -c "import json,sys; e=[json.loads(l) for l in open(sys.argv[1])][-1]; sys.exit(0 if e[\"state\"] is None else 1)" "$L"'

echo "2. Analyzer: verdicts on synthetic runs"
# mkrun <id> <scenario>: writes meta.json, transcript, events and driver log.
mkrun() {
  python3 - "$SB" "$1" "$2" <<'PY'
import json, os, sys, datetime
sb, rid, kind = sys.argv[1:4]
run = os.path.join(sb, "runs", rid); home = os.path.join(sb, "homes", rid); proj = f"/tmp/fakeproj-{rid}"
os.makedirs(run, exist_ok=True)
key = "".join(c if c.isalnum() else "-" for c in proj)
tdir = os.path.join(home, ".claude", "projects", key); os.makedirs(tdir, exist_ok=True)
T = lambda s: datetime.datetime.fromtimestamp(1000 + s, datetime.timezone.utc).isoformat().replace("+00:00", "Z")
ours = "- skill-router: route\n- engineering-standards: std\n- architecture-planning: plan\n- dataviz: charts\n"
builtin = "- dataviz: charts\n- init: init\n"
listing = builtin if kind == "nolisting" else ours
P1 = "Extend scripts/install.sh so that it also manages a hook entry"
P2 = "Add a --version option to scripts/install.sh"
entries = [
  {"type": "attachment", "timestamp": T(0), "attachment": {"type": "hook_success", "hookName": "SessionStart:startup", "content": "{\"hookSpecificOutput\": {\"reloadSkills\": true}}"}},
  {"type": "attachment", "timestamp": T(1), "attachment": {"type": "skill_listing", "content": listing}},
  {"type": "user", "timestamp": T(10), "message": {"content": P1}},
]
reply = "You've hit your 5-hour limit" if kind == "limit" else "Router: Infra | Feature | skills: architecture-planning, engineering-standards - x"
asst = [{"type": "text", "text": reply}]
if kind not in ("limit", "noassistant"):
    asst += [{"type": "tool_use", "name": "Skill", "id": "a", "input": {"skill": "skill-router"}},
             {"type": "tool_use", "name": "Skill", "id": "b", "input": {"skill": "architecture-planning"}},
             {"type": "tool_use", "name": "Skill", "id": "c", "input": {"skill": "engineering-standards"}}]
if kind != "noassistant":
    entries.append({"type": "assistant", "timestamp": T(12), "message": {"model": "m", "content": asst}})
entries += [{"type": "user", "timestamp": T(30), "message": {"content": "<command-name>/compact</command-name>"}},
            {"type": "user", "timestamp": T(40), "isCompactSummary": True, "message": {"content": "summary"}},
            {"type": "user", "timestamp": T(50), "message": {"content": P2}},
            {"type": "assistant", "timestamp": T(52), "message": {"model": "m", "content": [
                {"type": "tool_use", "name": "Skill", "id": "d", "input": {"skill": "skill-router"}},
                {"type": "tool_use", "name": "Skill", "id": "e", "input": {"skill": "engineering-standards"}},
                {"type": "tool_use", "name": "Bash", "id": "f", "input": {"command": "python3 - <<EOF ... EOF"}}]}}]
if kind != "notranscript":
    with open(os.path.join(tdir, "t.jsonl"), "w") as f:
        f.write("\n".join(json.dumps(e) for e in entries) + "\n")
ev = [{"ts": 1011, "phase": "post", "tool": "Skill", "state": "BASE"},
      {"ts": 1053, "phase": "pre", "tool": "Bash", "state": "BASE"},
      {"ts": 1054, "phase": "post", "tool": "Bash", "state": "CHANGED"}]
if kind == "mod_in_turn1":
    ev.insert(1, {"ts": 1013, "phase": "post", "tool": "Edit", "state": "EARLY"})
with open(os.path.join(run, "events.jsonl"), "w") as f:
    f.write("\n".join(json.dumps(e) for e in ev) + "\n")
drv = {"timeout": [{"type": "timeout", "ts": 1020}]}.get(kind, [])
with open(os.path.join(run, "driver.jsonl"), "w") as f:
    f.write("".join(json.dumps(d) + "\n" for d in drv))
e1 = {"skills_listed": ["skill-router", "engineering-standards", "architecture-planning"], "router_first": True,
      "skills_required": ["architecture-planning", "engineering-standards"], "modification": False}
e2 = {"router_first": True, "skills_required": ["engineering-standards"], "skills_forbidden": ["architecture-planning"], "modification": True}
json.dump({"id": rid, "home": home, "project": proj, "baseline": "BASE",
           "turns": [{"prompt": P1, "expect": e1}, {"prompt": P2, "expect": e2}]}, open(os.path.join(run, "meta.json"), "w"))
PY
}
verdicts() { ANALYZE_OUT="$SB/out-$1.json" python3 "$H/analyze.py" "$SB/runs/$1" >/dev/null; python3 -c "import json,sys; print(' '.join(t['verdict'] for t in json.load(open(sys.argv[1]))[0]['turns']))" "$SB/out-$1.json"; }
info() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))[0]['turns'][int(sys.argv[2])]['info'].get(sys.argv[3]))" "$SB/out-$1.json" "$2" "$3"; }
for k in good nolisting limit timeout noassistant notranscript mod_in_turn1; do mkrun "$k" "$k"; done
expect "complete evidence, expectations hold: Passed Passed" '[[ "$(verdicts good)" == "Passed Passed" ]]'
expect "turn 2 (after /compact) first modification attributed to Bash" '[[ "$(info good 1 first_modification)" == "post:Bash" ]]'
expect "turn 1 first modification is none" '[[ "$(info good 0 first_modification)" == "None" ]]'
expect "skills missing from the listing: turn 1 Failed" '[[ "$(verdicts nolisting)" == "Failed Passed" ]]'
expect "usage limit in the reply: turn 1 Blocked" '[[ "$(verdicts limit)" == Blocked* ]]'
expect "driver timeout inside the turn: Inconclusive" '[[ "$(verdicts timeout)" == Inconclusive* ]]'
expect "no assistant output: Inconclusive" '[[ "$(verdicts noassistant)" == Inconclusive* ]]'
expect "no transcript at all: Blocked Blocked" '[[ "$(verdicts notranscript)" == "Blocked Blocked" ]]'
expect "an edit in a turn that must not modify: Failed, attributed to Edit" '[[ "$(verdicts mod_in_turn1)" == Failed* && "$(info mod_in_turn1 0 first_modification)" == "post:Edit" ]]'
mkdir -p "$SB/runs/never"; echo '{"id":"never","home":"/nonexistent","project":"/x","turns":[{"prompt":"p","expect":{}}]}' > "$SB/runs/never/meta.json"
echo '{"type":"blocked","reason":"session never became ready","ts":1}' > "$SB/runs/never/driver.jsonl"
expect "session never became ready: Blocked" '[[ "$(verdicts never)" == "Blocked" ]]'

echo
echo "Harness tests: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
