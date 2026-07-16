# HarnessAgent vs ReActAgent — decision spike (av2 migration)

Branch: `av2/20260716-harness-spike` (based on `av2/20260716-foundation-main`). Not merged.
Method: `javap` on the actual 2.0.0 jars (`D:/env/.../io/agentscope/agentscope-{core,harness}/2.0.0`) + the **local full AgentScope 2.0 docs** (`D:\Users\admin\Documents\en\`) + an **offline PoC** that compiles and runs (`pig-agent-core/src/test/java/io/pigagent/core/spike/HarnessAgentSpikeTest.java`, 3/3 green).

> **Decision: adopt `HarnessAgent` for the interactive/always-on agent, incrementally, via `HarnessAgent.Builder.fromAgent(ReActAgent)`.** It is a *thin delegating wrapper around the same `ReActAgent`* pig already migrated to (`getDelegate()` returns a `ReActAgent`), it accepts pig's **custom `Toolkit` + custom `MiddlewareBase` + the identical `streamEvents(Msg): Flux<AgentEvent>` surface** (all three proven by the PoC), and every batteries-included extra is behind a `disableXxx()` toggle. Adopting it lets pig **delete** its hand-built session persistence, compaction, two-tier memory (and, more cautiously, parts of multi-agent + channels) while keeping every differentiator. Staying on `ReActAgent` is the fallback if the Phase-2 permission re-architecture proves risky — but it forfeits the free harness deletions and would re-implement session/compaction/memory by hand anyway.

---

## 1. javap-verified API surface

### 1.1 What `HarnessAgent` is (bytecode)

```
public class io.agentscope.harness.agent.HarnessAgent
        implements io.agentscope.core.agent.Agent, java.lang.AutoCloseable {
  public io.agentscope.core.ReActAgent getDelegate();      // <-- composition, NOT inheritance
  public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(io.agentscope.core.message.Msg);
  public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> streamEvents(java.util.List<Msg>);
  public reactor.core.publisher.Flux<AgentEvent> streamEvents(Msg, RuntimeContext); // + String/list overloads
  public reactor.core.publisher.Mono<Msg> call(java.util.List<Msg>);                 // + many overloads
  public void interrupt();  public void interrupt(Msg);
  public io.agentscope.core.tool.Toolkit getToolkit();
  public io.agentscope.core.model.Model getModel();  public int getMaxIters();
  public io.agentscope.core.state.AgentStateStore getStateStore();
  public io.agentscope.core.agent.RuntimeContext getRuntimeContext();
  public void setPermissionMode(RuntimeContext, io.agentscope.core.permission.PermissionMode);
  public io.agentscope.core.permission.PermissionMode getPermissionMode(String, String);
  public void enterPlanMode(RuntimeContext); public void exitPlanMode(...); public boolean isPlanModeActive(...);
  public io.agentscope.harness.agent.gateway.HarnessGateway gateway();
  public <T extends ...channel.Channel> T channel(T);
  public io.agentscope.harness.agent.middleware.CompactionMiddleware getCompactionHook();
  public java.util.List<...AgentSkillRepository> getSkillRepositories();
  public io.agentscope.harness.agent.subagent.DefaultAgentManager getSubagentAgentManager();
  public static io.agentscope.harness.agent.HarnessAgent$Builder builder();
}
```

**Key fact #1 — `HarnessAgent` is NOT a subclass of `ReActAgent`/`AgentBase`.** It `implements Agent` and holds a `ReActAgent` internally, exposed via `getDelegate()`. It is a *delegating wrapper* — which is exactly why every core-level feature (middleware, permission, state, events) is preserved unchanged, and why `fromAgent(ReActAgent)` can lift an existing configured `ReActAgent`.

### 1.2 `HarnessAgent.Builder` — what it ADDS over `ReActAgent.Builder`

Both builders share the core seams (verbatim from javap): `name/description/sysPrompt/model/toolkit/maxIters/hook(s)/middleware(s)/stateStore/defaultSessionId/toolExecutionContext/modelExecutionConfig/toolExecutionConfig/generateOptions/maxRetries/fallbackModel/stopOnReject/permissionContext/enableMetaTool/enableTaskList/enablePendingToolRecovery`.

**Key fact #2 — `HarnessAgent$Builder.fromAgent(io.agentscope.core.ReActAgent)` exists** (static → `Builder`). This is the incremental migration path: build pig's `ReActAgent` as today, then `HarnessAgent.builder().fromAgent(reactAgent)...build()`.

`HarnessAgent.Builder` adds (not on `ReActAgent.Builder`):

| Category | Added builder methods |
|---|---|
| Workspace | `workspace(Path\|String)`, `environmentMemory(String)`, `additionalContextFile`, `useLegacyXmlWorkspaceContext`, `projectGlobalSkillsDir` |
| Memory | `memory(MemoryConfig)`, `disableMemoryTools()`, `disableMemoryHooks()` |
| Compaction | `compaction(CompactionConfig)`, `disableCompaction()`, `toolResultEviction(...)`, `disableToolResultEviction()`, `maxContextTokens(int)` |
| Filesystem/shell | `filesystem(Local/Remote/Sandbox spec)`, `abstractFilesystem(...)`, `disableFilesystemTools()`, `disableShellTool()` |
| Plan mode | `enablePlanMode()`, `planFileDirectory`, `allowShellInPlanMode()` |
| Subagents | `subagent(...)`, `subagents(...)`, `subagentFactory(...)`, `disableSubagents()`, `disableDynamicSubagents()`, `buildSubagentEntries(...)` |
| Channels/gateway | (via `agent.channel(...)` / `gateway()` on the built agent) |
| Skills | `skillRepository(...)`, `enableSkillManageTool`, `enableSkillPromotionGate`, `enableSkillCurator`, `skillFilter`, `enableSkills/disableSkills`, `disableDynamicSkills()`, `disableDefaultWorkspaceSkills()` |
| Sessions | `disableSessionPersistence()`, `distributedStore(...)`, `agentId(...)` |
| Tools config | `toolsConfig(ToolsConfig)`, `disableToolsConfig()`, `disableWorkspaceContext()`, `disableAtPathExpansion()` |

**Key fact #3 — every harness extra is opt-out.** The `disableXxx()` family (filesystem/shell/memory-tools/memory-hooks/session-persistence/workspace-context/subagents/tools-config/compaction/eviction/dynamic-skills/at-path-expansion) means pig can adopt the wrapper and keep its *own* toolkit, guards, memory and session code, turning on native pieces one at a time.

### 1.3 Core seams shared by BOTH agents (so adoption loses nothing)

- **Streaming (Key fact #4):** `streamEvents(Msg): Flux<io.agentscope.core.event.AgentEvent>` is byte-identical to what `PigAgent.stream(Msg)` already calls on `ReActAgent`. Same `call/stream/observe/interrupt` family.
- **Middleware:** `io.agentscope.core.middleware.MiddlewareBase` interface — default methods `onAgent / onReasoning / onActing / onModelCall / onSystemPrompt`. Both builders accept `.middleware(MiddlewareBase)` / `.middlewares(List)`. pig's already-migrated `EphemeralMemoryMiddleware` (implements `onReasoning`+`onSystemPrompt`) needs **zero change**.
- **Permission (Key fact #5):** core package `io.agentscope.core.permission` — enum `PermissionMode { DEFAULT, ACCEPT_EDITS, EXPLORE, BYPASS, DONT_ASK }`, `PermissionEngine`, `PermissionContextState`, `PermissionRule`, `PermissionDecision`. Confirmation is a native event: `RequireUserConfirmEvent.getToolCalls()` → resume with `ConfirmResult(confirmed, toolCall, suggestedRules)`. Accepting `getSuggestedRules()` persists an allow rule = pig's `a`/"always". Both agents expose `setPermissionMode/getPermissionMode`.
- **Events + sub-agent tagging:** `AgentEventType` enum (28 values: `AGENT_START/END/RESULT`, `MODEL_CALL_*`, `TEXT_BLOCK_*`, `THINKING_BLOCK_*`, `DATA_BLOCK_*`, `TOOL_CALL_*`, `TOOL_RESULT_*`, `EXCEED_MAX_ITERS`, `REQUIRE_USER_CONFIRM`, `USER_CONFIRM_RESULT`, `REQUIRE_EXTERNAL_EXECUTION`, `EXTERNAL_EXECUTION_RESULT`, `REQUEST_STOP`, `SUBAGENT_EXPOSED`, `HINT_BLOCK`, `ALL_TOOLS_DENIED`, `CUSTOM`). `AgentEvent.getSource()` returns a slash-separated agent path (`null` at top level, e.g. `"main/researcher"` for a subagent) — the exact tag pig's CC-REPL renderer needs to distinguish sub-agent output.
- **Deferred tools:** `Toolkit` tool groups (`createToolGroup`, `updateToolGroups`, active/inactive) + `enableMetaTool` — this is the native mechanism pig's `deferred-tools` already builds on.

### 1.4 Config value objects (javap)

- `MemoryConfig` (builder): `model(Model)` (separate lightweight model!), `flushPrompt`, `consolidationPrompt`, `consolidationMaxTokens` (4000), `consolidationMinGap`, `dailyFileRetentionDays` (90), `sessionRetentionDays` (180), `flushTrigger`.
- `CompactionConfig` (builder): `model(Model)` (separate lightweight model!), `triggerMessages` (50), `triggerTokens` (80000), `keepMessages` (20), `keepTokens`, `keepTokensRatio`, `summaryPrompt`, `flushBeforeCompact`, `offloadBeforeCompact`, `prune`, `truncateArgs`.

Both natively support the "summarize with a cheaper model, keep-recent-N, preserve a raw log" design pig hand-built in `CompressionService`.

---

## 2. Decision table — pig differentiator × HarnessAgent

Legend: **SUPPORT** = works as-is / native; **ADAPT** = works but pig writes a thin layer or changes shape; **CONFLICT** = genuine friction.

| pig differentiator | Verdict | Basis |
|---|---|---|
| **CC-REPL rendering / `streamEvents`** | **SUPPORT** | Identical `streamEvents(Msg): Flux<AgentEvent>` (javap + PoC). Same 28-type event stream; `getSource()` gives sub-agent tags for the renderer. No renderer change vs the ReActAgent path already planned for Phase 4. |
| **Kernel façade (`AgentKernel`)** | **SUPPORT/ADAPT** | The façade depends only on `chat/stream/interrupt/useAgent/...`. HarnessAgent satisfies chat/stream/interrupt directly. `AgentKernel` keeps orchestrating; multi-agent semantics change shape (see below). |
| **Custom `Toolkit` + guards (contract/availability/deferred/MCP/SSRF/command-sandbox)** | **SUPPORT** | `.toolkit(Toolkit)` accepted; PoC shows a custom `@Tool` visible + dispatched on HarnessAgent. Built-in workspace tools are opt-out via `disableFilesystemTools()`/`disableShellTool()` (or `tools.json` allow/deny), so pig's `ShellTools`/`CommandGuard`/`FileSystemTools` don't collide. Guards are `Toolkit`/`AgentTool` decorators — unchanged. |
| **`EphemeralMemoryMiddleware`** | **SUPPORT** | Attaches via `.middleware(...)`; PoC proves it's wired into the delegate's chain **and fires** (retrieve() called during a run). Harness runs user middleware *before* its built-ins. |
| **Permission system (plan/ask/auto/bypass, y/a/N)** | **ADAPT (delete-and-map)** | Native `PermissionMode` maps 1:1: plan→`EXPLORE`, ask→`DEFAULT`, auto→`ACCEPT_EDITS`, bypass→`BYPASS`, unattended→`DONT_ASK`. `a`/always = accept `suggestedRules`. pig deletes its `ToolPermissionHook`/policy and maps `/permission` onto the native engine. (This is Phase 2 either way — not harness-specific.) |
| **State store / sessions** | **SUPPORT (delete)** | Native `AgentStateStore` keyed by `(userId, sessionId)`, auto load/save per call; `JsonFile`/`Redis`/`MySQL`/`OSS`. Replaces pig's hand-built `SessionManager`. `disableSessionPersistence()` if pig wants to keep its own. |
| **Digital-employee (cron autonomous + morning report)** | **ADAPT** | No cron primitive in harness (pig keeps `TaskScheduler`). Run path improves: `call()` with a synthetic `RuntimeContext` (anonymous userId) + `PermissionMode.DONT_ASK` (ASK→DENY, enforced-but-unattended) — cleaner than pig's fail-closed hack. Report writing stays pig's `FileReportWriter`. |
| **Multi-agent (`AgentRegistry`, `AgentSpec`, `/agent use`)** | **ADAPT / partial CONFLICT** | See §4. Native `subagents/<id>.md` / `SubagentDeclaration` covers per-agent model/tools/prompt/workspace, and `GatewayBootstrap` routes to named peer agents — but the shapes differ from pig's "switch the active peer agent" registry, and the subagent spec has **no per-agent permission field** (permission = `PermissionMode` + `tools.json`). Not a 1:1 drop-in. |
| **`/compress` + `/memory` UX** | **SUPPORT (thin layer)** | Native compaction (`CompactionConfig`, cheaper model, keep-recent, raw-log lineage) + two-layer memory (`MEMORY.md` + daily logs, `memory_search`/`memory_get`). `/compress` → force-compaction; `/memory` → toggle/inspect the memory files/tools. pig keeps the commands, deletes the engines. |
| **MCP / plugin / skills SPIs** | **SUPPORT** | MCP native (`registerMcpClient`, `mcp__server__tool` namespace); skills native (4-layer + repositories + curator, superset of pig's SKILL.md). pig's plugin SPI stays (it contributes tools/hooks into the same `Toolkit`/middleware). |
| **Proactive outreach / channels** | **SUPPORT/ADAPT** | Native Gateway+Channel: ships DingTalk/Feishu/WeCom/GitHub/GitLab + a chat UI; `sendStream()` returns the same `Flux<AgentEvent>`. Telegram/Discord/Slack remain custom `Channel` impls (same SPI). Gateway subsumes pig's "agent bridge" (session mapping, per-session concurrency, multi-agent routing). |
| **Multi-protocol model brain** | **SUPPORT** | Unchanged — `.model(Model)` takes any `Model`; pig's `ProtocolRegistry`/decorators (retry/interrupt) still wrap the `Model` and pass in. `maxRetries`/`fallbackModel` are also native at the builder. |

---

## 3. What pig can additionally DELETE under HarnessAgent (vs staying on ReActAgent)

Everything in Phase 2 (native permission) is the same work on either agent. The *extra* deletions HarnessAgent unlocks:

| Delete / stop-maintaining | Replaced by | Confidence |
|---|---|---|
| `CompressionService` + `compression/ContextEngineer` pipeline (budget/recursive-summary/importance/verbatim/consistency) | `CompactionConfig` (trigger tokens/messages, keep-recent, cheaper model, tool-result eviction, overflow recovery) | High — native superset; port `/compress` as a thin command |
| Two-tier `LongTermMemory` (`CompositeLongTermMemory` global+session markdown) + `memory/extraction` pipeline | Native two-layer memory (`MEMORY.md` + `memory/YYYY-MM-DD.md`, LLM flush/consolidation, `memory_search`/`memory_get`), per-tenant via `IsolationScope` | Medium — semantics differ (per-day-per-scope vs per-session); keep `EphemeralMemoryMiddleware` if pig wants its exact prefix-cache injection |
| `SessionManager` + `FileSystemSessionRepository` + JsonSession rewrite (the whole Phase-3 "session state rewrite") | Native `AgentStateStore` `(userId,sessionId)` + workspace `*.log.jsonl` | High — this is the single biggest saved effort; Phase 3 largely evaporates |
| `compression-lineage` (`SessionLineageWriter`) | Native raw-log offload (`offloadBeforeCompact`) + `session_search` | Medium |
| Plan mode (pig has none — but `permissions.plan` read-only) | Native `enablePlanMode()` + `plan_enter/write/exit` + HITL exit | Bonus (new capability, ~free) |
| Task list (pig's `/tasks` is a *scheduler*, different) | `enableTaskList()` (LLM todo decomposition) — **additive, does not replace `TaskScheduler`** | Bonus |
| Deferred-tools plumbing (partial) | Native tool groups + `enableMetaTool` (pig already builds on this) | Already aligned |
| Parts of channel bridge + several channel adapters | Native Gateway + shipped DingTalk/Feishu/WeCom/GitHub/GitLab | Medium — Telegram/Discord/Slack stay custom |

Staying on **ReActAgent** keeps *all* of the above as pig-maintained code (and Phase 3 must still be written by hand on `AgentStateStore`). HarnessAgent is the difference between "port the session/compaction/memory layer" and "delete it."

---

## 4. The multi-agent nuance (coordinator Q1)

pig's `AgentSpec` (per-agent model / tool subset / **permission mode** / prompt, persisted `agents/{id}.md`, switchable via `/agent use`) does **not** map 1:1 to native subagents:

- **`subagents/<id>.md` / `SubagentDeclaration`** front-matter carries `description / workspace(mode) / model (inherits parent) / tools (allowlist)` + body=prompt. That covers model/tools/prompt/workspace — **but not a per-agent permission field** (permission is governed by `PermissionMode` + `tools.json` allow/deny, not the spec). So pig's "own permission per agent" must move to those mechanisms.
- **Shape difference:** native subagents are *children* of one `HarnessAgent` (spawned via `agent_spawn`, background + auto reverse-notify, `expose_to_user`, `SubagentExposedEvent`), not *peer agents you switch the active one between*. pig's `/agent use <id>` (make X the active interactive agent) is closer to **`GatewayBootstrap.builder().agent("a",...).agent("b",...).mainAgent("a")` + `SendOptions.withAgentId(...)`** than to subagents.

**Verdict:** multi-agent is **ADAPT, not wholesale REPLACE.** pig can (a) express secondary/worker agents as native subagents (gaining background tasks, reverse-notify, remote, distributedStore for free), and (b) express top-level switchable personas via the Gateway multi-agent registry — but the `AgentKernel` façade + `/agent` UX need re-mapping, and per-agent permission moves to `PermissionMode`/`tools.json`. Recommend keeping pig's `AgentRegistry`/kernel as the top-level orchestrator initially and adopting native subagents *underneath* a HarnessAgent, rather than deleting multi-agent-kernel in the same pass.

---

## 5. PoC — what actually compiled and ran

File: `pig-agent-core/src/test/java/io/pigagent/core/spike/HarnessAgentSpikeTest.java` (throwaway, clearly labelled).
Run: `mvn -pl pig-agent-core test -Dtest=HarnessAgentSpikeTest` → **Tests run: 3, Failures: 0, Errors: 0**. Fully offline (fake `Model`, `@TempDir` workspace, all `disableXxx()` toggles). `agentscope-harness` is **already** a compile dependency of `pig-agent-core`, so no pom change was needed.

| Test | Proves | Result |
|---|---|---|
| `customToolkitAndMiddlewareAreAccepted_andAgentDelegatesToReActAgent` | Custom `Toolkit` + `@Tool` visible on the built HarnessAgent (`getToolkit().getToolNames()` contains it); `getDelegate() instanceof ReActAgent`; `EphemeralMemoryMiddleware` is in `getDelegate().getMiddlewares()` | **PASS** — custom toolkit + custom middleware accepted; wrapper-over-ReActAgent confirmed |
| `streamEventsRunsTextTurn_andMiddlewareFires` | `streamEvents(Msg)` runs a full turn (aggregated `TextBlockDeltaEvent` = "hello from fake"); the middleware's `onReasoning` **executed** (spy `LongTermMemory.retrieve` called ≥1) | **PASS** — identical stream surface + middleware fires on HarnessAgent |
| `streamEventsExecutesCustomTool_underHarnessAgent` | The custom tool is **dispatched** by HarnessAgent's ReAct loop (`ToolCallStartEvent.getToolCallName() == "spikeEcho"`); the turn completes ("done") | **PASS** — custom-toolkit tool is selected + dispatched under HarnessAgent |

**Honest limitations of the PoC:**
- The fake single-shot `ChatResponse` proves *dispatch* (`TOOL_CALL_START` for the custom tool) but does not always drive the full *acting/execution* phase (`TOOL_RESULT_*`) — a real streaming model does. An earlier console trace *did* show `PRE_ACTING → ToolExecutor → POST_ACTING` firing for `spikeEcho` under HarnessAgent, but that path also surfaced a reflective **parameter-name binding** quirk (inline test tool compiled without `-parameters` → arg maps as `arg0`, not `text`). This is a test-harness artifact, **not** a HarnessAgent limitation; the seam under test (custom toolkit accepted + tool dispatched) is proven.
- No live model was used (spike is offline). Real-model behaviour (streaming tool execution, permission HITL round-trip, compaction/memory LLM calls) needs a `*IT` before production adoption.

---

## 6. Recommendation, Phase 2-4 impact, risks

### Recommendation
**Adopt `HarnessAgent` for the interactive + always-on agent, via `fromAgent(ReActAgent)`, incrementally.** Rationale (the 4 facts that drive it): (1) it *delegates to* the same `ReActAgent` pig migrated to — not a fork; (2) `fromAgent(ReActAgent)` is a real, low-friction migration path; (3) the PoC proves pig's three core seams (custom Toolkit, custom middleware, `streamEvents`) survive; (4) every harness extra is opt-out, so adoption is reversible and staged. The payoff is deleting pig's session/compaction/memory layers (and shrinking Phase 3 dramatically) with no loss of differentiators.

### Phase 2-4 impact
- **Phase 2 (tools/permission)** — *unchanged by this decision.* Native `PermissionEngine`/`PermissionMode`/`ToolResultState.DENIED` re-arch is the same work on either agent; the custom `Toolkit` + guards survive (PoC). Do Phase 2 on `ReActAgent` as planned, then wrap.
- **Phase 3 (session state)** — *largely deleted.* Instead of rewriting `SessionManager` onto `AgentStateStore`, adopt HarnessAgent's native session persistence (`(userId,sessionId)` + workspace log). This is the biggest single saving. Also fold native compaction + memory here (delete `CompressionService`, retire the two-tier memory or keep only `EphemeralMemoryMiddleware`).
- **Phase 4 (frontends)** — *unchanged surface.* `renderStream` aggregates the same `AgentEvent` stream; `AgentBootstrap` builds one `HarnessAgent` (`fromAgent` the configured `ReActAgent`) with pig's toolkit + middleware + `disableXxx()` for anything pig keeps in-house, then turns native pieces on. `AgentKernel` keeps driving `chat/stream/interrupt`. Re-map `/agent`, `/compress`, `/memory`, `/permission` onto native mechanics.

### Risks
- **Multi-agent shape mismatch** (see §4) — do NOT try to delete `AgentRegistry`/`/agent` in the same pass; keep the kernel on top and adopt native subagents underneath. Per-agent permission must move to `PermissionMode`/`tools.json`.
- **Memory semantics differ** (per-day-per-scope vs pig's per-session markdown) — validate retention/sharing with `IsolationScope.SESSION` before deleting the two-tier memory; keep `EphemeralMemoryMiddleware` for the exact prefix-cache injection pig relies on.
- **`enableTaskList` ≠ pig's `/tasks`** — the native task list is LLM todo decomposition, not a cron scheduler; keep `TaskScheduler`.
- **Harness pulls extra deps** (`sqlite-jdbc`, `commons-compress`) — already resolved locally; fine.
- **Real-model gaps** — permission HITL round-trip, streaming tool execution, and the compaction/memory LLM calls are unproven offline; gate production adoption on a real-model `*IT`.
- **Docs are ReActAgent-centric** — the building-block docs almost never mention HarnessAgent; the PoC (not the docs) is what empirically confirms middleware/custom-toolkit parity. Trust bytecode + the PoC over prose.

### Fallback
If Phase 2's permission re-arch or the multi-agent re-mapping proves too risky, **stay on `ReActAgent`** and hand-write Phase 3 on `AgentStateStore`. This keeps pig in full control but forfeits the free session/compaction/memory deletions and re-implements them by hand. The decision is reversible precisely because HarnessAgent is a thin `fromAgent` wrapper — pig can adopt it for the interactive agent first and revert per-surface if needed.

---

## 7. Notes: docs vs the earlier web/summary framing

The local docs *broaden* the earlier "plan mode / task list / workspace tools / memory+compaction" framing rather than contradict it: HarnessAgent also natively provides **subagents, Gateway/Channel, skills+curator, sandbox/filesystem modes, and `(userId,sessionId)` session persistence**. Two precisions worth recording: **task list (`enableTaskList`) is a *core* `ReActAgent` feature, not harness-only** (only **plan mode** is harness-only); and **the subagent declarative spec has no permission field** (permission is `PermissionMode` + `tools.json`), so pig's per-agent permission does not port as a spec field. Where docs and prose diverged, this spike follows javap + the runnable PoC.
