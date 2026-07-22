---
name: log-triage
description: Locate, tail and filter logs to find the relevant lines fast.
keywords: logs, log, tail, follow, grep, select-string, error, filter, bash, powershell, windows, linux
version: 1.0.0
---
# Log Triage

Find the log that matters, then narrow to the few lines that explain the problem — without dumping
megabytes into context.

## When to use

Use this skill whenever you are diagnosing from logs: a service is failing, a job errored, or you need
the recent history of what happened. Reach for it before `cat`-ing a whole log file (which wastes tokens
and buries the signal).

## Locate the log

- Common locations: `./logs/`, `/var/log/` (Linux), the app's working dir, and for pig itself
  `~/.pig-agent/workspace/logs/pig-agent.log`.
- Find candidates: `findFiles` with `**/*.log` (preferred), or `find . -name '*.log'` /
  `Get-ChildItem -Recurse -Filter *.log`.

## Search contents (prefer `searchFiles`)

`searchFiles` does host-independent text/regex search — use it first. Shell equivalents:

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| Find a pattern | `grep -n ERROR app.log` | `Select-String -Pattern ERROR app.log` |
| Case-insensitive | `grep -in error app.log` | `Select-String -Pattern error app.log` (default) |
| With context | `grep -n -C3 ERROR app.log` | `Select-String ERROR app.log -Context 3,3` |
| Count matches | `grep -c ERROR app.log` | `(Select-String ERROR app.log).Count` |

## Read the ends and follow live

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| Last N lines | `tail -n 100 app.log` | `Get-Content app.log -Tail 100` |
| First N lines | `head -n 100 app.log` | `Get-Content app.log -TotalCount 100` |
| Follow (live) | `tail -f app.log` | `Get-Content app.log -Wait -Tail 20` |
| Errors only, tail | `tail -n 500 app.log \| grep -i error` | `Get-Content app.log -Tail 500 \| Select-String error` |

## Method

1. **Start at the end.** The newest lines (`tail`/`-Tail`) usually hold the failure and its stack.
2. **Filter to the signal** — grep/`Select-String` for `ERROR`/`Exception`/the failing id, with a few
   lines of context (`-C` / `-Context`) so you see cause and effect together.
3. **Bound the window.** Read a slice (last N lines, or a time range), not the whole file. Follow live
   (`-f` / `-Wait`) only when reproducing in real time.
4. **Correlate** the timestamp/request-id across services rather than reading each log top to bottom.
5. Feed what you found into `systematic-debugging` — a log line is evidence, not yet a fix.

## Checklist

- [ ] I found the right log file (used `findFiles`).
- [ ] I searched with `searchFiles`/grep instead of dumping the file.
- [ ] I read a bounded slice (tail / range), not the whole log.
- [ ] I kept the surrounding context lines for the key match.

## Anti-patterns

- Printing an entire multi-MB log into context.
- Grepping only the error line and losing the stack/context above it.
- Following a live log forever instead of reproducing, capturing, and stopping.
