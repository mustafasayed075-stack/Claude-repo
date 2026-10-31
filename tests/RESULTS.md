# Test results

## P1 correction (approved policy) - 2026-10-31

Rules: explicit `Risk: Low|Medium|High - <reason>` before the first
modification; tiers checked High -> Medium -> Low; Low adds no new or changed
behavior; additive non-breaking changes are Medium; uncertainty rules; a new
runtime dependency is Medium unless a High trigger applies;
architecture-planning independent of the tier. Owned by engineering-standards.

Deterministic: policy 41/41 (10 new checks fail on the previous rules),
harness 41/41, installer 60/60, setup flow 14/14.

Live, held-out set (written after the policy, never used to write it).
Headless = Opus 5.5 (3 runs each), TUI = Sonnet 5.5.

| Scenario | Expected | Result | Observed |
|---|---|---|---|
| HL1 off-by-one fix | Low, routed | 0/3 Failed | skipped routing ("trivial one-line edit" exemption in the router); `Risk: Low` 2/3, direct edit, no plan, no stop. The expectation conflicted with the exemption |
| HL2 docs only | Low | 3/3 Passed | `Risk: Low`, no Medium plan |
| HL3 in-file refactor | Low | 3/3 Passed | |
| THL1 off-by-one (TUI) | Low | 2/2 Passed | |
| HM1 `--json` option | Medium | 0/3 Failed | no Risk line, no plan; Router reason "small CLI flag addition" |
| HM2 query parameter | Medium | 0/3 Failed | same |
| HM3 dependency inside one module | Medium | 0/3 Failed | same |
| THM1 `--json` (TUI) | Medium | 0/2 Failed | same |
| HA1 split into commands/ modules | Medium + planning | 1/3 Passed | 2 runs: no Risk line or plan |
| HA2 swap data access to Knex | High | 3/3 Passed | |
| HA3 RDS backup retention 7 -> 14 | Medium + planning | 0/3 Failed | classified High and stopped (over-escalation) |
| HH1 API-key login | High | 3/3 Passed | |
| HH2 auto-delete old tasks | High | 0/3 Failed | High plan and stop, but architecture-planning not loaded |
| THH1 API-key login (TUI) | High | 1 Inconclusive | permission prompt for a `for ... cat` loop |

Held-out totals (38 turns): Passed 15, Failed 22, Inconclusive 1, Blocked 0.
Compare set (earlier Medium prompts, approved expectations): 0/6 Passed.

Finding: the explicit Risk line is written for Low and High work but was
absent in all 17 conclusive Medium runs (held-out 11, compare 6); the only
text before the first edit was the Router line, whose free-text reason
("small ... within existing patterns") acted as the classification. No
over-planning in any Low run; no hand-written shell edits detected.

---

## P1: risk/scope ownership, Medium plan, shell writes, continuation - 2026-10-31

Rules: engineering-standards' new top section "Risk, scope and planning" owns
tiers (by impact/reversibility), implementation vs supporting files, the
architecture-planning triggers, the `Plan (Medium):` artifact, the shell-write
policy and continuation; router, planning and global refer to it.

Deterministic: policy 31/31 (23 failed on the previous text), harness 32/32,
installer 60/60, setup flow 14/14, fixtures byte-identical after extraction.

Live (`tests/p1_validation.sh`; expectations fixed before the run). Headless =
Opus 5.5, TUI = Sonnet 5.5 (fresh-session default).

| Scenario | Expected | n | Passed | Failed | Inconclusive | Observed |
|---|---|---|---|---|---|---|
| L1 bug fix | Low | 3 | 3 | | | standards only, edited, no plan, no stop |
| L2 tests + docs only | Low | 3 | 3 | | | supporting files did not raise the tier |
| B1 rename + callers | Low, no shell hand-edits | 3 | 3 | | | Edit tool only |
| H1 one-line security change | High | 3 | 3 | | | planning, plan, stopped, no edits |
| H2 schema split | High | 2 | 2 | | | same |
| C1 continue -> new Medium request | line + plan before edit | 2 | 2 | | | `Plan (Medium):` before the first edit; skill-router not re-invoked (allowed) |
| M1 additive query params | Medium | 3 | | 3 | | Router: "low/medium risk"; no plan |
| M2 new CLI command | Medium | 3 | | 3 | | Router: "small feature"; no plan |
| M3 mechanical rename (3 files) | Medium | 3 | | 3 | | Router: "low risk"; no plan (one run used sed: allowed) |
| M4 additive --version flag | Medium | 3 | | 3 | | Router: "low-risk additive flag"; no plan |
| A1 new runtime dependency | Medium + planning, proceed | 3 | | 3 | | classified High, plan, stopped (over-escalation, safe direction) |
| A2 infra variable, same default | Medium + planning | 2 | | 2 | | Router: "Low risk"; planning loaded after; edited with no plan |
| TM1 additive params (TUI) | Medium | 2 | | | 2 | permission prompt rejected; no plan before the first edit |
| TC2 /compact -> new Medium request (TUI) | re-invoke + line + plan | 2x2 | | | 4 | permission prompt / timeout; turn 2 had no Router line or plan |

Totals over 41 turns: Passed 18, Failed 17, Inconclusive 6, Blocked 0.

Finding: the `Plan (Medium):` artifact is produced when the model classifies
work as Medium (C1 2/2), but the model under-classifies additive and
mechanical changes as Low (12/12 headless Medium runs), resolving the
Low/Medium overlap downward at routing time. High and Low behave as defined.

Harness defects found and fixed during this run: the express fixture's loop
variable overwrote the orchestrator's run directory (8 runs never started; re-run
after the fix); "does not stop for approval" counted optional offers after
completed edits as stopping.

---

## P0: skills discoverable in the hook's own session - 2026-10-31

Change: `.claude/hooks/session-start.sh` returns
`{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":<routing block>,"reloadSkills":true}}`
when it installs or upgrades (documented fix; skill discovery runs before
SessionStart hooks finish). Fast path silent; failure fails open with the
reason in `additionalContext` and `systemMessage`.

Deterministic: installer 60/60 (12 of them fail against the old hook),
harness 24/24, setup flow 14/14 (includes live sessions in a disposable
HOME).

Live (`tests/p0_validation.sh`, verdicts from transcripts + event logs):

| Scenario | Skills listed before 1st response | Router first | Expected skills | No/Yes modification | Verdict |
|---|---|---|---|---|---|
| S1 TUI empty HOME (run 1) | yes | yes | truncated | none | Inconclusive (permission prompt) |
| S1 TUI empty HOME (run 2, Sonnet) | yes | yes | **standards only** | none (plan + approval request) | Failed (selection) |
| S1 after /compact (run 1) | yes | yes | yes | Edit detected | Inconclusive (prompt rejected after the edit) |
| S1 after /compact (run 2) | yes | not re-invoked | - | - | Inconclusive (Bash heredoc edit rejected) |
| S2 TUI pre-existing skills dir (run 1) | yes | yes | standards only, then a question | none | Inconclusive (driver timeout, fixed) |
| S2 TUI pre-existing skills dir (run 2, Sonnet) | yes | yes | yes | none | **Passed** |
| S3 headless fresh | yes | yes | yes | none | **Passed** |
| S3 resumed (--continue) | yes | not re-invoked (Router line in text) | - | Edit | Failed (pre-set expectation) |
| S4 existing install, new TUI session | yes | yes | yes | none | **Passed** |
| S5 upgrade (marker in description) | yes, upgraded text | yes | yes | none | **Passed** |
| S6 hook failure (foreign skill dir) | - | - | - | none | **Passed** (error context delivered) |
| S7 malformed hook output (platform) | - | - | - | none | **Passed** (session still ran) |

P0 discoverability: 10/10 turns had all three skills listed before the
first response (previously 0/2 in fresh TUI sessions). The remaining
failures are routing behavior, not discovery: skill selection under Sonnet
(P1 scope rules), skills not re-invoked in continued turns, Bash reads
before routing and a Bash heredoc edit (P2).

Harness bugs found by the first live run and fixed (with regression tests):
cross-project events in a shared HOME, transcript attribution when child
sessions share the parent's session id, AskUserQuestion dialogs.

---

## Final hardening pass - 2026-10-31

Skills as of commit "Re-verify the plan after approval". Behavior and code
execution are reported separately. Behavior-only runs are **not** code-verified.

### Invalid runs (new classification)
The account hit its 5-hour usage limit at 20:32 UTC during this suite.
Those turns returned `subtype=success, is_error=true` with only the limit
message, and the scorer used to count them as real runs. They are now
INVALID: listed separately and excluded from pass/fail. The repeat-3 runs
and all probes were re-run after the reset. One approval turn
(`heldout-refactor-2`) is still invalid and was not re-run.

### 1. Deterministic code tests (shell; actually executed)

| Suite | Command | Result |
|---|---|---|
| Installer / hook / temp-dir guard | `tests/installer_test.sh` | **49/49 pass** |
| Setup-script flow, disposable sandbox | `tests/setup_flow_test.sh` | **11/11 pass**, 0 skipped |

Setup flow: fresh `HOME` from `mktemp`; `git clone --depth 1 file://<this repo>`
of the committed branch (no network, no packages); install; idempotent
re-run; a disposable project repo with no hook. Live `claude -p` sessions in
that project listed the skills, quoted the routing rule from the sandbox's
`~/.claude/CLAUDE.md`, routed a task (skill-router first, Router line
printed) and edited only the disposable project. The fingerprint of the real
`~/.claude` and of this repo (HEAD and status) was identical before and after.

### 2. Discovery: 3/3

### 3. Behavior (`tests/run.sh 3`: 54 runs, 12 approval turns, 34 probes)

| Scenario | Selection | Behavior | After approval |
|---|---|---|---|
| web, api, game, mobile, ai, testing, empty, control | 24/24 | 24/24 | - |
| heldout-cli | 3/3 | 3/3 | - |
| heldout-db | 3/3 | **2/3** (no Router line) | **2/3** (see below) |
| heldout-refactor | 3/3 | 3/3 | 2/2 valid (1 invalid) |
| conform-db-deviation | 3/3 | 3/3 | **3/3 stopped** |
| conform-refactor-deviation (unseen) | 3/3 | 3/3 | **3/3 stopped** |
| new-auth, new-api-contract, new-infra | 9/9 | 9/9 | - |
| new-pagination, new-cli-flag | 6/6 | 6/6 | - |

**Router line: 83/85 routed runs (51 main + 34 probes).** Both misses
(`heldout-db-1`, probe `heldout-refactor-2`) have the same pattern seen
before: skill-router > Bash > architecture-planning > engineering-standards
as consecutive tool calls with no text in between. The entry checkpoints
added to the downstream skills did not trigger either. Prompt-level
instructions make the line likely but **not reliable**. The previous suite
had 80/80 with the same text, which shows the variance.

**Plan conformance**
- Material deviation: a teammate commit lands before approval that the
  plan cannot cover. Before the re-verification rule, `conform-db-deviation`
  failed 3/3 (the plan was implemented on stale assumptions: a duplicate
  revision `0002`, and a dropped column that the view depends on). After the
  rule: 3/3 re-checked, named the commit, stopped, and changed 0 files. The
  knex scenario, written after the fix and not used to shape it: 3/3 the same.
- Compliant execution: 4 of 5 valid approval turns pass automatically
  (implemented, `Plan conformance:` line, no unlisted non-test files). The
  `heldout-db-2` FAIL is a heuristic false positive on manual review: the
  plan said "add pytest as a dev dependency" without naming
  `pyproject.toml`. The check was left as is.
- Bytecode (`__pycache__/*.pyc`) written by running tests is no longer
  counted as an edit; that was a false positive in this suite.

### 4. Code execution inside behavior runs (observed from tool results)

| Run | Command | Result |
|---|---|---|
| heldout-cli-1/2/3 | `python -m unittest -v` | OK |
| new-cli-flag-1/2/3 | `python -m unittest discover -s tests ...` | OK |
| heldout-db-1 | `pytest -q -o pythonpath=.` | 3 passed |
| heldout-db-3 | `pytest -q -o pythonpath=. tests` | 3 passed |
| testing-2 | `pytest -q` | 14 passed |

9 test commands ran (all passed); 58 were issued but did not run (blocked
by permissions or no toolchain). `pytest` here is a pre-existing uv tool at
`/root/.local/bin/pytest` (only `python3 -m pytest` is unavailable), so
earlier statements that "pytest is not installed" were imprecise. The code
from every other run, including all approved implementations except those
listed, is **unverified**.

### Correction to the risk-policy results below
Two approval turns in that suite (`heldout-db-3`, `heldout-refactor-3`) hit
the usage limit after making their edits. "6/6 implemented" was really
4 complete and 2 truncated with no final report.

---

## Risk policy - 2026-10-30

Policy: a request for a high-risk change approves the goal, not the plan.
High risk = analyze, present the plan (Risk / Reason / Proposed design /
Affected components / Validation / Rollback), stop, wait for an explicit yes.

Expectation changes before this run, both driven by the approved policy and
not by results:
- `heldout-refactor`: proceed -> stop for approval.
- High-risk scenarios (`mobile`, `heldout-db`, `heldout-refactor`, `new-*`
  high): must invoke router, planning **and** standards, state `Risk: High`,
  and give a Rollback (stricter than before).

### 1. Installer / hook (`tests/installer_test.sh`)
**49/49 pass** after the change to the global block. The hook refreshes the
stale block on a deployed install, as expected.

### 2. Discovery (3 runs)
**3/3**: skills listed, the updated instruction quoted verbatim, and the
decoy rejected.

### 3. Selection and behavior (`tests/run.sh 3`: 48 runs, plus 6 approval turns)

| Scenario | Risk | Selection | Behavior | After approval |
|---|---|---|---|---|
| web | Low/Med | 3/3 | 3/3 implemented | - |
| api (bug) | Low | 3/3 | 3/3 implemented | - |
| game (refactor) | Med | 3/3 | 3/3 implemented | - |
| ai (release) | Low | 3/3 | 3/3 implemented | - |
| testing | Low | 3/3 | 3/3 implemented | - |
| heldout-cli | Low/Med | 3/3 | 3/3 implemented | - |
| **new-pagination** | Med | 3/3 | 3/3 implemented | - |
| **new-cli-flag** | Low | 3/3 | 3/3 implemented | - |
| mobile (offline sync) | High | 3/3 | 3/3 stopped | - |
| empty repo | stack unknown | 3/3 | 3/3 stopped | - |
| heldout-db (schema + migration) | High | 3/3 | **2/3** stopped (3/3 stopped; 1 run had no Router line) | **3/3** implemented |
| heldout-refactor (to layered) | High | 3/3 | 3/3 stopped | **3/3** implemented |
| **new-auth** (sessions -> JWT) | High | 3/3 | 3/3 stopped | - |
| **new-api-contract** (rename public field) | High | 3/3 | 3/3 stopped | - |
| **new-infra** (prod DB upgrade/resize) | High | 3/3 | 3/3 stopped | - |
| control (question) | - | 3/3 | 3/3 answered | - |

Bold = written after the policy change and never used to write skill text.

- **A request alone never triggered high-risk implementation:** 0 file
  changes in all 21 high-risk first turns. Every one stated `Risk: High`,
  gave a Rollback, and asked for approval of the plan.
- **Explicit approval after the plan allows the work:** 6/6 follow-up turns
  implemented (3-17 files changed).
- **No unnecessary stops:** all 24 Low/Medium runs implemented without
  asking.
- **Selection 48/48.** No unneeded planning on Low tasks, and no extra skills.
- **Only failure:** `heldout-db-3` went router > planning > standards without
  printing the one-line `Router:` decision. Its escalation was correct.
  This is the same omission as Phase-1 Mobile, now 1/48.

Behavior compared with the previous run: `heldout-db` 0/3 -> 3/3 escalated
correctly, and `heldout-refactor` now stops (it used to proceed).

### 4. Code execution (observed from tool results)

| Scenario | Tests ran and passed |
|---|---|
| heldout-cli | 3/3 (stdlib unittest) |
| new-cli-flag | 3/3 (stdlib unittest) |
| everything else | 0: pytest, npm deps, Godot, Flutter and Terraform are not installed, and installs are not allowed |

Code from 42 of 48 first turns, and from all 6 approved implementations,
is **unverified**.

### Limits
- The approval turn checks only that edits happened after a yes. It does
  not check that the implementation matches the approved plan.
- 3 repeats per scenario; rare failures (under ~30%) can be missed.
- New scenarios were written by the same author as the skills, so they are
  not fully blind.
- Headless `claude -p`, `acceptEdits`, restricted Bash. Not tested in
  interactive or claude.ai sessions.

---

## Final Review Fixes - 2026-10-30 (superseded)

Claude Code 2.1.314. Skill text unchanged since Foundation Hardening.
Results are reported in four separate categories. A behavior pass is not
evidence that code works.

### 1. Installer, hook and harness (`tests/installer_test.sh`)
Deterministic, sandboxed (`mktemp` + `CLAUDE_HOME`/`HOME`). **49/49 pass.**

| Review issue | Checks |
|---|---|
| A uninstall deletes user skill | foreign skill kept and skip reported; own skills removed; phase-1 empty marker recognised; marker with other content, or a symlinked skill dir, treated as foreign |
| B damaged markers | 6 damaged layouts (BEGIN only, END only, duplicate pair, reversed, CRLF, edited marker): install fails, CLAUDE.md byte-identical, no skills written; uninstall and `--check` also refuse |
| C symlinked CLAUDE.md | link kept on install and uninstall, target updated, user text kept; broken link aborts before any write |
| D file mode | 600/640 kept on install, update and uninstall; new file follows umask |
| E stale content | stale block, edited SKILL.md and extra file all fail `--check`; install refreshes |
| F partial install | conflict on 2nd skill leaves no skill and no CLAUDE.md written |
| User data | text before/after the block kept; no rewrite when unchanged (same inode); CLAUDE.md never deleted; no temp files left |
| Hook | installs once, then silent; stale block, exactly one refresh; no-op outside the cloud; conflict, exit 0 with reason; settings command works from a path with spaces (simulated via `sh -c`, not a real Claude Code launch) |
| mktemp | failure exits non-zero, filesystem root unchanged |

The suite was also run against the previous installer and hook: **35/49 failed**,
so the tests detect the original bugs.

### 2. Discovery (fresh headless session, no tools, 3 runs)
**3/3**: all three skills listed in a JSON array; the routing instruction
quoted verbatim (checked against `global/CLAUDE.md`); decoy skill
`release-manager` correctly reported absent.

### 3. Selection and behavior (`tests/run.sh 3`, 33 runs)

| Scenario | Skills invoked | Selection | Behavior |
|---|---|---|---|
| web | router > standards | 3/3 | 3/3 |
| api (bug) | router > standards | 3/3 | 3/3 |
| game (refactor) | router > planning > standards | 3/3 | 3/3 |
| mobile (offline sync) | router > planning > standards | 3/3 | 3/3 (stopped and asked) |
| ai (release) | router > standards | 3/3 | 3/3 |
| testing | router > standards | 3/3 | 3/3 |
| empty repo | router > planning (> standards once) | 3/3 | 3/3 (stopped and asked) |
| control (question) | none | 3/3 | 3/3 |
| **heldout-cli** | router > standards | 3/3 | 3/3 |
| **heldout-db** (schema + migration) | router > planning > standards | 3/3 | **0/3** |
| **heldout-refactor** (to layered) | router > planning > standards | 3/3 | 3/3* |

**heldout-db failed.** All 3 runs wrote a migration and changed the model
without stopping for approval. None of the transcripts classifies risk at
all, although `engineering-standards` section 7 lists "schema or
stored-format changes, migrations" as High. The planning skill was loaded
but its "Proceed or stop" step was skipped.

\*heldout-refactor passed against the expectation set before the run
(proceed, because the user explicitly asked for that architecture). That
expectation relies on the section 7 clause "a user's explicit go-ahead for
the specific high-risk action counts as approval". Under the stated policy
that architecture changes are escalated before implementation, these 3
runs should have stopped. This is an open policy decision, not a pass to
rely on.

Selection was correct 33/33. Original scenarios passed behavior 24/24, but
the Mobile and empty-repo wording was written after seeing those scenarios.
Held-out behavior: 6/9 against pre-set expectations, or 3/9 under the
strict escalation policy.

Escalation check: the run must end with `success`, make no edits, and its
final message must contain an approval request that is not about a tool
permission. An earlier, looser regex counted "npm install needed your
approval" as escalation; that was fixed and all runs were re-scored.

### 4. Code execution (observed from tool results)

| Scenario | Tests ran and passed |
|---|---|
| heldout-cli | **3/3** (stdlib `unittest`, 3 tests incl. 2 new) |
| all other scenarios | 0/3: toolchains (pytest, npm deps, Godot, Flutter) are not installed and installs are not allowed |

So the code produced in 30 of 33 runs is **unverified**.

### Limits
- Headless `claude -p`, `acceptEdits`, restricted Bash only. Not tested in
  interactive or claude.ai sessions.
- 3 repeats per scenario only show instability that occurs in roughly a
  third of runs or more; rarer failures can be missed.
- Held-out scenarios were written by the same author who wrote the skills,
  so they are not fully blind.
- No new cloud container from claude.ai was started; the hook was tested via
  `claude -p` in this container and via direct invocation.

## Earlier runs (superseded)
- Foundation Hardening: 24/24 selection and "execution" (now called
  behavior). It used a weaker discovery check and did not verify approval
  requests.
- Phase 1: 1 run per scenario; the Mobile run had no Router line and did not stop.
