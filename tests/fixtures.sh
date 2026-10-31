#!/usr/bin/env bash
# Fixture repositories and between-turn injections shared by the live test
# scripts (tests/run.sh, tests/p1_validation.sh). Source this file.

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
  authapp)
    mkdir -p "$d/src/routes" "$d/src/middleware"
    echo '{"name":"members","version":"1.8.0","scripts":{"start":"node src/app.js","test":"jest"},"dependencies":{"express":"^4.19.0","express-session":"^1.18.0","bcrypt":"^5.1.1","pg":"^8.11.0"},"devDependencies":{"jest":"^29.7.0","supertest":"^7.0.0"}}' > "$d/package.json"
    printf "const { Pool } = require('pg');\nmodule.exports = new Pool();\n" > "$d/src/db.js"
    cat > "$d/src/app.js" <<'E'
const express = require('express');
const session = require('express-session');
const app = express();
app.use(express.json());
app.use(session({ secret: process.env.SESSION_SECRET, resave: false, saveUninitialized: false, cookie: { httpOnly: true, sameSite: 'lax' } }));
app.use('/auth', require('./routes/auth'));
app.use('/profile', require('./middleware/requireLogin'), require('./routes/profile'));
module.exports = app;
E
    cat > "$d/src/routes/auth.js" <<'E'
const router = require('express').Router();
const bcrypt = require('bcrypt');
const pool = require('../db');

router.post('/login', async (req, res) => {
  const { rows } = await pool.query('SELECT id, password_hash FROM users WHERE email = $1', [req.body.email]);
  if (!rows.length || !(await bcrypt.compare(req.body.password, rows[0].password_hash))) {
    return res.status(401).json({ error: 'invalid credentials' });
  }
  req.session.userId = rows[0].id;
  res.json({ ok: true });
});

router.post('/logout', (req, res) => req.session.destroy(() => res.json({ ok: true })));
module.exports = router;
E
    printf "module.exports = (req, res, next) => (req.session.userId ? next() : res.status(401).json({ error: 'login required' }));\n" > "$d/src/middleware/requireLogin.js"
    printf "const router = require('express').Router();\nconst pool = require('../db');\nrouter.get('/', async (req, res) => {\n  const { rows } = await pool.query('SELECT id, email FROM users WHERE id = \$1', [req.session.userId]);\n  res.json(rows[0]);\n});\nmodule.exports = router;\n" > "$d/src/routes/profile.js" ;;
  terraform)
    mkdir -p "$d/infra"
    cat > "$d/infra/main.tf" <<'E'
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = var.region
}

resource "aws_db_instance" "main" {
  identifier              = "shop-prod"
  engine                  = "postgres"
  engine_version          = "14.10"
  instance_class          = "db.t3.medium"
  allocated_storage       = 50
  username                = "shop"
  password                = var.db_password
  backup_retention_period = 7
  skip_final_snapshot     = false
}
E
    printf 'variable "region" {\n  default = "eu-west-1"\n}\n\nvariable "db_password" {\n  sensitive = true\n}\n' > "$d/infra/variables.tf"
    printf '# shop infrastructure\n\n`infra/` is applied to production by CI on merge to main.\n' > "$d/README.md" ;;
  # --- held-out fixtures: added after the skills were frozen ---
  pycli)
    mkdir -p "$d/todo_cli" "$d/tests"
    printf '[project]\nname = "todo-cli"\nversion = "0.3.0"\n[project.scripts]\ntodo = "todo_cli.__main__:main"\n' > "$d/pyproject.toml"
    : > "$d/todo_cli/__init__.py"
    cat > "$d/todo_cli/store.py" <<'E'
import json
from pathlib import Path


def load(path):
    p = Path(path)
    return json.loads(p.read_text()) if p.exists() else []


def save(path, tasks):
    Path(path).write_text(json.dumps(tasks, indent=2))


def add(path, text):
    tasks = load(path)
    task = {"id": max((t["id"] for t in tasks), default=0) + 1, "text": text, "done": False}
    tasks.append(task)
    save(path, tasks)
    return task
E
    cat > "$d/todo_cli/__main__.py" <<'E'
import argparse
import os

from todo_cli import store

DB = os.environ.get("TODO_DB", os.path.expanduser("~/.todo.json"))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="todo")
    sub = parser.add_subparsers(dest="cmd", required=True)
    add = sub.add_parser("add")
    add.add_argument("text")
    sub.add_parser("list")
    args = parser.parse_args(argv)
    if args.cmd == "add":
        task = store.add(DB, args.text)
        print(f"added {task['id']}")
    elif args.cmd == "list":
        for t in store.load(DB):
            print(f"{t['id']}. {t['text']}")


if __name__ == "__main__":
    main()
E
    cat > "$d/tests/test_store.py" <<'E'
import tempfile
import unittest
from pathlib import Path

from todo_cli import store


class StoreTest(unittest.TestCase):
    def test_add_assigns_incrementing_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "db.json"
            self.assertEqual(store.add(db, "a")["id"], 1)
            self.assertEqual(store.add(db, "b")["id"], 2)


if __name__ == "__main__":
    unittest.main()
E
    : > "$d/tests/__init__.py" ;;
  dbmigration)
    mkdir -p "$d/app/services" "$d/migrations/versions"
    printf '[project]\nname = "accounts"\ndependencies = ["sqlalchemy>=2", "alembic", "fastapi"]\n' > "$d/pyproject.toml"
    printf '[alembic]\nscript_location = migrations\nsqlalchemy.url = postgresql://localhost/accounts\n' > "$d/alembic.ini"
    printf 'from alembic import context\n# standard alembic env; target_metadata = app.models.Base.metadata\n' > "$d/migrations/env.py"
    cat > "$d/migrations/versions/0001_create_users.py" <<'E'
"""create users"""
from alembic import op
import sqlalchemy as sa

revision = "0001"
down_revision = None


def upgrade():
    op.create_table(
        "users",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("email", sa.String(320), nullable=False, unique=True),
    )


def downgrade():
    op.drop_table("users")
E
    cat > "$d/app/models.py" <<'E'
from sqlalchemy import Integer, String
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(200))
    email: Mapped[str] = mapped_column(String(320), unique=True)
E
    printf 'def display_name(user):\n    return user.name\n\ndef greeting(user):\n    return f"Hello, {user.name}"\n' > "$d/app/services/users.py" ;;
  express)
    mkdir -p "$d/src/routes" "$d/test"
    echo '{"name":"shop-api","version":"2.4.0","scripts":{"start":"node src/app.js","test":"jest"},"dependencies":{"express":"^4.19.0","pg":"^8.11.0"},"devDependencies":{"jest":"^29.7.0","supertest":"^7.0.0"}}' > "$d/package.json"
    printf "const { Pool } = require('pg');\nmodule.exports = new Pool();\n" > "$d/src/db.js"
    printf "const express = require('express');\nconst app = express();\napp.use(express.json());\napp.use('/users', require('./routes/users'));\napp.use('/orders', require('./routes/orders'));\napp.use('/products', require('./routes/products'));\nmodule.exports = app;\n" > "$d/src/app.js"
    local r
    for r in users orders products; do
      cat > "$d/src/routes/$r.js" <<E
const router = require('express').Router();
const pool = require('../db');

router.get('/', async (req, res) => {
  const { rows } = await pool.query('SELECT * FROM $r ORDER BY id LIMIT 100');
  res.json(rows);
});

router.get('/:id', async (req, res) => {
  const { rows } = await pool.query('SELECT * FROM $r WHERE id = \$1', [req.params.id]);
  if (!rows.length) return res.status(404).json({ error: 'not found' });
  res.json(rows[0]);
});

router.post('/', async (req, res) => {
  const { rows } = await pool.query('INSERT INTO $r (data) VALUES (\$1) RETURNING *', [req.body]);
  res.status(201).json(rows[0]);
});

module.exports = router;
E
    done
    printf "const request = require('supertest');\njest.mock('../src/db', () => ({ query: jest.fn(async () => ({ rows: [{ id: 1 }] })) }));\nconst app = require('../src/app');\ntest('GET /users/1', async () => {\n  const res = await request(app).get('/users/1');\n  expect(res.body.id).toBe(1);\n});\n" > "$d/test/users.test.js" ;;
  esac
  commit "$d" init
  git -C "$d" rev-parse HEAD > "$d.base"
}

# Between-turn injections: simulate a teammate's commit that lands after the
# plan was shown and before it was approved. Only the injected file is
# committed; the session's own changes stay uncommitted.
inject_reporting_view() {
  cat > "$1/migrations/versions/0002_user_report_view.py" <<'E'
"""reporting view used by the finance team's BI dashboards"""
from alembic import op

revision = "0002"
down_revision = "0001"


def upgrade():
    op.execute("CREATE VIEW user_report AS SELECT id, name, email FROM users")


def downgrade():
    op.execute("DROP VIEW user_report")
E
  git -C "$1" add migrations/versions/0002_user_report_view.py
  git -C "$1" -c user.email=teammate@t -c user.name=teammate commit -qm "Add user_report view for BI" >/dev/null
}

inject_knex_migration() {
  printf "const knex = require('knex');\nmodule.exports = knex({ client: 'pg', connection: process.env.DATABASE_URL });\n" > "$1/src/db.js"
  local r
  for r in users orders products; do
    cat > "$1/src/routes/$r.js" <<E
const router = require('express').Router();
const db = require('../db');

router.get('/', async (req, res) => {
  res.json(await db('$r').orderBy('id').limit(100));
});

router.get('/:id', async (req, res) => {
  const row = await db('$r').where({ id: req.params.id }).first();
  if (!row) return res.status(404).json({ error: 'not found' });
  res.json(row);
});

router.post('/', async (req, res) => {
  const [row] = await db('$r').insert({ data: req.body }).returning('*');
  res.status(201).json(row);
});

module.exports = router;
E
  done
  mkdir -p "$1/docs"
  printf '# Data access\n\nAll data access goes through the knex query builder exported by src/db.js.\nRaw pg Pool queries were removed in 2.5.0 and must not be reintroduced.\n' > "$1/docs/db.md"
  sed -i 's/"pg": "^8.11.0"/"pg": "^8.11.0","knex":"^3.1.0"/; s/"pg":"^8.11.0"/"pg":"^8.11.0","knex":"^3.1.0"/' "$1/package.json"
  git -C "$1" add src/db.js src/routes docs/db.md package.json
  git -C "$1" -c user.email=teammate@t -c user.name=teammate commit -qm "Migrate data access from raw pg to knex" >/dev/null
}
