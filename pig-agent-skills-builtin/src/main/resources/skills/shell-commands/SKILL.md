---
name: shell-commands
description: Run shell commands safely and portably across bash and PowerShell.
keywords: shell, bash, powershell, command, cross-platform, quoting, windows, linux, macos, executeCommand
version: 1.0.0
---
# Shell Commands

Run shell commands safely and portably, whether the host shell is bash/zsh (Linux/macOS) or PowerShell
(Windows). Prefer pig's native tools when one fits; drop to the shell only for what they cannot do.

## When to use

Use this skill before running `executeCommand` for anything non-trivial — chaining commands, quoting
paths with spaces, redirecting output, or when the same task must work on both a POSIX shell and
PowerShell. Reach for it whenever a command "worked on Linux" but you are now on Windows (or vice versa).

## Detect the shell first

Never assume the platform. Probe once, then branch.

| Check | bash / zsh | PowerShell |
|-------|------------|------------|
| Shell / OS | `uname -s` | `$PSVersionTable.PSVersion`, `$IsWindows` |
| Where is a tool | `command -v git` | `(Get-Command git).Source` |
| Current dir | `pwd` | `Get-Location` |

## Portability traps (bash → PowerShell)

- **Chaining:** `A && B` / `A || B` work in bash and PowerShell 7, but **not** Windows PowerShell 5.1.
  Portable form: run `A`, then `if ($?) { B }`; unconditional is `A; B`.
- **`head` / `tail` / `which` don't exist** as cmdlets: use `Select-Object -First N` / `-Last N` /
  `Get-Command`.
- **Writing files:** PowerShell's default encoding is UTF-16. When another tool must read the file,
  pass `-Encoding utf8` to `Out-File`/`Set-Content` — or just use pig's `writeFile`.
- **Env vars:** `$NAME` / `export NAME=v` (bash) vs `$env:NAME` / `$env:NAME = 'v'` (PowerShell).
- **stderr redirect:** avoid `2>&1` on native executables in PowerShell 5.1 (it wraps each line as an
  error and flips `$?`); pig already captures stderr for you.
- **Quoting:** double-quote any path with spaces on both shells. Backslashes are literal in PowerShell;
  in bash they escape.

## Method

1. **Prefer a native tool.** Read → `readFile`; list → `listDirectory`; find files → `findFiles`;
   search content → `searchFiles`; fetch a URL → `fetchUrl`. They are faster, cross-platform, and
   guarded — no quoting or platform branching needed.
2. **Detect the shell** (table above) if the command differs by platform.
3. **Dry-run destructive commands.** List/echo the targets first (`ls`, `Get-ChildItem`, `--dry-run`)
   and confirm before deleting, overwriting, or force-pushing.
4. **Run one coherent command**, quoting paths, and read the exit status (`$?` / `$LASTEXITCODE`).
5. **On failure, read the actual error** before retrying — do not re-run the same command in a loop.

## Safety

- The command sandbox **blocks** catastrophic commands (root/home deletes, `mkfs`/`format`, pipe-to-shell
  installs, fork bombs, `shutdown`). If a command is blocked, do not try to obfuscate it around the
  guard — explain and ask the user.
- **Never echo credentials** (`echo $ANTHROPIC_API_KEY`) or paste plaintext secrets into a command line.
- Medium-risk commands (`sudo`, global installs, `chmod 777`) may run but are flagged — mention the risk.

## Checklist

- [ ] I used a native tool instead of the shell where one fit.
- [ ] I detected the shell before using a platform-specific command.
- [ ] Paths with spaces are quoted; chaining uses a portable form.
- [ ] Destructive commands were dry-run and confirmed first.
- [ ] No secret is echoed or embedded in the command.
