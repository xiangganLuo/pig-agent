---
name: windows-printers
description: Manage Windows printers and the print queue — list, clear stuck jobs, restart the spooler.
keywords: windows, printer, print, spooler, print queue, Get-Printer, Remove-PrintJob, powershell
version: 1.0.0
---
# Windows Printers

Manage printers and the print queue — "clear the stuck print job", "restart the spooler", "list
printers", "set the default".

## When to use

A print job is stuck, the queue is jammed, or you need to inspect/add/set-default a printer.

## Inspect

```powershell
Get-Printer | Select Name, DriverName, PortName, Shared, PrinterStatus
Get-PrintJob -PrinterName 'HP LaserJet'          # queued jobs
Get-Printer | Where-Object { $_.PrinterStatus -ne 'Normal' }
```

## The classic "stuck queue" fix

```powershell
Get-PrintJob -PrinterName 'HP LaserJet' | Remove-PrintJob        # clear this printer's jobs
Restart-Service -Name Spooler                                    # restart the spooler (Admin)
# Nuclear option (Admin) if the spooler is wedged: stop spooler, clear C:\Windows\System32\spool\PRINTERS\*, start
```

## Add / set default

```powershell
Add-Printer -Name 'Office' -DriverName 'Microsoft Print To PDF' -PortName 'PORTPROMPT:'   # Admin
(New-Object -ComObject WScript.Network).SetDefaultPrinter('HP LaserJet')                  # set default
Get-CimInstance Win32_Printer | Where-Object Default                                       # current default
```

## Method

1. Check printer status + the queue first.
2. For a stuck job: remove the offending job(s), then restart the Spooler service if the queue is still
   jammed (that fixes most "nothing prints" cases).
3. Verify the queue is clear and status is Normal.

## Checklist

- [ ] I inspected printer status + queue before acting.
- [ ] Cleared the specific stuck jobs; restarted Spooler (elevated) only if needed.
- [ ] Verified the queue/status afterwards.

## Safety

- Clearing the queue discards pending documents — confirm with the user. Spooler restart / adding
  printers needs Admin (hand off if not elevated).
