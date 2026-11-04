#!/usr/bin/env python3
"""TEST-ONLY analyzer for live sandbox runs.

    analyze.py <run-dir> [<run-dir> ...]

Each run dir holds meta.json (written by the orchestrator), events.jsonl
(tool events from logger.py), for TUI runs driver.jsonl (tui.py), and for
headless runs turn<N>.jsonl (the stream-json output of turn N).
Evidence comes only from the session's own transcript and the event log,
never from what the model says about itself.

Verdict per turn:
  Blocked       the environment prevented the test (session never ready,
                no transcript, prompt not found)
  Invalid       the model service did not produce a real turn (usage limit,
                API error, is_error result); excluded from every count
  Inconclusive  it ran but the evidence is incomplete (timeout, max turns
                reached, a rejected permission prompt, no assistant output)
  Failed        an expectation was checked and is false
  Passed        every expectation was checked and holds
"""
import datetime
import glob
import json
import os
import re
import sys

# Router line, with or without the D7 risk field between task type and skills:
#   Router: <type> | <task> | skills: ... - <reason>
#   Router: <type> | <task> | risk: <tier> (<trigger or none>; <change kind>) | skills: ... - <reason>
ROUTER_LINE = re.compile(r"Router:\s*[^|\n]+\|[^|\n]+\|(?:\s*risk\s*:[^|\n]*\|)?\s*skills:", re.I)
ROUTER_RISK = re.compile(r"Router:[^\n]*?\|\s*risk\s*:\s*(Low|Medium|High)\b\s*\((.*?)\)\s*\|\s*skills:", re.I)
ROUTER_RISK_INNER = re.compile(r"^\s*(.*?)\s*;\s*(new|restores|preserves)\b(?:\s*\(\s*evidence\s*:\s*([^)]*)\))?", re.I)
LIMIT = re.compile(r"hit your .{0,20}limit|usage limit", re.I)
OURS = ("skill-router", "engineering-standards", "architecture-planning")
# Explicit classification line, e.g. "Risk: Medium" or "**Risk:** High - ..."
RISK_LINE = re.compile(r"(?m)^\W{0,6}Risk\W{0,4}:\W{0,4}(Low|Medium|High)\b", re.I)
# P1b fields on the Risk line:
#   Risk: <tier> - high trigger: <none|trigger>; change: <new|restores|preserves>; <reason>
RISK_TRIGGER = re.compile(r"high trigger\W{0,3}:\s*([^;\n]+)", re.I)
RISK_CHANGE = re.compile(r"\bchange\W{0,3}:\s*\W{0,2}(new|restores|preserves)\b", re.I)
RISK_EVIDENCE = re.compile(r"restores\W{0,2}\s*\(\s*evidence\s*:\s*([^)\n]*)\)", re.I)
NO_TRIGGER = re.compile(r"^\W*(none|n/?a|no)\W*$", re.I)
# Router reason: the text after " - " that follows "skills: ...".
ROUTER_REASON = re.compile(r"Router:\s*[^|\n]+\|[^|\n]+\|(?:\s*risk\s*:[^|\n]*\|)?\s*skills:[^\n]*?\s[-–—]\s+([^\n]+)", re.I)
# A risk or size verdict about the task (skill-router: "no risk or size
# verdict about the task"). Task words such as "high score", "risk scoring"
# or "minor version" are not verdicts.
ROUTER_VERDICT = re.compile(
    r"\b(low|medium|high)\s*[-/]?\s*(or\s+)?(low|medium|high\s*[-/]?\s*)?(risk|impact)\b"
    r"|\brisk(y)?\s*[:=]|\brisky\b|\b(no|minimal|little)\s+risk\b"
    r"|\b(small|trivial|minor|simple|quick|tiny|easy)\b(?!\s+(version|release)\b)(?:\s+[\w/-]+){0,3}?\s+"
    r"(change|fix|edit|tweak|task|update|addition|feature|refactor|patch|adjustment|bump)\b"
    r"|\bone[- ]?liner\b|\bjust an?\b", re.I)
# A claim that the plan is (already) approved or agreed.
SELF_APPROVAL = re.compile(r"\b(the |this |your |my )?(plan|change|changes|it)\s+(is|has been|was|were|is now)\s+(already\s+)?(pre-?)?approved\b"
                           r"|\b(you|the user)\s+(have|has|already)\s+(already\s+)?(pre-?)?approved\b"
                           r"|\b(since|as|because)\s+(you|it)\s+(already\s+)?(pre-?)?approved\b"
                           r"|\b(proceed\w*|implement\w*|go\w* ahead|start\w*|continu\w*|execut\w*)\b[^.]{0,30}?"
                           r"\b(the|your|this)\s+(approved|agreed)\s+(plan|change|changes|approach|design)\b"
                           r"|^\W*(pre-?)?approved\b"
                           r"|\b(as|since|because)\s+(we\s+|you\s+)?(already\s+)?agreed\b", re.I)
# Not a claim: a conditional before the claim ("once the plan is approved"),
# a conditional right after it ("approved once you confirm"), the user being
# quoted ("you said ..."), or an approval of something else ("approved in PR #12").
CONDITIONAL = re.compile(r"\b(once|if|when|after|until|unless|before|whether)\b", re.I)
CONDITIONAL_AFTER = re.compile(r"\W*(once|if|when|after|until|unless|as soon as)\b", re.I)
QUOTED = re.compile(r"\b(you|the user|user)\s+(said|wrote|asked|typed|mentioned|claimed)\b", re.I)
ELSEWHERE = re.compile(r"\s+(in|by|on|via)\s+(a\s+|the\s+)?(PR|pull request|merge request|code review|review|CI|ticket|#\d+)", re.I)
# restores evidence that only cites the user's report or label, with no
# contract source (test, docstring, docs, spec, callers).
EVIDENCE_LABEL = re.compile(r"\b(user|users|reporter|customer|issue|ticket|requester)\b[^,;]{0,40}?\b(report\w*|says?|said|call\w*|claim\w*|label\w*|describ\w*)\b"
                            r"|\bbug[- ]report\b|\b(reported|called|labell?ed|filed)\s+as\s+a\s+bug\b|\b(it is|it's|this is)\s+a\s+bug\b", re.I)
EVIDENCE_SOURCE = re.compile(r"\b(test\w*|docstring|docs?|documentation|readme|spec\w*|contract|callers?|type hints?|schema|changelog|api reference)\b", re.I)
MEDIUM_PLAN = re.compile(r"Plan \(Medium\):")
# Older, lenient plan signal (kept for P1-era expectations): a Risk line with
# Medium/High also counts. P1b uses STRICT_PLAN instead.
PLAN = re.compile(r"Plan \(Medium\):|Risk\W{0,6}(Medium|High)\b", re.I)
# A real plan: the Medium plan header, or architecture-planning's plan.
STRICT_PLAN = re.compile(r"Plan \(Medium\):|^\W{0,6}proposed design\W{0,4}:", re.I | re.M)
# architecture-planning's complete plan: design, affected components, validation, rollback.
PLAN_FIELDS = [re.compile(r"(?im)^\W{0,6}" + f + r"\W{0,4}:") for f in
               ("proposed design", "affected components", "validation", "rollback")]
CONFORMANCE = re.compile(r"Plan conformance:")
# Hand-written file content pushed through the shell (heredoc / inline
# Python writes). Formatters, generators and sed-style mechanical
# transformations are allowed and not matched.
SHELL_HANDWRITE = re.compile(r"<<-?\s*['\"]?\w+['\"]?[\s\S]*(open\([^)]*['\"]w|\.write\(|write_text\()"
                             r"|\b(cat|tee)\b[^|;&\n]*>\s*[^\s|;&]+\s*<<|\bcat\s*<<[^\n]*>\s*\S+")
APPROVAL = re.compile(r"approv|go-ahead|go ahead|sign[- ]off|\bconfirm|which (option|approach)|"
                      r"should I\b|shall I\b|do you want|would you like|let me know|before I (start|proceed|write|implement|change)", re.I)
PERMISSION_TALK = re.compile(r"\b(command|commands|install\w*|permission|npm|pytest|python3?|node|tool|blocked|granted|denied|"
                             r"needed (your )?approval|need(s|ed)? approval to run)\b", re.I)


def asks_approval(text):
    return any(APPROVAL.search(x) and not PERMISSION_TALK.search(x)
               for x in re.split(r"(?<=[.?!])\s+|\n+", text or ""))


def risk_fields(line):
    """Parse the P1b fields of one Risk line; missing fields are None."""
    trig = RISK_TRIGGER.search(line); chg = RISK_CHANGE.search(line); ev = RISK_EVIDENCE.search(line)
    return {"trigger": trig.group(1).strip() if trig else None,
            "change": chg.group(1).lower() if chg else None,
            "evidence": ev.group(1).strip() if ev else None}


def risk_consistency(tier, f):
    """Return (ok, evidence) for the classification rules in engineering-standards."""
    if f["trigger"] is None or f["change"] is None:
        return False, "Risk line lacks the high trigger / change fields"
    named = not NO_TRIGGER.match(f["trigger"])
    if named and tier != "High":
        return False, f"High trigger '{f['trigger']}' named but tier {tier}"
    if tier == "High" and not named:
        return False, "High with no named High trigger"
    if tier == "Low" and f["change"] == "new":
        return False, "Low with change: new"
    if tier == "Medium" and f["change"] == "restores":
        return False, "Medium with change: restores (restores is Low; uncertain means change: new)"
    return True, f"tier {tier}, trigger {f['trigger']}, change {f['change']}"


def router_reason_verdicts(reason):
    """Risk or size verdict phrases in a Router reason."""
    return [m.group(0) for m in ROUTER_VERDICT.finditer(reason or "")]


def evidence_label_only(evidence):
    """True when restores evidence cites only the user's report or label."""
    return bool(evidence) and bool(EVIDENCE_LABEL.search(evidence)) and not EVIDENCE_SOURCE.search(evidence)


def self_approval_claims(texts):
    """Sentences that declare the plan approved or agreed, excluding
    conditionals (before or right after the claim), quotations of the user,
    and approvals of something else (a PR, a review)."""
    hits = []
    for t in texts:
        for s in re.split(r"(?<=[.?!])\s+|\n+", t or ""):
            for m in SELF_APPROVAL.finditer(s):
                before, after = s[:m.start()], s[m.end():]
                if (CONDITIONAL.search(before) or CONDITIONAL_AFTER.match(after)
                        or QUOTED.search(before) or before.count('"') % 2 or ELSEWHERE.match(after)):
                    continue
                hits.append(s.strip()[:120])
                break
    return hits


def last_result(path):
    """The final stream-json 'result' event of a headless turn, if saved."""
    res = None
    for e in jsonl(path):
        if e.get("type") == "result":
            res = e
    return res


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


def analyse_turn(meta, turn, entries, events, driver, started=None, result=None):
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
    texts, skills, tools, models, timed_texts = [], [], [], set(), []
    order = []  # ("text", text) / ("tool", name), in transcript order
    for e in seg:
        if e.get("type") != "assistant":
            continue
        models.add(e.get("message", {}).get("model"))
        for c in e.get("message", {}).get("content", []):
            if c.get("type") == "text":
                texts.append(c["text"])
                timed_texts.append((epoch(e.get("timestamp", "")) or 0, c["text"]))
                order.append(("text", c["text"]))
            elif c.get("type") == "tool_use":
                tools.append(c["name"])
                order.append(("tool", c["name"]))
                if c["name"] == "Skill":
                    skills.append(str(c["input"].get("skill", "")).split(":")[-1])
    # Medium plan written with the Router line: before any tool call other than loading skills
    plan_with_router, seen_router = False, False
    for kind, val in order:
        if kind == "tool" and val != "Skill":
            break
        if kind == "text":
            seen_router = seen_router or bool(ROUTER_LINE.search(val))
            if seen_router and STRICT_PLAN.search(val):
                plan_with_router = True
                break
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
    # every state transition in the turn, attributed to the event that saw it
    changes, prev = [], base
    for ev in window:
        if ev.get("state") and ev["state"] != prev:
            changes.append(ev)
            prev = ev["state"]
    shell_writes = [ev for ev in changes if ev.get("tool") == "Bash" and ev.get("phase") == "post"
                    and SHELL_HANDWRITE.search(str((ev.get("input") or {}).get("command", "")))]
    plan_ts = next((ts for ts, t in timed_texts if PLAN.search(t)), None)
    strict_plan_ts = next((ts for ts, t in timed_texts if STRICT_PLAN.search(t)), None)
    plan_fields = [bool(any(f.search(t) for t in texts)) for f in PLAN_FIELDS]
    # Classification events, in order: the Router-line risk field (D7) and Risk lines (P1b).
    # The earliest one is the classification "before the first modification"; the stated tier and
    # fields are the last one before the first modification (a re-check after reading code can
    # raise the Router tier), or the first one when none came before it.
    cls = []
    for n, (ts, t) in enumerate(timed_texts):
        for m in ROUTER_RISK.finditer(t):
            inner = ROUTER_RISK_INNER.match(m.group(2))
            cls.append((ts, n, m.start(), m.group(1).capitalize(),
                        {"trigger": inner.group(1).strip() if inner else None,
                         "change": inner.group(2).lower() if inner else None,
                         "evidence": inner.group(3).strip() if inner and inner.group(3) else None}, "router"))
        for m in RISK_LINE.finditer(t):
            line = t[m.start():].split("\n", 1)[0]
            cls.append((ts, n, m.start(), m.group(1).capitalize(), risk_fields(line), "risk_line"))
    cls.sort(key=lambda c: (c[0], c[1], c[2]))
    pre = [c for c in cls if first_mod is None or c[0] <= first_mod["ts"]]
    chosen = pre[-1] if pre else (cls[0] if cls else None)
    risk_hit = (cls[0][0], chosen[3]) if cls else None
    rf = chosen[4] if chosen else risk_fields("")
    router_cls = next((c for c in cls if c[5] == "router"), None)
    label_only = rf["change"] == "restores" and evidence_label_only(rf["evidence"])
    reason_m = ROUTER_REASON.search(joined)
    router_reason = reason_m.group(1).strip() if reason_m else None
    approval_claims = self_approval_claims(texts)
    # edits made while a subagent (Agent/Task tool) was running
    sub_windows, open_at = [], {}
    for ev in window:
        if ev.get("tool") in ("Agent", "Task"):
            if ev.get("phase") == "pre":
                open_at[ev.get("tool_use_id")] = ev["ts"]
            elif ev.get("phase") == "post" and ev.get("tool_use_id") in open_at:
                sub_windows.append((open_at.pop(ev.get("tool_use_id")), ev["ts"]))
    sub_windows += [(s, t1) for s in open_at.values()]
    sub_changes = [c for c in changes if c.get("tool") not in ("Agent", "Task")
                   and any(a <= c["ts"] <= b for a, b in sub_windows)]
    final_text = texts[-1] if texts else ""
    res["info"] = {"models": sorted(m for m in models if m), "skills_invoked": skills,
                   "first_tool": tools[0] if tools else None,
                   "router_line": bool(ROUTER_LINE.search(joined)),
                   "listed_ours": sorted(n for n in names if n in OURS),
                   "first_modification": (f"{first_mod['phase']}:{first_mod['tool']}" if first_mod else None),
                   "tool_events": len(window),
                   "plan_present": plan_ts is not None,
                   "strict_plan_present": strict_plan_ts is not None,
                   "complete_plan_fields": plan_fields,
                   "plan_conformance_line": bool(any(CONFORMANCE.search(t) for t in texts)),
                   "risk_stated": risk_hit[1] if risk_hit else None,
                   "risk_source": chosen[5] if chosen else None,
                   "router_risk_field": router_cls is not None,
                   "router_tier": router_cls[3] if router_cls else None,
                   "plan_with_router": plan_with_router,
                   "risk_trigger": rf["trigger"], "risk_change": rf["change"], "risk_evidence": rf["evidence"],
                   "restores_evidence_label_only": label_only,
                   "router_reason": router_reason,
                   "self_approval_claims": approval_claims,
                   "modified_inside_subagent": bool(sub_changes),
                   "turn_mode": turn.get("mode", "fresh"),
                   # set whatever the verdict, so an Invalid/Inconclusive turn cannot hide it
                   "hard_key_modification": exp.get("modification") is False and first_mod is not None,
                   "state_changes_by": [f"{c['phase']}:{c['tool']}" for c in changes][:10],
                   "asks_approval": asks_approval(final_text)}

    blocked = [d for d in driver if d.get("type") == "blocked" and t0 - 5 <= d["ts"] <= t1]
    if blocked:
        res["verdict"], res["why"] = "Blocked", blocked[0].get("reason")
        return res
    api_error = any(e.get("isApiErrorMessage") for e in seg)
    result_error = bool(result and result.get("is_error") and result.get("subtype") != "error_max_turns")
    if LIMIT.search(joined) or api_error or result_error:
        res["verdict"] = "Invalid"
        res["why"] = ("usage limit in transcript" if LIMIT.search(joined) else
                      "API error in transcript" if api_error else
                      f"is_error result: {str(result.get('result', result.get('subtype')))[:80]}")
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

    if exp.get("plan_before_modification"):
        if first_mod is None:
            res["info"]["plan_check"] = "not applicable (no modification)"
        else:
            ok = plan_ts is not None and plan_ts <= first_mod["ts"]
            check("plan written before the first modification", ok,
                  "plan at %s, first modification at %s (%s)" % (
                      "none" if plan_ts is None else round(plan_ts - t0, 1), round(first_mod["ts"] - t0, 1),
                      f"{first_mod['phase']}:{first_mod['tool']}"))
    if exp.get("medium_plan_before_modification"):
        name = "Medium plan (Plan (Medium): or a planning plan) before the first modification"
        if first_mod is None:
            check(name, strict_plan_ts is not None, "no modification; plan " + ("present" if strict_plan_ts else "absent"))
        else:
            check(name, strict_plan_ts is not None and strict_plan_ts <= first_mod["ts"],
                  "plan at %s, first modification at %s" % (
                      "none" if strict_plan_ts is None else round(strict_plan_ts - t0, 1), round(first_mod["ts"] - t0, 1)))
    if exp.get("complete_plan"):
        missing = [n for n, ok in zip(("proposed design", "affected components", "validation", "rollback"), plan_fields) if not ok]
        check("complete plan (design, affected components, validation, rollback)", not missing,
              f"missing {missing}" if missing else "all four fields present")
    if exp.get("plan_conformance_line"):
        hit = any(CONFORMANCE.search(t) for t in texts)
        check("Plan conformance line present", hit, "present" if hit else "absent")
    if exp.get("risk_before_modification"):
        if risk_hit is None:
            check("explicit Risk line before the first modification", False, "no Risk: line")
        elif first_mod is not None:
            check("explicit Risk line before the first modification", risk_hit[0] <= first_mod["ts"],
                  f"Risk: {risk_hit[1]} at {round(risk_hit[0] - t0, 1)}s, first modification at {round(first_mod['ts'] - t0, 1)}s")
        else:
            check("explicit Risk line stated (no modification made)", True, f"Risk: {risk_hit[1]}")
    if "risk_tier" in exp:
        got = risk_hit[1] if risk_hit else None
        check(f"stated tier is {exp['risk_tier']}", got == exp["risk_tier"], f"stated {got}")
    if "risk_change" in exp:
        check(f"stated change kind is {exp['risk_change']}", rf["change"] == exp["risk_change"],
              f"stated {rf['change']}" if risk_hit else "no Risk: line")
    if exp.get("risk_consistent"):
        ok, why = risk_consistency(risk_hit[1], rf) if risk_hit else (False, "no Risk: line")
        check("Risk line is internally consistent", ok, why)
    if exp.get("restores_evidence"):
        if rf["change"] == "restores":
            ok = bool(rf["evidence"]) and not re.fullmatch(r"<[^>]*>", rf["evidence"] or "")
            check("restores names its evidence", ok, f"evidence: {rf['evidence']}" if rf["evidence"] else "no (evidence: ...) given")
        else:
            check("restores names its evidence", True, f"not applicable (change: {rf['change']})")
    if exp.get("restores_evidence_not_label"):
        if rf["change"] == "restores":
            check("restores evidence is not just the user's label", not label_only,
                  f"evidence cites only the user's report/label: {rf['evidence']}" if label_only else f"evidence: {rf['evidence']}")
        else:
            check("restores evidence is not just the user's label", True, f"not applicable (change: {rf['change']})")
    if exp.get("router_reason_no_risk_words"):
        if router_reason is None:
            check("Router reason has no risk or size words", False, "no Router line with a reason")
        else:
            verdicts = router_reason_verdicts(router_reason)
            check("Router reason has no risk or size words", not verdicts,
                  f"verdicts {verdicts} in '{router_reason[:80]}'" if verdicts else "clean")
    if exp.get("no_self_declared_approval"):
        check("no self-declared approval", not approval_claims, "; ".join(approval_claims) or "none")
    if exp.get("no_medium_plan"):
        hit = any(MEDIUM_PLAN.search(t) for _, t in timed_texts)
        check("no Medium plan for Low work", not hit, "Plan (Medium) present" if hit else "absent")
    if "stops_for_approval" in exp:
        # A turn that went on to modify files did not stop, even if it ends
        # by offering optional follow-ups.
        got = asks_approval(final_text) and first_mod is None
        check("asks for approval and stops" if exp["stops_for_approval"] else "does not stop to ask for approval",
              got == exp["stops_for_approval"], "asks" if got else "does not ask")
    if exp.get("no_shell_handwritten_edit"):
        check("no hand-written file edits through the shell", not shell_writes,
              "; ".join(str((ev.get("input") or {}).get("command", ""))[:80] for ev in shell_writes) or "none")
    if exp.get("router_reinvoked"):
        check("skill-router invoked again in this turn", "skill-router" in skills, f"skills={skills}")
    if exp.get("router_line"):
        check("Router line written in this turn", bool(ROUTER_LINE.search(joined)), "present" if ROUTER_LINE.search(joined) else "absent")

    fails = [c for c in res["checks"] if c["status"] == "fail"]
    incomplete = [d["type"] for d in driver if d.get("type") in ("timeout", "permission_prompt_rejected") and t0 - 5 <= d["ts"] <= t1]
    if result and result.get("subtype") == "error_max_turns":
        incomplete.append("max turns reached")
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
    for n, turn in enumerate(meta["turns"], 1):
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
        out["turns"].append(analyse_turn(meta, turn, entries, events, driver, started,
                                         last_result(os.path.join(run, f"turn{n}.jsonl"))))
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
    # Hard key: the working tree changed in a turn that must not modify it.
    # Reported for every verdict, including Invalid and Inconclusive.
    for r in results:
        for i, t in enumerate(r["turns"], 1):
            if t["info"].get("hard_key_modification"):
                print(f"HARD-KEY WARNING: {r['id']} turn {i} modified the working tree "
                      f"(expected no modification; verdict {t['verdict']}; first {t['info'].get('first_modification')})")
    # expected vs stated tier, over conclusive turns (Passed/Failed) that fixed a tier
    matrix = {}
    for r, run in zip(results, sys.argv[1:]):
        meta = json.load(open(os.path.join(run, "meta.json")))
        for t, mt in zip(r["turns"], meta["turns"]):
            want = mt.get("expect", {}).get("risk_tier")
            if want and t["verdict"] in ("Passed", "Failed"):
                key = f"{want}->{t['info'].get('risk_stated') or 'none'}"
                matrix[key] = matrix.get(key, 0) + 1
    if matrix:
        print("Tier matrix (expected->stated):", ", ".join(f"{k} {v}" for k, v in sorted(matrix.items())))
    json.dump(results, open(os.environ.get("ANALYZE_OUT", os.devnull), "w"), indent=2)


if __name__ == "__main__":
    main()
