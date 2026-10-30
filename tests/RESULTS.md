# Test results

## Final Review Fixes - 2026-10-30

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
