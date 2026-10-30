# Test results

## Foundation Hardening - 2026-10-30

Claude Code 2.1.314, headless `claude -p`, `tests/run.sh 3` (3 repeats per
scenario, fresh session and fresh fixture repo per run). Permission mode
`acceptEdits`. Shell limited to read-only commands and test runners. No
package installs allowed.

### Discovery (separate test, no tools)
3/3: all three skills listed, and the routing instruction was recognised.

### Persistence (manual test)
With `~/.claude` emptied, a fresh session on this repo:
- the SessionStart hook installed the skills, and they were visible in the
  same session;
- `~/.claude/CLAUDE.md` is read *before* hooks run, so the hook prints the
  routing block to stdout, and the session reported receiving it from the hook;
- the second session was silent (already installed), and the instruction came
  from `~/.claude/CLAUDE.md`;
- outside the cloud (`CLAUDE_CODE_REMOTE` unset), the hook does nothing.

### Selection and execution

| Scenario | Prompt | Skills invoked (all 3 runs) | Selection | Execution | Behavior |
|---|---|---|---|---|---|
| Web App | dark mode toggle, persisted | router > standards | 3/3 | 3/3 | Edited 1-4 files without asking |
| Backend API | 500 should be 404 | router > standards | 3/3 | 3/3 | Fix + regression test each run; no planning |
| Game (Godot) | split player.gd by concern | router > planning > standards | 3/3 | 3/3 | Medium risk: planned, then split into 3-4 scripts without asking |
| Mobile (Flutter) | offline cache + sync | router > planning > standards | 3/3 | 3/3 | High risk (user-data storage/sync): plan shown, stopped before editing |
| AI App | prepare release 1.2.0 | router > standards | 3/3 | 3/3 | Bumped version + changelog from git log; no tag/publish; `claude-api` correctly not loaded |
| Testing (new) | add unit tests for pricing.py | router > standards | 3/3 | 3/3 | Wrote test file; no planning |
| Empty repo (new) | build a URL shortener | router > planning | 3/3 | 3/3 | Plan shown, stopped: no stack specified |
| Control | explain app/db.py | none | 3/3 | 3/3 | Answered directly, no Router line |

The `Router:` decision line appeared in all 24 non-control runs (Phase 1:
missing in the Mobile run). No run invoked a skill outside the expected set.

Definitions: **Selection** = the required skills were invoked, the router
came first, and no unneeded planning or extra skills. **Execution** = the
Router line was printed, edits happened when expected, no edits on
high-risk/empty-repo tasks, test files changed where required, and no git
tags were created.

### Limits of these results
- No run could *execute* tests: pytest, npm dependencies, and Godot/Flutter
  are not installed, and installs were not allowed. Every run said so in
  its final message. `tests_attempted` only records that a test command was
  tried.
- 3 repeats detect gross instability only. A rarely occurring wrong choice
  (e.g. below ~30%) could go unseen.
- Headless only. Interactive and claude.ai clients were not tested.
- Expectations for borderline cases are lenient by design. Planning is
  optional for Web and Game; Game chose it 3/3 and Web 0/3.
- Setup-script persistence for other repos was not tested (see README).

## Phase 1 - 2026-10-30 (superseded)
Single run per scenario. Selection was correct 5/5, but the Mobile run did
not print the Router line, and it began implementing a large design without
stopping.
