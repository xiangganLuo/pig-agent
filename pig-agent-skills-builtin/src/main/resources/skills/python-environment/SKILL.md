---
name: python-environment
description: Set up and use a Python environment (interpreter, venv, dependencies) reliably in this agent's sandbox.
keywords: python, venv, virtualenv, pip, requirements, pyenv, uv, poetry, dependencies, bash, powershell, windows
version: 1.0.0
---
# Python Environment

Set up a working Python environment — locate an interpreter, create a virtual environment, install
dependencies — and, crucially, do it the way that actually works inside this agent's command sandbox.

## When to use

Use this skill before running a Python project, creating a venv, or installing Python packages. It
folds in the OS skills (`shell-commands`, `env-and-path`) with the sandbox-specific rules that trip up
a naive `activate`-then-`pip` flow.

## The two rules that make or break it here

1. **Each command runs in a fresh process.** `source .venv/bin/activate` (or `export`) does **not**
   persist to the next `executeCommand`. So NEVER rely on an activated venv across calls — instead call
   the venv's interpreter by its **full path** every time.
2. **Pipe-to-shell installers are blocked** by the sandbox (`curl … | sh`, PowerShell `iwr … | iex`).
   The one-liners for `uv`, `poetry`, `pyenv`, `rustup` etc. will be refused — download, inspect, then
   run in separate steps instead.

## Step 1 — detect what's already there

Use the OS tools, not guesswork:

- `whichCommand python` / `whichCommand python3` / `whichCommand pip` — is an interpreter on PATH?
- `systemInfo` — OS and architecture (picks the right installer later).
- On Windows the launcher is often `py` (`py -3 --version`); on Linux/macOS prefer `python3`.

If no interpreter exists, install one via the `install-tools` skill (e.g. `apt-get install python3
python3-venv`, `brew install python`, `winget install Python.Python.3.12`).

## Step 2 — create a venv

| Platform | Create | Interpreter path to use afterwards |
|----------|--------|------------------------------------|
| Linux / macOS | `python3 -m venv .venv` | `.venv/bin/python` |
| Windows | `py -3 -m venv .venv` (or `python -m venv .venv`) | `.venv\Scripts\python.exe` |

## Step 3 — install dependencies (call the venv python by path)

Because activation doesn't persist, drive pip **through the venv interpreter**:

```bash
# Linux / macOS
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
```
```powershell
# Windows
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install -r requirements.txt
```

- `pip install` runs but is flagged ⚠️ by the sandbox (it mutates the environment) — that's expected.
- Prefer a pinned `requirements.txt` / `pyproject.toml` (write it with `writeFile`) over ad-hoc installs
  so the environment is reproducible.

## Installing tools whose only installer is a pipe-to-shell script

The sandbox blocks `curl -LsSf https://astral.sh/uv/install.sh | sh`. Do it in three steps instead:

```bash
curl -LsSf https://astral.sh/uv/install.sh -o /tmp/uv-install.sh   # 1. download
# 2. inspect it (readFile /tmp/uv-install.sh) before trusting it
sh /tmp/uv-install.sh                                              # 3. run
```

Or prefer a package-manager install (see `install-tools`) that needs no script at all
(`pipx install poetry`, `brew install uv`).

## Step 4 — verify

- `.venv/bin/python -V` (Windows: `.venv\Scripts\python.exe -V`) — right version.
- `.venv/bin/python -m pip list` — dependencies present.
- Run the project's entry point / tests through the same interpreter path.

## Checklist

- [ ] I detected the existing interpreter with `whichCommand` before installing anything.
- [ ] I created `.venv` and used the venv interpreter by full path (no reliance on `activate`).
- [ ] Dependencies came from a pinned `requirements.txt`/`pyproject.toml` where possible.
- [ ] Any pipe-to-shell installer was downloaded → inspected → run, not piped.
- [ ] I verified the version and installed packages through the venv interpreter.

## Anti-patterns

- `source .venv/bin/activate` in one call and `pip install` in the next — the activation is already gone.
- A bare `pip install` into the system Python instead of the venv.
- Piping an installer straight into a shell (blocked, and unsafe).
