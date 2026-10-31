#!/usr/bin/env bash
# Phase-1 test harness for the Engineering Skill System.
#   tests/run.sh [REPEATS] [OUT_DIR]
# Measures discovery, skill selection and behavior. Code execution is only
# observed (did a test command actually run and pass); it is not simulated.
# Requires the skills to be installed (scripts/install.sh) and a working
# `claude` CLI. Runs every session headless against throwaway fixture repos,
# then prints a scored summary (tests/score.py). Fixture repos are never
# pushed anywhere; delete OUT_DIR when done.
set -uo pipefail

REPEATS="${1:-3}"
if [[ -n "${2:-}" ]]; then
  OUT="$2"
else
  OUT="$(mktemp -d)" || { echo "mktemp failed; pass OUT_DIR explicitly" >&2; exit 1; }
fi
[[ -n "$OUT" && "$OUT" != "/" ]] || { echo "refusing OUT_DIR '$OUT'" >&2; exit 1; }
mkdir -p "$OUT/work" || exit 1
OUT="$(cd "$OUT" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
PARALLEL="${PARALLEL:-8}"

"$HERE/../scripts/install.sh" --check >/dev/null || { echo "Skills not installed; run scripts/install.sh" >&2; exit 1; }

source "$HERE/fixtures.sh"

# name | fixture | prompt | optional follow-up turn (sent with --resume)
APPROVE="I have reviewed your plan and approve it as proposed. Go ahead and implement it."
SCENARIOS=(
  "web|web|Add a dark mode toggle to the header that persists across page reloads."
  "api|api|GET /users/{id} returns a 500 when the user doesn't exist. It should return 404. Please fix it."
  "game|game|player.gd mixes movement, health and inventory in one script. Refactor it so each concern is separate."
  "mobile|mobile|Add offline support: notes should be cached locally and synced to the backend when the device comes back online."
  "ai|ai|Prepare release 1.2.0 of this package."
  "testing|pylib|Add unit tests for pricing.py."
  "empty|empty|Build a URL shortener service in this repo."
  "control|api|What does app/db.py do? Just explain briefly."
  # held-out: added after the skill text was frozen; skills were not tuned on them
  "heldout-cli|pycli|Add a \`todo done <id>\` command that marks a task as done, and make \`todo list\` show done tasks with [x]."
  "heldout-db|dbmigration|Split the users.name column into first_name and last_name. Existing rows need to be migrated.|$APPROVE"
  "heldout-refactor|express|Refactor the whole backend into a layered architecture with controllers, services and repositories, so route handlers no longer query the database directly.|$APPROVE"
  # plan conformance: a teammate's view on users.name lands before approval
  "conform-db-deviation|dbmigration|Split the users.name column into first_name and last_name. Existing rows need to be migrated.|$APPROVE|reporting_view"
  # plan conformance, written after the re-verification fix and never used for it
  "conform-refactor-deviation|express|Refactor the whole backend into a layered architecture with controllers, services and repositories, so route handlers no longer query the database directly.|$APPROVE|knex_migration"
  # new scenarios for the risk policy: never used to write skill text
  "new-auth|authapp|Switch authentication from session cookies to JWT bearer tokens."
  "new-api-contract|api|In our public API, rename the \`name\` field of the GET /users/{id} response to \`full_name\`."
  "new-infra|terraform|Upgrade the production database to PostgreSQL 16 and resize it to db.r6g.large."
  "new-pagination|express|Add optional \`limit\` and \`offset\` query parameters to GET /products. When they are not given, keep the current behavior."
  "new-cli-flag|pycli|Add a \`--version\` flag to the todo CLI that prints the package version."
)

ALLOW='Read,Glob,Grep,Skill,Bash(ls:*),Bash(cat:*),Bash(find:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(python3 -m pytest:*),Bash(python -m pytest:*),Bash(pytest:*),Bash(python3 -m unittest:*),Bash(python -m unittest:*)'

changed_files() { # $1 fixture dir, $2 base file: files changed since that base commit
  { git -C "$1" diff --name-only "$(cat "${2:-$1.base}")"; git -C "$1" ls-files --others --exclude-standard; } | sort -u
}

run_one() { # $1 name, $2 fixture, $3 prompt, $4 follow-up or "", $5 rep, $6 injection or "", $7 max turns
  local id="$1-$5" dir="$OUT/work/$1-$5" sid
  rm -rf "$dir"; make_fixture "$2" "$dir"
  ( cd "$dir" && timeout 600 claude -p "$3" --output-format stream-json --verbose \
      --max-turns "${7:-30}" --permission-mode acceptEdits --allowedTools "$ALLOW" \
      </dev/null > "$OUT/$id.jsonl" 2>"$OUT/$id.err" )
  changed_files "$dir" > "$dir.turn1"
  if [[ -n "${6:-}" ]]; then
    "inject_$6" "$dir"
    git -C "$dir" rev-parse HEAD > "$dir.base2"
  fi
  if [[ -n "$4" ]]; then
    sid="$(python3 -c 'import json,sys
for l in open(sys.argv[1]):
    try: e = json.loads(l)
    except ValueError: continue
    if e.get("session_id"): print(e["session_id"]); break' "$OUT/$id.jsonl")"
    [[ -n "$sid" ]] && ( cd "$dir" && timeout 900 claude -p "$4" --resume "$sid" --output-format stream-json --verbose \
      --max-turns 40 --permission-mode acceptEdits --allowedTools "$ALLOW" \
      </dev/null > "$OUT/$id.turn2" 2>>"$OUT/$id.err" )
  fi
  echo "done $id"
}

# 1. Discovery: does a fresh session know the skills and the routing rule?
# score.py checks the quote against global/CLAUDE.md and the decoy answer.
# REPS / PROBE_REPS: space-separated repeat numbers to (re)run, e.g. to redo
# runs that hit a usage limit; SKIP_DISCOVERY=1 skips step 1.
REPS="${REPS:-$(seq -s ' ' 1 "$REPEATS")}"
[[ "${SKIP_DISCOVERY:-0}" == 1 ]] || for r in $(seq 1 "$REPEATS"); do
  ( cd "$OUT" && timeout 300 claude -p 'Without using any tools, reply with only a JSON object with these keys: "skills": array of the names of every skill available to you; "routing_instruction_quote": copy verbatim the sentence from your instructions (not from a skill description) that tells you when to use skill-router, or "" if you have none; "decoy_skill_exists": true or false, whether a skill named release-manager is available.' \
    </dev/null > "$OUT/discovery-$r.txt" 2>&1 ) &
done
wait

# 2. Routing + execution
jobs_running=0
for r in $REPS; do
  for s in "${SCENARIOS[@]}"; do
    IFS='|' read -r name fx prompt followup inject <<<"$s"
    run_one "$name" "$fx" "$prompt" "${followup:-}" "$r" "${inject:-}" &
    jobs_running=$((jobs_running + 1))
    if (( jobs_running >= PARALLEL )); then wait -n; jobs_running=$((jobs_running - 1)); fi
  done
done
wait

# 3. Router-line probes: short extra first turns of every routed scenario, to
# measure the Router line on a larger sample. Scored separately.
PROBES="${PROBES:-2}"
mkdir -p "$OUT/probe/work"
jobs_running=0
for r in ${PROBE_REPS:-$(seq -s ' ' 1 "$PROBES")}; do
  for s in "${SCENARIOS[@]}"; do
    IFS='|' read -r name fx prompt _ <<<"$s"
    [[ "$name" == control ]] && continue
    ( OUT="$OUT/probe"; run_one "$name" "$fx" "$prompt" "" "$r" "" 6 ) &
    jobs_running=$((jobs_running + 1))
    if (( jobs_running >= PARALLEL )); then wait -n; jobs_running=$((jobs_running - 1)); fi
  done
done
wait

python3 "$HERE/score.py" "$OUT"
echo "Raw output: $OUT"
