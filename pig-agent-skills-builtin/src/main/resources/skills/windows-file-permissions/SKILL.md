---
name: windows-file-permissions
description: Inspect and fix Windows NTFS file/folder permissions and ownership (icacls, takeown, Get-Acl).
keywords: windows, permissions, ntfs, acl, icacls, takeown, Get-Acl, access denied, ownership, powershell
version: 1.0.0
---
# Windows File Permissions

Inspect and repair NTFS permissions and ownership — the "Access is denied" / "I can't delete this
folder" / "grant this user access" problem.

## When to use

A file/folder can't be read, written, or deleted due to permissions, or you need to grant/revoke
access. Most changes need an elevated PowerShell.

## Inspect

```powershell
Get-Acl 'C:\path\to\item' | Format-List           # owner + access rules
icacls "C:\path\to\item"                           # compact ACL view
(Get-Acl 'C:\path').Owner                          # who owns it
```

## Grant / revoke (icacls)

```powershell
icacls "C:\path" /grant "DOMAIN\User:(OI)(CI)F"    # Full, inherited by files+subfolders
icacls "C:\path" /grant "Users:(OI)(CI)RX"         # Read+Execute
icacls "C:\path" /remove "DOMAIN\User"
icacls "C:\path" /reset /t /c                       # reset children to inherited (fixes broken ACLs)
```

Permission letters: `F` full, `M` modify, `RX` read+execute, `R` read, `W` write. `(OI)(CI)` = inherit
to files and subfolders.

## Take ownership (the "Access denied even as admin" fix)

```powershell
takeown /f "C:\path" /r /d y          # take ownership recursively (Admin)
icacls "C:\path" /grant administrators:F /t
```

## Method

1. Read the current owner + ACL first; identify exactly who lacks what.
2. Grant the **least** access that solves it to the **specific** principal — avoid `Everyone:F`.
3. For "access denied as admin", ownership is usually the cause → `takeown`, then re-grant.
4. Verify with `icacls` / `Get-Acl` afterwards.

## Checklist

- [ ] I read the current owner/ACL and identified the exact gap.
- [ ] Granted least-privilege to a specific principal (not Everyone); ran elevated.
- [ ] Verified the resulting ACL.

## Safety

- Broad grants (`Everyone`, `Users:F`) on sensitive paths are a security hole — scope tightly and
  confirm. Recursively resetting ACLs on a large tree is slow and hard to undo; back up / confirm first.
- Never loosen permissions on system directories (`C:\Windows`, Program Files) to fix an app.
