---
name: windows-datetime
description: View and set the Windows clock, time zone, and NTP time synchronization.
keywords: windows, time, date, timezone, Set-TimeZone, w32tm, ntp, clock sync, powershell
version: 1.0.0
---
# Windows Date & Time

Read and fix the clock, time zone, and time synchronization — "my clock is wrong", "change the time
zone", "sync the time", "time drifted".

## When to use

Wrong local time, wrong time zone, or clock drift (which breaks TLS, auth, kerberos, logs).

## Inspect

```powershell
Get-Date                          # current local date/time
Get-TimeZone                      # current time zone
Get-TimeZone -ListAvailable | Select Id, DisplayName   # valid Ids for Set-TimeZone
w32tm /query /status              # time source + last sync + offset
```

## Set time zone (Admin)

```powershell
Set-TimeZone -Id 'China Standard Time'      # use an Id from -ListAvailable (e.g. 'Pacific Standard Time')
```

## Sync the clock (Admin)

```powershell
Restart-Service W32Time                                          # ensure the time service runs
w32tm /resync                                                    # force a sync now
# point at a specific NTP pool if sync fails:
w32tm /config /manualpeerlist:"pool.ntp.org" /syncfromflags:manual /update
Restart-Service W32Time; w32tm /resync
```

Setting the time manually (rare): `Set-Date -Date '2026-07-22 14:30'` (Admin).

## Method

1. Show current time + zone + sync status.
2. For wrong *offset*, usually the **time zone** is wrong (fix that, not the clock). For *drift*, force
   an NTP resync.
3. Verify with `Get-Date` / `w32tm /query /status` (offset near zero).

## Checklist

- [ ] I distinguished a time-zone problem from clock drift.
- [ ] Zone/sync changes ran elevated; I verified the offset afterwards.

## Safety

- On a domain-joined machine, time is usually managed by the domain — don't override the NTP source
  without checking. A large clock change can invalidate sessions/certs momentarily; note that to the user.
