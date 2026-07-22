---
name: windows-power
description: Manage Windows power plans, sleep/hibernate and battery report (shutdown/restart handed to the user).
keywords: windows, power, powercfg, battery, sleep, hibernate, power plan, shutdown, energy, powershell
version: 1.0.0
---
# Windows Power

Manage power plans, sleep/hibernate behaviour, and battery health via `powercfg` — "stop it sleeping",
"switch to High Performance", "how's my battery", "schedule a shutdown".

## When to use

Sleep/display-timeout tweaks, switching power plans, a battery-health report, or scheduling a shutdown.

## Power plans

```powershell
powercfg /list                              # available plans + GUIDs
powercfg /getactivescheme                   # current
powercfg /setactive SCHEME_MIN              # High performance (or a GUID from /list)
```

## Sleep / hibernate (some need Admin)

```powershell
powercfg /change standby-timeout-ac 0       # 0 = never sleep on AC (minutes otherwise)
powercfg /change monitor-timeout-ac 15
powercfg /hibernate on                       # or off
powercfg /requests                           # what's currently *preventing* sleep
```

## Battery health report

```powershell
powercfg /batteryreport /output "$env:USERPROFILE\Desktop\battery.html"   # design vs full-charge capacity
powercfg /energy                                                          # energy efficiency diagnostics
```

## Shutdown / restart — handed to the user (sandbox-blocked)

**`shutdown`, `Stop-Computer` and `Restart-Computer` are blocked by the command sandbox.** For "shut
down in 1 hour" / "restart now", give the user the command to run themselves (e.g. via the REPL
`! shutdown /s /t 3600`, cancel with `! shutdown /a`) rather than trying to run it here.

## Method

1. Read the current plan / timeouts first.
2. Apply the specific change (plan or timeout); use `powercfg /requests` to explain "why won't it sleep".
3. For shutdown/restart, hand the exact command to the user — don't attempt it in-sandbox.

## Checklist

- [ ] I checked the current plan/timeouts before changing them.
- [ ] Shutdown/restart was handed to the user, not executed here.

## Safety

- "Never sleep" + a laptop in a bag can overheat/drain — mention the trade-off. Don't change power
  policy on a shared/managed machine without confirmation.
