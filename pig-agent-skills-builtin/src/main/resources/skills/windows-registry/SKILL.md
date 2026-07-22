---
name: windows-registry
description: Safely read and edit the Windows Registry from PowerShell (back up first, change one value).
keywords: windows, registry, regedit, reg, HKLM, HKCU, Get-ItemProperty, Set-ItemProperty, powershell
version: 1.0.0
---
# Windows Registry

Read and edit the Windows Registry — a tweak, a policy value, a per-app setting. High blast radius, so
this skill is built around **back up → change one value → verify**.

## When to use

A fix or setting that lives in the registry (an app option, a Windows tweak, a Run entry). Prefer a
proper Settings/app UI when one exists; use the registry when that's the only route.

## Elevation

`HKCU:` (current user) is writable unelevated. `HKLM:` (machine) needs an elevated PowerShell.

## Read

```powershell
Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced'
(Get-ItemProperty -Path 'HKCU:\...\Advanced' -Name 'Hidden').Hidden   # one value
Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'   # subkeys
```

PowerShell PSDrives are `HKCU:` and `HKLM:`. `reg.exe` uses raw `HKEY_CURRENT_USER\...` paths.

## Back up BEFORE editing

```powershell
reg export "HKCU\Software\Vendor\App" "$env:USERPROFILE\Desktop\App-backup.reg"   # restore: reg import
```

## Write / create / delete

```powershell
Set-ItemProperty  -Path 'HKCU:\...\Advanced' -Name 'Hidden' -Value 1              # existing value
New-Item          -Path 'HKCU:\Software\MyApp' -Force                             # new key
New-ItemProperty  -Path 'HKCU:\Software\MyApp' -Name 'Level' -Value 3 -PropertyType DWord
Remove-ItemProperty -Path 'HKCU:\Software\MyApp' -Name 'Level'                    # delete a value
Remove-Item       -Path 'HKCU:\Software\MyApp' -Recurse                           # delete a key (confirm!)
```

Value types matter: `String`, `DWord`, `QWord`, `ExpandString`, `MultiString`, `Binary`.

## Method

1. Read the current value first and record it (or `reg export` the key) so the change is reversible.
2. Change **one** value with the correct type; confirm HKLM/system-wide edits with the user.
3. Verify by reading it back; note that some settings need a sign-out/reboot or an Explorer restart to
   take effect.

## Checklist

- [ ] I backed up (exported) the key or recorded the old value first.
- [ ] I changed one value with the right type; HKLM changes ran elevated and were confirmed.
- [ ] I read it back and told the user if a restart is needed.

## Safety

- A wrong registry edit can break Windows or an app. Never delete keys you can't identify, never
  import a `.reg` from an untrusted source, and always keep the change reversible.
