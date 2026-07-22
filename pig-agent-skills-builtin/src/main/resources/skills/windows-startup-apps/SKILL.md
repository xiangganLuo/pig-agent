---
name: windows-startup-apps
description: Find and trim programs that launch at Windows startup to speed up boot/login.
keywords: windows, startup, autorun, boot, login, Run key, startup folder, msconfig, powershell
version: 1.0.0
---
# Windows Startup Apps

Find what launches automatically at login/boot and disable the ones the user doesn't need — the
"my PC takes forever to start / too much junk opens on login" problem.

## When to use

Boot/login is slow or cluttered with auto-launching apps. Pairs with `windows-slow-boot-triage`.

## Where startup entries live (check all)

```powershell
# 1. The classic combined view
Get-CimInstance Win32_StartupCommand | Select-Object Name, Command, Location

# 2. Registry "Run" keys (per-user and machine-wide)
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Run'

# 3. Startup folders (a shortcut here launches at login)
Get-ChildItem "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup"
Get-ChildItem "$env:ProgramData\Microsoft\Windows\Start Menu\Programs\Startup"
```

Some auto-launches are Scheduled Tasks with an *AtLogOn* trigger (see `windows-scheduled-tasks`) —
check those too.

## Disable an entry

```powershell
# Registry Run value (HKLM needs Admin):
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'Spotify'
# Or move the shortcut out of the Startup folder (reversible):
Move-Item "$env:APPDATA\...\Startup\Foo.lnk" "$env:APPDATA\...\Startup-disabled\"
```

> The Task Manager **Startup apps** tab (and Settings ▸ Apps ▸ Startup) is the GUI for this and also
> tracks a "last measured startup impact" — that toggle is GUI-only; the command-line equivalents above
> are what the agent can drive.

## Method

1. Enumerate all four sources above and present the list with what each entry is.
2. Recommend disabling only clearly optional apps (updaters, chat clients, game launchers); **confirm
   with the user** before removing anything.
3. Prefer the reversible move (shortcut out of the folder / export the Run value first) over deletion.

## Checklist

- [ ] I checked Run keys (HKCU+HKLM), both Startup folders, and AtLogOn tasks.
- [ ] I confirmed each removal with the user and kept it reversible.

## Safety

- Don't remove security/driver/OEM entries you can't identify. Back up a Run key (`reg export`) before
  editing HKLM.
