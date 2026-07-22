---
name: system-health
description: Snapshot disk / memory / CPU and reclaim space safely.
keywords: disk, memory, cpu, df, du, free, top, system, health, cleanup, powershell, bash, windows, linux
version: 1.0.0
---
# System Health

Take a quick, accurate snapshot of disk, memory and CPU — and reclaim disk space without deleting
anything important.

## When to use

Use this skill when the machine is slow, a build fails with "no space left on device", memory is
exhausted, or you simply need to report the host's current resource state before/after a heavy task.

## Disk

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| Free space per drive | `df -h` | `Get-PSDrive -PSProvider FileSystem` / `Get-Volume` |
| Biggest dirs here | `du -h -d1 . \| sort -h \| tail` | `Get-ChildItem -Directory \| ForEach-Object { [PSCustomObject]@{ Name=$_.Name; MB=[math]::Round((Get-ChildItem $_ -Recurse -File \| Measure-Object Length -Sum).Sum/1MB,1) } } \| Sort-Object MB` |
| Biggest files | `find . -type f -printf '%s %p\n' \| sort -nr \| head` | `Get-ChildItem -Recurse -File \| Sort-Object Length -Descending \| Select-Object -First 10 Name,Length` |

## Memory and CPU

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| Memory | `free -h` (Linux) / `vm_stat` (macOS) | `Get-CimInstance Win32_OperatingSystem \| Select-Object FreePhysicalMemory,TotalVisibleMemorySize` |
| CPU load / uptime | `uptime` | `Get-CimInstance Win32_Processor \| Select-Object LoadPercentage` |
| Top consumers | `top` / `ps aux --sort=-%mem \| head` | `Get-Process \| Sort-Object WS -Descending \| Select-Object -First 10 Name,CPU,WS` |

## Method

1. **Measure first.** Capture the relevant metric (disk / memory / CPU) so the diagnosis is based on
   numbers, not a guess.
2. **Localize the hog.** For disk, drill from drive → biggest directories → biggest files. For CPU/mem,
   sort processes by usage (see `process-and-ports` to act on a runaway process).
3. **Reclaim space by category, safest first:** build artifacts and caches (`target/`, `node_modules/`,
   `~/.cache`), then old logs and temp files. **List candidates and their sizes before deleting.**
4. **Delete only what is regenerable or clearly stale**, and confirm anything ambiguous with the user.
5. **Re-measure** to confirm the space/pressure was actually recovered.

## Safety / Anti-patterns

- Never blanket-delete a home or system directory to "free space" — target regenerable artifacts.
- List sizes (`du`/`Get-ChildItem … Length`) and confirm before removing; the biggest directory is not
  always safe to delete.
- Clearing a package cache is fine; deleting a lockfile, a `.git` dir, or user data is not.

## Checklist

- [ ] I measured the actual disk/memory/CPU state.
- [ ] I identified the specific hog (dir/file/process).
- [ ] I listed candidate files with sizes before deleting.
- [ ] I only removed regenerable/stale data and re-measured after.
