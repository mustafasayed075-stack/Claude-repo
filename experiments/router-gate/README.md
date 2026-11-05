# EXPERIMENT: router decision gate (not installed anywhere)

A PreToolUse/PostToolUse hook that enforces one invariant: after
`skill-router` is loaded, no other skill and no file-edit tool runs until
the Router decision has been recorded. Sandbox-only. The real
`~/.claude/settings.json`, the repo skills and `scripts/install.sh` are
unchanged.

## What Claude Code gives a hook (verified on 2.1.314)

The PreToolUse payload has `session_id`, `transcript_path`, `cwd`,
`prompt_id`, `permission_mode`, `effort`, `hook_event_name`, `tool_name`,
`tool_use_id`, and `tool_input` (PostToolUse adds `duration_ms`).
Skill loads arrive as `tool_name: "Skill"` with `tool_input.skill`. **No
assistant text is included**, so whether a free-text `Router:` line was
written can only be guessed by parsing the transcript, whose format is
undocumented. The probe in `live_test.sh` shows why that fails: the text
`Router:` is in the transcript as soon as the router skill's body loads,
so a grep sees it even when no line was written.

Headless sessions here all reported the same `session_id` (inherited), so
gate state is keyed on `session_id + transcript_path`.

## Design

- The decision is recorded as a structured, inert tool call:
  `echo 'Router: <type> | <task> | skills: <list or none> - <reason>'`
  (single-quoted, nothing else on the line).
- PostToolUse `Skill(skill-router)` -> PENDING. PostToolUse of that exact
  echo -> DECIDED. The gate auto-allows the echo (PreToolUse `allow`).
- PreToolUse on Skill (other than the router), Edit, Write, NotebookEdit or
  MultiEdit while PENDING -> `deny`, with the exact command to run.
- It never classifies tasks or chooses skills. Reads and ordinary Bash are
  not blocked. Malformed input or an internal error -> exit 0 with no
  output (fail open).
- `settings_merge.py` appends or removes only its own entries,
  recognised by script name.

## Files

| File | Purpose |
|---|---|
| `esk-router-gate.py` | the hook (`pre` / `post`) |
| `settings_merge.py` | sandbox install/uninstall into a settings.json |
| `test_gate.sh` | deterministic tests (synthetic payloads in the captured shape) |
| `live_test.sh` | live sessions in disposable HOMEs |

## Results (2026-10-31)

- `test_gate.sh`: 38/38. Mutations are caught: no-deny 13 failures,
  unanchored decision regex 4.
- `live_test.sh 3`:
  - Arm A (unchanged prompt Router + gate): the gate blocked 1-2 times per
    run (5 blocks in total). Each time the model recorded the echo and
    retried. 0 protected tools ran while pending, 3/3 recovered. All 3 runs
    had also written the text line, so these blocks are false positives
    with respect to the old contract.
  - Arm B (sandbox Router variant that records the echo): 3/3 recorded,
    0 blocks.
  - Non-engineering question and a trivial edit: router not loaded,
    0 blocks, and the edit went through.
  - Unrelated user hooks stayed first and unchanged, and kept firing
    (audit and Stop logs written).

## Limits

- It enforces that a decision was *recorded*, not that it is correct.
- It only acts after `skill-router` is loaded. Whether the router is
  loaded at all is still up to the model.
- Bash file writes, subagents and other tools are not gated.
- It needs the Router contract to change (record via echo), and it relies
  on a hook auto-allowing that one inert command.
- Fails open: if the hook errors, nothing is enforced.
- Headless sessions only. Interactive permission UX was not tested.
