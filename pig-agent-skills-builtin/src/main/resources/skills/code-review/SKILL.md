# Code Review

A method for reviewing a code change for correctness, security, and maintainability before it is
merged.

## When to use

Use this skill right after writing or modifying code, before committing to a shared branch, or when
asked to review a diff or pull request. Reach for the `security-review` skill in addition to this one
whenever the change touches authentication, user input, secrets, the filesystem, or external calls.

## Method

1. **Understand the change first.** Read the diff end to end and restate, in one sentence, what it is
   supposed to do. If you cannot, you are not ready to review — ask or investigate until you can.
2. **Scope the blast radius.** List the callers and data that the changed code touches. A change is
   only as safe as everything it can affect.
3. **Review against the checklist below**, highest-severity concerns first (security, then
   correctness, then maintainability, then style).
4. **Classify each finding** by severity and state it with a concrete fix, not a vague complaint:
   - `CRITICAL` — security hole or data-loss risk. Block the merge.
   - `HIGH` — a real bug or significant quality problem. Should fix before merge.
   - `MEDIUM` — maintainability concern. Fix when reasonable.
   - `LOW` — style or minor suggestion. Optional.
5. **Run the tests and check coverage** for the changed lines. Missing tests for new behaviour is a
   `HIGH` finding on its own.
6. **Decide:** approve (no CRITICAL/HIGH), approve-with-warning (only HIGH remaining), or block
   (any CRITICAL).

## Checklist

- [ ] The change does what its description says, and only that (no unrelated drift).
- [ ] Edge cases and error paths are handled explicitly; errors are never silently swallowed.
- [ ] All external input is validated at the boundary before use.
- [ ] No hardcoded secrets, credentials, or tokens; no leftover debug prints.
- [ ] Functions stay focused (< ~50 lines), files cohesive (< ~800 lines), nesting shallow (< 4).
- [ ] Names are clear; duplication is factored out only where the repetition is real (DRY, not
      speculative).
- [ ] Data is treated immutably where the codebase expects it; no hidden in-place mutation.
- [ ] New behaviour has tests, and the suite passes.
- [ ] No obvious performance traps (N+1 queries, unbounded loops/collections, missing pagination).

## Output

Give a short verdict (approve / warn / block) followed by findings grouped by severity. For each
finding, name the file and line, explain the risk, and propose the concrete fix.
