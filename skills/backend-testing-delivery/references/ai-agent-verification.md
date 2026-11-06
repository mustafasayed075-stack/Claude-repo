# AI-agent verification (backend-testing-delivery)

Status tags: V verified against a primary source; P partly verified or
secondary; T judgement or author knowledge. Word P and T items as "verify in
your environment". Sources: `../SOURCES.md`.

The person reading your report often cannot judge the code. The report must
therefore show what was actually run and what was not. The routine itself is
printed in `../SKILL.md`; this file adds the reasons, the reporting format and
the failure modes, with examples.

## Contents
1. The routine, step by step
2. What to report
3. Failure modes of AI-written tests
4. Examples
5. Weakening a test: when and how to ask

## 1. The routine, step by step
1. Bug fix: write a reproducing test first, confirm it fails for the right
   reason (the assertion about the bug, not an import error or a typo), then
   fix and confirm it passes. (T)
2. New behaviour: break or revert the implementation and confirm the new tests
   fail. A test that never failed proves nothing. (T)
3. Run the full relevant suite (unit, integration, migrations, linters, type
   checker), not only the new test. (T)
4. Never delete, skip, `xfail`, loosen assertions, raise timeouts, add retries
   or regenerate snapshots to get green without stating it and getting
   approval. Weakening any test, alert, scanner or check is a High trigger in
   this skill. (T)
5. Report what was and was not verified, and the assumptions. (T)

## 2. What to report
- The exact commands run and their results (pass and fail counts), copied from
  the output, not paraphrased. (T)
- The failing-first evidence: the test name and the failure message before the
  fix. (T)
- A "Not verified" list, for example: not run against the gateway sandbox;
  migration not timed on large data; not tested with several instances; no
  load or failure injection. (T)
- Assumptions you made where the requirement was ambiguous. (T)

## 3. Failure modes of AI-written tests
These tests pass and prove nothing. (T)
- Tautological tests that assert a mock returns what it was told to return.
- Re-implementing the production logic inside the test and comparing the two.
- Mocking the repository in a test of SQL.
- Asserting call order or private methods instead of outcomes.
- Mass-regenerated snapshots.
- Happy path only.
- Swallowed exceptions (a `try/except: pass` around the code under test).
- Wall-clock or test-order dependence.
- Editing the test when it disagrees with the code, instead of finding out
  which one is wrong.

## 4. Examples
Illustrations of the failure modes above, not facts from a source. (T)
- Tautology: the test stubs `pricing.total()` to return 100, calls code that
  returns `pricing.total()`, and asserts 100. Nothing real ran. Instead, call
  the real `total()` with inputs whose answer you worked out by hand.
- Re-implementation: the test computes the expected discount with the same
  formula as the code. A wrong formula passes. Instead, use known values (for
  example a documented example from the spec or a test vector).
- Mocked SQL: a test of "list my orders" mocks the repository and asserts it was
  called with `tenant_id`. The query could still return another tenant's rows.
  Instead, run it against a real Postgres with two tenants
  (`testing-strategy.md`).
- Order dependence: a test passes only because an earlier test inserted a row.
  Run with randomized order to expose it.

## 5. Weakening a test: when and how to ask
- If a test really must change (the requirement changed, or the test was
  wrong), say so before editing it: which test, why, what the new assertion
  checks, and what would have caught the old behaviour. Ask for approval
  (High). (T)
- Never quarantine a security, money or data-integrity test without approval
  (`testing-strategy.md`). (T)
