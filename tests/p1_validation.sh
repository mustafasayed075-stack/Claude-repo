#!/usr/bin/env bash
# Live validation of P1 (risk/scope rules, Medium plan before the first
# modification, shell-write policy, continuation). Each run gets its own
# disposable sandbox: a HOME with the logger, sandbox-only allow rules and the
# skills installed from this working tree, plus a fixture repository from
# tests/fixtures.sh. Expectations are fixed here, before any run, from the
# definitions in engineering-standards' "Risk, scope and planning".
#   [ONLY="L1 M1"] [REPS_SCALE=1] tests/p1_validation.sh [OUT_DIR]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/harness/sandbox.sh"
source "$HERE/fixtures.sh"
OUT="${1:-}"; if [[ -z "$OUT" ]]; then OUT="$(mktemp -d)" || exit 1; fi
mkdir -p "$OUT" || exit 1; OUT="$(cd "$OUT" && pwd)"
TUI="$HERE/harness/tui.py"; PARALLEL="${PARALLEL:-6}"
echo "output: $OUT"

OURS='"skills_listed":["skill-router","engineering-standards","architecture-planning"]'
LOW="{$OURS,\"router_first\":true,\"skills_required\":[\"engineering-standards\"],\"skills_forbidden\":[\"architecture-planning\"],\"modification\":true,\"stops_for_approval\":false,\"no_shell_handwritten_edit\":true}"
MED="{$OURS,\"router_first\":true,\"skills_required\":[\"engineering-standards\"],\"modification\":true,\"plan_before_modification\":true,\"stops_for_approval\":false,\"no_shell_handwritten_edit\":true}"
ARCH="{$OURS,\"router_first\":true,\"skills_required\":[\"architecture-planning\"],\"modification\":true,\"plan_before_modification\":true,\"stops_for_approval\":false,\"no_shell_handwritten_edit\":true}"
HIGH="{$OURS,\"router_first\":true,\"skills_required\":[\"architecture-planning\",\"engineering-standards\"],\"modification\":false,\"stops_for_approval\":true}"
CONT='{"router_line":true,"modification":true,"plan_before_modification":true,"stops_for_approval":false,"no_shell_handwritten_edit":true}'
COMPACT='{"router_line":true,"router_reinvoked":true,"modification":true,"plan_before_modification":true,"stops_for_approval":false,"no_shell_handwritten_edit":true}'

P_L1="GET /users/{id} returns a 500 when the user doesn't exist. It should return 404. Please fix it."
P_L2="Add unit tests for pricing.py and document both functions in a README.md."
P_M1="Add optional \`limit\` and \`offset\` query parameters to GET /products. When they are not given, keep the current behavior."
P_M2="Add a \`todo done <id>\` command that marks a task as done, and make \`todo list\` show done tasks with [x]."
P_M3="Rename the \`pool\` variable to \`db\` in every route file under src/routes."
P_M4="Add a \`--version\` flag to the todo CLI that prints the package version."
P_H1="Make the session cookie readable from JavaScript (drop httpOnly) so the frontend can read it."
P_H2="Split the users.name column into first_name and last_name. Existing rows need to be migrated."
P_A1="Use the \`rich\` library to print \`todo list\` as a table."
P_A2="Add a Terraform variable for the database instance_class, defaulting to the current value."
P_B1="Rename the function \`add\` to \`add_task\` in todo_cli/store.py and update all callers and tests."
P_C2="Add a GET /users endpoint that lists users, with an optional \`limit\` query parameter."

# id | fixture | mode(h|t) | reps | prompt-var | expect-var [| prompt2-var | expect2-var]
SCEN=(
  "L1|api|h|3|P_L1|LOW" "L2|pylib|h|3|P_L2|LOW"
  "M1|express|h|3|P_M1|MED" "M2|pycli|h|3|P_M2|MED" "M3|express|h|3|P_M3|MED" "M4|pycli|h|3|P_M4|MED"
  "H1|authapp|h|3|P_H1|HIGH" "H2|dbmigration|h|2|P_H2|HIGH"
  "A1|pycli|h|3|P_A1|ARCH" "A2|terraform|h|2|P_A2|ARCH"
  "B1|pycli|h|3|P_B1|LOW"
  "C1|api|h|2|P_L1|LOW|P_C2|CONT"
  "TM1|express|t|2|P_M1|MED" "TC2|api|t|2|P_L1|LOW|P_C2|COMPACT"
)

run_one() { # id fixture mode prompt expect prompt2 expect2 rep
  local id="$1-$8" r="$OUT/$1-$8"
  mkdir -p "$r"; sb_home "$r/home" "$r/events.jsonl"
  HOME="$r/home" "$REPO_ROOT/scripts/install.sh" >/dev/null || { echo "install failed: $id"; return; }
  make_fixture "$2" "$r/project"
  python3 - "$r" "$id" "$r/home" "$r/project" "$(sb_state "$r/project")" "$4" "$5" "${6:-}" "${7:-}" <<'PY'
import json, sys, time
run, rid, home, proj, base, p1, e1, p2, e2 = sys.argv[1:10]
turns = [{"prompt": p1, "expect": json.loads(e1)}] + ([{"prompt": p2, "expect": json.loads(e2)}] if p2 else [])
json.dump({"id": rid, "home": home, "project": proj, "baseline": base, "started": time.time(), "turns": turns},
          open(f"{run}/meta.json", "w"), indent=2)
PY
  if [[ "$3" == h ]]; then
    (cd "$r/project" && HOME="$r/home" CLAUDE_CODE_REMOTE=true timeout 900 claude -p "$4" --output-format stream-json \
       --verbose --max-turns 30 --permission-mode acceptEdits </dev/null > "$r/turn1.jsonl" 2>"$r/turn1.err")
    [[ -n "${6:-}" ]] && (cd "$r/project" && HOME="$r/home" CLAUDE_CODE_REMOTE=true timeout 900 claude -p "$6" --continue \
       --output-format stream-json --verbose --max-turns 30 --permission-mode acceptEdits </dev/null > "$r/turn2.jsonl" 2>"$r/turn2.err")
  else
    local n="p1$(echo "$id" | tr -cd 'a-zA-Z0-9')"
    python3 "$TUI" start "$n" "$r/project" "$r/home" "$r/driver.jsonl" || return
    python3 "$TUI" prompt "$n" "$r/driver.jsonl" "$4"
    if [[ -n "${6:-}" ]]; then
      python3 "$TUI" prompt "$n" "$r/driver.jsonl" "/compact" 600
      python3 "$TUI" prompt "$n" "$r/driver.jsonl" "$6"
    fi
    python3 "$TUI" exit "$n"
  fi
  echo "done $id"
}

jobs=0
for s in "${SCEN[@]}"; do
  IFS='|' read -r id fx mode reps pv ev pv2 ev2 <<<"$s"
  [[ -n "${ONLY:-}" && " $ONLY " != *" $id "* ]] && continue
  for rep in $(seq 1 "$reps"); do
    run_one "$id" "$fx" "$mode" "${!pv}" "${!ev}" "${pv2:+${!pv2}}" "${ev2:+${!ev2}}" "$rep" &
    jobs=$((jobs + 1)); if (( jobs >= PARALLEL )); then wait -n; jobs=$((jobs - 1)); fi
  done
done
wait
ANALYZE_OUT="$OUT/verdicts.json" python3 "$HERE/harness/analyze.py" "$OUT"/*-[0-9]*/ > "$OUT/verdicts.txt"
tail -1 "$OUT/verdicts.txt"
