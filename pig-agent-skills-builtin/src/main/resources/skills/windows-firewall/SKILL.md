---
name: windows-firewall
description: Inspect and manage Windows Defender Firewall rules (open a port, allow a program).
keywords: windows, firewall, port, NetFirewallRule, netsh advfirewall, inbound, allow, block, powershell
version: 1.0.0
---
# Windows Firewall

Open a port, allow/block a program, or see what the Windows Defender Firewall is currently permitting —
the "my server isn't reachable / this app can't connect" problem.

## When to use

A local service can't be reached from another machine, an app is blocked from the network, or you need
to audit/close firewall rules.

## Elevation

Listing rules works unelevated. **Creating/removing/changing rules requires an elevated PowerShell.**
Access-denied → hand to the user (elevated / `! cmd`).

## Inspect

```powershell
Get-NetFirewallProfile | Select-Object Name, Enabled          # Domain/Private/Public on?
Get-NetFirewallRule -DisplayName '*Remote Desktop*'
Get-NetFirewallRule -Enabled True -Direction Inbound -Action Allow |
    Select-Object DisplayName, Direction, Action
```

## Allow a port / program (Admin)

```powershell
# Inbound TCP port 8080
New-NetFirewallRule -DisplayName 'Allow 8080 (myapp)' -Direction Inbound `
    -Action Allow -Protocol TCP -LocalPort 8080

# Allow a specific program
New-NetFirewallRule -DisplayName 'MyApp' -Direction Inbound -Action Allow -Program 'C:\app\my.exe'
```

## Remove / disable (Admin)

```powershell
Remove-NetFirewallRule  -DisplayName 'Allow 8080 (myapp)'
Disable-NetFirewallRule -DisplayName 'Allow 8080 (myapp)'   # reversible
```

`netsh advfirewall firewall add rule name="..." dir=in action=allow protocol=TCP localport=8080` is the
legacy equivalent.

## Method

1. First confirm it's actually the firewall: is the service listening? (`checkPort localhost <port>` /
   `Get-NetTCPConnection -LocalPort <port>`). Don't open a firewall port for a service that isn't up.
2. Add the **narrowest** rule that solves it (specific port/program, specific profile), not "allow all".
3. Name the rule clearly so it can be found and removed later; verify with `Get-NetFirewallRule`.

## Checklist

- [ ] I confirmed the service is listening before touching the firewall.
- [ ] The rule is scoped (specific port/program/profile), clearly named, and reversible.
- [ ] Changes ran elevated; I verified the rule exists.

## Safety

- Opening an inbound port exposes a service to the network — confirm scope with the user; prefer the
  Private profile over Public. Never disable the firewall wholesale to "make it work".
