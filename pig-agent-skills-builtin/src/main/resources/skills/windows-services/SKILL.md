---
name: windows-services
description: Inspect, start, stop, restart and configure Windows services from PowerShell.
keywords: windows, service, services, Get-Service, Restart-Service, sc, spooler, startup type, powershell
version: 1.0.0
---
# Windows Services

Query and control Windows services — the classic "restart that service" / "why won't this service
start" / "make it start on boot" tasks.

## When to use

A background service is stopped, hung, or you need it to (not) start at boot: printing (Spooler),
Windows Update (wuauserv), a database, a custom app service.

## Elevation

Querying services works unelevated. **Starting/stopping/configuring requires an elevated (Admin)
PowerShell.** If a change returns "Access is denied / PermissionDenied", the session isn't elevated —
ask the user to run pig (or the single step via `! <command>`) from an Administrator terminal; a UAC
prompt cannot be answered from here.

## Inspect

```powershell
Get-Service                                   # all
Get-Service *update*                          # by name pattern
Get-Service | Where-Object Status -eq 'Running'
Get-CimInstance Win32_Service -Filter "Name='wuauserv'" |
    Select-Object Name, State, StartMode, StartName   # incl. startup type + run-as account
```

## Control (Admin)

```powershell
Start-Service   -Name Spooler
Stop-Service    -Name Spooler -Force
Restart-Service -Name Spooler                 # the usual "stuck printer" fix
```

## Change startup type (Admin)

```powershell
Set-Service -Name wuauserv -StartupType Automatic   # Automatic | Manual | Disabled
sc.exe config wuauserv start= delayed-auto          # sc.exe for "Automatic (Delayed Start)"
```

## Method

1. Identify the exact service **name** (not just display name): `Get-Service *X*` → note the `Name`.
2. Check its current state + start type before acting.
3. Restart (a hang) or set the start type (boot behaviour); prefer `Restart-Service` over stop+start.
4. Verify: `Get-Service X` shows the expected state.

## Checklist

- [ ] I used the service's real `Name`, confirmed its state first.
- [ ] Control/config commands ran elevated (or were handed to the user).
- [ ] I verified the resulting state.

## Safety

- Don't disable a service you can't identify — some are load-bearing (e.g. RpcSs, Dhcp). Confirm intent.
- Stopping a service can drop connections/data in flight; warn the user for anything user-facing.
