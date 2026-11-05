#!/usr/bin/env bash
# EXPERIMENT: live Claude Code sessions with the router gate, each in a
# disposable HOME + project. Never touches the real ~/.claude.
#   live_test.sh [REPEATS]
# Arm A: unchanged prompt-based skill-router + gate (decision echo unknown
#        to the router, so the gate must block and the model must recover).
# Arm B: sandbox-only router variant that records the decision with the
#        echo + gate (expect no blocks).
# Both arms start from a settings.json with unrelated user hooks that must
# keep working.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
REPEATS="${1:-3}"
SB="$(mktemp -d)" || exit 1
echo "sandbox: $SB"

mkhome() { # $1 dir, $2 arm
  local h="$1"; mkdir -p "$h"
  HOME="$h" "$REPO/scripts/install.sh" >/dev/null
  if [[ "$2" == B ]]; then
    python3 - "$h/.claude/skills/skill-router/SKILL.md" <<'P'
import sys
p = sys.argv[1]; s = open(p).read()
old = s[s.index("3. **Write one line of text**"):s.index("Then load the chosen skills")]
new = """3. **Record the decision** with exactly one Bash call, as your next tool
   call (single quotes, nothing else on the line):

   `echo 'Router: <project type> | <task type> | skills: <list or none> - <reason, max ~12 words>'`

"""
open(p, "w").write(s.replace(old, new))
P
  fi
  cat > "$h/user-audit.sh" <<E
#!/bin/bash
cat >/dev/null; echo audit >> "$h/user-audit.log"
E
  cat > "$h/user-stop.sh" <<E
#!/bin/bash
cat >/dev/null; echo stop >> "$h/user-stop.log"
E
  cat > "$h/probe.sh" <<'E'
#!/bin/bash
# Passive probe for the report: does the transcript already hold Router text?
p=$(cat); t=$(printf '%s' "$p" | jq -r .transcript_path); tool=$(printf '%s' "$p" | jq -r '.tool_name + ":" + (.tool_input.skill // "")')
n=$(grep -c 'Router:' "$t" 2>/dev/null); echo "$tool router_text_in_transcript=${n:-0}" >> "$(dirname "$0")/probe.log"
E
  chmod +x "$h"/*.sh
  cat > "$h/.claude/settings.json" <<E
{
  "theme": "dark",
  "hooks": {
    "PreToolUse": [
      {"matcher": "Bash", "hooks": [{"type": "command", "command": "$h/user-audit.sh"}]},
      {"matcher": "", "hooks": [{"type": "command", "command": "$h/probe.sh"}]}
    ],
    "Stop": [{"hooks": [{"type": "command", "command": "$h/user-stop.sh"}]}]
  }
}
E
  cp "$h/.claude/settings.json" "$h/settings.before.json"
  python3 "$HERE/settings_merge.py" install "$h/.claude/settings.json" "$HERE/esk-router-gate.py"
}

mkproj() { # $1 dir
  mkdir -p "$1/app/services" "$1/migrations/versions"; git -C "$1" init -q
  printf '[project]\nname = "accounts"\ndependencies = ["sqlalchemy>=2", "alembic"]\n' > "$1/pyproject.toml"
  printf 'from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column\nclass Base(DeclarativeBase): pass\nclass User(Base):\n    __tablename__ = "users"\n    id: Mapped[int] = mapped_column(primary_key=True)\n    name: Mapped[str]\n' > "$1/app/models.py"
  printf 'revision = "0001"\ndown_revision = None\ndef upgrade(): pass\n' > "$1/migrations/versions/0001_init.py"
  printf 'def display_name(u):\n    return u.name\n' > "$1/app/services/users.py"
  git -C "$1" add -A && git -C "$1" -c user.email=t@t -c user.name=t commit -qm init
}

run() { # $1 id, $2 home, $3 prompt
  local p="$SB/proj-$1"; mkproj "$p"
  (cd "$p" && HOME="$2" timeout 600 claude -p "$3" --output-format stream-json --verbose --max-turns 20 \
     --permission-mode acceptEdits --allowedTools 'Read,Glob,Grep,Skill,Bash(ls:*),Bash(cat:*),Bash(git status:*),Bash(git log:*)' \
     </dev/null > "$SB/$1.jsonl" 2>"$SB/$1.err")
  echo "done $1"
}

mkhome "$SB/homeA" A; mkhome "$SB/homeB" B
HIGH="Split the users.name column into first_name and last_name, with an Alembic migration for existing rows."
for r in $(seq 1 "$REPEATS"); do
  run "A-high-$r" "$SB/homeA" "$HIGH" &
  run "B-high-$r" "$SB/homeB" "$HIGH" &
done
run "B-question" "$SB/homeB" "What does app/services/users.py do? Answer briefly." &
run "B-low" "$SB/homeB" "Add a short docstring to display_name in app/services/users.py." &
wait

python3 - "$SB" <<'P'
import json, re, sys, glob, os
sb = sys.argv[1]
REASON = "skill-router ran but its decision is not recorded"
DEC = re.compile(r"^echo '(Router: [^'|\n]+\|[^'|\n]+\| ?skills: [^'\n]+)'$")
PROT = {"Skill", "Edit", "Write", "NotebookEdit", "MultiEdit"}
print("\n== Live runs")
for f in sorted(glob.glob(f"{sb}/*.jsonl")):
    rid = os.path.basename(f)[:-6]
    uses, res, final, sub, err = [], {}, "", None, None
    for l in open(f):
        try: e = json.loads(l)
        except ValueError: continue
        if e.get("type") == "assistant":
            for c in e["message"]["content"]:
                if c["type"] == "tool_use": uses.append(c)
                if c["type"] == "text" and re.search(r"Router:\s*[^|\n]+\|", c["text"]): uses.append({"text_router": True})
        elif e.get("type") == "user":
            for c in e["message"]["content"] if isinstance(e["message"]["content"], list) else []:
                if isinstance(c, dict) and c.get("type") == "tool_result":
                    t = c.get("content"); t = t if isinstance(t, str) else json.dumps(t)
                    res[c["tool_use_id"]] = (bool(c.get("is_error")), t)
        elif e.get("type") == "result":
            final, sub = e.get("result") or "", e.get("subtype")
            if e.get("is_error") and sub == "success": err = final[:60]
    if err:
        print(f"  {rid:12} INVALID ({err})"); continue
    state, denies, violations, decided, router, text_line = None, 0, [], None, False, False
    for u in uses:
        if u.get("text_router"): text_line = True; continue
        name, ti = u["name"], u.get("input", {})
        is_err, out = res.get(u["id"], (True, ""))
        skill = str(ti.get("skill", "")).split(":")[-1]
        if name == "Skill" and skill == "skill-router" and not is_err:
            router, state = True, "PENDING"; continue
        if name == "Bash" and DEC.match(str(ti.get("command", "")).strip()) and not is_err:
            state, decided = "DECIDED", ti["command"][6:80]; continue
        if REASON in out:
            denies += 1; continue
        if state == "PENDING" and name in PROT and not is_err:
            violations.append(f"{name}:{skill}")
    seq = ">".join(("Skill:" + str(u["input"].get("skill"))) if u.get("name") == "Skill" else u.get("name", "TEXT") for u in uses if u.get("name") in ("Skill", "Edit", "Write") or u.get("text_router"))
    print(f"  {rid:12} router={router} denies={denies} protected_ran_while_pending={violations or 'none'} decision={'yes' if decided else 'no'} text_line={text_line}")
    print(f"  {'':12} seq: {seq[:150]}")
    if decided: print(f"  {'':12} recorded: {decided}")
    print(f"  {'':12} final: {final.strip()[:140]!r}")
for arm in "AB":
    h = f"{sb}/home{arm}"
    s_before = json.load(open(f"{h}/settings.before.json")); s_now = json.load(open(f"{h}/.claude/settings.json"))
    pre = s_now["hooks"]["PreToolUse"]
    print(f"\n== Arm {arm} settings: user hooks first and unchanged = {pre[:2] == s_before['hooks']['PreToolUse'] and s_now['hooks']['Stop'] == s_before['hooks']['Stop'] and s_now['theme'] == 'dark'}")
    for log in ("user-audit.log", "user-stop.log"):
        n = sum(1 for _ in open(f"{h}/{log}")) if os.path.exists(f"{h}/{log}") else 0
        print(f"   {log}: {n} entries")
    if os.path.exists(f"{h}/probe.log"):
        rows = [l.split() for l in open(f"{h}/probe.log")]
        down = [r for r in rows if r[0].startswith("Skill:") and "skill-router" not in r[0] and r[0] != "Skill:"]
        print(f"   probe: downstream Skill pre-events={len(down)}, transcript already had 'Router:' text in {sum(1 for r in down if not r[1].endswith('=0'))}")
P
echo "sandbox kept for inspection: $SB"
