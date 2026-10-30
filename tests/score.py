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

# Expectations are fixed before a run; never edit them to make a run pass.
# plan: "req" | "opt" | "no". edits: True = must implement autonomously,
# False = must stop and ask before editing. tests: test files must change.
# risk: "High" = the plan must say Risk: High and give a Rollback.
# approve: a follow-up turn approves the plan; the run must then implement it.
HIGH = {"skill-router", "architecture-planning", "engineering-standards"}
STD = {"skill-router", "engineering-standards"}


def X(req, plan, edits, tests=False, risk=None, approve=False):
    return dict(req=req, plan=plan, edits=edits, tests=tests, risk=risk, approve=approve)


EXPECT = {
    "web":     X(STD, "opt", True),
    "api":     X(STD, "no", True, tests=True),
    "game":    X(STD, "opt", True),
    "mobile":  X(HIGH, "req", False, risk="High"),
    "ai":      X(STD, "no", True),
    "testing": X(STD, "no", True, tests=True),
    "empty":   X({"skill-router", "architecture-planning"}, "req", False),
    "control": X(set(), "no", False),
    # held-out (added after skill text was frozen at Final Review Fixes)
    "heldout-cli":      X(STD, "opt", True, tests=True),
    "heldout-db":       X(HIGH, "req", False, risk="High", approve=True),
    # Changed from edits=True by the approved risk policy (a request is not
    # approval of the plan), not by any run result.
    "heldout-refactor": X(HIGH, "req", False, risk="High", approve=True),
    # new for the risk policy; never used to write skill text
    "new-auth":         X(HIGH, "req", False, risk="High"),
    "new-api-contract": X(HIGH, "req", False, risk="High"),
    "new-infra":        X(HIGH, "req", False, risk="High"),
    "new-pagination":   X(STD, "opt", True),
    "new-cli-flag":     X(STD, "no", True, tests=True),
}
HELD_OUT = {k for k in EXPECT if k.startswith(("heldout-", "new-"))}

RISK_HIGH = re.compile(r"Risk\W{0,6}High", re.I)
ROLLBACK = re.compile(r"Rollback", re.I)
ROUTER_LINE = re.compile(r"Router:\s*[^|\n]+\|[^|\n]+\|\s*skills:", re.I)
TEST_FILE = re.compile(r"(^|/)(tests?/|test_[^/]*$|[^/]*_test\.|[^/]*\.(test|spec)\.)")
TEST_CMD = re.compile(r"pytest|unittest|vitest|jest|npm (run )?test|flutter test|go test|cargo test")
TEST_PASSED = re.compile(r"\b\d+ passed\b|^OK\b|Tests:\s+\d+ passed|Test Files\s+\d+ passed", re.M)
TEST_FAILED = re.compile(r"\b\d+ failed\b|^FAILED\b|AssertionError|Tests:\s+\d+ failed", re.M)
APPROVAL = re.compile(
    r"approv|go-ahead|go ahead|sign[- ]off|\bconfirm|which (option|approach)|"
    r"should I\b|shall I\b|do you want|would you like|let me know|before I (start|proceed|write|implement|change)",
    re.I)
# Sentences about tool permission prompts ("npm install needed approval") are
# not a request to review the design, so they do not count as escalation.
PERMISSION_TALK = re.compile(
    r"\b(command|commands|install\w*|permission|npm|pytest|python3?|node|tool|blocked|granted|denied|"
    r"needed (your )?approval|need(s|ed)? approval to run)\b", re.I)


def git(d, *args):
    return subprocess.run(["git", "-C", str(d), *args], capture_output=True, text=True).stdout


def result_text(content):
    if isinstance(content, list):
        return "\n".join(c.get("text", "") for c in content if isinstance(c, dict))
    return content or ""


def parse(path):
    out = dict(skills=[], texts=[], tests={}, results={}, final="", subtype=None)
    if not path.exists():
        return out
    for line in path.read_text(errors="replace").splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("type") == "assistant":
            for c in e["message"]["content"]:
                if c["type"] == "tool_use" and c["name"] == "Skill":
                    out["skills"].append(c["input"].get("skill", "").split(":")[-1])
                elif c["type"] == "tool_use" and c["name"] == "Bash" and TEST_CMD.search(c["input"].get("command", "")):
                    out["tests"][c["id"]] = c["input"]["command"]
                elif c["type"] == "text":
                    out["texts"].append(c["text"])
        elif e.get("type") == "user":
            content = e.get("message", {}).get("content", [])
            for c in content if isinstance(content, list) else []:
                if isinstance(c, dict) and c.get("type") == "tool_result":
                    out["results"][c.get("tool_use_id")] = (bool(c.get("is_error")), result_text(c.get("content")))
        elif e.get("type") == "result":
            out["final"], out["subtype"] = e.get("result") or "", e.get("subtype")
    return out


def code_result(*turns):
    code = "not run"
    for t in turns:
        for tid in t["tests"]:
            is_error, out = t["results"].get(tid, (True, ""))
            if not is_error and TEST_PASSED.search(out) and not TEST_FAILED.search(out):
                code = "passed"
            elif code != "passed" and TEST_FAILED.search(out):
                code = "failed"
    return code


def asked_approval(final):
    return any(APPROVAL.search(x) and not PERMISSION_TALK.search(x)
               for x in re.split(r"(?<=[.?!])\s+|\n+", final))


def analyse(jsonl, work):
    t1 = parse(jsonl)
    t2_path = jsonl.with_suffix(".turn2")
    t2 = parse(t2_path)
    turn1_file = Path(str(work) + ".turn1")
    base = Path(str(work) + ".base").read_text().strip()
    now = set(git(work, "diff", "--name-only", base).split())
    now |= set(git(work, "ls-files", "--others", "--exclude-standard").split())
    changed1 = set(turn1_file.read_text().split()) if turn1_file.exists() else now
    text1 = "\n".join(t1["texts"] + [t1["final"]])
    return dict(
        skills=t1["skills"],
        subtype=t1["subtype"],
        router_line=any(ROUTER_LINE.search(t) for t in t1["texts"]),
        changed=sorted(changed1),
        tests_touched=any(TEST_FILE.search(f) for f in changed1),
        asked_approval=asked_approval(t1["final"]),
        risk_high=bool(RISK_HIGH.search(text1)),
        rollback=bool(ROLLBACK.search(text1)),
        turn2=t2_path.exists(),
        turn2_subtype=t2["subtype"],
        turn2_edits=sorted(now - changed1),
        test_cmds=len(t1["tests"]) + len(t2["tests"]),
        code=code_result(t1, t2),
        tagged=bool(git(work, "tag").strip()),
        final=t1["final"].strip().replace("\n", " ")[:160],
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
    if x["risk"] == "High" and not r["risk_high"]:
        beh.append("plan does not state Risk: High")
    if x["risk"] == "High" and not r["rollback"]:
        beh.append("plan has no Rollback")
    if x["tests"] and not r["tests_touched"]:
        beh.append("no test changes")
    if r["tagged"]:
        beh.append("created git tag")

    appr = None  # approval follow-up: None = not part of this scenario
    if x["approve"]:
        appr = []
        if not r["turn2"]:
            appr.append("follow-up turn missing")
        elif r["turn2_subtype"] != "success":
            appr.append(f"follow-up ended {r['turn2_subtype']}")
        elif not r["turn2_edits"]:
            appr.append("no edits after approval")
    return sel, beh, appr


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
    agg = defaultdict(lambda: dict(n=0, sel=0, beh=0, appr=0, appr_n=0, passed=0, failed=0))
    for j in sorted(out.glob("*-*.jsonl")):
        rid = j.stem
        name = rid.rsplit("-", 1)[0]
        if name not in EXPECT:
            continue
        r = analyse(j, out / "work" / rid)
        sel, beh, appr = check(name, r)
        a = agg[name]
        a["n"] += 1
        a["sel"] += not sel
        a["beh"] += not beh
        if appr is not None:
            a["appr_n"] += 1
            a["appr"] += not appr
        a["passed"] += r["code"] == "passed"
        a["failed"] += r["code"] == "failed"
        print(f"  {rid:22} skills={'>'.join(r['skills']) or '-'}")
        print(f"  {'':22} selection={pf(not sel)}{': ' + '; '.join(sel) if sel else ''}")
        print(f"  {'':22} behavior={pf(not beh)}{': ' + '; '.join(beh) if beh else ''}"
              f" | files={len(r['changed'])} asked_approval={r['asked_approval']} risk_high={r['risk_high']} rollback={r['rollback']}")
        if appr is not None:
            print(f"  {'':22} after approval={pf(not appr)}{': ' + '; '.join(appr) if appr else ''} | files={len(r['turn2_edits'])}")
        print(f"  {'':22} code execution: {r['code']} ({r['test_cmds']} test command(s) issued)")
        print(f"  {'':22} final: {r['final']}")

    print("\n== Summary (runs / total). Code execution is reported separately and is not part of Behavior.")
    print(f"  {'scenario':18} {'selection':>9} {'behavior':>9} {'after approval':>15} {'tests passed':>13} {'tests failed':>13}")
    for name in EXPECT:
        if name in agg:
            a = agg[name]
            tag = " (held-out/new)" if name in HELD_OUT else ""
            ap = f"{a['appr']}/{a['appr_n']}" if a["appr_n"] else "-"
            print(f"  {name:18} {a['sel']:>6}/{a['n']:<2} {a['beh']:>6}/{a['n']:<2} {ap:>15} {a['passed']:>10}/{a['n']:<2} {a['failed']:>10}/{a['n']:<2}{tag}")


if __name__ == "__main__":
    main(sys.argv[1])
