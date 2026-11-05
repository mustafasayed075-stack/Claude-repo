#!/usr/bin/env bash
# Deterministic checks for the backend domain skills (skills/backend-*). No
# claude calls; installs only into a mktemp CLAUDE_HOME.
#   tests/backend_skills_test.sh [REPO_DIR]
# Each skill folder holds SKILL.md, references/<name>.md and SOURCES.md. The
# "Backend common rules" block must be byte-identical in every backend skill,
# and no payment gateway may be named anywhere under these folders.
set -uo pipefail
R="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
PASS=0 FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
SANDBOX="$(mktemp -d)" || { echo "mktemp failed" >&2; exit 1; }
trap 'rm -rf "$SANDBOX"' EXIT

# The backend skills this stage ships. Stage 2 adds the other three here.
EXPECTED=(backend-api-data)
# Bracket patterns, so this file does not itself contain a gateway name.
GATEWAYS='s[t]ripe|f[a]wry|p[a]ymob|x[p]ay|p[a]ytabs|p[a]ypal|a[d]yen|b[r]aintree|k[a]shier|g[e]idea|r[a]zorpay|c[h]eckout\.com'
fm() { [[ -f "$1" ]] || return 0; python3 -B -c 'import re,sys; t=open(sys.argv[1]).read(); p=t.split("---"); m=re.search(r"^"+sys.argv[2]+r": (.+)$", p[1], re.M) if t.startswith("---") and len(p)>2 else None; print(m.group(1) if m else "")' "$1" "$2"; }
# the "## Backend common rules" section, up to the next "## " heading
common_block() { awk 'index($0,"## ")==1 { on = ($0 == "## Backend common rules") } on' "$1"; }

echo "1. Skill folders"
for s in "${EXPECTED[@]}"; do
  d="$R/skills/$s"
  expect "$s: SKILL.md, SOURCES.md and a references/ folder exist" '[[ -f "$d/SKILL.md" && -f "$d/SOURCES.md" && -d "$d/references" ]]'
  expect "$s: frontmatter name is $s" '[[ "$(fm "$d/SKILL.md" name)" == "$s" ]]'
  expect "$s: description starts \"In a backend/server codebase\"" '[[ "$(fm "$d/SKILL.md" description)" == "In a backend/server codebase"* ]]'
  expect "$s: description is at most 1024 characters" 'n=$(fm "$d/SKILL.md" description | wc -c); [[ $n -gt 1 && $n -le 1025 ]]'
done
found=$(cd "$R/skills" && ls -d backend-* 2>/dev/null | sort | tr '\n' ' ')
expect "no unexpected backend-* folder (found: ${found:-none})" '[[ "$found" == "$(printf "%s\n" "${EXPECTED[@]}" | sort | tr "\n" " ")" ]]'

echo "2. Size limits"
for s in "${EXPECTED[@]}"; do
  f="$R/skills/$s/SKILL.md"; [[ -f "$f" ]] || { bad "$s: SKILL.md missing"; continue; }
  n=$(wc -l < "$f")
  expect "$s: SKILL.md has fewer than 500 lines (hard cap; $n)" '[[ $n -lt 500 ]]'
  expect "$s: SKILL.md has fewer than 300 lines (target; $n)" '[[ $n -lt 300 ]]'
  for r in "$R/skills/$s"/references/*.md; do
    [[ -f "$r" ]] || continue
    m=$(wc -l < "$r")
    expect "$s: $(basename "$r") has at most 400 lines ($m)" '[[ $m -le 400 ]]'
    expect "$s: $(basename "$r") over 100 lines has a contents list" '[[ $m -le 100 ]] || grep -qiE "^## Contents" "$r"'
  done
done

echo "3. Reference pointers"
for s in "${EXPECTED[@]}"; do
  d="$R/skills/$s"; [[ -f "$d/SKILL.md" ]] || continue
  missing=""; for p in $(grep -ohE 'references/[a-z0-9-]+\.md' "$d/SKILL.md" "$d"/references/*.md "$d/SOURCES.md" 2>/dev/null | sort -u); do
    [[ -f "$d/$p" ]] || missing+="$p "; done
  expect "$s: every references/*.md named in the skill exists${missing:+ (missing: $missing)}" '[[ -z "$missing" ]]'
  orphan=""; for r in "$d"/references/*.md; do
    [[ -f "$r" ]] || continue
    grep -qF "references/$(basename "$r")" "$d/SKILL.md" || orphan+="$(basename "$r") "; done
  expect "$s: every reference file is pointed to from SKILL.md${orphan:+ (orphans: $orphan)}" '[[ -z "$orphan" ]]'
  expect "$s: SKILL.md points to SOURCES.md" 'grep -qF "SOURCES.md" "$d/SKILL.md"'
done

echo "4. Shared rules and risk wording"
first=""; same=1; n=0
for s in "${EXPECTED[@]}"; do
  f="$R/skills/$s/SKILL.md"; [[ -f "$f" ]] || continue
  b="$(common_block "$f")"; n=$((n + 1))
  expect "$s: has a non-empty \"## Backend common rules\" block" '[[ $(wc -l <<< "$b") -ge 5 ]]'
  if [[ -z "$first" ]]; then first="$b"; elif [[ "$b" != "$first" ]]; then same=0; fi
  expect "$s: tells Claude to write a Risk: High line before editing when a trigger applies" 'grep -qF "If a trigger above applies, write a \`Risk: High\` line before editing." "$f"'
  expect "$s: defers to engineering-standards for the tiers" 'grep -q "engineering-standards" "$f" && grep -qF "Plan (Medium):" "$f"'
done
expect "the Backend common rules block is byte-identical across $n backend skill(s)" '[[ $same -eq 1 && $n -ge 1 ]]'

echo "5. No payment gateway names"
hits=$(grep -rniE "$GATEWAYS" "$R"/skills/backend-* 2>/dev/null | head -5)
expect "no payment gateway named under skills/backend-*${hits:+ (first hits: $hits)}" '[[ -z "$hits" ]]'

echo "6. Install, check, uninstall"
H="$SANDBOX/home"; CLAUDE_HOME="$H" "$R/scripts/install.sh" >/dev/null 2>&1
CLAUDE_HOME="$H" "$R/scripts/install.sh" --check >/dev/null 2>&1; rc=$?
expect "install.sh --check passes after install" '[[ $rc -eq 0 ]]'
for s in "${EXPECTED[@]}"; do
  expect "$s: deployed with its references and SOURCES.md" 'diff -rq -x .engineering-skill-system "$R/skills/$s" "$H/skills/$s" >/dev/null 2>&1'
done
CLAUDE_HOME="$H" "$R/scripts/install.sh" --uninstall >/dev/null 2>&1
left=""; for s in "${EXPECTED[@]}"; do [[ -e "$H/skills/$s" ]] && left+="$s "; done
expect "uninstall removes the backend skills${left:+ (left: $left)}" '[[ -z "$left" ]]'

echo
echo "Backend skills tests: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
