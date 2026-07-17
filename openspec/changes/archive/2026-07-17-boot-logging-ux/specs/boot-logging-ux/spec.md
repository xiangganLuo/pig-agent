## ADDED Requirements

### Requirement: Clean interactive TUI (no logger noise)

The interactive REPL SHALL NOT be polluted by routine logger output. The CONSOLE log appender MUST
only emit `ERROR` (and above); all `INFO`/`WARN` startup and rebuild output (model switches, MCP and
memory rebuilds, config-drift warnings, the native workspace scan) MUST go to the FILE appender only,
which MUST retain full `INFO` + stack traces for diagnostics. Genuinely user-facing startup facts are
printed via the banner / `Ansi`, not the logger.

Additionally, the workspace MUST seed an inert `AGENTS.md` (plural) alongside the editable `AGENT.md`
(singular) so the underlying harness's `AGENTS.md` workspace scan finds a file and stays silent on
every agent build/rebuild. The seeded `AGENTS.md` MUST NOT be assembled into PigAgent's system prompt.

#### Scenario: Console suppresses non-error logs

- **WHEN** the agent logs an `INFO` or `WARN` line during startup or a mid-chat rebuild
- **THEN** the line does not appear on the console (TUI), and the full line is written to the FILE log

#### Scenario: AGENTS.md seeded to silence the native scan

- **WHEN** a fresh workspace is initialized
- **THEN** both `AGENT.md` (the editable prompt) and an inert `AGENTS.md` exist, and re-initializing
  does not overwrite an existing `AGENTS.md`

### Requirement: Readable startup-failure message

A bootstrap/onboarding failure SHALL surface as one readable, credential-safe line, not a raw Java
stack. `PigAgentCli.main` MUST guard its body: on an exception it MUST print the root-cause message
(credential-redacted) as a single error line, log the full stack to the FILE log, and exit with a
non-zero status.

#### Scenario: Bootstrap exception prints one line

- **WHEN** bootstrap or onboarding throws during startup
- **THEN** one credential-safe root-cause line is printed to the console, the full stack is logged to
  FILE, and the process exits with status 1

### Requirement: Legacy-console-safe startup banner

The startup banner (emitted before JLine enables VT) SHALL NOT print raw ANSI escapes on a console
that cannot render them. The banner MUST be styled only when VT support is clearly indicated
(interactive console plus a TERM/OS/terminal signal); otherwise it MUST be emitted as plain text.

#### Scenario: Plain banner on a non-VT console

- **WHEN** stdout is redirected/piped or the console is a legacy Windows console with no VT signal
- **THEN** the banner is printed as plain text (no ANSI escape sequences)

### Requirement: Config-driven native model failover

The system SHALL support an optional `model.fallback-model-id` so a primary-model outage
auto-degrades to a working saved model. `AgentBootstrap` MUST resolve the fallback (the configured id
when set, resolvable, and distinct from the primary; otherwise the store's default when distinct from
the primary; otherwise none) and thread it into the interactive and channel agent build paths. When
no distinct fallback is configured, the resolved fallback MUST be `null` (today's behavior). Fallback
resolution MUST NOT throw.

#### Scenario: Configured distinct fallback is wired

- **WHEN** `model.fallback-model-id` names a saved model distinct from the primary
- **THEN** a non-null fallback model is wired into the interactive and channel agents

#### Scenario: No distinct fallback yields none

- **WHEN** no `model.fallback-model-id` is configured and the store's default equals the primary
- **THEN** the resolved fallback is `null` (no behavior change)

### Requirement: Agent lifecycle close on graceful exit

On graceful exit the shared shutdown path SHALL close the interactive and channel agents (releasing
the `HarnessAgent` vehicle resources). The close MUST be best-effort: any failure is swallowed and
logged so it never aborts the rest of shutdown.

#### Scenario: Agents closed during shutdown

- **WHEN** the CLI shuts down gracefully
- **THEN** the interactive and channel agents are closed, and a close failure does not abort the
  remaining shutdown steps

### Requirement: Authoritative seeded config template

The seeded default `application.yaml` SHALL be a commented, current, full-surface template rather than
a stale stub. It MUST NOT carry the stale `model-name` stub (models live in `models.json`) nor a stale
`max-iters` value, MUST document the key config knobs (permissions, compression, memory, sandbox,
loop-detection, retry, `fallback-model-id`) as commented examples, and MUST remain `createIfAbsent`
(never overwriting a user's existing file) while still parsing to a non-null config.

#### Scenario: Default template drops stale values and documents the surface

- **WHEN** a fresh workspace seeds `application.yaml`
- **THEN** the file contains no stale `model-name`/`max-iters: 10` values, documents the key knobs
  (including `fallback-model-id`), and keeps at least one active top-level key so it parses
