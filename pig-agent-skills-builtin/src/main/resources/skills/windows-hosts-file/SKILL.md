---
name: windows-hosts-file
description: View and edit the Windows hosts file to map or block a hostname (with backup + DNS flush).
keywords: windows, hosts file, dns, mapping, block site, 127.0.0.1, etc hosts, resolution, powershell
version: 1.0.0
---
# Windows Hosts File

Map a hostname to an IP or block a domain by editing the Windows hosts file — "point mysite.local at
this server", "block a domain", "override DNS for testing".

## When to use

Local hostname overrides for development/testing, or blocking a domain by pointing it at `0.0.0.0`.

## The file

`C:\Windows\System32\drivers\etc\hosts` — a plain text file. Each line: `<IP>  <hostname>` (`#` comments).

## View

```powershell
Get-Content C:\Windows\System32\drivers\etc\hosts      # or use readFile on that path
```

## Edit (needs Admin — the file is system-owned)

1. **Back up first:**
   ```powershell
   Copy-Item C:\Windows\System32\drivers\etc\hosts "$env:USERPROFILE\hosts.bak"
   ```
2. Add entries (append), e.g.:
   ```
   192.168.1.50   myserver.local
   0.0.0.0        ads.example.com      # block
   ```
   Use `writeFile`/`editFile` (or an elevated `Add-Content`). A non-elevated write returns **Access
   denied** — if so, hand the edit to the user (run pig elevated, or `! notepad` the file as admin).
3. **Flush DNS so the change takes effect:** `ipconfig /flushdns`.

## Method

1. Read the current hosts file and back it up.
2. Append the mapping/block (don't rewrite unrelated lines); keep a comment noting why/when.
3. Flush DNS and verify with `resolveHost <name>` (should now return the mapped IP).

## Checklist

- [ ] Backed up the hosts file before editing.
- [ ] Appended a clearly-commented entry; wrote it elevated (or handed off on Access-denied).
- [ ] Flushed DNS and verified resolution.

## Safety

- The hosts file is a common malware target — be transparent about every entry you add and never add a
  mapping the user didn't ask for. Blocking/redirecting a real domain can break sites; keep the backup.
