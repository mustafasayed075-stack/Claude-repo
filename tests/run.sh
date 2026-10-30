#!/usr/bin/env bash
# Phase-1 test harness for the Engineering Skill System.
#   tests/run.sh [REPEATS] [OUT_DIR]
# Requires the skills to be installed (scripts/install.sh) and a working
# `claude` CLI. Runs every session headless against throwaway fixture repos,
# then prints a scored summary (tests/score.py). Fixture repos are never
# pushed anywhere; delete OUT_DIR when done.
set -uo pipefail

REPEATS="${1:-3}"
OUT="${2:-$(mktemp -d)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PARALLEL="${PARALLEL:-8}"
mkdir -p "$OUT/work"

"$HERE/../scripts/install.sh" --check >/dev/null || { echo "Skills not installed; run scripts/install.sh" >&2; exit 1; }

commit() { git -C "$1" add -A && git -C "$1" -c user.email=t@t -c user.name=t commit -qm "$2" >/dev/null || true; }

make_fixture() { # $1 kind, $2 dir
  local d="$2"; mkdir -p "$d"; git -C "$d" init -q
  case "$1" in
  web)
    mkdir -p "$d/src"
    echo '{"name":"web","private":true,"scripts":{"dev":"vite","build":"vite build","test":"vitest"},"dependencies":{"react":"^18.3.1","react-dom":"^18.3.1"},"devDependencies":{"vite":"^5.4.0","vitest":"^2.0.0"}}' > "$d/package.json"
    echo '<div id="root"></div><script type="module" src="/src/main.jsx"></script>' > "$d/index.html"
    printf "import Header from './Header.jsx';\nexport default function App() { return (<><Header /><main>Hello</main></>); }\n" > "$d/src/App.jsx"
    echo 'export default function Header() { return <header><h1>My App</h1></header>; }' > "$d/src/Header.jsx" ;;
  api)
    mkdir -p "$d/app/routes" "$d/tests"
    printf '[project]\nname = "api"\ndependencies = ["fastapi", "sqlalchemy", "uvicorn"]\n[project.optional-dependencies]\ntest = ["pytest", "httpx"]\n' > "$d/pyproject.toml"
    printf 'from fastapi import FastAPI\nfrom app.routes import users\napp = FastAPI()\napp.include_router(users.router)\n' > "$d/app/main.py"
    printf 'from fastapi import APIRouter\nfrom app.db import get_user\nrouter = APIRouter()\n\n@router.get("/users/{user_id}")\ndef read_user(user_id: int):\n    user = get_user(user_id)\n    return {"id": user.id, "name": user.name}\n' > "$d/app/routes/users.py"
    printf 'USERS = {1: type("U", (), {"id": 1, "name": "Ada"})()}\ndef get_user(user_id):\n    return USERS.get(user_id)\n' > "$d/app/db.py"
    printf 'from fastapi.testclient import TestClient\nfrom app.main import app\ndef test_read_user():\n    assert TestClient(app).get("/users/1").json()["name"] == "Ada"\n' > "$d/tests/test_users.py" ;;
  game)
    mkdir -p "$d/scripts" "$d/scenes"
    printf 'config_version=5\n[application]\nconfig/name="Dungeon"\nrun/main_scene="res://scenes/main.tscn"\n' > "$d/project.godot"
    cat > "$d/scripts/player.gd" <<'E'
extends CharacterBody2D
var speed = 200
var health = 100
var inventory = []
func _physics_process(delta):
    var dir = Input.get_vector("left","right","up","down")
    velocity = dir * speed
    move_and_slide()
func take_damage(n):
    health -= n
    if health <= 0: queue_free()
func add_item(item):
    inventory.append(item)
func use_item(i):
    var item = inventory.pop_at(i)
    if item == "potion": health = min(100, health + 25)
E
    ;;
  mobile)
    mkdir -p "$d/lib/screens" "$d/lib/services" "$d/android" "$d/ios"
    printf 'name: notes\ndependencies:\n  flutter:\n    sdk: flutter\n  http: ^1.2.0\n' > "$d/pubspec.yaml"
    echo "import 'package:flutter/material.dart'; void main() => runApp(const MaterialApp(home: Text('Notes')));" > "$d/lib/main.dart"
    echo "import 'package:http/http.dart' as http; class NotesApi { Future<List> fetch() async => []; Future save(Map n) async {} }" > "$d/lib/services/notes_api.dart"
    echo "// lists notes from NotesApi" > "$d/lib/screens/notes_screen.dart"
    touch "$d/android/.keep" "$d/ios/.keep" ;;
  ai)
    mkdir -p "$d/src"
    echo '{"name":"support-agent","version":"1.1.4","main":"dist/index.js","scripts":{"build":"tsc","test":"vitest"},"dependencies":{"@anthropic-ai/sdk":"^0.40.0"},"devDependencies":{"typescript":"^5.5.0","vitest":"^2.0.0"}}' > "$d/package.json"
    printf 'import Anthropic from "@anthropic-ai/sdk";\nconst client = new Anthropic();\nexport async function answer(q: string) {\n  const r = await client.messages.create({ model: "claude-sonnet-4-5", max_tokens: 1024, messages: [{ role: "user", content: q }] });\n  return r.content;\n}\n' > "$d/src/agent.ts"
    printf '# Changelog\n\n## 1.1.4\n- Fix retry on 529 errors\n' > "$d/CHANGELOG.md"
    commit "$d" "release 1.1.4"
    printf 'export function redact(s: string) { return s.replace(/\\b\\d{16}\\b/g, "[card]"); }\n' > "$d/src/redact.ts"
    commit "$d" "feat: redact card numbers from transcripts" ;;
  pylib)
    printf 'def apply_discount(price, percent):\n    if not 0 <= percent <= 100:\n        raise ValueError("percent must be 0-100")\n    return round(price * (1 - percent / 100), 2)\n\ndef total(items):\n    return round(sum(p * q for p, q in items), 2)\n' > "$d/pricing.py"
    printf '[project]\nname = "pricing"\nversion = "0.1.0"\n[project.optional-dependencies]\ntest = ["pytest"]\n' > "$d/pyproject.toml" ;;
  empty)
    echo "# shortener" > "$d/README.md" ;;
  esac
  commit "$d" init
  git -C "$d" rev-parse HEAD > "$d.base"
}

# name | fixture | prompt
SCENARIOS=(
  "web|web|Add a dark mode toggle to the header that persists across page reloads."
  "api|api|GET /users/{id} returns a 500 when the user doesn't exist. It should return 404. Please fix it."
  "game|game|player.gd mixes movement, health and inventory in one script. Refactor it so each concern is separate."
  "mobile|mobile|Add offline support: notes should be cached locally and synced to the backend when the device comes back online."
  "ai|ai|Prepare release 1.2.0 of this package."
  "testing|pylib|Add unit tests for pricing.py."
  "empty|empty|Build a URL shortener service in this repo."
  "control|api|What does app/db.py do? Just explain briefly."
)

ALLOW='Read,Glob,Grep,Skill,Bash(ls:*),Bash(cat:*),Bash(find:*),Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(python3 -m pytest:*),Bash(python -m pytest:*),Bash(pytest:*),Bash(python3 -m unittest:*),Bash(python -m unittest:*)'

run_one() { # $1 name, $2 fixture, $3 prompt, $4 rep
  local id="$1-$4" dir="$OUT/work/$1-$4"
  rm -rf "$dir"; make_fixture "$2" "$dir"
  ( cd "$dir" && timeout 600 claude -p "$3" --output-format stream-json --verbose \
      --max-turns 30 --permission-mode acceptEdits --allowedTools "$ALLOW" \
      </dev/null > "$OUT/$id.jsonl" 2>"$OUT/$id.err" )
  echo "done $id"
}

# 1. Discovery: does a fresh session know the skills and the routing rule?
for r in $(seq 1 "$REPEATS"); do
  ( cd "$OUT" && timeout 300 claude -p "Without using any tools, answer in exactly two lines. Line 1: the comma-separated names of available skills containing 'engineering', 'router' or 'architecture'. Line 2: YES if you have an instruction telling you to use skill-router before editing code, else NO." \
    </dev/null > "$OUT/discovery-$r.txt" 2>&1 ) &
done
wait

# 2. Routing + execution
jobs_running=0
for r in $(seq 1 "$REPEATS"); do
  for s in "${SCENARIOS[@]}"; do
    IFS='|' read -r name fx prompt <<<"$s"
    run_one "$name" "$fx" "$prompt" "$r" &
    jobs_running=$((jobs_running + 1))
    if (( jobs_running >= PARALLEL )); then wait -n; jobs_running=$((jobs_running - 1)); fi
  done
done
wait

python3 "$HERE/score.py" "$OUT"
echo "Raw output: $OUT"
