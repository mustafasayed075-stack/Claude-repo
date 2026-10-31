#!/usr/bin/env bash
# Deterministic consistency checks for the instruction set (no claude calls).
# Ownership: engineering-standards' "Risk, scope and planning" section is the
# single source of truth for risk tiers, scope, the Medium plan, shell writes
# and continuation; skill-router, architecture-planning and global/CLAUDE.md
# refer to it instead of defining their own thresholds.
#   tests/policy_test.sh [REPO_DIR]
set -uo pipefail
R="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
STD="$R/skills/engineering-standards/SKILL.md"; RT="$R/skills/skill-router/SKILL.md"
AP="$R/skills/architecture-planning/SKILL.md"; GL="$R/global/CLAUDE.md"
SECTION="Risk, scope and planning"
PASS=0 FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
has() { grep -qiE "$2" "$1"; }
# body of the owner section: from its heading to the next "## " heading
owner() { awk -v s="$SECTION" 'index($0, "## ")==1 { on = (index($0, s) > 0) } on' "$STD"; }

echo "1. Single owner for risk and scope"
expect "engineering-standards has a '$SECTION' section" 'grep -q "^## .*$SECTION" "$STD"'
expect "the section comes before section 1 (top of the skill)" 'a=$(grep -n "^## .*$SECTION" "$STD" | cut -d: -f1); b=$(grep -n "^## 1\." "$STD" | cut -d: -f1); [[ -n "$a" && -n "$b" && $a -lt $b ]]'
expect "it defines all three tiers" 'owner | grep -q "\*\*High" && owner | grep -q "\*\*Medium" && owner | grep -q "\*\*Low"'
expect "High keeps the approved triggers" '( for t in "schema" "migration" "architecture" "authentication|authorization|permission" "secret" "break" "hard to undo|hard-to-undo" "downtime|data loss" "force-push|destructive git" "deploy|publish"; do owner | grep -qiE "$t" || exit 1; done )'
expect "size never lowers risk (a one-line change can be High)" 'owner | grep -qiE "one-line change can be High|regardless of size|any size"'
expect "no raw file-count threshold anywhere (no '3+ files')" '! grep -qE "3\+ files" "$STD" "$RT" "$AP" "$GL"'
expect "other files do not redefine High triggers (no 'migration' list in router table)" '! grep -qE "^\| High risk:" "$RT"'
expect "router, planning and global refer to the section by name" 'grep -q "$SECTION" "$RT" && grep -q "$SECTION" "$AP" && grep -q "$SECTION" "$GL"'
expect "no stale numeric reference to 'section 7' for tier definitions" '! grep -qiE "risk tiers in .engineering-standards. section 7|using .engineering-standards. section 7" "$AP" "$RT" "$GL"'

echo "2. Scope: implementation vs supporting files"
expect "defines implementation files" 'owner | grep -qiE "implementation files?"'
expect "defines supporting files (tests, docs) and says they do not raise the tier" 'owner | grep -qiE "supporting files?" && owner | grep -qiE "never raise|do not raise|don.t raise"'

echo "3. When architecture-planning is required"
expect "standards lists the planning triggers" '( for t in "boundar" "dependenc" "storage|data model|data-model" "infrastructure|build|deploy" "breaking"; do owner | grep -qiE "$t" || exit 1; done )'
expect "planning skill's 'Use it when' refers to the standards triggers, not a size rule" 'grep -A3 -i "^Use it when" "$AP" | grep -q "$SECTION"'
expect "router table first row is high risk / architectural impact, then the rest" 'awk "/^\| Situation/{f=1;next} f&&/^\|---/{next} f&&/^\|/{print;exit}" "$RT" | grep -qiE "high risk|architectural"'

echo "4. Medium plan artifact"
expect "standards defines the 'Plan (Medium):' header" 'owner | grep -q "Plan (Medium):"'
expect "it must come before the first modification by any tool" 'owner | grep -qiE "before the first (working-tree )?modification" && owner | grep -qiE "any tool|whatever tool|regardless of (the )?tool"'
expect "Medium does not wait for approval" 'owner | grep -qiE "without waiting|do not (wait|ask) for approval|no approval"'
expect "only standards defines the header; others refer to it" '[[ $(grep -c "Plan (Medium):" "$RT" "$AP" "$GL" | awk -F: "{s+=\$2} END{print s}") -le 3 ]] && grep -q "Plan (Medium):" "$AP"'

echo "5. Shell writes"
expect "hand-written edits to tracked files use the file tools" 'owner | grep -qiE "hand-?written" && owner | grep -qiE "Edit|Write"'
expect "generators, formatters, lockfiles and mechanical transformations stay allowed" '( for t in "generator" "formatter" "lockfile" "mechanical"; do owner | grep -qiE "$t" || exit 1; done )'
expect "no shell workaround around risk or approval controls" 'owner | grep -qiE "never .{0,40}(bypass|get around|avoid).{0,60}(approval|plan|risk|permission)"'
expect "a modification is tool-agnostic" 'owner | grep -qiE "any (change|modification) to the working tree|whatever tool|regardless of (the )?tool"'

echo "6. Continuation and compaction"
expect "the routing decision persists within a task" 'owner | grep -qiE "routing decision (persists|stays|carries)"'
expect "a new coding request gets a fresh Router line" 'owner | grep -qiE "new (coding )?request.{0,80}Router" || grep -qiE "new (coding )?request.{0,80}Router" "$RT"'
expect "re-invocation is conditional on content being out of context (e.g. after compaction)" 'cat "$STD" "$RT" | grep -qiE "compact" && cat "$STD" "$RT" | grep -qiE "no longer in context|not in context"'
expect "no unconditional re-invocation rule" '! cat "$STD" "$RT" "$GL" | grep -qiE "always (re-?invoke|invoke .?skill-router.? again)"'

echo "8. Approved P1 corrections (explicit tier, uncertainty, dependencies, planning independence)"
flat() { owner | tr '\n' ' ' | tr -s ' '; }
expect "an explicit 'Risk: Low|Medium|High' line is required before the first modification" 'owner | grep -qF "Risk: Low|Medium|High" && flat | grep -qiE "Risk: Low\|Medium\|High.{0,160}before the first modification|before the first modification.{0,200}Risk: Low\|Medium\|High"'
expect "uncertain Low vs Medium: Medium" 'flat | grep -qiE "uncertain between Low and Medium.{0,40}Medium"'
expect "uncertain Medium vs High: check the High triggers" 'flat | grep -qiE "uncertain between Medium and High.{0,120}(High trigger|triggers)"'
expect "additive options and query parameters are Medium" 'flat | grep -qiE "Medium.{0,400}additive" && flat | grep -qiE "query parameter" && flat | grep -qiE "CLI option"'
expect "Low excludes new behavior (no overlap with additive changes)" 'flat | grep -qiE "\*\*Low[^:*]{0,40}:\*\*[^*]{0,300}(no new or changed behavior|adds no behavior|no added behavior)"'
expect "a new runtime dependency is Medium by default, High only on an explicit trigger" 'flat | grep -qiE "new runtime dependency is Medium" && flat | grep -qiE "High only (when|if) .{0,80}trigger"'
expect "architecture-planning is independent of the risk tier" 'flat | grep -qiE "independent of the (risk )?tier"'
expect "planning trigger is a meaningful architectural dependency, not any dependency" 'flat | grep -qiE "meaningful architectural dependency" && ! grep -qiE "new runtime dependency" "$RT" "$AP"'
expect "High: complete plan, then explicit approval before any modification" 'flat | grep -qiE "complete plan" && flat | grep -qiE "explicit approval"'
expect "global refers to the explicit Risk line without redefining tiers" 'grep -qF "Risk:" "$GL" && ! grep -qiE "additive|query parameter|runtime dependency" "$GL"'

echo "7. Hygiene"
for f in "$STD" "$RT" "$AP"; do
  n=$(basename "$(dirname "$f")")
  expect "$n: frontmatter name matches, description <= 1024 chars" '( python3 - "$f" "$n" <<PY
import re, sys
t = open(sys.argv[1]).read(); fm = t.split("---")[1]
name = re.search(r"^name: (.+)$", fm, re.M).group(1).strip()
desc = re.search(r"^description: (.+)$", fm, re.M).group(1)
sys.exit(0 if name == sys.argv[2] and len(desc) <= 1024 else 1)
PY
)'
done
expect "Router line format unchanged" 'grep -qF "Router: <project type> | <task type> | skills: <list or none> - <reason" "$RT"'
expect "High approval rules still present" 'has "$STD" "approves the goal, not the" && has "$STD" "Plan conformance: followed approved plan"'

echo
echo "Policy tests: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
