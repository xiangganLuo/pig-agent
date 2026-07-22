---
name: windows-scheduled-tasks
description: Create, inspect and run Windows Scheduled Tasks (run at boot/logon/daily) from PowerShell.
keywords: windows, scheduled task, task scheduler, schtasks, Register-ScheduledTask, cron, autostart, powershell
version: 1.0.0
---
# Windows Scheduled Tasks

Automate "run this at boot / at logon / every day at 3am / every hour" using the Windows Task
Scheduler — the Windows equivalent of cron.

## When to use

The user wants a script/program to run on a schedule or at startup, or wants to see/stop an existing
scheduled task. (For *pig's own* recurring agent jobs use `/tasks`; this skill is for OS-level tasks.)

## Elevation

User-level tasks can be created in a normal session; tasks that run **as SYSTEM / at boot / for all
users** need an elevated PowerShell. Access-denied → hand the step to the user (elevated / `! cmd`).

## Inspect

```powershell
Get-ScheduledTask                              # all
Get-ScheduledTask -TaskName '*backup*'
Get-ScheduledTaskInfo -TaskName 'MyJob'        # LastRunTime / LastTaskResult / NextRunTime
```

## Create — run a script daily at 03:00

```powershell
$action  = New-ScheduledTaskAction -Execute 'powershell.exe' `
             -Argument '-NoProfile -File "C:\scripts\backup.ps1"'
$trigger = New-ScheduledTaskTrigger -Daily -At 3am           # or -AtLogOn / -AtStartup / -Once
Register-ScheduledTask -TaskName 'DailyBackup' -Action $action -Trigger $trigger `
    -Description 'Nightly backup' -RunLevel Limited          # -RunLevel Highest for elevated
```

## Run / enable / remove

```powershell
Start-ScheduledTask    -TaskName 'DailyBackup'   # run now
Disable-ScheduledTask  -TaskName 'DailyBackup'
Enable-ScheduledTask   -TaskName 'DailyBackup'
Unregister-ScheduledTask -TaskName 'DailyBackup' -Confirm:$false
```

`schtasks.exe /create /tn ... /tr ... /sc daily /st 03:00` is the legacy equivalent.

## Method

1. Decide the trigger (Daily/AtStartup/AtLogOn/Once) and the exact action (full path to interpreter +
   script, quoted).
2. Register it; then **run it once** (`Start-ScheduledTask`) and check `Get-ScheduledTaskInfo`
   `LastTaskResult` (0 = success) — don't assume it works.
3. Confirm with the user before creating anything that runs elevated or at boot.

## Checklist

- [ ] Trigger and action (full quoted paths) are correct.
- [ ] I test-ran the task and checked `LastTaskResult == 0`.
- [ ] Elevated/boot tasks were confirmed and run with the right privilege.

## Safety

- A task running at boot as SYSTEM is powerful — review the script it runs; never register an
  unreviewed command. Name tasks clearly so the user can find and remove them.
