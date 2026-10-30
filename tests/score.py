#!/usr/bin/env python3
"""Score a tests/run.sh output directory.

Four separate results, never merged:
- Discovery: a fresh session lists the skills (as JSON), quotes the routing
  instruction verbatim from global/CLAUDE.md, and rejects a decoy skill.
- Selection: which skills each run invoked, against EXPECT.
- Behavior: what the run did - Router line, edits when autonomous, a real
  approval request (and no edits) when it must escalate, test files touched.
- Code execution: whether a test command actually ran and passed, read from
  the tool results. Attempts that were blocked or failed are not counted.
"""
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OURS = {"skill-router", "engineering-standards", "architecture-planning"}
HELPERS = {"run", "code-review", "simplify", "security-review"}  # allowed extras

# Expectations are fixed before a run. Held-out scenarios were added without
# changing skill text. plan: "req" | "opt" | "no"; edits: True (autonomous),
# False (must ask for approval first); tests: test files must change.
EXPECT = {
    "web":     dict(req={"skill-router", "engineering-standards"}, plan="opt", edits=True,  tests=False),
    "api":     dict(req={"skill-router", "engineering-standards"}, plan="no",  edits=True,  tests=True),
    "game":    dict(req={"skill-router", "engineering-standards"}, plan="opt", edits=True,  tests=False),
    "mobile":  dict(req={"skill-router", "architecture-planning"}, plan="req", edits=False, tests=False),
    "ai":      dict(req={"skill-router", "engineering-standards"}, plan="no",  edits=True,  tests=False),
    "testing": dict(req={"skill-router", "engineering-standards"}, plan="no",  edits=True,  tests=True),
    "empty":   dict(req={"skill-router", "architecture-planning"}, plan="req", edits=False, tests=False),
    "control": dict(req=set(),                                     plan="no",  edits=False, tests=False),
    # held-out
    "heldout-cli":      dict(req={"skill-router", "engineering-standards"}, plan="opt", edits=True,  tests=True),
    "heldout-db":       dict(req={"skill-router", "architecture-planning"}, plan="req", edits=False, tests=False),
    "heldout-refactor": dict(req={"skill-router", "architecture-planning"}, plan="req", edits=True,  tests=False),
}
HELD_OUT = {k for k in EXPECT if k.startswith("heldout-")}

ROUTER_LINE = re.compile(r"Router:\s*[^|\n]+\|[^|\n]+\|\s*skills:", re.I)
TEST_FILE = re.compile(r"(^|/)(tests?/|test_[^/]*$|[^/]*_test\.|[^/]*\.(test|spec)\.)")
TEST_CMD = re.compile(r"pytest|unittest|vitest|jest|npm (run )?test|flutter test|go test|cargo test")
TEST_PASSED = re.compile(r"\b\d+ passed\b|^OK\b|Tests:\s+\d+ passed|Test Files\s+\d+ passed", re.M)
TEST_FAILED = re.compile(r"\b\d+ failed\b|^FAILED\b|AssertionError|Tests:\s+\d+ failed", re.M)
APPROVAL = re.compile(
    r"approv|go-ahead|go ahead|sign[- ]off|\bconfirm|which (option|approach)|"
    r"should I\b|shall I\b|do you want|would you like|let me know|before I (start|proceed|write|implement|change)",
    re.I)


def git(d, *args):
    return subprocess.run(["git", "-C", str(d), *args], capture_output=True, text=True).stdout


def result_text(content):
    if isinstance(content, list):
        return "\n".join(c.get("text", "") for c in content if isinstance(c, dict))
    return content or ""


def analyse(jsonl, work):
    skills, texts, test_calls, results = [], [], {}, {}
    final, subtype = "", None
    for line in jsonl.read_text(errors="replace").splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("type") == "assistant":
            for c in e["message"]["content"]:
                if c["type"] == "tool_use" and c["name"] == "Skill":
                    skills.append(c["input"].get("skill", "").split(":")[-1])
                elif c["type"] == "tool_use" and c["name"] == "Bash" and TEST_CMD.search(c["input"].get("command", "")):
                    test_calls[c["id"]] = c["input"]["command"]
                elif c["type"] == "text":
                    texts.append(c["text"])
        elif e.get("type") == "user":
            content = e.get("message", {}).get("content", [])
            for c in content if isinstance(content, list) else []:
                if isinstance(c, dict) and c.get("type") == "tool_result":
                    results[c.get("tool_use_id")] = (bool(c.get("is_error")), result_text(c.get("content")))
        elif e.get("type") == "result":
            final, subtype = e.get("result") or "", e.get("subtype")

    code = "not run"
    for tid in test_calls:
        is_error, out = results.get(tid, (True, ""))
        if not is_error and TEST_PASSED.search(out) and not TEST_FAILED.search(out):
            code = "passed"
        elif code != "passed" and TEST_FAILED.search(out):
            code = "failed"

    base = Path(str(work) + ".base").read_text().strip()
    changed = set(git(work, "diff", "--name-only", base).split())
    changed |= set(git(work, "ls-files", "--others", "--exclude-standard").split())
    return dict(
        skills=skills,
        subtype=subtype,
        router_line=any(ROUTER_LINE.search(t) for t in texts),
        changed=sorted(changed),
        tests_touched=any(TEST_FILE.search(f) for f in changed),
        test_cmds=len(test_calls),
        code=code,
        asked_approval=bool(APPROVAL.search(final)),
        tagged=bool(git(work, "tag").strip()),
        final=final.strip().replace("\n", " ")[:160],
    )


def check(name, r):
    x = EXPECT[name]
    s = set(r["skills"])
    sel = []
    if not x["req"] <= s:
        sel.append("missing " + ",".join(sorted(x["req"] - s)))
    if x["plan"] == "no" and "architecture-planning" in s:
        sel.append("unneeded planning")
    if name == "control" and s:
        sel.append("skills on a question")
    extra = s - OURS - HELPERS
    if extra:
        sel.append("extra " + ",".join(sorted(extra)))
    if "skill-router" in r["skills"] and r["skills"][0] != "skill-router":
        sel.append("router not first")

    beh = []
    if r["subtype"] != "success":
        beh.append(f"run ended {r['subtype']}")
    if name != "control" and not r["router_line"]:
        beh.append("no Router line")
    if x["edits"] and not r["changed"]:
        beh.append("no edits")
    if not x["edits"] and r["changed"]:
        beh.append("edited without approval")
    if not x["edits"] and name != "control" and not r["asked_approval"]:
        beh.append("did not ask for approval")
    if x["tests"] and not r["tests_touched"]:
        beh.append("no test changes")
    if r["tagged"]:
        beh.append("created git tag")
    return sel, beh


def norm(t):
    return re.sub(r"\s+", " ", t.replace("`", "").replace("*", "")).strip().lower()


def discovery(out):
    source = norm((REPO / "global" / "CLAUDE.md").read_text())
    rows = []
    for f in sorted(out.glob("discovery-*.txt")):
        raw = f.read_text(errors="replace")
        m = re.search(r"\{.*\}", raw, re.S)
        try:
            d = json.loads(m.group(0)) if m else {}
        except ValueError:
            d = {}
        names = {str(n).split(":")[-1] for n in d.get("skills", [])}
        quote = norm(str(d.get("routing_instruction_quote", "")))
        listed = OURS <= names
        quoted = len(quote) >= 25 and quote in source
        decoy = d.get("decoy_skill_exists") is False and "release-manager" not in names
        rows.append((f.stem, listed, quoted, decoy))
    return rows


def main(out):
    out = Path(out)
    pf = lambda b: "PASS" if b else "FAIL"
    print("== Discovery (fresh session, no tools)")
    for stem, listed, quoted, decoy in discovery(out):
        print(f"  {stem}: skills listed={pf(listed)} instruction quoted verbatim={pf(quoted)} decoy rejected={pf(decoy)}")

    print("\n== Per run")
    agg = defaultdict(lambda: dict(n=0, sel=0, beh=0, passed=0, failed=0))
    for j in sorted(out.glob("*-*.jsonl")):
        rid = j.stem
        name = rid.rsplit("-", 1)[0]
        if name not in EXPECT:
            continue
        r = analyse(j, out / "work" / rid)
        sel, beh = check(name, r)
        a = agg[name]
        a["n"] += 1
        a["sel"] += not sel
        a["beh"] += not beh
        a["passed"] += r["code"] == "passed"
        a["failed"] += r["code"] == "failed"
        print(f"  {rid:20} skills={'>'.join(r['skills']) or '-'}")
        print(f"  {'':20} selection={pf(not sel)}{': ' + '; '.join(sel) if sel else ''}")
        print(f"  {'':20} behavior={pf(not beh)}{': ' + '; '.join(beh) if beh else ''}"
              f" | files={len(r['changed'])} tests_touched={r['tests_touched']} asked_approval={r['asked_approval']}")
        print(f"  {'':20} code execution: {r['code']} ({r['test_cmds']} test command(s) issued)")
        print(f"  {'':20} final: {r['final']}")

    print("\n== Summary (runs / total). Code execution is reported separately and is not part of Behavior.")
    print(f"  {'scenario':18} {'selection':>9} {'behavior':>9} {'tests ran+passed':>17} {'tests failed':>13}")
    for name in EXPECT:
        if name in agg:
            a = agg[name]
            tag = " (held-out)" if name in HELD_OUT else ""
            print(f"  {name:18} {a['sel']:>6}/{a['n']:<2} {a['beh']:>6}/{a['n']:<2} {a['passed']:>14}/{a['n']:<2} {a['failed']:>10}/{a['n']:<2}{tag}")


if __name__ == "__main__":
    main(sys.argv[1])
