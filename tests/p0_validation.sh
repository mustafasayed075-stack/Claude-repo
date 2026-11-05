#!/usr/bin/env bash
# Live validation of P0 (skills discoverable in the session whose
# SessionStart hook installs them). Every scenario runs in its own disposable
# sandbox (fresh HOME + copy of this working tree); the real ~/.claude is
# never touched. Results: tests/harness/analyze.py verdicts per turn.
#   [ONLY="s1 s2"] tests/p0_validation.sh [OUT_DIR]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/harness/sandbox.sh"
OUT="${1:-}"; if [[ -z "$OUT" ]]; then OUT="$(mktemp -d)" || exit 1; fi
mkdir -p "$OUT" || exit 1; OUT="$(cd "$OUT" && pwd)"
TUI="$HERE/harness/tui.py"
echo "output: $OUT"

P_HIGH="Extend scripts/install.sh so that it also installs and manages a hook entry in the user's ~/.claude/settings.json."
P_LOW="Add a --version option to scripts/install.sh that prints 'engineering-skill-system phase-1' and exits 0."
P_Q="What does scripts/install.sh do? Answer in one sentence without using tools."
E_HIGH='{"skills_listed":["skill-router","engineering-standards","architecture-planning"],"router_first":true,"skills_required":["architecture-planning","engineering-standards"],"modification":false}'
E_LOW='{"skills_listed":["skill-router","engineering-standards","architecture-planning"],"router_first":true,"skills_required":["engineering-standards"],"modification":true}'

# setup <id> [--skills-dir]: run dir + sandbox HOME + project copy
setup() {
  local r="$OUT/$1"; mkdir -p "$r"
  sb_home "$r/home" "$r/events.jsonl" "${2:-}"
  sb_project "$r/project"
}
# meta <id> <home> <project> <turns-json>
meta() {
  python3 - "$OUT/$1" "$1" "$2" "$3" "$(sb_state "$3")" "$4" <<'PY'
import json, sys, time
run, rid, home, proj, base, turns = sys.argv[1:7]
json.dump({"id": rid, "home": home, "project": proj, "baseline": base, "started": time.time(),
           "turns": json.loads(turns)}, open(f"{run}/meta.json", "w"), indent=2)
PY
}
turns() { python3 -c 'import json,sys; a=sys.argv[1:]; print(json.dumps([{"prompt":a[i],"expect":json.loads(a[i+1])} for i in range(0,len(a),2)]))' "$@"; }
headless() { # <home> <project> <events> <out.jsonl> <prompt> [extra args]
  local h="$1" p="$2" o="$4" q="$5"; shift 5
  (cd "$p" && HOME="$h" CLAUDE_CODE_REMOTE=true timeout 900 claude -p "$q" "$@" --output-format stream-json --verbose \
     --max-turns 25 --permission-mode acceptEdits </dev/null > "$o" 2>"$o.err")
}

s1() { # fresh TUI, empty HOME; then /compact; then a second engineering prompt
  local r="$OUT/S1-tui-empty-home"; setup S1-tui-empty-home
  meta S1-tui-empty-home "$r/home" "$r/project" "$(turns "$P_HIGH" "$E_HIGH" "$P_LOW" "$E_LOW")"
  python3 "$TUI" start p0s1 "$r/project" "$r/home" "$r/driver.jsonl" || return
  python3 "$TUI" prompt p0s1 "$r/driver.jsonl" "$P_HIGH"
  python3 "$TUI" prompt p0s1 "$r/driver.jsonl" "/compact" 600
  python3 "$TUI" prompt p0s1 "$r/driver.jsonl" "$P_LOW"
  python3 "$TUI" exit p0s1
}
s2() { # fresh TUI with a pre-existing empty ~/.claude/skills/
  local r="$OUT/S2-tui-skills-dir"; setup S2-tui-skills-dir --skills-dir
  meta S2-tui-skills-dir "$r/home" "$r/project" "$(turns "$P_HIGH" "$E_HIGH")"
  python3 "$TUI" start p0s2 "$r/project" "$r/home" "$r/driver.jsonl" || return
  python3 "$TUI" prompt p0s2 "$r/driver.jsonl" "$P_HIGH"
  python3 "$TUI" exit p0s2
}
s3() { # headless fresh HOME; then a resumed (--continue) turn
  local r="$OUT/S3-headless-fresh"; setup S3-headless-fresh
  meta S3-headless-fresh "$r/home" "$r/project" "$(turns "$P_HIGH" "$E_HIGH" "$P_LOW" "$E_LOW")"
  headless "$r/home" "$r/project" "$r/events.jsonl" "$r/turn1.jsonl" "$P_HIGH"
  headless "$r/home" "$r/project" "$r/events.jsonl" "$r/turn2.jsonl" "$P_LOW" --continue
}
s4() { # existing installation (S2's HOME), new TUI session, same project
  local src="$OUT/S2-tui-skills-dir" r="$OUT/S4-tui-existing"; mkdir -p "$r"
  meta S4-tui-existing "$src/home" "$src/project" "$(turns "$P_HIGH" "$E_HIGH")"
  python3 "$TUI" start p0s4 "$src/project" "$src/home" "$r/driver.jsonl" || return
  python3 "$TUI" prompt p0s4 "$r/driver.jsonl" "$P_HIGH"
  python3 "$TUI" exit p0s4
  cp "$src/events.jsonl" "$r/events.jsonl"
}
s5() { # upgrade: S3's installed HOME, project whose skill-router changed
  local src="$OUT/S3-headless-fresh" r="$OUT/S5-tui-upgrade"; mkdir -p "$r"
  sb_project "$r/project"
  sed -i 's/^description: Decide how/description: UPGRADE-MARKER-P0 Decide how/' "$r/project/skills/skill-router/SKILL.md"
  local e; e=$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); d["listing_contains"]="UPGRADE-MARKER-P0"; print(json.dumps(d))' "$E_HIGH")
  meta S5-tui-upgrade "$src/home" "$r/project" "$(turns "$P_HIGH" "$e")"
  python3 "$TUI" start p0s5 "$r/project" "$src/home" "$r/driver.jsonl" || return
  python3 "$TUI" prompt p0s5 "$r/driver.jsonl" "$P_HIGH"
  python3 "$TUI" exit p0s5
  cp "$src/events.jsonl" "$r/events.jsonl"
}
s6() { # hook failure: a foreign skill-router directory blocks the install
  local r="$OUT/S6-headless-hook-failure"; setup S6-headless-hook-failure
  mkdir -p "$r/home/.claude/skills/skill-router"; echo "user's own folder" > "$r/home/.claude/skills/skill-router/README"
  meta S6-headless-hook-failure "$r/home" "$r/project" "$(turns "$P_Q" '{"hook_context_contains":"not managed by this system","modification":false}')"
  headless "$r/home" "$r/project" "$r/events.jsonl" "$r/turn1.jsonl" "$P_Q"
}
s7() { # platform tolerance: hook prints malformed output (disposable copy only)
  local r="$OUT/S7-headless-malformed-output"; setup S7-headless-malformed-output
  printf '#!/bin/bash\necho "not json {"\nexit 0\n' > "$r/project/.claude/hooks/session-start.sh"
  meta S7-headless-malformed-output "$r/home" "$r/project" "$(turns "$P_Q" '{"modification":false}')"
  headless "$r/home" "$r/project" "$r/events.jsonl" "$r/turn1.jsonl" "$P_Q"
}

# ONLY="s1 s2" runs a subset (s4 needs s2's run, s5 needs s3's, in the same OUT_DIR).
if [[ -n "${ONLY:-}" ]]; then
  for s in $ONLY; do "$s" & done; wait
else
  s1 & s2 & s3 & s6 & s7 & wait
  s4 & s5 & wait
fi
ANALYZE_OUT="$OUT/verdicts.json" python3 "$HERE/harness/analyze.py" "$OUT"/S*/ | tee "$OUT/verdicts.txt"
