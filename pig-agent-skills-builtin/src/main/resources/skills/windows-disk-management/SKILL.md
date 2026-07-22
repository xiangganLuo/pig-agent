---
name: windows-disk-management
description: Inspect Windows disks, partitions, volumes and drive health (read-only first; destructive ops handed off).
keywords: windows, disk, partition, volume, Get-Disk, Get-Volume, chkdsk, diskpart, SMART, health, powershell
version: 1.0.0
---
# Windows Disk Management

Inspect drives, partitions, volumes and health — "which drive is full", "is my disk failing", "what
partitions are there". For "free up space" see `windows-disk-cleanup`.

## When to use

A drive is full, a disk seems to be failing, or you need to understand the disk/partition layout.

## Inspect (read-only, safe)

```powershell
Get-Volume                                        # drive letters, FS, size, free
Get-Disk | Select Number, FriendlyName, Size, PartitionStyle, OperationalStatus
Get-Partition
Get-PhysicalDisk | Select FriendlyName, MediaType, HealthStatus, OperationalStatus   # SMART-ish health
```

For free-space triage (biggest files/dirs), use `system-health` / `windows-disk-cleanup`.

## Check a filesystem

```powershell
chkdsk C:                 # read-only scan (reports errors)
# chkdsk C: /f  needs exclusive access → schedules a check at next reboot; hand the reboot to the user
```

## Destructive operations — handed off, not run here

**The command sandbox blocks `diskpart` and `format`** (and `mkfs`), by design. So the agent:

- **does** the read-only inspection above and identifies the problem, then
- **hands the user the exact command to run themselves** for repartition/format/resize, e.g.
  `Resize-Partition -DriveLetter D -Size 100GB` (Admin), or a `diskpart` script — with a clear warning.

`Resize-Partition` / `Optimize-Volume` (defrag/trim) run in an elevated session if the user asks and
accepts the risk.

## Method

1. Identify the disk/volume by number + friendly name; report size, free space, health.
2. For "disk failing": check `Get-PhysicalDisk` HealthStatus + `System` event log for `disk`/`Ntfs`
   errors (see `windows-event-logs`).
3. Never initiate a format/repartition automatically — inspect, explain, and give the user the command.

## Checklist

- [ ] Inspection was read-only; I identified the specific disk/volume.
- [ ] Any destructive step was handed to the user with a clear warning, not executed.

## Safety

- Format/partition operations destroy data irreversibly — always the user's explicit, informed action.
- Back up before resizing; a wrong drive letter/disk number is catastrophic.
