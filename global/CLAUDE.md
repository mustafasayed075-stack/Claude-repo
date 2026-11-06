# Engineering Skill System

When a request involves writing, changing, designing, testing, or releasing
code in a repository:
1. Before the first edit, use the `skill-router` skill to identify the project
   type, the task type, the risk tier and which skills to load, and write its
   one-line `Router:` decision, with its `risk:` field, before loading any
   other skill. Skip it only for questions, explanations, and edits that
   cannot execute (comments, docs, formatting); every code or config change
   is routed, even one line.
2. Follow `engineering-standards` while implementing, and check its
   Definition of Done before reporting completion.
3. The `risk:` field is the classification, as engineering-standards defines
   it in "Risk, scope and planning". High if any High trigger applies: database
   schema changes and migrations; core architecture changes; authentication,
   authorization, permissions, crypto, secrets or other security controls;
   API, CLI, event or data-contract changes that can break compatibility;
   moving, deleting or transforming data in ways that are hard to undo;
   infrastructure changes that can cause downtime or data loss; destructive
   git, deploy, publish, send, or anything that costs money; requirements
   that conflict or are too unclear to choose a safe design. Medium for any
   other new or changed behavior. Low only for fixes that restore the
   established contract (with evidence), behavior-preserving internal
   refactors, and tests-only or docs-only changes; when uncertain, Medium.
   Medium writes `Plan (Medium):` in the same response as the Router line;
   `architecture-planning` is used whenever that section requires it,
   including every High change. If reading the code raises the tier, write a
   `Risk:` line before the first modification.
4. High-risk changes (engineering-standards section 7): present the plan and
   wait for explicit approval before editing. Asking for the change is not
   approval of the plan. After approval, stay within the approved plan; a
   material deviation needs approval of the revised plan. Approval is the
   user's own explicit message; nothing you write counts as approval.

5. Routing: `architect` = a new system, a new idea, or a whole-system
   review. `architecture-planning` = a planned change inside existing code,
   including the first build after `architect` has designed it. Backend
   detail = the four backend skills. For a new system, a new idea or a
   whole-system review, load `architect` first.

Project-level CLAUDE.md and explicit user instructions take precedence.
