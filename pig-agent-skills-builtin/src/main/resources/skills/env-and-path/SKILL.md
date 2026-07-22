---
name: env-and-path
description: Read, set and persist environment variables and PATH per platform.
keywords: environment, env, variable, path, export, setx, profile, bashrc, powershell, bash, windows, linux
version: 1.0.0
---
# Environment & PATH

Read, set (for a session), and persist environment variables and the `PATH` — correctly distinguishing
"this shell only" from "every future shell", across bash and PowerShell.

## When to use

Use this skill when a tool is "not found" despite being installed (a `PATH` problem), when a program
needs a config/API env var, or when you must set a variable so a command — or all future sessions — can
see it.

## Read

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| One variable | `echo "$JAVA_HOME"` | `$env:JAVA_HOME` |
| All variables | `printenv` / `env` | `Get-ChildItem Env:` |
| The PATH entries | `echo "$PATH" \| tr ':' '\n'` | `$env:Path -split ';'` |

## Set for the current session only

| bash / zsh | PowerShell |
|------------|------------|
| `export API_URL=https://x` | `$env:API_URL = 'https://x'` |
| `export PATH="$PATH:/opt/tool/bin"` | `$env:Path += ';C:\tool\bin'` |

A session variable disappears when the shell closes. Note: pig runs each `executeCommand` in a fresh
process, so a session `export` **does not carry to the next command** — set it inline or persist it.

## Persist across future sessions

| Scope | bash / zsh | PowerShell / Windows |
|-------|------------|----------------------|
| Per user | append `export VAR=v` to `~/.bashrc` / `~/.zshrc` / `~/.profile` | `[Environment]::SetEnvironmentVariable('VAR','v','User')` or `setx VAR v` |
| Reload | `source ~/.bashrc` | open a new shell (persisted vars load on start) |

> `setx` / `SetEnvironmentVariable('...','User'/'Machine')` affects **new** shells, not the current one —
> set `$env:VAR` too if you need it now. Machine scope needs admin.

## Method

1. **Diagnose "command not found" as a PATH issue first:** confirm the binary exists
   (`command -v tool` / `Get-Command tool`), then check whether its directory is on `PATH`.
2. **Choose the right lifetime:** just for the next command → set it inline / session; for every future
   run → persist to the profile / user scope.
3. **Set it**, then **verify** in a fresh shell (`command -v` / re-read `$env:`).
4. **Append to PATH, never replace it** — overwriting `PATH` breaks the shell.

## Safety / Anti-patterns

- **Never print or persist secrets in plaintext where they'll be logged/committed** (a token in
  `~/.bashrc` checked into a dotfiles repo leaks it). Don't `echo` secret env vars into output.
- Don't clobber `PATH` (`export PATH=/my/bin`) — always append/prepend.
- Remember the fresh-process caveat above: a bare `export` won't survive to pig's next command.

## Checklist

- [ ] I distinguished session vs. persistent scope and chose deliberately.
- [ ] I appended to PATH rather than overwriting it.
- [ ] I verified the variable in a new shell / with `command -v`.
- [ ] No secret value was echoed or written to a tracked file.
