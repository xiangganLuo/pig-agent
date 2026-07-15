# Test-Driven Development

Write the test first, watch it fail, then write the minimal code to make it pass.

## When to use

Use this skill when implementing a new feature, fixing a bug, or changing behaviour with a
verifiable outcome. It is the default workflow for production code. Skip it only for pure
exploration or throwaway spikes — and delete the spike before shipping.

## The Red-Green-Refactor loop

1. **RED — write one failing test.** Express a single new behaviour as a test. Name it after the
   behaviour, not the method ("returns empty list when no match", not "testSearch"). Run it and
   confirm it fails **for the right reason** (the behaviour is missing, not a typo or bad import).
2. **GREEN — make it pass minimally.** Write the least code that satisfies the test. Resist adding
   anything the test does not force. Run the test; confirm it passes.
3. **REFACTOR — improve with the safety net.** Now clean up names, duplication, and structure while
   the tests stay green. Refactor the test too if it is unclear.
4. **Repeat** for the next behaviour. Small steps: each loop should take minutes, not hours.

## Rules that keep TDD honest

- **One behaviour per test.** If a test needs "and" in its name, split it.
- **Arrange–Act–Assert.** Set up inputs, invoke once, assert the outcome — visibly separated.
- **See it fail first.** A test that has never failed proves nothing. If it passes before you write
  the code, it is testing the wrong thing.
- **Test behaviour, not implementation.** Assert on observable outputs and effects, not private
  internals, so refactoring does not break the tests.
- **Fix the code, not the test** — unless the test itself encodes the wrong expectation.
- **Keep tests isolated and deterministic:** no shared mutable state, no reliance on order, no real
  clock/network/filesystem unless that is the unit under test (use temp dirs / fakes otherwise).

## Checklist

- [ ] A new test exists and failed for the right reason before any implementation.
- [ ] The implementation is the minimum that makes it pass.
- [ ] Names describe behaviour; each test asserts one thing in AAA form.
- [ ] Refactoring happened with the suite green.
- [ ] Tests are isolated, deterministic, and cover the edge and error paths (aim ≥ 80% on new code).
