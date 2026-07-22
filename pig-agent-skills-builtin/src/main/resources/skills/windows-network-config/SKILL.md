---
name: windows-network-config
description: View and change Windows IP / DNS / adapter settings and flush the DNS cache.
keywords: windows, network, ip, dns, ipconfig, adapter, static ip, flushdns, dhcp, netsh, powershell
version: 1.0.0
---
# Windows Network Config

Read and change the machine's IP/DNS/adapter settings — "what's my IP", "change my DNS", "flush DNS",
"set a static IP", "renew DHCP".

## When to use

Connectivity/name-resolution problems, switching DNS (e.g. to 1.1.1.1), assigning a static IP, or
after editing hosts/DNS when stale entries linger.

## Elevation

Viewing is unelevated. **Changing IP/DNS and flushing require an elevated PowerShell** (Access-denied →
hand to the user). Diagnosing *reachability* is in `networking-diagnostics` / `checkPort`.

## Inspect

```powershell
Get-NetIPConfiguration                 # IP, gateway, DNS per adapter (the quick overview)
Get-NetAdapter | Select Name, Status, LinkSpeed, MacAddress
ipconfig /all                          # legacy, very detailed
Get-DnsClientServerAddress             # current DNS servers per interface
```

## Change DNS (Admin)

```powershell
Set-DnsClientServerAddress -InterfaceAlias 'Wi-Fi' -ServerAddresses 1.1.1.1, 8.8.8.8
Set-DnsClientServerAddress -InterfaceAlias 'Wi-Fi' -ResetServerAddresses   # back to DHCP/auto
```

## Flush / renew

```powershell
ipconfig /flushdns          # or:  Clear-DnsClientCache
ipconfig /release; ipconfig /renew    # renew a DHCP lease
```

## Static IP (Admin)

```powershell
New-NetIPAddress -InterfaceAlias 'Ethernet' -IPAddress 192.168.1.50 -PrefixLength 24 `
    -DefaultGateway 192.168.1.1
# revert to DHCP:
Set-NetIPInterface -InterfaceAlias 'Ethernet' -Dhcp Enabled
```

## Method

1. Get the exact **InterfaceAlias** (`Get-NetAdapter`) — commands key off it.
2. Make the change, then verify (`Get-NetIPConfiguration`) and test resolution/reachability
   (`resolveHost` / `checkPort`).
3. For DNS problems, change DNS **and** `ipconfig /flushdns` — stale cache is a common culprit.

## Checklist

- [ ] I used the correct InterfaceAlias.
- [ ] Changes ran elevated; I flushed DNS where relevant.
- [ ] I verified config + resolution afterwards.

## Safety

- A wrong static IP/gateway can cut the machine off the network — note the old settings first so you
  can revert; prefer DHCP unless the user needs static.
