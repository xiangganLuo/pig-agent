---
name: archive-and-compress
description: Create and extract tar / zip archives cross-platform.
keywords: archive, tar, zip, unzip, gzip, compress, extract, compress-archive, expand-archive, bash, powershell
version: 1.0.0
---
# Archive & Compress

Create and extract `tar` and `zip` archives on bash and PowerShell, and do it without clobbering files
or falling for path-traversal ("zip slip").

## When to use

Use this skill to package files for transfer, extract a downloaded archive, or bundle build output /
logs. Reach for it whenever you see `.zip`, `.tar`, `.tar.gz`/`.tgz`, or `.gz`.

## Inspect before extracting

Always list the contents first — so you know *where* files will land and can spot an entry that escapes
the target dir.

| Format | bash / zsh | PowerShell |
|--------|------------|------------|
| zip | `unzip -l a.zip` | `Expand-Archive a.zip -DestinationPath tmp -WhatIf` or inspect via .NET `ZipFile` |
| tar | `tar -tzf a.tar.gz` | `tar -tzf a.tar.gz` (bundled on Windows 10+) |

## Create

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| zip a folder | `zip -r out.zip dir/` | `Compress-Archive -Path dir\* -DestinationPath out.zip` |
| tar.gz a folder | `tar -czf out.tar.gz dir/` | `tar -czf out.tar.gz dir` |
| add to existing zip | `zip -r out.zip more/` | `Compress-Archive -Path more\* -Update -DestinationPath out.zip` |

## Extract

| Task | bash / zsh | PowerShell |
|------|------------|------------|
| unzip to a dir | `unzip a.zip -d out/` | `Expand-Archive a.zip -DestinationPath out` |
| untar to a dir | `tar -xzf a.tar.gz -C out/` | `tar -xzf a.tar.gz -C out` |

## Method

1. **Extract into a fresh, empty directory** (`-d out/` / `-DestinationPath out`), never into the current
   dir — this keeps an archive from overwriting existing files and makes the result easy to inspect or
   discard.
2. **List contents first** (table above) and check no entry uses an absolute path or `../` that would
   escape the target (zip slip). If it does, do not extract blindly.
3. **Create archives with relative paths**, from the parent of the folder you're packing, so the archive
   unpacks cleanly.
4. **Verify** after: list the extracted tree / re-list the created archive.

## Safety / Anti-patterns

- Do not extract an untrusted archive over an existing tree — use a clean directory and review.
- Watch for path traversal: an entry like `../../etc/...` or `/abs/path` must be treated as hostile.
- Very large archives can fill the disk — check free space (see `system-health`) before extracting.

## Checklist

- [ ] I listed the archive contents before extracting.
- [ ] I extracted into a fresh, empty directory.
- [ ] I checked for absolute/`..` paths (zip slip).
- [ ] I verified the created/extracted result and the command matches the host shell.
