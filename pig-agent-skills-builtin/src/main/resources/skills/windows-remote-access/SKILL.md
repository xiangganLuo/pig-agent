---
name: windows-remote-access
description: Enable Remote Desktop (RDP) or the OpenSSH server on Windows, with the required firewall rules.
keywords: windows, remote desktop, rdp, ssh, openssh, sshd, remote access, mstsc, firewall, powershell
version: 1.0.0
---
# Windows Remote Access

Turn on remote access to a Windows machine — Remote Desktop (RDP) or the built-in OpenSSH server —
including the firewall rule it needs.

## When to use

The user wants to reach this machine remotely (RDP for the desktop, SSH for a shell). All steps need an
elevated PowerShell.

## Remote Desktop (RDP)

```powershell
# Enable (Admin): allow connections + open the firewall group
Set-ItemProperty -Path 'HKLM:\System\CurrentControlSet\Control\Terminal Server' `
    -Name 'fDenyTSConnections' -Value 0
Enable-NetFirewallRule -DisplayGroup 'Remote Desktop'
# (optional, more secure) require Network Level Authentication:
Set-ItemProperty -Path 'HKLM:\System\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp' `
    -Name 'UserAuthentication' -Value 1

# Check status:
(Get-ItemProperty 'HKLM:\System\CurrentControlSet\Control\Terminal Server').fDenyTSConnections  # 0 = enabled
```

The user connects with `mstsc` to this machine's IP (see `windows-network-config` for the IP).

## OpenSSH server

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0     # install (Admin)
Start-Service sshd
Set-Service  sshd -StartupType Automatic
Get-NetFirewallRule -Name *OpenSSH-Server* | Enable-NetFirewallRule   # port 22 (usually auto-added)
```

## Method

1. Confirm the user really wants the machine reachable, and over which (RDP vs SSH).
2. Enable the service/setting **and** the matching firewall rule — one without the other fails silently.
3. Verify: status setting is on, the rule is enabled, and (from another host) `checkPort <ip> 3389`
   (RDP) or `22` (SSH) is OPEN.

## Checklist

- [ ] User confirmed exposing remote access; ran elevated.
- [ ] Both the service/setting and the firewall rule are enabled.
- [ ] Verified the port is reachable.

## Safety

- RDP/SSH exposed to an untrusted network is a major attack surface. Require strong passwords / NLA /
  keys, prefer the Private firewall profile or a VPN, and never expose it to the public internet
  without the user's explicit, informed consent.
