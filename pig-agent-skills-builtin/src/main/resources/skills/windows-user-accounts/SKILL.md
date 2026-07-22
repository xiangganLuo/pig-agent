---
name: windows-user-accounts
description: Manage local Windows users and groups — create, disable, reset password, add to Administrators.
keywords: windows, user, account, local group, administrators, Get-LocalUser, net user, password, powershell
version: 1.0.0
---
# Windows User Accounts

Create and manage **local** Windows accounts and group membership — "add a user", "reset a password",
"make this account an admin", "who are the administrators".

## When to use

Local account administration. (Domain/Azure-AD accounts are managed elsewhere — these cmdlets are for
local accounts only.)

## Elevation

**All changes require an elevated PowerShell.** Listing is unelevated.

## Inspect

```powershell
Get-LocalUser
Get-LocalGroup
Get-LocalGroupMember -Group 'Administrators'
```

## Create a user (Admin) — handle the password carefully

```powershell
# Prefer letting the user set the password interactively at first logon:
New-LocalUser -Name 'alice' -NoPassword -FullName 'Alice' -Description 'Project account'
# If a password must be set programmatically, treat it as a secret (never echo it, never log it):
$pw = ConvertTo-SecureString 'CHOSEN-PASSWORD' -AsPlainText -Force
New-LocalUser -Name 'alice' -Password $pw
```

## Modify (Admin)

```powershell
Add-LocalGroupMember -Group 'Administrators' -Member 'alice'   # grant admin
Set-LocalUser  -Name 'alice' -Password $pw                     # reset password
Disable-LocalUser -Name 'alice'                                # disable (reversible)
Enable-LocalUser  -Name 'alice'
Remove-LocalUser  -Name 'alice'                                # delete (confirm!)
```

`net user alice * /add` / `net localgroup administrators alice /add` are the legacy equivalents.

## Method

1. List first; confirm the exact account name.
2. For a password, prefer `-NoPassword` (user sets it) or take the value from the user out-of-band —
   **do not print the password back** in output/logs.
3. Confirm before granting Administrators membership or deleting an account.

## Checklist

- [ ] Changes ran elevated; account name confirmed.
- [ ] Password handled as a secret (not echoed/logged); admin grants and deletes confirmed.

## Safety

- Adding to Administrators is a privilege escalation — always confirm with the user.
- Never store or surface the plaintext password; deleting an account can destroy its profile/data.
