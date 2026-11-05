#!/usr/bin/env bash
# Deploy the Engineering Skill System to the user-level Claude Code config.
#   ./scripts/install.sh            install / update
#   ./scripts/install.sh --check    report what is installed, change nothing
#   ./scripts/install.sh --uninstall
# Target defaults to ~/.claude; override with CLAUDE_HOME.
#
# Safety rules:
# - A skill directory is replaced or removed only if it holds the ownership
#   marker this script writes. Anything else is left alone.
# - CLAUDE.md is changed only between a well-formed BEGIN/END pair; damaged
#   markers abort before anything is written. Symlinks are followed so the
#   link and its target survive, the file mode is kept, the file is written
#   atomically and only when its content changes, and it is never deleted.
# - All checks run before the first write.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CLAUDE_HOME="${CLAUDE_HOME:-$HOME/.claude}"
SKILLS_DIR="$CLAUDE_HOME/skills"
GLOBAL_MD="$CLAUDE_HOME/CLAUDE.md"
SOURCE_MD="$REPO_DIR/global/CLAUDE.md"
SKILLS=(engineering-standards skill-router architecture-planning)
MARKER=".engineering-skill-system"
MARKER_TEXT="managed-by: engineering-skill-system"
BEGIN="<!-- BEGIN engineering-skill-system (managed by Claude-repo/scripts/install.sh) -->"
END="<!-- END engineering-skill-system -->"

TMP_PATHS=()
cleanup() { for p in "${TMP_PATHS[@]+"${TMP_PATHS[@]}"}"; do rm -rf "$p"; done; }
trap cleanup EXIT

die() { echo "ERROR: $*" >&2; exit 1; }

# ours | absent | foreign. Phase-1 installs wrote an empty marker, so an empty
# marker file also counts as ours; no other tool creates this file name.
skill_state() {
  local d="$SKILLS_DIR/$1"
  if [[ ! -e "$d" && ! -L "$d" ]]; then
    echo absent
  elif [[ -d "$d" && ! -L "$d" && -f "$d/$MARKER" && ! -L "$d/$MARKER" ]] &&
       { [[ ! -s "$d/$MARKER" ]] || [[ "$(cat "$d/$MARKER")" == "$MARKER_TEXT" ]]; }; then
    echo ours
  else
    echo foreign
  fi
}

# The real file behind CLAUDE.md, so a symlinked CLAUDE.md keeps its link.
md_target() {
  if [[ -L "$GLOBAL_MD" ]]; then
    local t
    t="$(readlink -f "$GLOBAL_MD" 2>/dev/null || true)"
    [[ -n "$t" && -f "$t" ]] || die "$GLOBAL_MD is a symlink to a missing file; fix the link first. Nothing was changed."
    echo "$t"
  else
    echo "$GLOBAL_MD"
  fi
}

# none | ok | corrupt. Any line naming a BEGIN/END engineering-skill-system
# marker that is not exactly ours, or a missing/duplicate/out-of-order pair,
# is corrupt.
block_state() {
  [[ -f "$1" ]] || { echo none; return; }
  awk -v b="$BEGIN" -v e="$END" '
    /(BEGIN|END) engineering-skill-system/ {
      if ($0 == b) { nb++; lb = NR } else if ($0 == e) { ne++; le = NR } else bad++
    }
    END {
      if (bad || nb > 1 || ne > 1 || nb != ne || (nb && lb > le)) print "corrupt"
      else if (nb) print "ok"
      else print "none"
    }' "$1"
}

block_body() { awk -v b="$BEGIN" -v e="$END" '$0==e{inb=0} inb{print} $0==b{inb=1}' "$1"; }
without_block() { awk -v b="$BEGIN" -v e="$END" '$0==b{skip=1;next} $0==e{skip=0;next} !skip' "$1"; }

# $1 with the managed block replaced in place, or appended if absent.
render_md() {
  if [[ "$(block_state "$1")" == ok ]]; then
    awk -v b="$BEGIN" -v e="$END" -v src="$SOURCE_MD" '
      $0==b { print; while ((getline l < src) > 0) print l; skip=1; next }
      $0==e { skip=0 }
      !skip' "$1"
  else
    if [[ -s "$1" ]]; then
      cat "$1"
      [[ -z "$(tail -c1 "$1")" ]] || echo
      echo
    fi
    echo "$BEGIN"
    cat "$SOURCE_MD"
    echo "$END"
  fi
}

file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }

# Atomically replace $1 with stdin, keeping its mode; no-op if unchanged.
write_file() {
  local t="$1" tmp
  tmp="$(mktemp "$(dirname "$t")/.CLAUDE.md.XXXXXX")"
  TMP_PATHS+=("$tmp")
  cat > "$tmp"
  if [[ -f "$t" ]]; then
    if cmp -s "$tmp" "$t"; then rm -f "$tmp"; return 0; fi
    chmod "$(file_mode "$t")" "$tmp"
  else
    chmod "$(printf '%o' $(( 8#666 & ~8#$(umask) )))" "$tmp"
  fi
  mv "$tmp" "$t"
}

check() {
  local rc=0 s md
  for s in "${SKILLS[@]}"; do
    case "$(skill_state "$s")" in
      ours)
        if diff -rq -x "$MARKER" "$REPO_DIR/skills/$s" "$SKILLS_DIR/$s" >/dev/null 2>&1; then
          echo "ok       skill $s"
        else
          echo "STALE    skill $s differs from source"; rc=1
        fi ;;
      absent) echo "MISSING  skill $s"; rc=1 ;;
      foreign) echo "FOREIGN  $SKILLS_DIR/$s exists but is not managed by this system"; rc=1 ;;
    esac
  done
  md="$(md_target)"
  case "$(block_state "$md")" in
    ok)
      if diff -q <(block_body "$md") "$SOURCE_MD" >/dev/null; then
        echo "ok       $GLOBAL_MD managed block is current"
      else
        echo "STALE    $GLOBAL_MD managed block differs from source"; rc=1
      fi ;;
    none) echo "MISSING  managed block in $GLOBAL_MD"; rc=1 ;;
    corrupt) echo "DAMAGED  $GLOBAL_MD has incomplete or damaged BEGIN/END markers"; rc=1 ;;
  esac
  return $rc
}

install() {
  local s md new
  for s in "${SKILLS[@]}"; do
    [[ "$(skill_state "$s")" != foreign ]] ||
      die "$SKILLS_DIR/$s exists and is not managed by this system. Nothing was changed."
  done
  md="$(md_target)"
  [[ "$(block_state "$md")" != corrupt ]] ||
    die "$md has incomplete or damaged engineering-skill-system BEGIN/END markers. Fix or remove them by hand. Nothing was changed."

  mkdir -p "$SKILLS_DIR"
  for s in "${SKILLS[@]}"; do
    new="$SKILLS_DIR/.$s.new.$$"
    TMP_PATHS+=("$new")
    cp -R "$REPO_DIR/skills/$s" "$new"
    printf '%s\n' "$MARKER_TEXT" > "$new/$MARKER"
    rm -rf "${SKILLS_DIR:?}/$s"
    mv "$new" "$SKILLS_DIR/$s"
  done
  write_file "$md" < <(render_md "$md")
  echo "Installed to $CLAUDE_HOME. Start a new Claude Code session to load it."
  check
}

uninstall() {
  local rc=0 s md
  md="$(md_target)"
  [[ "$(block_state "$md")" != corrupt ]] ||
    die "$md has incomplete or damaged engineering-skill-system BEGIN/END markers. Fix or remove them by hand. Nothing was changed."
  for s in "${SKILLS[@]}"; do
    case "$(skill_state "$s")" in
      ours) rm -rf "${SKILLS_DIR:?}/$s" ;;
      foreign) echo "SKIPPED  $SKILLS_DIR/$s: not managed by this system" >&2; rc=1 ;;
      absent) ;;
    esac
  done
  if [[ "$(block_state "$md")" == ok ]]; then
    write_file "$md" < <(without_block "$md")
  fi
  echo "Uninstalled."
  return $rc
}

case "${1:-}" in
  --check) check ;;
  --uninstall) uninstall ;;
  "") install ;;
  *) echo "usage: $0 [--check|--uninstall]" >&2; exit 2 ;;
esac
