#!/usr/bin/env bash
# Deterministic tests for scripts/install.sh, the SessionStart hook and the
# run.sh temp-dir guard. Everything runs inside a fresh mktemp sandbox with
# its own CLAUDE_HOME / HOME; the real ~/.claude is never touched.
#   tests/installer_test.sh
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL="$REPO/scripts/install.sh"
HOOK="$REPO/.claude/hooks/session-start.sh"
BEGIN="<!-- BEGIN engineering-skill-system (managed by Claude-repo/scripts/install.sh) -->"
END="<!-- END engineering-skill-system -->"
SANDBOX="$(mktemp -d)" || { echo "mktemp failed" >&2; exit 1; }
trap 'rm -rf "$SANDBOX"' EXIT
PASS=0 FAIL=0

ok()   { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
home() { local h="$SANDBOX/$1"; mkdir -p "$h"; echo "$h"; }
inst() { CLAUDE_HOME="$1" "$INSTALL" "${@:2}" >/dev/null 2>&1; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
tree_sha() { (cd "$1" && find . -type f -exec sha256sum {} + 2>/dev/null | sort | sha256sum | cut -d' ' -f1); }
mode() { stat -c %a "$1"; }

echo "A. Ownership marker"
H=$(home a1); mkdir -p "$H/skills/skill-router"; echo "user skill" > "$H/skills/skill-router/SKILL.md"
inst "$H" --uninstall; rc=$?
expect "uninstall keeps a same-named user skill" '[[ "$(cat "$H/skills/skill-router/SKILL.md")" == "user skill" ]]'
expect "uninstall reports the skip (exit 1)" '[[ $rc -eq 1 ]]'
H=$(home a2); inst "$H"; inst "$H" --uninstall
expect "uninstall removes its own skills" '[[ ! -e "$H/skills/skill-router" && ! -e "$H/skills/engineering-standards" ]]'
H=$(home a3); inst "$H"; : > "$H/skills/skill-router/.engineering-skill-system"; inst "$H" --uninstall
expect "legacy empty marker (phase 1) is recognised" '[[ ! -e "$H/skills/skill-router" ]]'
H=$(home a4); mkdir -p "$H/skills/skill-router"; echo "someone else" > "$H/skills/skill-router/.engineering-skill-system"
inst "$H" --uninstall
expect "marker with foreign content is not trusted" '[[ -d "$H/skills/skill-router" ]]'
H=$(home a5); mkdir -p "$H/skills" "$SANDBOX/mine"; echo x > "$SANDBOX/mine/SKILL.md"; touch "$SANDBOX/mine/.engineering-skill-system"
ln -s "$SANDBOX/mine" "$H/skills/skill-router"; inst "$H"; rc=$?; inst "$H" --uninstall
expect "symlinked skill dir is foreign: install refuses" '[[ $rc -ne 0 ]]'
expect "symlinked skill dir and target survive uninstall" '[[ -L "$H/skills/skill-router" && -f "$SANDBOX/mine/SKILL.md" ]]'

echo "B. Damaged CLAUDE.md markers abort without changes"
n=0
for content in \
  "top\n$BEGIN\nold\nUSER AFTER\n" \
  "top\n$END\nUSER AFTER\n" \
  "$BEGIN\nx\n$END\n$BEGIN\ny\n$END\n" \
  "$END\nx\n$BEGIN\n" \
  "$BEGIN\r\nx\n$END\n" \
  "<!-- BEGIN engineering-skill-system edited by hand -->\nx\n$END\n"; do
  n=$((n + 1)); H=$(home b$n); printf "$content" > "$H/CLAUDE.md"; before=$(sha "$H/CLAUDE.md")
  inst "$H"; rc=$?
  expect "case $n: install fails" '[[ $rc -ne 0 ]]'
  expect "case $n: CLAUDE.md byte-identical, no skills written" '[[ "$(sha "$H/CLAUDE.md")" == "$before" && ! -e "$H/skills" ]]'
done
H=$(home b9); inst "$H"; printf 'mine\n%s\n' "$BEGIN" > "$H/CLAUDE.md"; before=$(sha "$H/CLAUDE.md")
inst "$H" --uninstall; rc=$?
expect "uninstall aborts on damaged markers, file and skills untouched" '[[ $rc -ne 0 && "$(sha "$H/CLAUDE.md")" == "$before" && -d "$H/skills/skill-router" ]]'
CLAUDE_HOME="$H" "$INSTALL" --check >/dev/null 2>&1
expect "--check reports damaged markers (exit 1)" '[[ $? -eq 1 ]]'

echo "C. Symlinked CLAUDE.md"
H=$(home c1); mkdir -p "$SANDBOX/dotfiles"; printf '# my rules\nkeep me\n' > "$SANDBOX/dotfiles/CLAUDE.md"
ln -s "$SANDBOX/dotfiles/CLAUDE.md" "$H/CLAUDE.md"; inst "$H"
expect "link kept after install" '[[ -L "$H/CLAUDE.md" ]]'
expect "target got the block and kept user text" 'grep -qF "$BEGIN" "$SANDBOX/dotfiles/CLAUDE.md" && grep -q "keep me" "$SANDBOX/dotfiles/CLAUDE.md"'
inst "$H" --uninstall
expect "link kept after uninstall, block removed, user text kept" '[[ -L "$H/CLAUDE.md" ]] && ! grep -qF "$BEGIN" "$SANDBOX/dotfiles/CLAUDE.md" && grep -q "keep me" "$SANDBOX/dotfiles/CLAUDE.md"'
H=$(home c2); ln -s "$SANDBOX/nowhere/CLAUDE.md" "$H/CLAUDE.md"; inst "$H"; rc=$?
expect "broken symlink aborts before any write" '[[ $rc -ne 0 && -L "$H/CLAUDE.md" && ! -e "$H/skills" ]]'

echo "D. File modes"
H=$(home d1); echo "# mine" > "$H/CLAUDE.md"; chmod 600 "$H/CLAUDE.md"; inst "$H"
expect "600 kept on install" '[[ "$(mode "$H/CLAUDE.md")" == 600 ]]'
sed -i 's/Engineering Skill System/OLD/' "$H/CLAUDE.md"; chmod 640 "$H/CLAUDE.md"; inst "$H"
expect "640 kept on update" '[[ "$(mode "$H/CLAUDE.md")" == 640 ]]'
inst "$H" --uninstall
expect "640 kept on uninstall" '[[ "$(mode "$H/CLAUDE.md")" == 640 ]]'
H=$(home d2); (umask 027; inst "$H")
expect "new CLAUDE.md follows umask 027 (640)" '[[ "$(mode "$H/CLAUDE.md")" == 640 ]]'

echo "E. --check detects stale content"
H=$(home e1); inst "$H"; sed -i 's/Engineering Skill System/OLD VERSION/' "$H/CLAUDE.md"
CLAUDE_HOME="$H" "$INSTALL" --check >/dev/null; rc=$?
expect "stale CLAUDE.md block fails --check" '[[ $rc -eq 1 ]]'
inst "$H"; CLAUDE_HOME="$H" "$INSTALL" --check >/dev/null
expect "install refreshes it" '[[ $? -eq 0 ]]'
echo edited >> "$H/skills/skill-router/SKILL.md"; CLAUDE_HOME="$H" "$INSTALL" --check >/dev/null
expect "edited SKILL.md fails --check" '[[ $? -eq 1 ]]'
inst "$H"; touch "$H/skills/skill-router/extra.md"; CLAUDE_HOME="$H" "$INSTALL" --check >/dev/null
expect "extra file in skill dir fails --check" '[[ $? -eq 1 ]]'

echo "F. No partial install on conflict"
H=$(home f1); mkdir -p "$H/skills/skill-router"; echo x > "$H/skills/skill-router/SKILL.md"; inst "$H"; rc=$?
expect "install fails" '[[ $rc -ne 0 ]]'
expect "no other skill and no CLAUDE.md written" '[[ ! -e "$H/skills/engineering-standards" && ! -e "$H/CLAUDE.md" ]]'

echo "G. User content and idempotency"
H=$(home g1); printf 'line one\n\n  indented\nno newline at end' > "$H/CLAUDE.md"; inst "$H"
expect "user text kept verbatim before the block" '[[ "$(head -4 "$H/CLAUDE.md")" == "$(printf "line one\n\n  indented\nno newline at end")" ]]'
printf 'after block\n' >> "$H/CLAUDE.md"; s1=$(sha "$H/CLAUDE.md"); ino=$(stat -c %i "$H/CLAUDE.md"); inst "$H"
expect "reinstall with no changes does not rewrite the file" '[[ "$(sha "$H/CLAUDE.md")" == "$s1" && "$(stat -c %i "$H/CLAUDE.md")" == "$ino" ]]'
sed -i 's/Engineering Skill System/OLD/' "$H/CLAUDE.md"; inst "$H"
expect "update replaces the block in place, text after it kept" '[[ "$(tail -1 "$H/CLAUDE.md")" == "after block" ]] && grep -q "Engineering Skill System" "$H/CLAUDE.md"'
inst "$H" --uninstall
expect "uninstall leaves only user lines (plus one separator blank line)" '[[ "$(grep -v "^$" "$H/CLAUDE.md")" == "$(printf "line one\n  indented\nno newline at end\nafter block")" ]]'
expect "uninstall never deletes CLAUDE.md" '[[ -f "$H/CLAUDE.md" ]]'
expect "no temp files left anywhere" '[[ -z "$(find "$SANDBOX" -name ".CLAUDE.md.*" -o -name ".*.new.*")" ]]'

echo "H. SessionStart hook"
HH=$(home hookhome)
out1=$(HOME="$HH" CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$REPO" "$HOOK")
out2=$(HOME="$HH" CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$REPO" "$HOOK")
expect "first run installs and prints the routing block" '[[ "$out1" == "$(cat "$REPO/global/CLAUDE.md")" ]] && CLAUDE_HOME="$HH/.claude" "$INSTALL" --check >/dev/null'
expect "second run is silent (no reinstall)" '[[ -z "$out2" ]]'
sed -i 's/Engineering Skill System/OLD/' "$HH/.claude/CLAUDE.md"
out3=$(HOME="$HH" CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$REPO" "$HOOK")
expect "stale block triggers exactly one refresh" '[[ -n "$out3" ]] && [[ -z "$(HOME="$HH" CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$REPO" "$HOOK")" ]]'
HN=$(home hooklocal)
out=$(HOME="$HN" CLAUDE_CODE_REMOTE= CLAUDE_PROJECT_DIR="$REPO" "$HOOK"); rc=$?
expect "not remote: no-op, exit 0" '[[ $rc -eq 0 && -z "$out" && ! -e "$HN/.claude" ]]'
HC=$(home hookconflict); mkdir -p "$HC/.claude/skills/skill-router"
out=$(HOME="$HC" CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$REPO" "$HOOK"); rc=$?
expect "conflict: exit 0 and states the reason" '[[ $rc -eq 0 && "$out" == *"not managed by this system"* ]]'
SP="$SANDBOX/path with spaces/repo"; mkdir -p "$SP"; cp -R "$REPO/.claude" "$REPO/scripts" "$REPO/skills" "$REPO/global" "$SP/"
HS=$(home hookspaces)
cmd=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["hooks"]["SessionStart"][0]["hooks"][0]["command"])' "$REPO/.claude/settings.json")
out=$(HOME="$HS" CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$SP" sh -c "$cmd" </dev/null)
expect "settings.json command works from a path with spaces (run via sh -c)" '[[ -n "$out" ]] && CLAUDE_HOME="$HS/.claude" "$INSTALL" --check >/dev/null'

echo "I. run.sh temp-dir guard"
before=$(ls / | sha256sum)
out=$(cd "$SANDBOX" && TMPDIR="$SANDBOX/does-not-exist" "$REPO/tests/run.sh" 0 2>&1); rc=$?
expect "mktemp failure exits non-zero" '[[ $rc -ne 0 && "$out" == *"mktemp failed"* ]]'
expect "nothing created at filesystem root" '[[ "$(ls / | sha256sum)" == "$before" && ! -e /work ]]'

echo
echo "Installer tests: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
