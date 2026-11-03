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
flat() { owner | tr '\n' ' ' | tr -s ' '; }
# Texts are captured once and matched with here-strings (q/m/mF). A pipe into
# grep -q under pipefail can fail when grep exits early and the writer gets
# SIGPIPE; that made checks flaky and could fake a negated check.
OWN="$(owner)"; FLAT="$(flat)"
q() { local t="$1"; shift; grep "$@" <<< "$t"; }   # q <text> <grep args...>

echo "1. Single owner for risk and scope"
expect "engineering-standards has a '$SECTION' section" 'grep -q "^## .*$SECTION" "$STD"'
expect "the section comes before section 1 (top of the skill)" 'a=$(grep -n "^## .*$SECTION" "$STD" | cut -d: -f1); b=$(grep -n "^## 1\." "$STD" | cut -d: -f1); [[ -n "$a" && -n "$b" && $a -lt $b ]]'
expect "it defines all three tiers" 'q "$OWN" -q "\*\*High" && q "$OWN" -q "\*\*Medium" && q "$OWN" -q "\*\*Low"'
expect "High keeps the approved triggers" '( for t in "schema" "migration" "architecture" "authentication|authorization|permission" "secret" "break" "hard to undo|hard-to-undo" "downtime|data loss" "force-push|destructive git" "deploy|publish"; do q "$OWN" -qiE "$t" || exit 1; done )'
expect "size never lowers risk (a one-line change can be High)" 'q "$OWN" -qiE "one-line change can be High|regardless of size|any size"'
expect "no raw file-count threshold anywhere (no '3+ files')" '! grep -qE "3\+ files" "$STD" "$RT" "$AP" "$GL"'
expect "other files do not redefine High triggers (no 'migration' list in router table)" '! grep -qE "^\| High risk:" "$RT"'
expect "router, planning and global refer to the section by name" 'grep -q "$SECTION" "$RT" && grep -q "$SECTION" "$AP" && grep -q "$SECTION" "$GL"'
expect "no stale numeric reference to 'section 7' for tier definitions" '! grep -qiE "risk tiers in .engineering-standards. section 7|using .engineering-standards. section 7" "$AP" "$RT" "$GL"'

echo "2. Scope: implementation vs supporting files"
expect "defines implementation files" 'q "$OWN" -qiE "implementation files?"'
expect "defines supporting files (tests, docs) and says they do not raise the tier" 'q "$OWN" -qiE "supporting files?" && q "$OWN" -qiE "never raise|do not raise|don.t raise"'

echo "3. When architecture-planning is required"
expect "standards lists the planning triggers" '( for t in "boundar" "dependenc" "storage|data model|data-model" "infrastructure|build|deploy" "breaking"; do q "$OWN" -qiE "$t" || exit 1; done )'
expect "planning skill's 'Use it when' refers to the standards triggers, not a size rule" 'q "$(grep -A3 -i "^Use it when" "$AP")" -q "$SECTION"'
expect "router table first row is high risk / architectural impact, then the rest" 'q "$(awk "/^\| Situation/{f=1;next} f&&/^\|---/{next} f&&/^\|/{print;exit}" "$RT")" -qiE "high risk|architectural"'

echo "4. Medium plan artifact"
expect "standards defines the 'Plan (Medium):' header" 'q "$OWN" -q "Plan (Medium):"'
expect "it must come before the first modification by any tool" 'q "$OWN" -qiE "before the first (working-tree )?modification" && q "$OWN" -qiE "any tool|whatever tool|regardless of (the )?tool"'
expect "Medium does not wait for approval" 'q "$OWN" -qiE "without waiting|do not (wait|ask) for approval|no approval"'
expect "only standards defines the header; others refer to it" '[[ $(grep -c "Plan (Medium):" "$RT" "$AP" "$GL" | awk -F: "{s+=\$2} END{print s}") -le 3 ]] && grep -q "Plan (Medium):" "$AP"'

echo "5. Shell writes"
expect "hand-written edits to tracked files use the file tools" 'q "$OWN" -qiE "hand-?written" && q "$OWN" -qiE "Edit|Write"'
expect "generators, formatters, lockfiles and mechanical transformations stay allowed" '( for t in "generator" "formatter" "lockfile" "mechanical"; do q "$OWN" -qiE "$t" || exit 1; done )'
expect "no shell workaround around risk or approval controls" 'q "$OWN" -qiE "never .{0,40}(bypass|get around|avoid).{0,60}(approval|plan|risk|permission)"'
expect "a modification is tool-agnostic" 'q "$OWN" -qiE "any (change|modification) to the working tree|whatever tool|regardless of (the )?tool"'

echo "6. Continuation and compaction"
expect "the routing decision persists within a task" 'q "$OWN" -qiE "routing decision (persists|stays|carries)"'
expect "a new coding request gets a fresh Router line" 'q "$OWN" -qiE "new (coding )?request.{0,80}Router" || grep -qiE "new (coding )?request.{0,80}Router" "$RT"'
expect "re-invocation is conditional on content being out of context (e.g. after compaction)" 'q "$(cat "$STD" "$RT")" -qiE "compact" && q "$(cat "$STD" "$RT")" -qiE "no longer in context|not in context"'
expect "no unconditional re-invocation rule" '! q "$(cat "$STD" "$RT" "$GL")" -qiE "always (re-?invoke|invoke .?skill-router.? again)"'

echo "8. Approved P1 corrections (explicit tier, uncertainty, dependencies, planning independence)"
expect "an explicit 'Risk: Low|Medium|High' line is required before the first modification" 'q "$OWN" -qF "Risk: Low|Medium|High" && q "$FLAT" -qiE "Risk: Low\|Medium\|High.{0,160}before the first modification|before the first modification.{0,200}Risk: Low\|Medium\|High"'
expect "uncertain Low vs Medium: Medium" 'q "$FLAT" -qiE "uncertain between Low and Medium.{0,40}Medium"'
expect "uncertain Medium vs High: check the High triggers" 'q "$FLAT" -qiE "uncertain between Medium and High.{0,120}(High trigger|triggers)"'
expect "additive options and query parameters are Medium" 'q "$FLAT" -qiE "Medium.{0,400}additive" && q "$FLAT" -qiE "query parameter" && q "$FLAT" -qiE "CLI option"'
expect "Low excludes new behavior (no overlap with additive changes)" 'q "$FLAT" -qiE "\*\*Low[^:*]{0,40}:\*\*[^*]{0,300}(no new or changed behavior|adds no behavior|no added behavior)"'
expect "a new runtime dependency is Medium by default, High only on an explicit trigger" 'q "$FLAT" -qiE "new runtime dependency is Medium" && q "$FLAT" -qiE "High only (when|if) .{0,80}trigger"'
expect "architecture-planning is independent of the risk tier" 'q "$FLAT" -qiE "independent of the (risk )?tier"'
expect "planning trigger is a meaningful architectural dependency, not any dependency" 'q "$FLAT" -qiE "meaningful architectural dependency" && ! grep -qiE "new runtime dependency" "$RT" "$AP"'
expect "High: complete plan, then explicit approval before any modification" 'q "$FLAT" -qiE "complete plan" && q "$FLAT" -qiE "explicit approval"'
expect "global refers to the explicit Risk line without redefining tiers" 'grep -qF "Risk:" "$GL" && ! grep -qiE "additive|query parameter|runtime dependency" "$GL"'

echo "9. P1b: classification by change kind, routing exemption, High planning, approval source"
# Uses the captured texts above (no pipes into grep -q).
ff() { tr '\n' ' ' < "$1" | tr -s ' '; }
FSTD="$(ff "$STD")"; FRT="$(ff "$RT")"; FAP="$(ff "$AP")"; FGL="$(ff "$GL")"
TBL="$(awk '/^\| Situation/{f=1;next} f&&/^\|---/{next} f&&/^\|/{print} f&&!/^\|/{exit}' "$RT")"
m() { grep -qiE -- "$2" <<< "$1"; }   # m <text> <ERE>, case-insensitive
mF() { grep -qF -- "$2" <<< "$1"; }   # mF <text> <fixed string>
# Classification procedure (owner section)
expect "classification happens in engineering-standards, not in the Router line" 'm "$FLAT" "not in the Router line"'
expect "the Risk line names the High trigger and the change kind (new|restores|preserves)" 'mF "$OWN" "high trigger: <none|" && mF "$OWN" "change: <new|restores|preserves>"'
expect "a new capability or intentional contract change is Medium" 'm "$FLAT" "(new capability|intentional(ly)? (contract )?chang)[^.]{0,160}\bMedium\b"'
expect "restoring the established contract is Low and must name its evidence" 'm "$FLAT" "restor[^.]{0,200}\bLow\b" && m "$FLAT" "restor[^.]{0,200}only with (named )?evidence"'
expect "the user calling it a bug is not evidence" 'm "$FLAT" "(calls? it a bug|called a bug|bug label)[^.]{0,80}not evidence"'
expect "changing behavior that is itself the established contract is new, not a restore" 'm "$FLAT" "(current|existing) behavior is (itself )?the (established |existing )?contract[^.]{0,120}\bnew\b"'
expect "behavior-preserving restructuring is Low unless a Medium item or planning applies" 'm "$FLAT" "preserv[^.]{0,200}\bLow\b[^.]{0,200}unless"'
expect "uncertain between restores and new: new (Medium)" 'm "$FLAT" "uncertain (whether|between)[^.]{0,80}restor[^.]{0,100}(\bnew\b|Medium)"'
expect "explicit 'Not Low, even if one line' list" 'm "$FLAT" "Not Low, even (if|for) (a )?one[- ]line[^.]{0,200}option[^.]{0,80}parameter[^.]{0,80}endpoint"'
expect "the Risk line is stated as an instruction, not mechanical enforcement" 'm "$FLAT" "nothing blocks|not a mechanical check"'
expect "guard: no rule claims a gate or hook enforcement" '! m "$FSTD $FRT $FAP $FGL" "\bgate\b|enforced by|hook (blocks|denies)"'
expect "the Risk line records the classification, never an approval" 'm "$FLAT" "records the classification, never (an )?approval"'
# High and architecture-planning
expect "High loads architecture-planning before its plan" 'm "$FLAT" "High[^.]{0,200}load\w* .?architecture-planning"'
expect "every High change requires architecture-planning (standards, router, planning, global)" 'm "$FSTD" "every High change" && m "$FRT" "every High change" && m "$FAP" "every High change" && m "$FGL" "every High change"'
expect "guard: architectural impact still requires planning independent of the tier" 'm "$FLAT" "independent of the (risk )?tier"'
expect "router table does not route on a tier it has not decided (no 'High risk' row)" '! m "$TBL" "high risk"'
expect "router: a High classification in engineering-standards loads architecture-planning" 'm "$FRT" "High classification[^.|]{0,120}architecture-planning"'
expect "Router reason carries no risk or size verdict" 'm "$FRT" "never its risk, size"'
# Routing exemption
expect "global and router exempt only edits that cannot execute" 'm "$FGL" "cannot execute" && m "$FRT" "cannot execute"'
expect "no size- or dictation-based routing exemption remains" '! m "$FRT $FGL" "trivial (one-line )?edits|named the approach|command the user specified"'
expect "docstrings and user-visible strings are routed (not exempt)" 'm "$FRT" "docstrings[^.]{0,80}user-visible strings"'
expect "standards points to the non-executable exemption" 'm "$FLAT" "non-executable"'
# Approval source
expect "own text, Risk line, tool and subagent output are never approval" 'm "$FSTD" "Risk line[^.]{0,200}subagent output[^.]{0,160}never approval"'
expect "pre-approval of a plan not yet shown is not approval" 'm "$FSTD" "pre-approval"'
expect "never describe a plan as approved unless the user said so" 'm "$FSTD" "never (describe|call)[^.]{0,30}plan as approved"'
expect "global: nothing you write counts as approval" 'm "$FGL" "nothing you write counts as approval"'
expect "planning Step 0 reuses the Risk line already written in the turn" 'm "$FAP" "Step 0[^#]{0,400}Risk:\W{0,2} line[^.]{0,80}(already|was) written"'
expect "guard: existing High approval rules unchanged" 'm "$FSTD" "explicit yes from the user" && m "$FSTD" "never approval of a plan the user has not seen" && m "$FSTD" "Material deviation"'

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
