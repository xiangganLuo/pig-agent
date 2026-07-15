---
name: refactoring
description: Improve internal structure without changing observable behaviour, safely and in small steps.
keywords: refactor, cleanup, code smell, restructure, technical debt
version: 1.0.0
---
# Refactoring

Improve the internal structure of code without changing its observable behaviour.

## When to use

Use this skill when code is hard to read, change, or test but works; when you touched an area and
left it messier than you found it; or right after getting a feature GREEN (the "refactor" step of
TDD). Do **not** mix refactoring with behaviour changes in the same commit — keep them separate so a
diff is either "same behaviour, cleaner" or "new behaviour", never both.

## Method

1. **Establish a safety net first.** Ensure the code under change is covered by tests that pass. If
   coverage is thin, add characterization tests that pin the current behaviour before you touch
   anything. No net → no refactor.
2. **Identify the smell** precisely: long function (> ~50 lines), large file (> ~800 lines), deep
   nesting (> 4 levels), duplication, unclear names, a class doing several jobs, primitive
   obsession, or a leaky abstraction.
3. **Choose the smallest transformation** that removes the smell:
   - Extract a well-named function/variable to name a concept.
   - Replace nested conditionals with early returns / guard clauses.
   - Consolidate duplicated logic into one place (only when the repetition is real, not
     speculative — respect YAGNI).
   - Split a large file/class along a cohesion seam into smaller focused units.
   - Introduce a named constant for a magic number.
4. **Apply it in one small step, then run the tests.** Keep steps tiny and the suite green between
   each. If a step turns red, revert that step and take a smaller one.
5. **Commit the refactor on its own** with a `refactor:` message describing the structural change.

## Principles

- **Behaviour is invariant.** Same inputs, same outputs, same side effects — only the shape changes.
- **Prefer many small, cohesive files over few large ones.** Organize by feature/domain.
- **Immutability:** return new values instead of mutating in place where the codebase expects it.
- **Clarity over cleverness (KISS).** The goal is code the next reader understands quickly.

## Checklist

- [ ] Tests covered the code and were green before I started.
- [ ] Each step was small and the suite stayed green throughout.
- [ ] Observable behaviour is unchanged (no new feature snuck in).
- [ ] The named smell is actually gone; functions/files/nesting are within limits.
- [ ] The change is committed separately as `refactor:`.
