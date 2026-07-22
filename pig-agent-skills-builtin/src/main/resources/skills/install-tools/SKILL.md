---
name: install-tools
description: Install a CLI tool or package by picking the right OS package manager, non-interactively and safely.
keywords: install, package manager, apt, dnf, yum, pacman, brew, winget, choco, scoop, pip, npm, cargo, windows, linux, macos
version: 1.0.0
---
# Install Tools

Install a command-line tool or library by detecting the platform's package manager and running the
right non-interactive command — inside this agent's command sandbox, where interactive prompts and
pipe-to-shell installers do not work.

## When to use

Use this skill whenever something is "not found" and needs installing, or the user asks to install a
tool/SDK/runtime. Detect first, then pick the manager, then install non-interactively, then verify.

## Step 1 — detect platform and available manager

- `systemInfo` — OS name/version/arch.
- `whichCommand <mgr>` for each candidate to see what's actually installed: `brew`, `apt-get`, `dnf`,
  `yum`, `pacman`, `winget`, `choco`, `scoop`, `pipx`, `npm`, `cargo`.

## Step 2 — pick the command (non-interactive!)

Interactive confirmation prompts have **no TTY to answer** in the sandbox and will time out, so always
pass the "assume yes" flag.

| Platform / manager | Install command |
|--------------------|-----------------|
| macOS — Homebrew | `brew install <pkg>` |
| Debian/Ubuntu — apt | `sudo apt-get install -y <pkg>` (prefix `DEBIAN_FRONTEND=noninteractive`) |
| Fedora/RHEL — dnf | `sudo dnf install -y <pkg>` |
| Arch — pacman | `sudo pacman -S --noconfirm <pkg>` |
| Windows — winget | `winget install --id <Id> -e --accept-package-agreements --accept-source-agreements` |
| Windows — Chocolatey | `choco install <pkg> -y` |
| Windows — Scoop | `scoop install <pkg>` |
| Language: Python CLI | `pipx install <pkg>` (or `python -m pip install <pkg>`) |
| Language: Node global | `npm install -g <pkg>` |
| Language: Rust | `cargo install <pkg>` |

`apt`/`pip`/`sudo`/global-`npm` runs but is flagged ⚠️ by the sandbox — expected. `brew`/`winget`/
`choco`/`dnf`/`pacman` run silently.

## Step 3 — handle privilege and script-only installers

- **sudo password**: the sandbox can't answer an interactive password prompt (it times out). If a step
  needs root and sudo is not passwordless, ask the user to run that one step themselves via the REPL
  `! <command>` affordance, or to configure NOPASSWD — then continue.
- **Pipe-to-shell installers are blocked** (`curl … | sh`, PowerShell `iwr … | iex`). Prefer a package
  manager that needs no script. If a tool truly only ships an install script, do it in steps:
  `curl -fsSL <url> -o install.sh` → inspect with `readFile` → `sh install.sh`.

## Step 4 — verify

- `whichCommand <tool>` — it's now on PATH.
- `<tool> --version` — it runs.
- If it isn't found after install, it may be a PATH issue — see `env-and-path` (the installer's bin dir
  may need adding to PATH, and remember PATH changes don't persist across sandbox commands).

## Checklist

- [ ] I detected the OS and the available package manager before choosing a command.
- [ ] The install command is non-interactive (`-y`/`--noconfirm`/`-e`/accept flags).
- [ ] Privileged steps that need a password were handed to the user, not left to hang.
- [ ] No pipe-to-shell installer was used (package manager, or download→inspect→run).
- [ ] I verified with `whichCommand` + `--version`.

## Safety

- Prefer official package managers / repositories over random scripts.
- Never install from an untrusted URL without inspecting it first; never disable TLS verification to
  force an install.
- Don't `chmod 777` or broaden permissions to make an install "work" — fix the actual cause.
