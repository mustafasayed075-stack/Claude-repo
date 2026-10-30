#!/usr/bin/env bash
# Deploy the Engineering Skill System to the user-level Claude Code config.
#   ./scripts/install.sh            install / update
#   ./scripts/install.sh --check    report what is installed, change nothing
#   ./scripts/install.sh --uninstall
# Target defaults to ~/.claude; override with CLAUDE_HOME.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CLAUDE_HOME="${CLAUDE_HOME:-$HOME/.claude}"
SKILLS_DIR="$CLAUDE_HOME/skills"
GLOBAL_MD="$CLAUDE_HOME/CLAUDE.md"
SKILLS=(engineering-standards skill-router architecture-planning)
BEGIN="<!-- BEGIN engineering-skill-system (managed by Claude-repo/scripts/install.sh) -->"
END="<!-- END engineering-skill-system -->"

strip_block() {
  # Print $1 without the managed block.
  awk -v b="$BEGIN" -v e="$END" '$0==b{skip=1;next} $0==e{skip=0;next} !skip' "$1"
}

check() {
  local ok=0
  for s in "${SKILLS[@]}"; do
    if [[ -f "$SKILLS_DIR/$s/SKILL.md" ]] && diff -q "$REPO_DIR/skills/$s/SKILL.md" "$SKILLS_DIR/$s/SKILL.md" >/dev/null; then
      echo "ok       skill $s"
    else
      echo "MISSING  skill $s (or differs from source)"; ok=1
    fi
  done
  if [[ -f "$GLOBAL_MD" ]] && grep -qF "$BEGIN" "$GLOBAL_MD"; then
    echo "ok       $GLOBAL_MD contains managed block"
  else
    echo "MISSING  managed block in $GLOBAL_MD"; ok=1
  fi
  return $ok
}

uninstall() {
  for s in "${SKILLS[@]}"; do rm -rf "${SKILLS_DIR:?}/$s"; done
  if [[ -f "$GLOBAL_MD" ]]; then
    strip_block "$GLOBAL_MD" > "$GLOBAL_MD.tmp" && mv "$GLOBAL_MD.tmp" "$GLOBAL_MD"
    [[ -s "$GLOBAL_MD" ]] || rm -f "$GLOBAL_MD"
  fi
  echo "Uninstalled."
}

install() {
  mkdir -p "$SKILLS_DIR"
  for s in "${SKILLS[@]}"; do
    # Refuse to clobber a same-named skill this script did not create.
    if [[ -d "$SKILLS_DIR/$s" && ! -f "$SKILLS_DIR/$s/.engineering-skill-system" ]]; then
      echo "ERROR: $SKILLS_DIR/$s exists and is not managed by this system; aborting." >&2
      exit 1
    fi
    rm -rf "${SKILLS_DIR:?}/$s"
    cp -R "$REPO_DIR/skills/$s" "$SKILLS_DIR/$s"
    touch "$SKILLS_DIR/$s/.engineering-skill-system"
  done
  # Replace (or append) the managed block, keeping any other user content.
  local rest=""
  [[ -f "$GLOBAL_MD" ]] && rest="$(strip_block "$GLOBAL_MD")"
  {
    [[ -n "$rest" ]] && printf '%s\n\n' "$rest"
    echo "$BEGIN"
    cat "$REPO_DIR/global/CLAUDE.md"
    echo "$END"
  } > "$GLOBAL_MD.tmp"
  mv "$GLOBAL_MD.tmp" "$GLOBAL_MD"
  echo "Installed to $CLAUDE_HOME. Start a new Claude Code session to load it."
  check
}

case "${1:-}" in
  --check) check ;;
  --uninstall) uninstall ;;
  "") install ;;
  *) echo "usage: $0 [--check|--uninstall]" >&2; exit 2 ;;
esac
