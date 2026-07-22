---
name: file-operations
description: Find, search, copy, move, rename and safely delete files across platforms.
keywords: file, directory, find, search, copy, move, rename, delete, glob, bash, powershell, windows, linux
version: 1.0.0
---
# File Operations

Locate, inspect and manipulate files and directories — portably across bash and PowerShell, and safely
(especially for deletes and overwrites).

## When to use

Use this skill for everyday file chores: finding files by name or content, copying/moving/renaming,
creating or cleaning directories, and deleting files. Consult it before any bulk or recursive operation,
which is where cross-platform and safety mistakes bite.

## Prefer pig's native file tools

They are cross-platform and guarded — no shell quoting, no platform branching:

- **`findFiles`** — locate files by glob (e.g. `**/*.log`), instead of `find` / `Get-ChildItem -Recurse`.
- **`searchFiles`** — search file contents by text/regex, instead of `grep -r` / `Select-String`.
- **`readFile` / `listDirectory`** — read a file / list a directory.
- **`writeFile` / `editFile`** — create or modify a file (correct encoding handled for you).

Drop to the shell only for operations these don't cover (copy, move, rename, delete, mkdir).

## Shell equivalents

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| Copy file | `cp src dst` | `Copy-Item src dst` |
| Copy dir (recursive) | `cp -r src dst` | `Copy-Item src dst -Recurse` |
| Move / rename | `mv old new` | `Move-Item old new` |
| Make dirs | `mkdir -p a/b/c` | `New-Item -ItemType Directory -Force a/b/c` |
| Delete file | `rm file` | `Remove-Item file` |
| Delete dir (recursive) | `rm -rf dir` | `Remove-Item dir -Recurse -Force` |
| Size of a file | `du -h file` | `(Get-Item file).Length` |

> On Windows, `New-Item -Force` on a **file** truncates existing content — use `New-Item` only to create,
> and prefer pig's `writeFile`/`editFile` to change contents.

## Method

1. **Find before you act.** Use `findFiles` (by name) or `searchFiles` (by content) to get the exact
   target set. For a bulk op, list the matches first and eyeball them.
2. **Copy/move** with the table above; quote paths that contain spaces.
3. **Delete safely — always list first.** Run the *listing* form (`ls dir`, `Get-ChildItem dir`) or a
   glob match, confirm it is exactly what you intend, *then* delete. Never widen a delete "to be safe".
4. **Batch rename** by iterating the matched set (`for f in *.txt; do mv "$f" "${f%.txt}.md"; done` /
   `Get-ChildItem *.txt | Rename-Item -NewName { $_.Name -replace '\.txt$','.md' }`) — after a dry-run print.
5. **Verify** the result (re-list the directory) before reporting done.

## Safety / Anti-patterns

- **Never** run `rm -rf /`, `rm -rf ~`, `rm -rf .` from an unknown directory, or a recursive delete on a
  glob you have not printed first — the sandbox blocks the worst of these, but a scoped `rm -rf ./build`
  can still destroy real work.
- Do not read or write the workspace credential files (`models.json`/`mcp.json`) — they are off-limits.
- Confirm with the user before deleting anything you did not create.

## Checklist

- [ ] I used `findFiles`/`searchFiles` to get the exact targets.
- [ ] Recursive/bulk deletes were listed and confirmed before running.
- [ ] Paths with spaces are quoted; the command matches the host shell.
- [ ] I verified the outcome by re-listing.
