#!/usr/bin/env bash
# EXPERIMENT: deterministic tests for esk-router-gate.py and settings_merge.py.
# Payloads have the shape captured from Claude Code 2.1.314. Everything runs
# in a mktemp sandbox; the real ~/.claude is never touched.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GATE="$HERE/esk-router-gate.py"; MERGE="$HERE/settings_merge.py"
SB="$(mktemp -d)" || exit 1; trap 'rm -rf "$SB"' EXIT
export ESK_GATE_STATE_DIR="$SB/state"
PASS=0 FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

# payload <tool> <tool_input-json> [session] [transcript]
payload() { printf '{"session_id":"%s","transcript_path":"%s","cwd":"/p","hook_event_name":"X","tool_name":"%s","tool_input":%s}' "${3:-S1}" "${4:-/t/a.jsonl}" "$1" "$2"; }
pre()  { payload "$@" | "$GATE" pre; }
post() { payload "$@" | "$GATE" post >/dev/null; }
decision() { pre "$@" | python3 -c 'import json,sys; t=sys.stdin.read().strip(); print(json.loads(t)["hookSpecificOutput"]["permissionDecision"] if t else "none")'; }
SK() { printf '{"skill":"%s"}' "$1"; }
BA() { python3 -c 'import json,sys; print(json.dumps({"command": sys.argv[1]}))' "$1"; }
LINE="echo 'Router: Backend/API | Architecture | skills: architecture-planning, engineering-standards - schema change'"

echo "1. Router state machine"
expect "no router yet: other skills and edits pass" '[[ $(decision Skill "$(SK architecture-planning)") == none && $(decision Edit "{\"file_path\":\"/p/x\"}") == none ]]'
expect "loading skill-router itself is never blocked" '[[ $(decision Skill "$(SK skill-router)") == none ]]'
post Skill "$(SK skill-router)"
expect "after router, before decision: next skill denied" '[[ $(decision Skill "$(SK architecture-planning)") == deny ]]'
expect "consecutive calls stay denied (2nd skill)" '[[ $(decision Skill "$(SK engineering-standards)") == deny ]]'
expect "Edit / Write / NotebookEdit denied while pending" '[[ $(decision Edit "{}") == deny && $(decision Write "{}") == deny && $(decision NotebookEdit "{}") == deny ]]'
expect "reads and ordinary Bash are not blocked while pending" '[[ $(decision Read "{\"file_path\":\"/p/x\"}") == none && $(decision Bash "$(BA "ls -la")") == none && $(decision Grep "{}") == none ]]'
expect "deny reason names the exact command to run" 'pre Skill "$(SK architecture-planning)" | grep -q "echo '"'"'Router: "'
expect "inert decision echo is auto-allowed" '[[ $(decision Bash "$(BA "$LINE")") == allow ]]'
post Bash "$(BA "$LINE")"
expect "after decision: skills and edits pass" '[[ $(decision Skill "$(SK architecture-planning)") == none && $(decision Edit "{}") == none ]]'
expect "namespaced skill name (plugin:skill-router) is recognised" 'post Skill "$(SK x:skill-router)"; [[ $(decision Skill "$(SK engineering-standards)") == deny ]]'
post Bash "$(BA "$LINE")"
post Skill "$(SK skill-router)"
expect "router loaded again (new task) requires a new decision" '[[ $(decision Skill "$(SK engineering-standards)") == deny ]]'

echo "2. Decision command must be exact and inert"
for c in "$LINE; rm -rf /tmp/x" "$LINE && touch /tmp/x" "echo \"Router: a | b | skills: c - d\"" "echo 'Router: a | b | skills: c - d' > f" "echo 'Router: missing fields'" "echo 'Router: a | b | skills: c \$(id)' x" "printf 'Router: a | b | skills: c - d'"; do
  expect "rejected: $c" '[[ $(decision Bash "$(BA "$c")") == none ]] && { post Bash "$(BA "$c")"; [[ $(decision Skill "$(SK architecture-planning)") == deny ]]; }'
done

echo "3. Isolation and reset"
expect "same session_id, different transcript: independent state" '[[ $(decision Skill "$(SK architecture-planning)" S1 /t/b.jsonl) == none ]]'
expect "new session: nothing blocked until router runs" '[[ $(decision Skill "$(SK architecture-planning)" S2 /t/c.jsonl) == none ]]'

echo "4. Malformed input and failures fail open"
for raw in "" "not json" "[]" "{}" '{"tool_name":"Skill"}' '{"session_id":"S1","transcript_path":"/t/a.jsonl","tool_name":"Skill","tool_input":"str"}'; do
  out=$(printf '%s' "$raw" | "$GATE" pre); rc=$?
  expect "input ${raw:-<empty>}: exit 0, no decision" '[[ $rc -eq 0 && -z "$out" ]]'
done
out=$(payload Skill "$(SK skill-router)" | ESK_GATE_STATE_DIR=/proc/forbidden "$GATE" post); rc=$?
expect "unwritable state dir: exit 0 (fail open)" '[[ $rc -eq 0 && -z "$out" ]]'
out=$(payload Skill "$(SK architecture-planning)" | "$GATE"); rc=$?
expect "missing phase argument: exit 0, no decision" '[[ $rc -eq 0 && -z "$out" ]]'

echo "5. settings.json merge keeps everything else"
S="$SB/settings.json"
cat > "$S" <<'E'
{
  "model": "opus",
  "permissions": {"allow": ["Bash(npm *)"], "deny": ["Read(./.env)"]},
  "hooks": {
    "PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "user-audit.sh"}]}],
    "Stop": [{"hooks": [{"type": "command", "command": "user-stop.sh"}]}]
  },
  "env": {"FOO": "1"}
}
E
chmod 600 "$S"; orig=$(jq -c . "$S")
python3 "$MERGE" install "$S" "$GATE"; rc=$?
expect "install exits 0, mode 600 kept" '[[ $rc -eq 0 && $(stat -c %a "$S") == 600 ]]'
expect "user hook stays first, gate appended after it" '[[ $(jq -r ".hooks.PreToolUse[0].hooks[0].command" "$S") == user-audit.sh && $(jq -r ".hooks.PreToolUse[1].hooks[0].command" "$S") == *esk-router-gate* ]]'
expect "unrelated keys and Stop hook unchanged, key order kept" '[[ $(jq -c "del(.hooks.PreToolUse[1:], .hooks.PostToolUse)" "$S") == "$(echo "$orig" | jq -c .)" && $(jq -r "keys_unsorted|join(\",\")" "$S") == "model,permissions,hooks,env" ]]'
s1=$(sha256sum < "$S"); python3 "$MERGE" install "$S" "$GATE"
expect "install is idempotent (no change)" '[[ $(sha256sum < "$S") == "$s1" && $(jq ".hooks.PreToolUse|length" "$S") == 2 ]]'
python3 "$MERGE" uninstall "$S"
expect "uninstall restores the original JSON exactly (values and order)" '[[ $(jq -c . "$S") == "$(echo "$orig" | jq -c .)" ]]'
printf '{ "broken": ' > "$SB/bad.json"; b=$(sha256sum < "$SB/bad.json")
python3 "$MERGE" install "$SB/bad.json" "$GATE" 2>/dev/null; rc=$?
expect "invalid JSON: refuses, file untouched" '[[ $rc -ne 0 && $(sha256sum < "$SB/bad.json") == "$b" ]]'
echo '["x"]' > "$SB/arr.json"; python3 "$MERGE" install "$SB/arr.json" "$GATE" 2>/dev/null; rc=$?
expect "non-object JSON: refuses" '[[ $rc -ne 0 && $(cat "$SB/arr.json") == "[\"x\"]" ]]'
mkdir -p "$SB/dot"; echo '{"theme":"dark"}' > "$SB/dot/settings.json"; ln -s "$SB/dot/settings.json" "$SB/link.json"
python3 "$MERGE" install "$SB/link.json" "$GATE"
expect "symlinked settings: link kept, target updated" '[[ -L "$SB/link.json" && $(jq -r .theme "$SB/dot/settings.json") == dark && $(jq ".hooks.PreToolUse|length" "$SB/dot/settings.json") == 1 ]]'
python3 "$MERGE" install "$SB/new.json" "$GATE"
expect "missing settings file is created with only the gate hooks" '[[ $(jq -c "keys" "$SB/new.json") == "[\"hooks\"]" ]]'
expect "no temp files left" '[[ -z "$(find "$SB" -name ".settings.*")" ]]'

echo
echo "Gate tests: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
