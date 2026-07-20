## Why

When the active agent spawns a subagent with `expose_to_user=true`, AgentScope 2.0 registers it as a
user-addressable session and emits a `SubagentExposedEvent`. Today the REPL only prints a one-line
note (F3 surfaced background spawns but has **no switch**) — there is no way to view the exposed
subagents or talk to one directly. This is 方案2: let the user **view + switch into** an exposed
subagent from the REPL and continue the child's own conversation, then return to the parent.

The native switch primitive already exists (`ChatUiChannel.sendToSubagent`/`sendToSubagentStream` over
the `HarnessGateway`; pig also has `GatewayChannelKernel` wrapping it), but it is **not wired into the
REPL**, which drives everything through the `AgentKernel` façade. The kernel-façade rule requires any
new capability be exposed **through the façade**, never by leaking gateway/registry internals to the
frontend.

## What Changes

- **SPIKE (load-bearing, done first):** javap-confirm the native signatures + write an offline PoC
  proving that binding the gateway onto the interactive agent does **not** regress the direct
  `stream(...)` chat path. Outcome: **PROCEED** — `harness.gateway()` is a lazy, separate object; the
  direct `streamEvents` path never routes through it, so no fair-queue/routing regression. The PoC also
  proves the full expose→switch flow offline with scripted models.
- `PigAgent`: eagerly initialize the native gateway at build **when subagents are enabled** (the
  documented `expose_to_user` enabler — wires the `SubagentGatewayBridge`), and add
  `streamSubagent(subagentId, msg)` routing a turn to an exposed subagent via
  `HarnessGateway.runSubagentStream`.
- `AgentKernel` façade: add `noteSubagentExposed(...)` / `listSubagents()` / `subagentOutput(id)` /
  `chatWithSubagent(id, msg)` (+ a new `ExposedSubagent` value type). The kernel owns the
  exposed-subagent list (populated from `SubagentExposedEvent`s the frontend sees) and clears it on an
  agent switch/rebuild (exposed subagents belong to the previous agent's gateway).
- REPL: `/agent sub list|view <id>|switch <id>|back` (a subcommand under `AgentCommand`) + an
  `AgentRepl`-owned `SubagentSwitchState`; while switched, `runTurn` routes input via
  `chatWithSubagent` (NOT the parent), the prompt shows `[sub <id>]`, and `back` returns to the parent.
  `onEvent` tracks exposed ids on the kernel and shows a switch hint. Rendering reuses the existing
  stream renderer.
- Only `expose_to_user` subagents are switchable; non-exposed background spawns stay view-only via
  their `task_output`.

## Capabilities

### New Capabilities
- `subagent-online-switch`: from the REPL, view the subagents the active agent exposed to the user and
  switch into one to continue its conversation directly (then return to the parent), routed through the
  `AgentKernel` façade over the native gateway.

## Impact

- **Code:** `pig-agent-core` (`PigAgent.streamSubagent` + eager gateway init; `AgentKernel` façade
  methods + `ExposedSubagent`), `pig-agent-cli` (`AgentCommand /agent sub`, `SubagentSwitchState`,
  `AgentRepl` routing + tracking, `ReplContext`). Edits to `AgentBootstrap`/`PigAgentCli` are avoided —
  the gateway is bound off the active agent's own harness, so a model switch that rebuilds the agent
  gets a fresh gateway automatically (the tracked exposed subagents are cleared on switch/rebuild).
- **Behavior:** additive and default-invisible — no exposed subagent ⇒ `/agent sub list` is empty and
  chat behaves exactly as today. Binding the gateway is spike-proven not to regress the interactive
  stream.
- **Limits:** real-model behavior (a live model deciding to expose + a live switch) is deferred to a
  `*IT`; 方案3 (a separate always-on window per subagent) is shelved. A subagent-turn interrupt is
  stream-termination (best-effort), and exposed subagents do not survive a model switch (documented).
