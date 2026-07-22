---
name: windows-wifi
description: List saved Wi-Fi networks, recover a saved Wi-Fi password, and connect from PowerShell.
keywords: windows, wifi, wireless, netsh wlan, password, ssid, profile, connect, powershell
version: 1.0.0
---
# Windows Wi-Fi

Manage Wi-Fi from the command line — the very common "what's the Wi-Fi password I'm connected to?",
"list saved networks", "connect to X".

## When to use

The user forgot a saved Wi-Fi password, wants to see/forget saved networks, or connect to a known one.

## Commands (via `netsh wlan`)

```powershell
netsh wlan show profiles                       # all saved network names (SSIDs)
netsh wlan show interfaces                      # current connection: SSID, signal, state
netsh wlan show networks mode=bssid             # networks currently in range
```

## Recover a saved password

```powershell
netsh wlan show profile name="MyWiFi" key=clear
# → the password is the "Key Content" line under Security settings
```

Get every saved password at once:

```powershell
(netsh wlan show profiles) -match 'All User Profile' -replace '.*: ' | ForEach-Object {
    $p = $_.Trim()
    $key = (netsh wlan show profile name="$p" key=clear | Select-String 'Key Content')
    "$p => $($key -replace '.*:\s*','')"
}
```

## Connect / forget

```powershell
netsh wlan connect name="MyWiFi"
netsh wlan delete profile name="MyWiFi"        # "forget" a saved network
```

## Method

1. `show profiles` to get exact SSID names, then `show profile name=... key=clear` for the password.
2. To connect, the profile must already be saved (adding a brand-new network with credentials from CLI
   needs an XML profile — usually easier for the user to click once).

## Checklist

- [ ] I used the exact SSID from `show profiles`.
- [ ] For a password request, I returned the Key Content to the user.

## Safety & privacy

- A Wi-Fi password is sensitive. It's the user's own machine/network, so retrieving it for them is
  fine — but present it directly to the user and don't write it into logs, reports, or memory.
- `key=clear` may require an elevated session on some configurations.
