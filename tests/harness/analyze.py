#!/usr/bin/env python3
"""TEST-ONLY analyzer for live sandbox runs.

    analyze.py <run-dir> [<run-dir> ...]

Each run dir holds meta.json (written by the orchestrator), events.jsonl
(tool events from logger.py) and, for TUI runs, driver.jsonl (tui.py).
Evidence comes only from the session's own transcript and the event log,
never from what the model says about itself.

Verdict per turn:
  Blocked       the environment prevented the test (session never ready,
                usage limit / API error, no transcript)
  Inconclusive  it ran but the evidence is incomplete (timeout, a rejected
                permission prompt, the turn produced no assistant output)
  Failed        an expectation was checked and is false
  Passed        every expectation was checked and holds
"""
import datetime
import glob
import json
import os
import re
import sys

ROUTER_LINE = re.compile(r"Router:\s*[^|\n]+\|[^|\n]+\|\s*skills:", re.I)
LIMIT = re.compile(r"hit your .{0,20}limit|usage limit", re.I)
OURS = ("skill-router", "engineering-standards", "architecture-planning")


def epoch(ts):
    try:
        return datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()
    except Exception:
        return None


def jsonl(path):
    out = []
    if path and os.path.exists(path):
        for line in open(path, errors="replace"):
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out


def text_of(e):
    c = e.get("message", {}).get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(x.get("text", "") for x in c if isinstance(x, dict) and x.get("type") == "text")
    return ""


def is_prompt(e):
    if e.get("type") != "user" or e.get("isMeta") or e.get("isCompactSummary"):
        return False
    c = e.get("message", {}).get("content")
    if isinstance(c, list) and any(isinstance(x, dict) and x.get("type") == "tool_result" for x in c):
        return False
    t = text_of(e).lstrip()
    return bool(t) and not t.startswith(("<command-", "<local-command", "Caveat:"))


def transcripts(meta):
    key = re.sub(r"[^A-Za-z0-9]", "-", meta["project"])
    files = glob.glob(os.path.join(meta["home"], ".claude", "projects", key, "*.jsonl"))
    return sorted(files, key=os.path.getmtime)


def listing_names(entries, upto):
    names, raw = set(), ""
    for e in entries[:upto]:
        a = e.get("attachment") or {}
        if str(a.get("type", "")).startswith("skill_listing"):
            c = a.get("content") if isinstance(a.get("content"), str) else json.dumps(a)
            raw += c + "\n"
            names |= {l[2:].split(":")[0].strip() for l in c.splitlines() if l.startswith("- ")}
    return names, raw


def analyse_turn(meta, turn, entries, events, driver, started=None):
    exp = turn.get("expect", {})
    res = {"prompt": turn["prompt"][:70], "checks": [], "info": {}}

    def check(name, ok, evidence):
        res["checks"].append({"check": name, "status": "pass" if ok else "fail", "evidence": evidence})

    # Sessions here can share one transcript file (child sessions inherit the
    # parent's session id), so take the first matching prompt sent after
    # this run started.
    start = next((i for i, e in enumerate(entries) if is_prompt(e) and turn["prompt"][:60] in text_of(e)
                  and (started is None or (epoch(e.get("timestamp", "")) or 0) >= started - 5)), None)
    if start is None:
        res["verdict"], res["why"] = "Blocked", "prompt not found in transcript"
        return res
    end = next((i for i in range(start + 1, len(entries)) if is_prompt(entries[i])), len(entries))
    seg = entries[start:end]
    t0 = epoch(entries[start].get("timestamp", "")) or 0
    t1 = epoch(entries[end].get("timestamp", "")) if end < len(entries) else float("inf")

    assistant = [i for i, e in enumerate(seg) if e.get("type") == "assistant"]
    first_assistant = start + assistant[0] if assistant else end
    names, raw = listing_names(entries, first_assistant)
    texts, skills, tools, models = [], [], [], set()
    for e in seg:
        if e.get("type") != "assistant":
            continue
        models.add(e.get("message", {}).get("model"))
        for c in e.get("message", {}).get("content", []):
            if c.get("type") == "text":
                texts.append(c["text"])
            elif c.get("type") == "tool_use":
                tools.append(c["name"])
                if c["name"] == "Skill":
                    skills.append(str(c["input"].get("skill", "")).split(":")[-1])
    hook_ctx = "\n".join(json.dumps(e.get("attachment")) for e in entries
                         if str((e.get("attachment") or {}).get("hookName", "")).startswith("SessionStart"))
    joined = "\n".join(texts)

    # first modification in this turn, from the event log
    proj = os.path.realpath(meta["project"])
    events = [ev for ev in events if os.path.realpath(ev.get("cwd") or "") == proj]
    before = [ev for ev in events if ev["ts"] < t0]
    base = before[-1]["state"] if before else meta.get("baseline")
    window = [ev for ev in events if t0 <= ev["ts"] < t1]
    first_mod = next((ev for ev in window if ev.get("state") and ev["state"] != base), None)
    res["info"] = {"models": sorted(m for m in models if m), "skills_invoked": skills,
                   "first_tool": tools[0] if tools else None,
                   "router_line": bool(ROUTER_LINE.search(joined)),
                   "listed_ours": sorted(n for n in names if n in OURS),
                   "first_modification": (f"{first_mod['phase']}:{first_mod['tool']}" if first_mod else None),
                   "tool_events": len(window)}

    blocked = [d for d in driver if d.get("type") == "blocked" and t0 - 5 <= d["ts"] <= t1]
    if blocked or LIMIT.search(joined):
        res["verdict"] = "Blocked"
        res["why"] = blocked[0].get("reason") if blocked else "usage limit in transcript"
        return res

    if "skills_listed" in exp:
        miss = [s for s in exp["skills_listed"] if s not in names]
        check("skills listed before the first response", not miss, f"missing {miss}" if miss else f"listed {sorted(n for n in names if n in OURS)}")
    if "listing_contains" in exp:
        check(f"listing contains {exp['listing_contains']!r}", exp["listing_contains"] in raw, "found" if exp["listing_contains"] in raw else "absent")
    if "hook_context_contains" in exp:
        ok = exp["hook_context_contains"] in hook_ctx
        check(f"SessionStart hook context contains {exp['hook_context_contains']!r}", ok, "found" if ok else "absent")
    if exp.get("router_first"):
        check("skill-router is the first skill invoked", bool(skills) and skills[0] == "skill-router", f"skills={skills}")
    if "skills_required" in exp:
        miss = [s for s in exp["skills_required"] if s not in skills]
        check("required skills invoked", not miss, f"invoked={skills}")
    if "skills_forbidden" in exp:
        bad = [s for s in exp["skills_forbidden"] if s in skills]
        check("no forbidden skills invoked", not bad, f"invoked={skills}")
    if "modification" in exp:
        got = first_mod is not None
        check("working tree modified" if exp["modification"] else "working tree not modified",
              got == exp["modification"], res["info"]["first_modification"] or "no change")

    fails = [c for c in res["checks"] if c["status"] == "fail"]
    incomplete = [d["type"] for d in driver if d.get("type") in ("timeout", "permission_prompt_rejected") and t0 - 5 <= d["ts"] <= t1]
    asked = [d for d in driver if d.get("type") == "question_asked" and t0 - 5 <= d["ts"] <= t1]
    if asked:
        res["info"]["stopped_with_question"] = True
    if not assistant:
        incomplete.append("no assistant output")
    if fails and not incomplete:
        res["verdict"] = "Failed"
    elif incomplete:
        res["verdict"], res["why"] = "Inconclusive", ", ".join(sorted(set(incomplete)))
        if fails:
            res["why"] += "; failed checks may be caused by the truncation"
    else:
        res["verdict"] = "Passed"
    return res


def analyse(run):
    meta = json.load(open(os.path.join(run, "meta.json")))
    events = sorted(jsonl(os.path.join(run, "events.jsonl")), key=lambda e: e["ts"])
    driver = jsonl(os.path.join(run, "driver.jsonl"))
    out = {"id": meta["id"], "turns": []}
    files = transcripts(meta)
    # Several runs can share a project and prompt; only transcripts whose
    # prompt was sent after this run started belong to it.
    started = meta.get("started") or min((d["ts"] for d in driver if d.get("type") in
                                          ("onboarding", "trust_accepted", "ready", "sent")), default=None)
    never_ready = any(d.get("type") == "blocked" and "ready" in d.get("reason", "") for d in driver)
    for turn in meta["turns"]:
        entries = []
        for f in files:
            es = jsonl(f)
            hits = [e for e in es if is_prompt(e) and turn["prompt"][:60] in text_of(e)]
            if hits and (started is None or any((epoch(e.get("timestamp", "")) or 0) >= started - 5 for e in hits)):
                entries = es
                if started is not None:
                    break
        if never_ready or not files:
            out["turns"].append({"prompt": turn["prompt"][:70], "verdict": "Blocked",
                                 "why": "session never ready" if never_ready else "no transcript", "checks": [], "info": {}})
            continue
        out["turns"].append(analyse_turn(meta, turn, entries, events, driver, started))
    return out


def main():
    results = [analyse(r) for r in sys.argv[1:]]
    for r in results:
        for i, t in enumerate(r["turns"], 1):
            print(f"{r['id']} turn {i}: {t['verdict']}" + (f" ({t['why']})" if t.get("why") else "") + f"  [{t['prompt']}]")
            for c in t["checks"]:
                print(f"    {c['status'].upper():4}  {c['check']}  -- {c['evidence']}")
            if t["info"]:
                print(f"    info: {json.dumps(t['info'])}")
    tally = {}
    for r in results:
        for t in r["turns"]:
            tally[t["verdict"]] = tally.get(t["verdict"], 0) + 1
    print("\nTurn verdicts:", ", ".join(f"{k} {v}" for k, v in sorted(tally.items())))
    json.dump(results, open(os.environ.get("ANALYZE_OUT", os.devnull), "w"), indent=2)


if __name__ == "__main__":
    main()
