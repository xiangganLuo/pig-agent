---
name: windows-disk-cleanup
description: Reclaim disk space on Windows — temp files, recycle bin, update cache, big-file triage (C: is full).
keywords: windows, disk cleanup, C drive full, temp, recycle bin, cleanmgr, DISM, free space, powershell
version: 1.0.0
---
# Windows Disk Cleanup

Free up space when "C: is full / low on disk" — find what's eating the drive, then clear the safe,
regenerable stuff.

## When to use

Low disk space, a full system drive, a failed install/update due to space.

## 1. Find what's using the space (measure first)

```powershell
Get-Volume C                                              # confirm how little is free
# biggest files on C: (skip access-denied):
Get-ChildItem C:\ -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object Length -Descending | Select-Object -First 20 FullName,
    @{n='GB';e={[math]::Round($_.Length/1GB,2)}}
# biggest top-level folders in the user profile:
Get-ChildItem $env:USERPROFILE -Directory | ForEach-Object {
    [PSCustomObject]@{ Folder=$_.Name; GB=[math]::Round(((Get-ChildItem $_ -Recurse -File -EA SilentlyContinue |
        Measure-Object Length -Sum).Sum)/1GB,2) } } | Sort-Object GB -Descending
```

## 2. Clear the safe, regenerable stuff (list, then confirm)

```powershell
Remove-Item "$env:TEMP\*"        -Recurse -Force -ErrorAction SilentlyContinue   # user temp
Remove-Item "C:\Windows\Temp\*"  -Recurse -Force -ErrorAction SilentlyContinue   # system temp (Admin)
Clear-RecycleBin -Force                                                          # recycle bin (confirm)
```

## 3. System caches (Admin)

```powershell
cleanmgr /verylowdisk                                       # the Disk Cleanup tool, aggressive preset
Dism /Online /Cleanup-Image /StartComponentCleanup          # shrink the WinSxS / update cache
```

Other big wins to *review* (don't auto-delete): `%USERPROFILE%\Downloads`, old installers, hibernation
file (`powercfg /hibernate off` reclaims `hiberfil.sys`), and package caches (`npm cache`, pip, nuget).

## Method

1. **Measure first** — show the biggest files/folders so cleanup is targeted, not guesswork.
2. Clear regenerable caches/temp/recycle bin; **list what will go and confirm** before deleting user-
   reachable content (Downloads, etc.).
3. Re-measure `Get-Volume C` to confirm space was actually recovered.

## Checklist

- [ ] I measured the biggest consumers before deleting anything.
- [ ] Only regenerable/temp/cache data was auto-cleared; user data was confirmed first.
- [ ] I re-checked free space afterwards.

## Safety

- Never blanket-delete a profile, `C:\Windows`, or anything you can't identify. Temp/cache/recycle bin
  are safe; documents, Downloads and app data are not — always confirm those with the user.
