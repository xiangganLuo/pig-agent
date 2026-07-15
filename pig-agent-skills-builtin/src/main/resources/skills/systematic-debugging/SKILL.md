# Systematic Debugging

A disciplined loop for finding and fixing a bug by evidence rather than guesswork.

## When to use

Use this skill whenever something does not behave as expected: a failing test, a crash, a wrong
result, or a flaky intermittent failure. Use it especially when the temptation is to "just try a
change and see" — that is the failure mode this method prevents.

## Method

1. **Reproduce reliably.** Find the smallest, most deterministic way to trigger the bug. If you
   cannot reproduce it on demand, your first job is to make it reproducible — a bug you cannot
   reproduce cannot be confirmed fixed.
2. **State the expectation vs. reality.** Write down precisely what you expected and what actually
   happened, with the exact error text or wrong value.
3. **Form one hypothesis at a time.** Name a specific, falsifiable cause ("the timezone is applied
   twice"), not a vague area ("something in date handling").
4. **Localize by bisection.** Narrow where the truth diverges from the expectation: add a check
   (log, assertion, breakpoint) at the midpoint of the suspect path and see which side is wrong.
   Halve the search space each step instead of scanning linearly.
5. **Test the hypothesis cheaply** before changing production code — a targeted log line, a probe in
   a REPL, or a unit test that asserts the suspected behaviour.
6. **Fix the root cause, not the symptom.** If you find yourself special-casing an output or adding
   a retry to hide a race, stop — you have found a symptom. Trace one level deeper.
7. **Add a regression test** that fails before your fix and passes after. This proves the fix and
   stops the bug from returning.
8. **Verify and widen.** Re-run the full suite and think about whether the same root cause hides
   elsewhere.

## Checklist

- [ ] I can reproduce the bug on demand.
- [ ] I wrote down expected vs. actual explicitly.
- [ ] I have exactly one falsifiable hypothesis right now.
- [ ] I confirmed the hypothesis with evidence before editing production code.
- [ ] The fix addresses the root cause, not a downstream symptom.
- [ ] A regression test fails before the fix and passes after.
- [ ] The full suite is green and I checked for the same cause elsewhere.

## Anti-patterns

- Changing several things at once so you cannot tell what worked.
- "Fixing" by suppressing an error, widening a catch, or adding a sleep.
- Editing tests to match buggy behaviour instead of fixing the code.
