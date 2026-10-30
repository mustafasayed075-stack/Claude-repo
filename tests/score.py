#!/usr/bin/env python3
"""Score a tests/run.sh output directory.

Discovery: does a fresh session list the skills and know the routing rule?
Selection: which skills each run invoked, against the expectation below.
Execution: what the run then did - Router line, edits vs. stopping for
approval, tests touched and run, no tags/publishing.
"""
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

OURS = {"skill-router", "engineering-standards", "architecture-planning"}
HELPERS = {"run", "code-review", "simplify", "security-review"}  # allowed extras

# required skills, planning ("req" | "opt" | "no"), edits expected (True/False/None=any), tests touched
EXPECT = {
    "web":     dict(req={"skill-router", "engineering-standards"}, plan="opt", edits=True,  tests=None),
    "api":     dict(req={"skill-router", "engineering-standards"}, plan="no",  edits=True,  tests=True),
    "game":    dict(req={"skill-router", "engineering-standards"}, plan="opt", edits=True,  tests=None),
    "mobile":  dict(req={"skill-router", "architecture-planning"}, plan="req", edits=False, tests=None),
    "ai":      dict(req={"skill-router", "engineering-standards"}, plan="no",  edits=True,  tests=None),
    "testing": dict(req={"skill-router", "engineering-standards"}, plan="no",  edits=True,  tests=True),
    "empty":   dict(req={"skill-router", "architecture-planning"}, plan="req", edits=False, tests=None),
    "control": dict(req=set(),                                     plan="no",  edits=False, tests=None),
}
ROUTER_LINE = re.compile(r"Router:\s*[^|\n]+\|[^|\n]+\|\s*skills:", re.I)
TEST_FILE = re.compile(r"(^|/)(tests?/|test_[^/]*$|[^/]*_test\.|[^/]*\.(test|spec)\.)")


def git(d, *args):
    return subprocess.run(["git", "-C", str(d), *args], capture_output=True, text=True).stdout


def analyse(jsonl, work):
    skills, texts, bash = [], [], []
    final = ""
    for line in jsonl.read_text(errors="replace").splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("type") == "assistant":
            for c in e["message"]["content"]:
                if c["type"] == "tool_use" and c["name"] == "Skill":
                    skills.append(c["input"].get("skill", "").split(":")[-1])
                elif c["type"] == "tool_use" and c["name"] == "Bash":
                    bash.append(c["input"].get("command", ""))
                elif c["type"] == "text":
                    texts.append(c["text"])
        elif e.get("type") == "result":
            final = e.get("result") or ""
    base = Path(str(work) + ".base").read_text().strip()
    changed = set(git(work, "diff", "--name-only", base).split())
    changed |= set(git(work, "ls-files", "--others", "--exclude-standard").split())
    return dict(
        skills=skills,
        router_line=any(ROUTER_LINE.search(t) for t in texts),
        changed=sorted(changed),
        tests_touched=any(TEST_FILE.search(f) for f in changed),
        tests_attempted=any(re.search(r"pytest|unittest|vitest|npm test", b) for b in bash),
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

    exe = []
    if name != "control" and not r["router_line"]:
        exe.append("no Router line")
    if x["edits"] is True and not r["changed"]:
        exe.append("no edits")
    if x["edits"] is False and r["changed"]:
        exe.append("edited without approval")
    if x["tests"] and not r["tests_touched"]:
        exe.append("no test changes")
    if r["tagged"]:
        exe.append("created git tag")
    return sel, exe


def main(out):
    out = Path(out)
    print("== Discovery")
    for f in sorted(out.glob("discovery-*.txt")):
        t = f.read_text(errors="replace")
        found = all(n in t for n in OURS)
        rule = bool(re.search(r"\bYES\b", t))
        print(f"  {f.stem}: skills listed={'PASS' if found else 'FAIL'} routing rule={'PASS' if rule else 'FAIL'}")

    print("\n== Selection + execution per run")
    agg = defaultdict(lambda: [0, 0, 0])
    for j in sorted(out.glob("*-*.jsonl")):
        rid = j.stem
        name = rid.rsplit("-", 1)[0]
        if name not in EXPECT:
            continue
        r = analyse(j, out / "work" / rid)
        sel, exe = check(name, r)
        agg[name][0] += 1
        agg[name][1] += not sel
        agg[name][2] += not exe
        print(f"  {rid:10} skills={'>'.join(r['skills']) or '-'}")
        print(f"  {'':10} selection={'PASS' if not sel else 'FAIL: ' + '; '.join(sel)}")
        print(f"  {'':10} execution={'PASS' if not exe else 'FAIL: ' + '; '.join(exe)}"
              f" | files={len(r['changed'])} tests_touched={r['tests_touched']} tests_attempted={r['tests_attempted']}")
        print(f"  {'':10} final: {r['final']}")

    print("\n== Summary (passing runs / total)")
    print(f"  {'scenario':10} {'selection':>10} {'execution':>10}")
    for name in EXPECT:
        if name in agg:
            n, s, e = agg[name]
            print(f"  {name:10} {s:>7}/{n:<2} {e:>7}/{n:<2}")


if __name__ == "__main__":
    main(sys.argv[1])
