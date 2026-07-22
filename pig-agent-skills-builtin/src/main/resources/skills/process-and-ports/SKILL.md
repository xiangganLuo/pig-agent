---
name: process-and-ports
description: Inspect processes, free a busy port, and terminate safely.
keywords: process, port, kill, pid, netstat, lsof, taskkill, powershell, bash, windows, linux, EADDRINUSE
version: 1.0.0
---
# Processes and Ports

Find running processes, discover what is holding a port, and stop a process safely — across bash and
PowerShell.

## When to use

Use this skill when a server won't start ("address already in use" / `EADDRINUSE`), when you need to see
what is running, when something is consuming CPU/memory, or when you must stop a process you (or a
previous run) started.

## Find a process

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| List all | `ps aux` | `Get-Process` |
| By name | `pgrep -fl node` / `ps aux \| grep node` | `Get-Process node` |
| By PID | `ps -p 1234` | `Get-Process -Id 1234` |
| Top CPU/mem | `top` / `htop` | `Get-Process \| Sort-Object CPU -Descending \| Select-Object -First 10` |

## Find what is using a port

| bash / zsh (Linux) | macOS | PowerShell (Windows) |
|--------------------|-------|----------------------|
| `ss -ltnp \| grep :3000` | `lsof -nP -iTCP:3000 -sTCP:LISTEN` | `Get-NetTCPConnection -LocalPort 3000` |
| `lsof -i :3000` | `lsof -i :3000` | `Get-Process -Id (Get-NetTCPConnection -LocalPort 3000).OwningProcess` |

On Windows without `Get-NetTCPConnection`: `netstat -ano | findstr :3000` → note the PID in the last
column → `Get-Process -Id <pid>`.

## Terminate safely

Prefer a graceful stop, escalate only if needed.

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| Graceful (SIGTERM) | `kill <pid>` | `Stop-Process -Id <pid>` |
| Force (SIGKILL) | `kill -9 <pid>` | `Stop-Process -Id <pid> -Force` |
| By name | `pkill -f node` | `Stop-Process -Name node` |

## Method

1. **Identify precisely.** Get the exact PID (and confirm the process name/command line) before killing.
   Never `pkill node` blindly if several node apps run — you may kill the wrong one.
2. **Free a busy port:** find the owning PID (table above) → confirm it is the process you expect →
   stop it gracefully first, force only if it ignores SIGTERM.
3. **Confirm before force-killing** anything you did not start, or any system/service process.
4. **Verify** the process is gone / the port is free before retrying the original action.

## Safety / Anti-patterns

- Do not force-kill (`kill -9` / `-Force`) as a first move — a graceful stop lets the process flush and
  clean up. Escalate only when it refuses to exit.
- Killing by name (`pkill`/`Stop-Process -Name`) is a blunt instrument — prefer PID.
- Never `shutdown`/`reboot` the host (the sandbox blocks it) and don't kill unknown system processes.

## Checklist

- [ ] I have the exact PID and confirmed the process identity.
- [ ] For a busy port, I found the owner before stopping it.
- [ ] I tried a graceful stop before forcing.
- [ ] I verified the process ended / port freed.
