## Why

Real-usage review surfaced a batch of startup / logging / lifecycle / resilience rough edges:

- **Logger noise leaks into the TUI.** INFO startup/rebuild lines and a repeating native
  `AGENTS.md not found` WARN print mid-chat into the Claude-Code-style REPL, garbling it. The user
  literally saw a `WorkspaceManager - AGENTS.md not found` WARN during a conversation.
- **Bootstrap/onboarding failures dump a raw Java stack** to stderr (no top-level guard), so a
  first-run misconfiguration is a wall of frames instead of one readable line.
- **The ANSI banner is emitted before JLine enables VT**, so on a legacy console the escapes print
  raw (`←[1m…`).
- **`fallbackModel` is dead code.** Every build path passes `null`, so native model failover — a
  key gap for a 24h always-on assistant — never triggers even though the plumbing supports it.
- **Agents are never closed on graceful exit**, and the seeded default `application.yaml` is a thin,
  stale stub (`model-name: mimo-v2.5-pro`, `max-iters: 10`) that mismatches real defaults.

## What Changes

- **Console log threshold → ERROR (CONSOLE appender only).** The interactive TUI shows essentially
  no logger output; the FILE appender keeps full INFO + stack traces for diagnostics. `%nopex` keeps
  even a console ERROR to one line.
- **Seed an inert `AGENTS.md`** alongside `AGENT.md` (`createIfAbsent`) so the harness's plural
  `AGENTS.md` workspace scan finds a file and stays silent on every build/rebuild. pig's editable
  system prompt remains `AGENT.md` (singular); the plural file is not assembled into the prompt.
- **Top-level try/catch in `PigAgentCli.main`.** On failure: print one credential-safe root-cause
  line (`启动失败：…`), log the full stack to FILE, exit 1.
- **Safe startup banner.** Emit the banner styled only when VT is clearly supported (interactive +
  TERM/OS/Windows-Terminal signal); otherwise plain — no raw escapes on a legacy console.
- **Wire `model.fallback-model-id` (new, optional).** `AgentBootstrap` resolves it (else the store's
  default when distinct from the primary) and threads it into the interactive + channel
  `AgentFactory` paths, so a primary-model outage auto-degrades to a working saved model. Null-safe
  (nothing distinct configured → `null` → today's behavior). Peer/autonomous tracks: follow-up.
- **Close agents on graceful exit** in `shutdownCommon` (best-effort, guarded, errors swallowed).
- **Authoritative default `application.yaml`.** Drop the stale `model-name`/`max-iters` stub; emit a
  commented, current, full-surface template (permissions / compression / memory / sandbox /
  loop-detection / retry / fallback-model-id / …). Still `createIfAbsent` (never overwrites a user's
  file); parses to all-defaults except a harmless active `agent.name`.

Non-goals: no OS-level isolation, no per-switch agent close (ModelManager, follow-up), no
peer/autonomous fallback wiring (follow-up).

## Capabilities

### New Capabilities
- `boot-logging-ux`: a clean interactive TUI (no logger noise), a readable one-line startup-failure
  message, a legacy-console-safe banner, config-driven native model failover, agent lifecycle close
  on exit, and an authoritative seeded config template.

## Impact

- **Code:** `pig-agent-cli` (`logback.xml`, `PigAgentCli`, `AgentBootstrap`); `pig-agent-workspace`
  (`WorkspaceManager` — seed `AGENTS.md`, rewrite default yaml); `pig-agent-config` (`PigAgentConfig`
  — additive `model.fallback-model-id`).
- **Tests:** `WorkspaceManager` seeds `AGENTS.md` + default-yaml sanity; `PigAgentConfig`
  fallback-model-id round-trip; `AgentBootstrap.resolveFallbackModel` wiring. logback/banner/main
  try-catch verified by inspection.
- **Docs:** none required (config surface documented inline in the seeded template).
