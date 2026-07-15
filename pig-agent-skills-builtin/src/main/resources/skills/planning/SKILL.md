---
name: planning
description: Turn a non-trivial request into a researched, ordered, low-risk plan before coding.
keywords: planning, design, breakdown, roadmap, research
version: 1.0.0
---
# Planning

Turn a non-trivial request into a researched, ordered, low-risk implementation plan before coding.

## When to use

Use this skill before starting a complex feature, a refactor that spans multiple files, or any task
where the approach is not obvious. For a one-line change, skip it. When in doubt, spend five minutes
planning — it is cheaper than a wrong implementation.

## Method

1. **Clarify the goal and scope.** Restate what "done" means in one or two sentences, and list what
   is explicitly out of scope. If the request is ambiguous, ask targeted questions instead of
   guessing.
2. **Research and reuse first.** Before designing anything new, look for existing patterns in this
   codebase and proven libraries/implementations to adopt or adapt. Prefer extending a battle-tested
   approach over writing net-new code.
3. **Map the impact.** List the files, modules, and interfaces the change will touch, and the
   existing conventions it must follow (naming, immutability, error handling, testing).
4. **Design the approach.** Decide the key structural choices and record the *why* for each
   non-obvious one. Prefer the simplest design that meets the requirement (KISS, YAGNI).
5. **Break it into small, ordered steps.** Each step should be independently verifiable and leave
   the build green. Sequence them so dependencies come first; note which steps can be done in
   parallel.
6. **Surface risks and unknowns** up front, each with a mitigation or a spike to resolve it.
7. **Define the verification** for each step — the test or check that proves it works — and plan to
   drive the feature TDD-style.

## Output — a plan containing

- **Goal & non-goals** — one paragraph.
- **Approach & key decisions** — the design, with a short rationale per decision.
- **Step list** — ordered, small, each with its verification (test/command) and dependencies.
- **Risks** — each with a mitigation.

## Checklist

- [ ] "Done" and out-of-scope are stated explicitly.
- [ ] I checked for existing code/libraries to reuse before designing new work.
- [ ] Impacted files/modules and the conventions to follow are listed.
- [ ] Steps are small, ordered by dependency, and each is independently verifiable.
- [ ] Key decisions carry a rationale; risks carry mitigations.
