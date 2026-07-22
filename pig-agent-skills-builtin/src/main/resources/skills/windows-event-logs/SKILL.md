---
name: windows-event-logs
description: Query the Windows Event Log to diagnose crashes, errors, boots and app failures.
keywords: windows, event log, Get-WinEvent, Event Viewer, crash, error, bsod, application, system, powershell
version: 1.0.0
---
# Windows Event Logs

Read the Windows Event Log to answer "why did it crash / reboot / fail last night" — the command-line
Event Viewer.

## When to use

An app crashed, the machine rebooted unexpectedly, something fails intermittently, and you need the
evidence with timestamps.

## Core queries (Get-WinEvent)

```powershell
# System errors in the last 24h (Level 1=Critical, 2=Error)
Get-WinEvent -FilterHashtable @{ LogName='System'; Level=1,2; StartTime=(Get-Date).AddDays(-1) } |
    Select-Object TimeCreated, Id, ProviderName, Message | Format-Table -Wrap

# Application crashes (Windows Error Reporting / app fault = Event 1000)
Get-WinEvent -FilterHashtable @{ LogName='Application'; Id=1000; StartTime=(Get-Date).AddDays(-2) }

# Unexpected shutdown / restart cause
Get-WinEvent -FilterHashtable @{ LogName='System'; Id=41,6008,1074 }   # 41=dirty power-off, 6008=unexpected
```

The classic logs are `System`, `Application`, and `Security` (Security needs Admin).

## Narrow and export

```powershell
... | Where-Object { $_.Message -match 'timeout' }
... | Select-Object TimeCreated, Id, Message | Export-Csv errors.csv -NoTypeInformation
```

## Method

1. Start from the **time of the incident** and the right log (crashes → Application Id 1000; reboots →
   System Id 41/6008/1074; service issues → System + the service's provider).
2. Read the newest matching entries first; keep the `Message` (it holds the faulting module / exit
   code / bugcheck code).
3. Correlate across logs by timestamp; feed the finding into `systematic-debugging` — an event is
   evidence, not yet a fix.

## Checklist

- [ ] I picked the right log + level + time window (not a full dump).
- [ ] I kept the Message detail for the key events and correlated by timestamp.

## Safety

- The Security log can contain sensitive account/audit data and needs Admin — only read it when
  relevant, and don't surface personal audit detail beyond what's needed.
