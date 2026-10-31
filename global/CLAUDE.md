# Engineering Skill System

When a request involves writing, changing, designing, testing, or releasing
code in a repository:
1. Before the first edit, use the `skill-router` skill to identify the project
   type, the task type, and which skills to load, and write its one-line
   `Router:` decision before loading any other skill. Skip it for questions,
   explanations, and trivial one-line edits.
2. Follow `engineering-standards` while implementing, and check its
   Definition of Done before reporting completion.
3. Risk, scope and planning are defined in engineering-standards ("Risk,
   scope and planning"). Before the first modification, whatever tool makes
   it, state `Risk: Low|Medium|High`; Medium then writes `Plan (Medium):`,
   and `architecture-planning` is used whenever that section requires it.
4. High-risk changes (engineering-standards section 7): present the plan and
   wait for explicit approval before editing. Asking for the change is not
   approval of the plan. After approval, stay within the approved plan; a
   material deviation needs approval of the revised plan.

Project-level CLAUDE.md and explicit user instructions take precedence.
