# Design — subagent-online-switch (方案2)

## Spike outcome (javap + offline PoC) — the gate

Confirmed against `agentscope-core`/`agentscope-harness` 2.0.0 (sources jars):

- **(a) Signatures.** `ChatUiChannel.sendToSubagent(String, String): Mono<Msg>` and
  `sendToSubagentStream(String, String): Flux<AgentEvent>` both delegate to
  `HarnessGateway.runSubagent(...)`/`runSubagentStream(subagentId, List<Msg>)`. `SubagentExposedEvent`
  getters: `getSubagentId()`, `getAgentId()`, `getSessionId()`, `getLabel()` (all `String`).
  `HarnessAgent.channel(T): T` and `HarnessAgent.gateway(): HarnessGateway` both call the same lazy
  `ensureGateway()`. `agent_spawn` has an `expose_to_user` boolean param; on expose it emits a
  `SubagentExposedEvent` onto the parent's `streamEvents` via `withSubagentExposedEvent(...)`.
- **(b) Enumeration.** There is **no** gateway API to list exposed subagents (`HarnessGateway` keeps a
  private `exposedSessions` map; `agent_list` lists spawned, not exposure state). → the exposed set is
  **tracked from `SubagentExposedEvent`s** seen on the parent stream.
- **(c) The critical risk — NO regression.** `ensureGateway()` creates a **separate, lazy**
  `HarnessGateway`, calls `bindMainAgent(this)` + `setGatewayBridge(bridge)` on the subagent middleware +
  `channel.init(...)`. The direct chat path (`PigAgent.stream → HarnessAgent.streamEvents →
  delegate.streamEvents`) **never routes through the gateway** — no fair-queuing, no routing engine.
  The only side effect on the agent is wiring the expose bridge (exactly the enabler we want). The
  offline PoC (`SubagentExposeSpikeTest`) builds a subagent-enabled agent, binds the gateway, and shows
  the normal `stream("hi")` still yields the model's answer, AND drives the full expose→switch flow.

**Verdict: PROCEED with the full switch** (no view-only fallback needed).

## Key decisions

1. **Bind the gateway off the active agent's OWN harness, eagerly at build (when subagents enabled).**
   Rather than wire a `GatewayChannelKernel` in `AgentBootstrap`/`PigAgentCli` over one fixed harness
   (which a model switch would leave stale — the documented `channel-gateway` caveat), `PigAgent.build()`
   calls `harness.gateway()` when `subagentsEnabled`. Subagents are enabled only on interactive + peer
   tracks (channel/autonomous keep them off), so this is exactly scoped. **Solves the model-switch
   rebind for free:** a rebuilt agent (new harness) gets a fresh gateway; the kernel routes
   `chatWithSubagent` through `registry.active().agent().streamSubagent(...)`, always the current agent.
   No `AgentBootstrap`/`PigAgentCli` edit — minimal blast radius, no overlap with a parallel fixer.

2. **The `AgentKernel` façade is the single frontend seam.** It owns the exposed-subagent registry
   (`ExposedSubagent` records, populated via `noteSubagentExposed` from the REPL's stream renderer),
   exposes `listSubagents()`/`subagentOutput(id)`, and routes `chatWithSubagent(id, msg)` (interrupt-
   wrapped like `chat`). The REPL never touches the gateway/`GatewayChannelKernel` directly. Tracked
   subagents are cleared on `useAgent`/active-`updateAgent` (they live on the prior gateway).

3. **Switch state lives in the REPL, shared via `ReplContext`.** A tiny mutable `SubagentSwitchState`
   (a single nullable id) is owned by `AgentRepl` and handed to `AgentCommand` through `ReplContext`.
   `runTurn` reads it: switched ⇒ route to `chatWithSubagent` (no parent session note/compress/save,
   since the subagent has its own conversation); the prompt shows `[sub <id>]`; a stale id falls back to
   the parent. `ReplContext` gets a backward-compatible 16-arg constructor so existing call sites/tests
   are untouched.

4. **`/agent sub` under the existing `AgentCommand`.** Reuses the command tree + `ReplContext`; no new
   top-level command. Actions: `list` / `view <id>` / `switch <id>` / `back`. `switch` validates the id
   against `kernel.subagentOutput(id)`.

## Falls-out / follow-up tracking table

| Finding | Landing | Status |
|---|---|---|
| Gateway binding must not regress interactive path | Spike PoC `SubagentExposeSpikeTest` gate 1 | Implemented |
| No native enumeration of exposed subagents | Track from `SubagentExposedEvent` on the kernel | Implemented |
| Model-switch rebind caveat (channel-gateway) | Bind off active agent's harness + clear tracked on switch/rebuild | Implemented |
| Only `expose_to_user` subagents switchable | `switch` gated on `kernel.subagentOutput(id)`; non-exposed = view-only `task_output` | Implemented |
| Subagent-turn interrupt | `chatWithSubagent` wraps in `TurnHandle`/`takeUntilOther` (stream-termination, best-effort) | Implemented |
| Real model expose+switch | Deferred to a `*IT`; 方案3 shelved | Deferred |

## Limits

- Offline tests use scripted fake models; a live model actually choosing to expose + a real switch is a
  `*IT`. Exposed subagents do not survive a model switch (fresh gateway) — tracked handles are cleared.
  The subagent-turn interrupt terminates the frontend stream (the child's own cooperative interrupt is
  best-effort).
