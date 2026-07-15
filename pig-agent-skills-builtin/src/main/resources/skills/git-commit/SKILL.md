---
name: git-commit
description: Craft small, focused commits with clear conventional-commit messages.
keywords: git, commit, conventional commits, message, staging
version: 1.0.0
---
# Git Commit

Craft small, focused commits with clear conventional-commit messages.

## When to use

Use this skill whenever you are about to commit work, split a large change into commits, or write a
pull-request summary. A good commit history is a design document — treat each commit as a unit of
understandable, revertible change.

## Message format

```
<type>: <short imperative summary>

<optional body: what changed and why, wrapped ~72 cols>
```

- **Types:** `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`.
- **Summary:** imperative mood ("add", not "added"/"adds"), no trailing period, ≤ ~72 chars, says
  *what* the change does.
- **Body (when non-trivial):** explain *why* — the motivation and any consequence a future reader
  needs. Skip the body for a one-line obvious change.

## Method

1. **Review what you are about to commit.** Run the diff of the staged changes and confirm it is one
   coherent idea. If it contains two ideas, stage and commit them separately.
2. **Separate refactors from behaviour changes.** A commit is either "same behaviour, restructured"
   (`refactor:`) or "new/changed behaviour" (`feat:`/`fix:`) — never both.
3. **Pick the type** that matches the primary intent. On a bug-fix branch the commit is still `fix:`.
4. **Write the summary line**, then a body if the change needs a "why".
5. **Verify before committing:** no secrets or debug output in the diff, the build/tests are green,
   and nothing unrelated is included.
6. **Never bypass hooks or signing** (`--no-verify`, `--no-gpg-sign`) unless explicitly asked; if a
   hook fails, fix the cause.

## Pull-request summary

When opening a PR, base the summary on the **whole** branch history (`git diff main...HEAD`), not
just the last commit: state the goal, the key changes, and a short test plan.

## Checklist

- [ ] The commit is one coherent, self-contained change.
- [ ] Type prefix matches the intent; refactor and behaviour are not mixed.
- [ ] Summary is imperative, concise, and specific; body explains "why" when needed.
- [ ] No secrets, no debug leftovers; build and tests pass.
