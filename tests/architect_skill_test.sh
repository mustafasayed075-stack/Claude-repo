#!/usr/bin/env bash
# Deterministic checks for the architect skill (skills/architect). No claude
# calls; installs only into mktemp CLAUDE_HOME directories.
#   tests/architect_skill_test.sh [REPO_DIR]
set -uo pipefail
R="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
D="$R/skills/architect"
PASS=0 FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
SANDBOX="$(mktemp -d)" || { echo "mktemp failed" >&2; exit 1; }
trap 'rm -rf "$SANDBOX"' EXIT
# Bracket patterns, so this file does not itself contain a gateway name.
GATEWAYS='s[t]ripe|f[a]wry|p[a]ymob|x[p]ay|p[a]ytabs|p[a]ypal|a[d]yen|b[r]aintree|k[a]shier|g[e]idea|r[a]zorpay|c[h]eckout\.com'
fm() { [[ -f "$1" ]] || return 0; python3 -B -c 'import re,sys; t=open(sys.argv[1]).read(); p=t.split("---"); m=re.search(r"^"+sys.argv[2]+r": (.+)$", p[1], re.M) if t.startswith("---") and len(p)>2 else None; print(m.group(1) if m else "")' "$1" "$2"; }
ALL=$(find "$D" -type f -name '*.md' 2>/dev/null | sort)

echo "1. Frontmatter and layout"
expect "SKILL.md, SOURCES.md, references/ and templates/ exist" '[[ -f "$D/SKILL.md" && -f "$D/SOURCES.md" && -d "$D/references" && -d "$D/templates" ]]'
expect "frontmatter name is architect" '[[ "$(fm "$D/SKILL.md" name)" == "architect" ]]'
desc="$(fm "$D/SKILL.md" description)"
expect "description starts with \"When\"" '[[ "$desc" == When* ]]'
expect "description is under 1024 characters (${#desc})" '[[ ${#desc} -gt 0 && ${#desc} -lt 1024 ]]'
expect "description has no angle brackets" '[[ "$desc" != *"<"* && "$desc" != *">"* ]]'
for f in style-and-boundaries security-by-design scalability-and-cost governance-adr-c4-fitness decision-method; do
  expect "reference $f.md exists" '[[ -f "$D/references/$f.md" ]]'; done
for f in ARCHITECTURE_BRIEF ADR c4 fitness-functions; do
  expect "template $f.md exists" '[[ -f "$D/templates/$f.md" ]]'; done

echo "2. Size"
n=$( [[ -f "$D/SKILL.md" ]] && wc -l < "$D/SKILL.md" || echo 999)
expect "SKILL.md has fewer than 500 lines (hard cap; $n)" '[[ $n -lt 500 ]]'
expect "SKILL.md has fewer than 300 lines (target; $n)" '[[ $n -lt 300 ]]'

echo "3. Pointers"
missing=""; for p in $(grep -ohE '(backend-[a-z-]+/)?(references|templates)/[A-Za-z0-9_-]+\.md' $ALL 2>/dev/null | sort -u); do
  if [[ "$p" == backend-* ]]; then [[ -f "$R/skills/$p" ]] || missing+="$p "; else [[ -f "$D/$p" ]] || missing+="$p "; fi; done
expect "every own references/templates pointer and every backend-x/references/y.md pointer resolves${missing:+ (missing: $missing)}" '[[ -z "$missing" ]]'
unq=$(grep -ohE '(^|[^/a-z-])(backend-[a-z-]+)[^/ ]{0,3}`?references/' $ALL 2>/dev/null | head -3)
expect "backend pointers are written qualified, as backend-x/references/y.md${unq:+ (found: $unq)}" '[[ -z "$unq" ]]'
orphan=""; for f in "$D"/references/*.md "$D"/templates/*.md; do
  [[ -f "$f" ]] || continue; rel="${f#$D/}"; grep -qF "$rel" "$D/SKILL.md" || orphan+="$rel "; done
expect "every reference and template is pointed to from SKILL.md${orphan:+ (orphans: $orphan)}" '[[ -z "$orphan" ]]'
expect "SKILL.md points to SOURCES.md" 'grep -qF "SOURCES.md" "$D/SKILL.md"'

echo "4. Rule IDs"
ids=$(grep -oE '^\| ARCH-[0-9]+ \|' "$D/SKILL.md" 2>/dev/null | grep -oE '[0-9]+')
expect "ARCH rule IDs in SKILL.md are unique and contiguous 1..49" '[[ "$(printf "%s\n" $ids | sort -n | tr "\n" " ")" == "$(seq 1 49 | tr "\n" " ")" ]]'
badtag=$(grep -E '^\| ARCH-[0-9]+ \|' "$D/SKILL.md" 2>/dev/null | awk -F'|' '{t=$5; gsub(/ /,"",t); if (t !~ /^[VPT](\/[VPT])?$/) print $2}' | tr -d ' ' | tr '\n' ' ')
expect "every ARCH rule carries a V, P or T tag${badtag:+ (bad: $badtag)}" '[[ -z "$badtag" && -n "$ids" ]]'
foreign=$(grep -ohE '\b(API|SEC|REL|TST)-[0-9]+\b' $ALL 2>/dev/null | sort -u | tr '\n' ' ')
expect "no rule IDs of other skills (file pointers only)${foreign:+ (found: $foreign)}" '[[ -z "$foreign" ]]'

echo "5. Content rules"
hits=$(grep -rniE "$GATEWAYS" "$D" 2>/dev/null | head -3)
expect "no payment gateway named under skills/architect${hits:+ (first hits: $hits)}" '[[ -z "$hits" ]]'
nolegal=""; for f in $ALL; do
  grep -q "151/2020" "$f" || continue
  grep -qi "not legal advice" "$f" && grep -qi "Arabic text governs" "$f" || nolegal+="${f#$D/} "; done
expect "every file that mentions Law 151/2020 says \"not legal advice\" and \"Arabic text governs\"${nolegal:+ (missing in: $nolegal)}" '[[ -z "$nolegal" ]]'

echo "6. Routing and the retired architecture-brief skill"
RT="$R/skills/skill-router/SKILL.md"; GL="$R/global/CLAUDE.md"
for f in "$RT" "$GL"; do
  n=$(basename "$(dirname "$f")")/$(basename "$f")
  flat="$(tr '\n' ' ' < "$f" | tr -s ' ')"   # the line may wrap
  expect "$n has the routing line (architect = new system / idea / whole-system review; architecture-planning = change inside existing code)" 'grep -qiF "\`architect\` = a new system, a new idea, or a whole-system review" <<< "$flat" && grep -qiF "\`architecture-planning\` = a planned change inside existing code" <<< "$flat"'
  expect "$n does not mention architecture-brief" '! grep -q "architecture-brief" "$f"'
done
expect "the installer lists architect" 'grep -qE "^SKILLS=\(.*\barchitect\b" "$R/scripts/install.sh"'
H="$SANDBOX/h1"; CLAUDE_HOME="$H" "$R/scripts/install.sh" >/dev/null 2>&1
CLAUDE_HOME="$H" "$R/scripts/install.sh" --check >/dev/null 2>&1; rc=$?
expect "install.sh --check passes after install" '[[ $rc -eq 0 ]]'
expect "architect is deployed with its references, templates and SOURCES.md" 'diff -rq -x .engineering-skill-system "$D" "$H/skills/architect" >/dev/null 2>&1'
mkdir -p "$H/skills/architecture-brief"; echo "managed-by: engineering-skill-system" > "$H/skills/architecture-brief/.engineering-skill-system"; echo x > "$H/skills/architecture-brief/SKILL.md"
CLAUDE_HOME="$H" "$R/scripts/install.sh" --check >/dev/null 2>&1; rc=$?
expect "--check fails while an architecture-brief folder exists" '[[ $rc -ne 0 ]]'
CLAUDE_HOME="$H" "$R/scripts/install.sh" >/dev/null 2>&1; CLAUDE_HOME="$H" "$R/scripts/install.sh" --check >/dev/null 2>&1; rc=$?
expect "install removes a managed architecture-brief, then --check passes" '[[ ! -e "$H/skills/architecture-brief" && $rc -eq 0 ]]'
H2="$SANDBOX/h2"; CLAUDE_HOME="$H2" "$R/scripts/install.sh" >/dev/null 2>&1; mkdir -p "$H2/skills/architecture-brief"; echo mine > "$H2/skills/architecture-brief/SKILL.md"
CLAUDE_HOME="$H2" "$R/scripts/install.sh" >/dev/null 2>&1; CLAUDE_HOME="$H2" "$R/scripts/install.sh" --check >/dev/null 2>&1; rc=$?
expect "an architecture-brief the installer does not own is kept, and --check fails" '[[ -f "$H2/skills/architecture-brief/SKILL.md" && $rc -ne 0 ]]'
CLAUDE_HOME="$H" "$R/scripts/install.sh" --uninstall >/dev/null 2>&1
expect "uninstall removes architect" '[[ ! -e "$H/skills/architect" ]]'

echo
echo "Architect skill tests: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
