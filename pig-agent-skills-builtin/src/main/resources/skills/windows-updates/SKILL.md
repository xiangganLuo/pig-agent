---
name: windows-updates
description: Check installed patches and update Windows apps/OS (winget upgrade, hotfixes, PSWindowsUpdate).
keywords: windows, update, patch, hotfix, winget upgrade, PSWindowsUpdate, Get-HotFix, powershell
version: 1.0.0
---
# Windows Updates

See what's installed and bring the machine up to date — split honestly into **app** updates (easy) and
**OS** updates (limited from the command line).

## When to use

"Am I up to date?", "update my apps", "what patches are installed", "check for Windows updates".

## Installed patches (no elevation)

```powershell
Get-HotFix | Sort-Object InstalledOn -Descending | Select-Object -First 20
```

## App updates — winget (the reliable path; see `install-tools`)

```powershell
winget upgrade                 # list apps with available updates
winget upgrade --all --accept-package-agreements --accept-source-agreements   # apply all
winget upgrade --id Microsoft.PowerShell -e
```

## OS updates — honest limitations

Windows Update has **no built-in PowerShell cmdlet**. Options, worst-to-best for automation:

- `UsoClient StartScan` / `StartInstall` — undocumented, no reliable output.
- The community module **PSWindowsUpdate** (Admin): `Install-Module PSWindowsUpdate -Scope CurrentUser`
  then `Get-WindowsUpdate`, `Install-WindowsUpdate -AcceptAll -AutoReboot`. Best for scripting, but it's
  a third-party module and needs elevation + install.
- Otherwise the reliable route is **Settings ▸ Windows Update** (GUI) — tell the user to click "Check
  for updates"; the agent cannot fully drive OS updates headlessly.

## Method

1. Report installed hotfixes + `winget upgrade` list first (read-only, always works).
2. Offer to apply **app** updates via winget (confirm — installs mutate the machine and may restart apps).
3. For **OS** updates, state the limitation and either use PSWindowsUpdate (with consent to install the
   module, elevated) or hand off to the Settings GUI. Don't pretend to install OS updates you can't.

## Checklist

- [ ] I showed installed patches + available app updates before changing anything.
- [ ] App updates via winget were confirmed with the user.
- [ ] For OS updates I was explicit about the limitation / route taken.

## Safety

- Updates can force app/OS restarts — confirm timing with the user; never `-AutoReboot` a machine the
  user is actively working on without asking.
