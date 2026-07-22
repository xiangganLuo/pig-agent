---
name: windows-slow-boot-triage
description: Systematically diagnose slow Windows boot/login and identify the biggest contributors.
keywords: windows, slow boot, slow startup, login slow, boot time, performance, diagnostics, triage, powershell
version: 1.0.0
---
# Windows Slow Boot Triage

A systematic pass for "my PC takes forever to boot / log in" — measure the contributors, then fix the
biggest ones. This is a composite skill that pulls in the others.

## When to use

Boot or login is slow and you need to find *why* rather than guess. Produce a ranked list of causes,
then act on the top ones (with confirmation).

## The pass (measure each, then rank)

1. **Boot-time from the event log.** The Diagnostics-Performance log records boot duration + slow
   contributors:
   ```powershell
   Get-WinEvent -LogName 'Microsoft-Windows-Diagnostics-Performance/Operational' -MaxEvents 20 |
       Where-Object Id -in 100,101,102,103 |
       Select-Object TimeCreated, Id, Message      # 100=boot time; 101/102/103=slow app/driver/service
   ```
2. **Startup programs.** Too many auto-launch apps → slow login. Enumerate and trim via
   `windows-startup-apps` (Run keys + Startup folders + AtLogOn tasks).
3. **Auto-start services.** Services set to Automatic run at boot:
   ```powershell
   Get-CimInstance Win32_Service -Filter "StartMode='Auto' AND State='Running'" |
       Select Name, DisplayName
   ```
   Consider switching non-critical ones to *Automatic (Delayed Start)* (see `windows-services`).
4. **Disk health & free space.** A nearly-full or failing system disk makes boot crawl — check
   `Get-Volume C` + `Get-PhysicalDisk` HealthStatus (`windows-disk-management` / `system-health`).
5. **Errors around boot.** System log Critical/Error near startup time (`windows-event-logs`,
   Id 41/6008 for bad shutdowns, driver/service failures that stall boot).
6. **Fast Startup / power.** Odd hybrid-boot behaviour: `powercfg /a` (available sleep states),
   `windows-power`.

## Method

1. Run all six checks and **rank** the contributors by impact (event 101/102/103 messages name the slow
   app/driver/service directly — start there).
2. Propose the top fixes: trim startup apps, delay non-critical auto services, free disk space, address
   a flagged driver. **Confirm each change with the user**; keep them reversible.
3. Never auto-disable a service/driver you can't identify as safe.

## Checklist

- [ ] I measured boot time + the named slow contributors (event 100–103) rather than guessing.
- [ ] I checked startup apps, auto services, disk health/space, and boot-time errors.
- [ ] I ranked causes and confirmed each fix with the user, reversibly.

## Safety

- Startup/service changes can disable something the user needs (security, drivers, sync) — confirm and
  keep a record of what was disabled so it can be restored.
