## 1. Console log noise (HIGH)

- [x] 1.1 `logback.xml`: add a `ThresholdFilter` at `ERROR` on the CONSOLE appender only; keep FILE at INFO; add `%nopex` to the console pattern.
- [x] 1.2 `WorkspaceManager.initialize()`: seed an inert `AGENTS.md` via `createIfAbsent` (root cause of the repeated native WARN); add `defaultAgentsMd()`.
- [x] 1.3 Tests: `WorkspaceManagerTest` — seeds `AGENTS.md` alongside `AGENT.md`, idempotent (no overwrite).

## 2. Startup error handling + banner (MEDIUM)

- [x] 2.1 `PigAgentCli.main`: top-level try/catch → print credential-safe root-cause line (`启动失败：…`), `log.error` full stack, `System.exit(1)`; extract the body into `run(args)`.
- [x] 2.2 `PigAgentCli`: emit the banner plain unless VT is clearly supported (`ansiLikelySupported()`); add `rootCause(...)` helper.
- [x] 2.3 Verified by inspection (logback/banner/try-catch are hard to unit-test) + a manual note in the report.

## 3. Model fallback resilience (MEDIUM)

- [x] 3.1 `PigAgentConfig.ModelConfig`: add optional `fallback-model-id` (blank default; null-tolerant setter).
- [x] 3.2 `AgentBootstrap.resolveFallbackModel(config, modelManager, primary)`: configured id → else distinct default → else null; never throws.
- [x] 3.3 Thread the resolved fallback into the interactive + channel `AgentFactory` paths (was always `null`). Peer/autonomous: documented follow-up.
- [x] 3.4 Tests: `ModelConfigTest` (fallback-model-id default + YAML round-trip); `FallbackModelWiringTest` (non-null when configured/distinct-default, null otherwise).

## 4. Lifecycle + config template (LOW)

- [x] 4.1 `AgentBootstrap.Services.shutdownCommon`: close the interactive + channel agents (best-effort, guarded, swallow errors). Per-switch close: follow-up (ModelManager).
- [x] 4.2 `WorkspaceManager.defaultConfigYaml()`: drop stale `model-name`/`max-iters: 10`; emit a commented, current, full-surface template; keep `createIfAbsent`.
- [x] 4.3 Test: `WorkspaceManagerTest` — default-yaml has no stale `mimo-v2.5-pro`, documents the key knobs + `fallback-model-id`, keeps an active `agent:` key so it parses.

## 5. Verify

- [x] 5.1 `mvn -pl pig-agent-cli -am test` GREEN (report counts).
- [x] 5.2 Confirm no out-of-scope files touched.
