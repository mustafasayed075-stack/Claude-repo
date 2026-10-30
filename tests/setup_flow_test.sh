#!/usr/bin/env bash
# End-to-end test of the setup-script route for *other* repositories, run in
# a disposable sandbox: a fresh HOME, a local file:// clone of this repo's
# committed HEAD (no network, no packages), and a throwaway project repo that
# has no hook of its own. The real ~/.claude and this repo are fingerprinted
# before and after and must not change.
#   tests/setup_flow_test.sh            installer + live claude checks
#   LIVE=0 tests/setup_flow_test.sh     installer checks only (no claude)
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
LIVE="${LIVE:-1}"
SANDBOX="$(mktemp -d)" || { echo "mktemp failed" >&2; exit 1; }
[[ -n "$SANDBOX" && -d "$SANDBOX" ]] || exit 1
trap 'rm -rf "$SANDBOX"' EXIT
H="$SANDBOX/home"; SRC="$SANDBOX/opt/engineering-skills"; PROJ="$SANDBOX/projects/other"
mkdir -p "$H" "$PROJ"
PASS=0 FAIL=0 SKIP=0
ok()   { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
skip() { SKIP=$((SKIP + 1)); echo "  SKIP  $1"; }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

fingerprint() {
  { sha256sum "$HOME/.claude/CLAUDE.md" 2>/dev/null || echo "no CLAUDE.md"
    find "$HOME/.claude/skills" -type f -exec sha256sum {} + 2>/dev/null | sort
    git -C "$REPO" rev-parse HEAD; git -C "$REPO" status --porcelain; } | sha256sum
}
before="$(fingerprint)"
[[ -z "$(git -C "$REPO" status --porcelain)" ]] || echo "  note: working tree is dirty; the test uses committed HEAD only"
branch="$(git -C "$REPO" rev-parse --abbrev-ref HEAD)"

echo "1. Setup script (README command, with a local source instead of GitHub)"
setup() { git clone -q --depth 1 --branch "$branch" "file://$REPO" "$SRC" && HOME="$H" "$SRC/scripts/install.sh" >/dev/null; }
setup; rc=$?
expect "clone + install succeed in a fresh HOME" '[[ $rc -eq 0 ]]'
expect "--check passes in the fresh HOME" 'HOME="$H" "$SRC/scripts/install.sh" --check >/dev/null'
expect "skills and CLAUDE.md block are in the fresh HOME" '[[ -f "$H/.claude/skills/skill-router/SKILL.md" ]] && grep -q "BEGIN engineering-skill-system" "$H/.claude/CLAUDE.md"'
ino="$(stat -c %i "$H/.claude/CLAUDE.md")"; sum="$(sha256sum < "$H/.claude/CLAUDE.md")"
rm -rf "$SRC"; setup; rc=$?
expect "re-running the setup script is idempotent (CLAUDE.md not rewritten)" '[[ $rc -eq 0 && "$(stat -c %i "$H/.claude/CLAUDE.md")" == "$ino" && "$(sha256sum < "$H/.claude/CLAUDE.md")" == "$sum" ]]'

echo "2. Disposable project with no hook of its own"
git -C "$PROJ" init -q
printf '[project]\nname = "units"\nversion = "0.1.0"\n' > "$PROJ/pyproject.toml"
printf 'def km_to_miles(km):\n    return km * 0.621371\n' > "$PROJ/units.py"
git -C "$PROJ" add -A && git -C "$PROJ" -c user.email=t@t -c user.name=t commit -qm init
expect "project has no .claude directory" '[[ ! -e "$PROJ/.claude" ]]'

if [[ "$LIVE" != 1 ]] || ! command -v claude >/dev/null; then
  skip "live claude checks (LIVE=0 or claude not found)"
else
  out="$(cd "$PROJ" && HOME="$H" timeout 300 claude -p 'Without using any tools, reply with only a JSON object: "skills": names of every skill available to you; "routing_instruction_quote": copy verbatim the sentence from your instructions (not a skill description) that tells you when to use skill-router, or "".' </dev/null 2>&1)"
  q="$(python3 - "$out" "$SRC/global/CLAUDE.md" <<'P'
import json, re, sys
m = re.search(r"\{.*\}", sys.argv[1], re.S)
d = json.loads(m.group(0)) if m else {}
norm = lambda t: re.sub(r"\s+", " ", t.replace("`", "").replace("*", "")).strip().lower()
names = {str(n).split(":")[-1] for n in d.get("skills", [])}
quote = norm(str(d.get("routing_instruction_quote", "")))
ok_sk = {"skill-router", "engineering-standards", "architecture-planning"} <= names
ok_q = len(quote) >= 25 and quote in norm(open(sys.argv[2]).read())
print(int(ok_sk), int(ok_q))
P
)"
  expect "live session in the other project lists the skills" '[[ "${q%% *}" == 1 ]]'
  expect "live session quotes the routing rule from ~/.claude/CLAUDE.md" '[[ "${q##* }" == 1 ]]'

  (cd "$PROJ" && HOME="$H" timeout 600 claude -p "Add a miles_to_km function to units.py with unit tests." \
     --output-format stream-json --verbose --max-turns 20 --permission-mode acceptEdits \
     --allowedTools 'Read,Glob,Grep,Skill,Bash(ls:*),Bash(cat:*),Bash(python3 -m unittest:*),Bash(python -m unittest:*)' \
     </dev/null > "$SANDBOX/task.jsonl" 2>&1)
  r="$(python3 - "$SANDBOX/task.jsonl" <<'P'
import json, re, sys
skills, router = [], False
for l in open(sys.argv[1]):
    try: e = json.loads(l)
    except ValueError: continue
    if e.get("type") == "assistant":
        for c in e["message"]["content"]:
            if c["type"] == "tool_use" and c["name"] == "Skill": skills.append(c["input"].get("skill", ""))
            if c["type"] == "text" and re.search(r"Router:\s*[^|\n]+\|[^|\n]+\|\s*skills:", c["text"], re.I): router = True
print(int(bool(skills) and skills[0] == "skill-router"), int(router))
P
)"
  expect "routed task in the other project starts with skill-router" '[[ "${r%% *}" == 1 ]]'
  expect "routed task prints the Router line" '[[ "${r##* }" == 1 ]]'
  expect "routed task edited the disposable project" '[[ -n "$(git -C "$PROJ" status --porcelain)" ]]'
fi

echo "3. Isolation"
expect "real ~/.claude and this repo unchanged" '[[ "$(fingerprint)" == "$before" ]]'

echo
echo "Setup-flow tests: $PASS passed, $FAIL failed, $SKIP skipped"
[[ $FAIL -eq 0 ]]
