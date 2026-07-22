---
name: windows-defender
description: Run Microsoft Defender scans, check protection status, and manage exclusions from PowerShell.
keywords: windows, defender, antivirus, Start-MpScan, Get-MpComputerStatus, exclusion, malware, threat, powershell
version: 1.0.0
---
# Windows Defender

Drive Microsoft Defender Antivirus from PowerShell — "run a scan", "am I protected", "add a dev folder
to exclusions", "what did it find".

## When to use

Malware concern, a scan request, checking protection status, or adding/removing exclusions (e.g. a
build folder Defender keeps quarantining).

## Status (no elevation)

```powershell
Get-MpComputerStatus | Select AntivirusEnabled, RealTimeProtectionEnabled,
    AMServiceEnabled, AntivirusSignatureLastUpdated, QuickScanAge
```

## Scan

```powershell
Start-MpScan -ScanType QuickScan        # or FullScan, or:
Start-MpScan -ScanType CustomScan -ScanPath 'C:\Downloads'
Update-MpSignature                      # update definitions first
```

## Threats

```powershell
Get-MpThreatDetection | Select ThreatID, InitialDetectionTime, Resources
Get-MpThreat
Remove-MpThreat                         # act on active threats
```

## Exclusions (Admin) — use sparingly

```powershell
Get-MpPreference | Select ExclusionPath, ExclusionExtension, ExclusionProcess
Add-MpPreference    -ExclusionPath 'C:\dev\build'
Remove-MpPreference -ExclusionPath 'C:\dev\build'
```

## Method

1. Report protection status first; update signatures before a scan.
2. Prefer a targeted `CustomScan` of the suspect path over a slow full scan when you know where to look.
3. Add an exclusion only when a legitimate file is being flagged, it's the narrowest path possible, and
   the user confirms — exclusions are a security hole.

## Checklist

- [ ] I checked status + updated signatures before scanning.
- [ ] Exclusions were narrow, justified, confirmed, and run elevated.

## Safety

- **Never disable real-time protection** to "make something work". An exclusion weakens security —
  scope it tightly and tell the user exactly what was excluded so they can review it later.
