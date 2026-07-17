# AgentScope Java 1.0.12 → 2.0 Migration Map

> **Status: Phase 0-redux + Phase 1 + Phase 2 (tools framework + native permission) + Phase 3
> (session/state rewrite) + Phase 4 (frontends: cli + web + channel) + Phase 5a (delete self-built
> decorators → native retry/interrupt, hooks → native middleware, drop the 1.x `agentscope` dep)
> + Phase 5b (adopt the `HarnessAgent` vehicle + native tool-result eviction) + Phase 6a (adopt native
> subagent delegation, opt-in) + Phase 6b (subagent permission inheritance + command-granular allowlist
> + the P0 contract-guard permission-bypass fix; `subagents.enabled` default flipped ON) COMPLETE.**
> Phase 6b lives on branch `av2/20260716-subagent-perms` (off `av2/20260716-foundation-main`): pig
> enforces subagent permission inheritance itself (custom `subagentFactory` builds each leaf child under
> a fail-closed context derived from the parent — parent DENY binds the child), honors command-granular
> `allowlist.commands` via a `CommandPermissionTool` (`ToolBase.checkPermissions`), and fixes a P0 where
> the contract guard (a non-`ToolBase` wrapper) was bypassing the native permission engine for every
> guarded tool in production. Whole reactor GREEN (1034 tests). See **§15 Phase 6b execution log**.
> Phase 5b lives on branch `av2/20260716-harness-adopt` (off `av2/20260716-foundation-main`): every
> `PigAgent` now wraps a `HarnessAgent` built directly via `HarnessAgent.builder()` (carrying pig's
> exact `Toolkit`/middlewares/`stateStore`/`permissionContext`/`maxRetries`/`fallbackModel`/`maxIters`),
> with the native filesystem/shell/memory/compaction/subagent/workspace-context/session-log features
> **disabled** (pig keeps its differentiated ephemeral prefix-cache memory + A4 extraction + A5
> context-engineering compaction + guarded tools + `AgentStateStore`) and native **tool-result
> eviction** turned ON — the one capability pig lacked. `PigAgent`'s method surface is unchanged
> (cli/web/channel/kernel untouched). See **§13 Phase 5b execution log**.
> Phase 5a lives on branch `av2/20260716-cleanup` (off `av2/20260716-foundation-main`): it deletes
> `RetryingModel`/`RetryPolicy`/`TransientErrorClassifier` + `InterruptibleModel` (→ native
> `ReActAgent.Builder.maxRetries`/`.fallbackModel` + `ReActAgent.interrupt`), migrates the last three
> deprecated hooks (`EphemeralMemoryContextHook`, `LoopDetectionHook`, `LoggingHook`/`ToolCallLoggingHook`)
> + the plugin SPI to native `MiddlewareBase`, and removes the legacy 1.x `io.agentscope:agentscope`
> depMgmt + `agentscope.version` property from the parent POM. **No pig code imports
> `io.agentscope.core.hook.*` anymore; the reactor builds on pure 2.0 artifacts.** See **§12 Phase 5a
> execution log** for the javap-grounded retry/interrupt decisions, the prefix-cache/loop preservation
> proofs, and the remaining Phase-5b/6 open items.
> Phase 4 lives on branch `av2/20260716-frontends` (off `av2/20260716-foundation-main`): the **whole
> reactor compiles + unit-tests GREEN on 2.0**. It wires native permission
> (`PermissionContextFactory.build(...)` → `ReActAgent.Builder.permissionContext(...)` via
> `PigAgent.Builder`, runtime `/permission mode` → `setPermissionMode`, ASK→HITL via
> `RequireUserConfirmEvent`/`ConfirmResult`), the shared `JsonFileAgentStateStore` (root
> `workspace/state/`) through `AgentFactory`/`AgentInstanceFactory`, and rewrites the CLI/web/channel
> event rendering onto the typed `AgentEvent` stream. STAYS on the `ReActAgent`-based `PigAgent` (the
> HarnessAgent wrap + native compaction/memory/subagent/plan-mode are Phase 5). See **§11 Phase 4
> execution log** for the wiring, the security findings closed (C-1/H-1/M-1/M-2/M-3/L-1/L-2), the tests
> rewritten, and Phase-5 open items.
> Phase 3 lives on branch `av2/20260716-session-state` (off `av2/20260716-foundation-main`): it
> rewrites `pig-agent-session` onto the native `AgentStateStore` (`io.agentscope.core.session.Session`/
> `JsonSession` deleted), threads a per-`(userId,sessionId)` `RuntimeContext` through
> `PigAgent`/`AgentKernel`, and keeps pig's `Session` metadata + compression-lineage as a thin sidecar.
> See **§10 Phase 3 execution log** for the design, the javap-confirmed `AgentStateStore`/
> `RuntimeContext` signatures, the data-migration note, and Phase-4 open items.
>
> Phase 2 lives on branch `av2/20260716-tools-permission` (off `av2/20260716-foundation-main`):
> Step 0 relocated `AgentModelSwitcher` to core (unblocking model/onboarding), Step 1 got
> `tools`/`mcp`/`plugin`/`plugin-builtin`/`skills-builtin` compiling+green on 2.0 (legacy-hook
> bridge, POM-only), Step 2 replaced `ToolPermissionHook`+`PermissionDeniedTool` with the native
> `PermissionEngine`/`PermissionContextState`. See **§9 Phase 2 execution log** for the mode/risk→rule
> mapping table, the javap-confirmed API surprises, the security self-review, and Phase-4 open items.
>
> Branch
> `av2/20260716-foundation-main` (off current `main`, which carries the 6 deerflow improvements —
> loop-detection/A1, memory-extraction/A4, context-engineering/A5, deferred-tools, sandbox-warn-tier,
> composite-skill). This re-applies the proven Phase-0 template (branch `av2/20260715-foundation`,
> off the older `main-v2`) on top of that newer code. See **§8 Phase 0-redux + Phase 1 execution log**
> for exactly what changed, the test counts, and what stopped at the Phase-3 (session) boundary.
>
> This document is the concrete, per-module plan for the full migration. It is grounded in `javap`
> inspection of the resolved 2.0 jars (trusted over docs) + working PoC code, not guesses. Where a
> fact was verified, the source class/signature is cited.

## TL;DR

- **2.0 artifacts resolve** from the configured repo (Aliyun-hosted `repo-cnhpt`): `agentscope-core`,
  `agentscope-harness`, and all **five** `agentscope-extensions-model-{openai,anthropic,gemini,ollama,dashscope}`
  at `2.0.0`. Vendor SDKs (anthropic-java 2.14.0, google-genai 1.45.0, dashscope-sdk-java 2.22.9) come
  transitively via the extensions — the explicit 1.x SDK declarations were removed from `pig-agent-providers`.
- **`pig-agent-core` + `pig-agent-providers` compile GREEN on 2.0**, and the **full 116-test core suite passes**
  (0 failures) — the migrated core is behaviorally consistent for everything exercisable offline (retry,
  interrupt, compression, memory, kernel, agent registry).
- **Both HIGH-RISK equivalence points are REPLICATED** (PoCs green): ephemeral memory + prefix cache
  (legacy hook still works end-to-end **and** a native `onReasoning` middleware works), and permission
  veto (native `PermissionEngine` DENY gates before execution; tool never runs).
- **Big wins available:** delete `RetryingModel`+`InterruptibleModel` (native `.maxRetries`/`.fallbackModel`/
  `interrupt()`), `ToolPermissionHook`+`PermissionDeniedTool` (native `PermissionEngine`), the
  self-built session store (`AgentStateStore`), and possibly `CompressionService`/two-tier memory
  (native `CompactionConfig`/harness memory). The **largest** module rewrite is `pig-agent-session`
  (`JsonSession`/`SessionManager` removed) and the tools **permission** re-architecture.

## 1. Verified 2.0 API facts (javap-confirmed)

| Area | 1.x | 2.0 (verified) | Evidence |
|------|-----|----------------|----------|
| Artifact | `io.agentscope:agentscope` | `agentscope-core` + `agentscope-harness` + `agentscope-extensions-model-*` | resolved from repo |
| Model classes | `io.agentscope.core.model.{OpenAI,Anthropic,Gemini,Ollama,DashScope}ChatModel` | `io.agentscope.extensions.model.<p>.<P>ChatModel` — **builder API identical** (`.apiKey/.modelName/.baseUrl/.build`) | `javap` extension jars |
| `Model` interface | `stream(List<Msg>,List<ToolSchema>,GenerateOptions)` + `getModelName()` | **UNCHANGED** | `javap Model` |
| Builder memory | `.memory(Memory)` | **removed** → `.stateStore(AgentStateStore)` (+ `.defaultSessionId`) | `javap ReActAgent$Builder` |
| Builder retry | (none; self-built) | native `.maxRetries(int)`, `.fallbackModel(Model|String)`, `.stopOnReject(boolean)` | `javap ReActAgent$Builder` |
| Interrupt | (self-built decorator) | native `ReActAgent.interrupt()` / `interrupt(RuntimeContext)` | `javap ReActAgent` |
| Hooks | `.hook/.hooks` + `io.agentscope.core.hook.*` events | **still present (deprecated, via `LegacyHookDispatcher`)** — `PreReasoningEvent.get/setInputMessages`, `HookEvent.getMemory()` intact | `javap` hook pkg |
| Middleware | (none) | `MiddlewareBase` 5 stages: `onAgent/onReasoning/onActing/onModelCall/onSystemPrompt`; `ReasoningInput(msgs,tools,opts)` record | `javap MiddlewareBase/ReasoningInput` |
| Streaming | `stream(Msg): Flux<io.agentscope.core.agent.Event>` (single-arg) | single-arg **removed**; `streamEvents(Msg): Flux<io.agentscope.core.event.AgentEvent>` (~28 typed events). Old `stream(List,StreamOptions,RuntimeContext)` + `Event`/`EventType` **soft-deprecated, still exist** | `javap ReActAgent` |
| `call` | `call(Msg): Mono<Msg>` | single-arg **removed**; `call(List<Msg>, RuntimeContext)` / `call(String, RuntimeContext)` | `javap ReActAgent` |
| Sessions | `io.agentscope.core.session.Session` + `SessionManager`/`JsonSession` + `agent.saveTo/loadIfExists` | **class removed** → `AgentStateStore` (`InMemoryAgentStateStore`/`JsonFileAgentStateStore`) keyed by `(userId,sessionId)` + `agent.saveAgentState(user,session)` | `javap` (session.Session not found) |
| Memory | `agent.getMemory(): Memory` | **removed**; conversation is `AgentState.getContext()`/`contextMutable()`. `Memory` interface still exists (deprecated, small: add/get/delete/clear/saveTo/loadFrom) | `javap AgentState/Memory` |
| `LongTermMemory` | `record(List)`/`retrieve(Msg):Mono<String>` | **UNCHANGED** (deprecated) | `javap LongTermMemory` |
| Permission | (self-built `ToolPermissionHook`) | native `io.agentscope.core.permission.*`: `PermissionEngine.checkPermission(ToolBase,Map):Mono<PermissionDecision>`, `PermissionMode{DEFAULT,ACCEPT_EDITS,EXPLORE,BYPASS,DONT_ASK}`, `PermissionBehavior{ALLOW,DENY,ASK,PASSTHROUGH}`, `PermissionContextState.builder()`, `ToolResultState.DENIED`, `AllToolsDeniedEvent` | `javap` permission pkg |
| Toolkit | `Toolkit` + `registration()`/`copy()`/`removeTool` | **preserved**: `registration()`, `copy()`, `removeTool(String)`, `registerTool(Object)`, `registerMcpClient(McpClientWrapper)` all present | `javap Toolkit` |

## 2. The two HIGH-RISK equivalence points — VERDICT

### Risk 1 — Ephemeral memory + prefix cache: **REPLICATED (two ways)**

pig's `EphemeralMemoryContextHook` injects retrieved memory as a trailing user message per reasoning
step, ephemerally (never persisted), keeping the system prompt byte-stable (prefix-cache friendly).

- **Legacy hook path still works, unchanged, end-to-end on 2.0.** `PreReasoningEvent.get/setInputMessages`
  and `HookEvent.getMemory()` are intact; hooks dispatch via `LegacyHookDispatcher`. The existing
  end-to-end `MemoryUserSideInjectionTest` (6 tests, drives the whole agent via a fake `CapturingModel`)
  **passes on 2.0**: memory on the user side (not system prompt), system prompt byte-stable across turns
  as memory changes, injection non-accumulating in history, `record` to session tier, `/memory off` no-op.
- **Native forward path proven:** `EphemeralMemoryMiddleware` (new, `pig-agent-core`) implements
  `MiddlewareBase.onReasoning` — it builds a **new** `ReasoningInput(augmentedMsgs, tools, opts)` and calls
  `next`, so the incoming list (`AgentState.getContext()` at runtime) is never mutated (ephemeral), and
  `onSystemPrompt` is identity (byte-stable). `EphemeralMemoryMiddlewareTest` (4 tests) is green.

**Conclusion:** no gap. Migrate the hook → `onReasoning` middleware at leisure; the legacy hook is a
zero-risk bridge in the meantime. (The `record`-to-memory half moves to an `onAgent` post-phase — deferred.)

### Risk 2 — Permission veto: **REPLICATED NATIVELY**

pig's `ToolPermissionHook` vetoes by rewriting a `ToolUseBlock` to a read-only `PermissionDeniedTool`
sentinel so the tool never runs and the model gets a denial + continues.

- 2.0's native `PermissionEngine` **is a gate evaluated before execution**. PoC `PermissionVetoPocTest`
  (3 tests, green): a deny rule on a tool → `checkPermission` returns `PermissionBehavior.DENY` and the
  spy tool's `callAsync` is **never invoked**; an unmatched tool is not denied; `ToolResultState.DENIED`
  exists (the ReAct loop feeds a DENIED result back so the model continues — same effect as the sentinel).
- **The custom sentinel is unnecessary in 2.0** — the engine + `DENIED` result state replicate it.
- Deferred to Phase 1 (needs a live model): a real-turn IT confirming "model receives DENIED and continues"
  end-to-end (documented native behavior; `AllToolsDeniedEvent` + `ToolResultState.DENIED` are the surface).

**Conclusion:** no gap; a Middleware is NOT needed. Map pig's modes (`plan/ask/auto/bypass`) →
`PermissionMode` + `PermissionContextState` rules, wire via `.permissionContext(...)`, and route ASK/HITL
through `RequireUserConfirmEvent`/`ConfirmResult`.

## 3. Per-module migration map

Effort: **S** ≤ ~0.5 day, **M** ~1–2 days, **L** ~3+ days. Modules are ordered by dependency.
"Compile-effort" (get it building on 2.0) is separated from "native-cleanup" (delete self-built code)
where they differ.

| # | Module | Effort | 2.0 changes needed | Risk |
|---|--------|:------:|--------------------|------|
| 0 | **pig-agent-core** | ✅ DONE | `PigAgent` builder `.memory`→`.stateStore`; `call`/`stream`→`call(List,ctx)`/`streamEvents`; `getMemory` via `ConversationMemory` adapter over `AgentState`; `saveTo/loadIfExists(sessionId)`; `AgentKernel.chat`→`Flux<AgentEvent>`. Decorators kept (Model unchanged). | Low — done, 116 tests green |
| 0 | **pig-agent-providers** | ✅ DONE | 5 import moves to `io.agentscope.extensions.model.*`; POM → 5 model extensions; drop redundant vendor SDKs. | Low — done |
| 1 | **pig-agent-config** | ✅ DONE | No AgentScope coupling at all → compiles + 36 tests green on 2.0 with **zero changes** (inherits parent POM only). | Low — done |
| 1 | **pig-agent-workspace** | ✅ DONE | No AgentScope coupling → compiles on 2.0 with **zero changes** (1 class). | Low — done |
| 1 | **pig-agent-task** | ✅ DONE | **Zero** `io.agentscope.*` imports (the map's "uses `Msg` incidentally" was stale) → compiles + tests green on 2.0 with **zero changes**. | Low — done |
| 1 | **pig-agent-onboarding** | ✅ DONE (Phase 2 Step 0) | 2.0-clean in itself; unblocked by relocating `AgentModelSwitcher` to `pig-agent-core`. Compiles on 2.0 (no unit tests). | Low — done |
| 1→2 | **pig-agent-model** (`ModelManager`) | ✅ DONE (Phase 2 Step 0) | Unblocked by moving the `AgentModelSwitcher` seam to core (breaks the `model→session` edge); dropped its redundant 1.x `agentscope` + `pig-agent-session` deps. 21 tests green (1 skipped POSIX-perms on Windows). | Low — done |
| 2 | **pig-agent-tools** (framework) | ✅ DONE | **Permission re-architecture landed** (see §4/§9): `ToolPermissionHook`/`PermissionDeniedTool`/`PermissionResolver` **deleted** → native `PermissionEngine` via new `PermissionContextFactory` (mode+risk→rules). Guards (`ToolContractGuard`/`GuardedAgentTool`/availability/SSRF/credential-file) unchanged. Read-only `@Tool` methods flagged `readOnly=true`. 309 tests green. | **High (done)** — security self-review in §9 |
| 3 | **pig-agent-mcp** (`McpManager`) | ✅ DONE | `McpClientBuilder.create()/.stdioTransport()/.sseTransport()/.streamableHttpTransport()/.buildSync()` **byte-identical** in 2.0 (javap) — **no code change**, POM → `agentscope-core`. 14 tests green (1 skipped). | Low — done |
| 3 | **pig-agent-plugin** | ✅ DONE | POM → `agentscope-core`; plugin SPI still uses the legacy `io.agentscope.core.hook.Hook` bridge (deprecation warnings, present via `LegacyHookDispatcher`). 18 tests green. | Low — done |
| 3 | **pig-agent-plugin-builtin** | ✅ DONE | POM → `agentscope-core`; read-only compute + `webSearch` `@Tool` flagged `readOnly=true`. `SsrfGuard`/`SmartWebFetchTool` unchanged (no hook). 132 tests green. | Low — done |
| 3 | **pig-agent-skills-builtin** | ✅ DONE | Classpath `SKILL.md` + `SkillProvider` SPI, pig-owned; no agentscope dep. 12 tests green. | Low — done |
| 4 | **pig-agent-session** (`SessionManager`) | ✅ DONE (Phase 3) | **Rewritten onto native state.** Deleted the `io.agentscope.core.session.Session agentSession` field + all `saveTo(agentSession,id)`/`loadIfExists(agentSession,id)`/`clearMemory()`-then-load handshakes. Conversation now persists automatically via the native `AgentStateStore` keyed by `(userId="pig", sessionId)`; the active session id is threaded per turn (`PigAgent.stream(Msg,sessionId)` → `RuntimeContext`). `SessionManager` keeps only the metadata sidecar (`Session` record + temp-memory + compression lineage) and does save-current→ensure-model→point-temp-memory→record-id on switch. POM: `agentscope` (1.x all-in-one) → `agentscope-core` (drops the dual-jar). **session 45 tests / core 234 tests green on 2.0.** See §10. | **High → resolved** (data-format change for existing session dirs — see §10 migration note) |
| 5 | **pig-agent-channel** | M | `ChannelAgentBridge` routes turns via the agent (`call`/`stream`) + a channel-mode permission track. Move to `streamEvents`/native permission-mode; **thread its own session id via the new `PigAgent.stream(Msg,sessionId)` / `AgentKernel.chat(id,msg,sessionId)` seam** so channel conversations persist in their own `(userId,sessionId)` slot. | Med |
| 6 | **pig-agent-cli** (`AgentRepl` + renderers) | **L** | Consumes `kernel.chat` → now `Flux<AgentEvent>`: rewrite `renderStream` to aggregate typed events (`TextBlockDeltaEvent.getDelta()`, `ThinkingBlock*`, `ToolCall*`, `ToolResult*`, `AgentEndEvent`, HITL `RequireUserConfirmEvent`) instead of `Event`/`EventType`. `AgentBootstrap` re-wires the **shared `JsonFileAgentStateStore`** (via the new `AgentFactory`/`AgentInstanceFactory` store seam) so per-session state survives model switches + restarts, passes `sessionManager.getCurrentSessionId()` into `kernel.chat(id,msg,sessionId)`, plus permission context, middleware, retry (native). **Preserve** CC-REPL renderers, StatusLine, InlineSelector, slash completion. | Med–High (event-model rewrite; most user-visible) |
| 6 | **pig-agent-web** | M | SSE handler consumes the same event stream → same `AgentEvent` aggregation as CLI (share a mapper). | Med |
| 6 | **pig-agent-cli `*IT`** | M | `PermissionVetoSpikeIT`/`PermissionEnforcementIT`/`FullLinkAgentIT` re-expressed against native permission + `streamEvents`; these become the live-model proof for Risk 2 end-to-end. | Med |

## 4. Delete-vs-preserve ledger

### Delete (replace with native 2.0)

| Self-built | Replace with | Notes |
|------------|--------------|-------|
| `RetryingModel` (`core.retry`) | `.maxRetries(int)` + `.fallbackModel(...)` | Native retry is on the builder. **Verify** it wraps `model.stream` (fresh HTTP) not the single-flight agent, and honors a "don't re-run after content emitted" guard — pig's `RetryPolicy`/`TransientErrorClassifier` may still be wanted for transient-vs-permanent classification. Keep the classifier if native lacks it. |
| `InterruptibleModel` (`core.interrupt`) | native `ReActAgent.interrupt(...)` | Native interrupt exists. Keep pig's `InterruptController`/`TurnHandle` as the frontend-facing turn abstraction, but drive native `interrupt()` underneath. |
| `ToolPermissionHook` + `PermissionDeniedTool` (`tools.permission`) | native `PermissionEngine` + `PermissionContextState` + `PermissionMode` + `ToolResultState.DENIED` | Risk-2 PoC proves parity. Keep pig's mode names/`/permission` UX + risk classifier → map to rules. |
| `io.agentscope.core.session.Session` usage + parts of `SessionManager` | `AgentStateStore` (`JsonFileAgentStateStore`) | **✅ DONE (Phase 3).** Conversation persistence is native + automatic per `(userId,sessionId)`; `SessionManager` keeps only the metadata sidecar. |
| `EphemeralMemoryContextHook` (injection half) | `EphemeralMemoryMiddleware.onReasoning` (already written) | Legacy hook is a bridge; middleware is the forward path. |
| **Candidate:** `CompressionService` + `CompositeLongTermMemory`/`FileSystemLongTermMemory`/`CachingLongTermMemory` | native `CompactionConfig`/`MemoryConfig` (harness) + `AgentState` | **Evaluate in Phase 1.** Native compaction can use a dedicated cheap model. pig's two-tier (global + per-session temp) + `/compress`/`/memory` UX + compression-lineage are differentiators — port the UX over native mechanics rather than keep the whole self-built stack. `ConversationMemory` adapter is an interim bridge. |
| **Candidate:** `LoggingHook`/`ToolCallLoggingHook` | `OtelTracingMiddleware` or `onActing`/`onModelCall` middleware | Optional; legacy hooks still work. |

### Preserve (pig-agent's differentiators — port, don't drop)

- **CC-style REPL** (`repl/render/*`, `StatusLine`, `repl/select/*`, slash auto-completion) — pure pig UX.
- **`/ls:*` AI dev pipeline**, `/opsx:*` — tooling, no AgentScope coupling.
- **Digital-employee** (`AgentRunner` morning report `我做了/我发现/等你决定`, `DeniedActionRecorder`, `FileReportWriter`) — the `DeniedActionRecorder` re-wires onto native permission DENY events.
- **Operator commands** (`/model`, `/agent`, `/session`, `/mcp`, `/permission`, `/compress`, `/memory`, `/tasks`) + Chinese UX.
- **Kernel façade** (`AgentKernel`/`KernelEvent`) — the "add a frontend = add an adapter" seam; keep, retarget internals to native.
- **Multi-agent** (`AgentSpec`/`AgentRegistry`/`AgentInstanceFactory` + per-agent `Toolkit.copy()`/permission mode) — `copy()` preserved.
- **Protocol-by-standard** provider design (`ModelProtocol`/`ModelSpec`) — thin wrappers over the extension builders; **done**.
- **Tool contract** (`{"error"}` + `CredentialSanitizer`) + **availability gate** + **SSRF/credential-file guards** — orthogonal to permission; port onto native `Toolkit`.
- **MCP dynamic management** + **plugin SPI** + **built-in skills** — pig-owned SPIs.

## 5. Recommended migration order

1. **✅ Phase 0 (redux, on current `main`):** POM → 2.0; `core` (234 tests) + `providers` compile + green;
   risk PoCs; this map. Re-proven on the newer code that carries the 6 deerflow improvements — see §8.
2. **◑ Phase 1 — leaf/independent modules:** `config`, `workspace`, `task` **✅ done (2.0-green)**;
   `model` + `onboarding` **⛔ blocked** — they depend (transitively) on `pig-agent-session`, which is a
   Phase-3 L rewrite (proven boundary, §3 + §8). Either do Phase 3 first, or relocate the tiny
   `AgentModelSwitcher` interface out of session (§3 note) to unblock them independently.
3. **Phase 2 — tools framework (the keystone):** first a *compile* pass (legacy `PreActingEvent` hook as a
   bridge), then the **native permission re-architecture** (delete `ToolPermissionHook`/`PermissionDeniedTool`)
   with a security review + the live-model permission IT. `mcp`, `plugin`, `plugin-builtin`, `skills-builtin`
   recompile behind it.
3. **✅ Phase 3 — session (the state rewrite):** `JsonSession`→`AgentStateStore`, per-session `RuntimeContext`
   threaded through `PigAgent`/`AgentKernel`, metadata sidecar preserved, data-migration note (§10).
   Compression/memory kept on the `ConversationMemory` bridge (native `CompactionConfig`/`MemoryConfig`
   adoption deferred to Phase 4). session 45 + core 234 tests green.
4. **Phase 4 — frontends:** `cli` (event-model rewrite of `renderStream`; wire native retry/permission/state
   in `AgentBootstrap`) then `web` (shared `AgentEvent` mapper) and `channel`. Re-express the `*IT`s.
5. **Phase 5 — cleanup:** delete the superseded decorators/hooks; migrate `EphemeralMemoryContextHook` →
   middleware; adopt native compaction/skills if chosen; drop the legacy `agentscope` (1.x) dep + the
   `agentscope.version` property from the parent POM once no module references it.

## 6. Open questions / to-verify in Phase 1 (not blockers)

- Native `.maxRetries` semantics vs pig's `RetryingModel` — **STILL OPEN.** Phase 0-redux kept
  `RetryingModel`/`InterruptibleModel` as `Model` decorators (unchanged; `Model.stream(List,tools,opts)`
  is unchanged in 2.0) — a zero-risk bridge. Native `.maxRetries`/`.fallbackModel` adoption is deferred;
  keep `TransientErrorClassifier` until native transient-vs-permanent classification is confirmed.
- `McpClientWrapper` construction API for stdio/SSE/streamable-http — **not touched** (Phase 3, `mcp`).
- Per-session `RuntimeContext` wiring — **✅ RESOLVED (Phase 3).** `PigAgent` now has session-aware
  `call(Msg,sessionId)`/`stream(Msg,sessionId)` that build `RuntimeContext.builder().userId("pig")
  .sessionId(id)` so each session persists to its own `(pig,id)` slot automatically; the no-arg
  `call(Msg)`/`stream(Msg)` still use the default session. `AgentKernel.chat(id,msg,sessionId)` threads
  it from the frontend. See §10.
- `JsonFileAgentStateStore` on-disk format vs pig's existing `workspace/sessions/{id}/` — **format
  change documented (Phase 3 §10).** The store seam is provided (`PigAgent.Builder.stateStore` +
  `AgentFactory`/`AgentInstanceFactory` optional `AgentStateStore`); the CLI passes a shared
  `JsonFileAgentStateStore` in Phase 4. Old 1.x-`JsonSession` conversation dirs are **not**
  auto-converted — fault-tolerant "start fresh if the native store has no slot"; the pig `meta.json`
  sidecar (name/timestamps/model/lineage) is unaffected and still read.
- Whether to keep `CompressionService` or adopt native `CompactionConfig` — **can be deferred safely.**
  Resolved for now: `CompressionService` + `ContextEngineer` (A5) run **green on 2.0 unchanged** via the
  `ConversationMemory` adapter (a `Memory` view over `AgentState.contextMutable()`), so the native-vs-port
  decision does not block anything. It belongs with the Phase-3 memory/state decision (the coordinator
  flagged `LongTermMemory` as officially `@Deprecated`/"v2 rewrite in progress; do not add dependencies",
  so A4 memory-extraction stays on the compat surface for now and is revisited in Phase 3).

## 7. Blockers

**None in Phase 0-redux; one expected Phase-boundary in Phase 1.** All 7 2.0 artifacts resolve (cached +
via the `rdc`/`repo-cnhpt` profile in the global Maven `settings.xml` — **no POM `<repositories>` change
needed**); `core` (234 tests) + `providers` compile and pass on 2.0; `config`/`workspace`/`task` compile
(+ tests) green on 2.0. The **one boundary hit**: `pig-agent-model` + `pig-agent-onboarding` cannot go
2.0-green because they depend (transitively) on `pig-agent-session`, whose `SessionManager` needs the
Phase-3 L rewrite (removed `io.agentscope.core.session.Session` + old 2-arg `saveTo/loadIfExists`). Per
scope this was **not** half-done — STOPPED and reported. Remaining effort is still concentrated in
**session-state** (now proven as the gate for model/onboarding), **tools-permission**, and
**cli-event-model** (all L/Med-High).

## 8. Phase 0-redux + Phase 1 execution log (branch `av2/20260716-foundation-main`)

**Base:** current `main` @ `86f2323` (carries the 6 deerflow improvements). **Mechanism:** the entire
Phase-0 change set was byte-identical between the old Phase-0 base (`ecd686a^`) and current `main` for
every file Phase-0 touched, so it was brought in verbatim via `git checkout ecd686a -- <16 files>`
(3 POMs + `PigAgent` + `AgentKernel` + 5 providers + 2 new memory files + 4 tests) — a deterministic
re-apply, not a merge. The migration doc was seeded from `av2/20260715-foundation` and updated here.

**POM changes (identical to `ecd686a`):** parent adds `agentscope2.version=2.0.0` + 2.0 depMgmt
(`agentscope-core`, `agentscope-harness`, 5 `agentscope-extensions-model-*`), keeps 1.x `agentscope` in
depMgmt for not-yet-migrated modules; `pig-agent-core` → `agentscope-core` + `agentscope-harness`;
`pig-agent-providers` → the 5 model extensions, dropping the now-transitive vendor SDK declarations. **No
`<repositories>` edit** — `repo-cnhpt` is provided by the active `rdc` profile in the environment's global
`settings.xml`.

**What the 6 improvements needed beyond the old Phase 0: NOTHING.** The core-touching new code ported to
2.0 with **zero code changes** on top of the Phase-0 `PigAgent`/`AgentKernel` migration:
- **A1 loop-detection** (`core/loop/*`): `LoopDetectionHook` uses only legacy hook events
  (`Hook`/`HookEvent`/`PreActingEvent`/`PreReasoningEvent`) — present-but-deprecated in 2.0 via
  `LegacyHookDispatcher` — plus `Msg`/`MsgRole`/`TextBlock`/`ToolUseBlock` (unchanged). Kept on the
  legacy hook bridge as instructed. 26 loop tests green.
- **A4 memory-extraction** (`core/memory/extraction/*`): `LlmMemoryExtractor` calls `PigAgent.call(Msg)`
  (pig-level, Phase-0-migrated) and builds on `LongTermMemory` (deprecated-but-present — kept per the
  coordinator's B.6 note; revisit in Phase 3). All `Msg` built as USER+`TextBlock`, so the new 2.0 A.6
  role validation never trips. ~60 extraction tests green.
- **A5 context-engineering** (`compression/ContextEngineer` + friends): `ModelSummarizer` uses
  `PigAgent.call(Msg)`; `CompressionService` reads/rewrites the live conversation via
  `PigAgent.getMemory()` → now the `ConversationMemory` adapter over `AgentState`. All compression +
  context-engineer tests green.
- **deferred-tools / sandbox-warn-tier / composite-skill** live in `pig-agent-tools`/`plugin-builtin`
  (Phase 2), not in `core`/`providers`, so they are untouched this phase.

**Acceptance (single-threaded surefire, no parallel config):**
- `pig-agent-core`: **49 test classes / 234 tests / 0 failures / 0 errors / 0 skipped** on 2.0
  (was 116 on the old Phase-0 base; +118 from the 6 improvements' tests). Includes the Phase-0 risk PoCs
  `EphemeralMemoryMiddlewareTest` (4) + `PermissionVetoPocTest` (3).
- `pig-agent-providers`: compiles on 2.0 (no unit tests, as before).
- `pig-agent-config`: compiles + **36 tests** green. `pig-agent-workspace`: compiles (1 class).
  `pig-agent-task`: compiles + tests green. All three have **zero** AgentScope coupling.
- `pig-agent-model` / `pig-agent-onboarding`: **not green — stopped at the `pig-agent-session` Phase-3
  boundary** (see §3/§7). Empirical failure: `SessionManager.java:{103,153,174,218}` old 2-arg
  `saveTo`/`loadIfExists`.

**2.0 API surprises:** none beyond the old Phase-0 findings. Environment note: the active Maven local
repo is `D:/env/apache-maven-3.9.10/repository` (per global `settings.xml`), not `~/.m2` — all 7 2.0
artifacts were already cached there.

## 9. Phase 2 execution log (branch `av2/20260716-tools-permission`)

**Base:** `av2/20260716-foundation-main`. Three steps, each committed separately; **not merged** (lead
merges into the v2 line).

### Step 0 — Phase-1 unblock (relocate `AgentModelSwitcher`)

The tiny AgentScope-free `AgentModelSwitcher` interface (1 method) moved from `pig-agent-session`
(`io.pigagent.session`) to `pig-agent-core` (`io.pigagent.core.agent`). `pig-agent-model` dropped its
`pig-agent-session` **and** redundant 1.x `agentscope` deps; `SessionManager`/`SessionManagerTest`/
`ModelManager` import from core now. This breaks the `model → session` edge so model/onboarding build
without touching the Phase-3 session rewrite. **Acceptance:** `mvn -pl pig-agent-model,pig-agent-onboarding
-am test` GREEN (core 234, model 21/1-skipped, onboarding compiles).

### Step 1 — tools + downstream compile+test GREEN on 2.0 (legacy-bridge, POM-only)

POMs for `tools`/`mcp`/`plugin`/`plugin-builtin` moved from the 1.x all-in-one `agentscope` to
`agentscope-core` (removing the dual-jar classpath). **Zero source changes** — the deprecated legacy
hook surface (`io.agentscope.core.hook.*` incl. `PreActingEvent`, via `LegacyHookDispatcher`) still
compiles, so `ToolPermissionHook`/`PermissionDeniedTool`/`LoopDetectedTool` build as a bridge for this
step. **Acceptance:** all five + model/onboarding `-am test` GREEN.

### Step 2 — native permission re-architecture

**Deleted:** `ToolPermissionHook`, `PermissionDeniedTool` (+ its `ToolProvider` + SPI service line),
`PermissionResolver`, and their tests (`ToolPermissionHookTest`, `PermissionResolverTest`).
**Added:** `io.pigagent.tool.permission.PermissionContextFactory` — maps pig's model onto a native
`PermissionContextState`. **Kept (pure decision core, unchanged):** `ToolRiskClassifier`/`ToolRisk`,
`PermissionPolicy` + pig `PermissionDecision` enum (feed the mapper), `CommandKeys`/`AllowlistWriter`/
`PermissionConfirmer` (retained for the Phase-4 HITL round-trip).

**Native classes used (javap-confirmed, `agentscope-core-2.0.0`):**
`io.agentscope.core.permission.{PermissionEngine(PermissionContextState), PermissionContextState.builder()
.mode/.addAllowRule/.addDenyRule/.addAskRule/.build, PermissionRule(toolName,ruleContent,behavior,source),
PermissionMode{DEFAULT,ACCEPT_EDITS,EXPLORE,BYPASS,DONT_ASK}, PermissionBehavior{ALLOW,DENY,ASK,PASSTHROUGH},
PermissionDecision.getBehavior()}` · `PermissionEngine.checkPermission(ToolBase,Map)→Mono<PermissionDecision>`
· `io.agentscope.core.message.ToolResultState.DENIED` · agent wiring (for Phase-4, verified present):
`ReActAgent.Builder.permissionContext(PermissionContextState)`, `ReActAgent.setPermissionMode(RuntimeContext|
(userId,sessionId), PermissionMode)`, `getPermissionEngine()`/`getPermissionContext()` ·
`ToolBase.generateSuggestions(Map)→List<PermissionRule>` (native allowlist-suggestion equivalent of
`AllowlistWriter`), `ToolBase.matchRule(ruleContent,input)`.

**Mode + risk → native rule mapping (implemented by `PermissionContextFactory`):**

Base native mode: `plan→EXPLORE`, `ask→DEFAULT`, `auto→ACCEPT_EDITS`, `bypass→BYPASS`; **non-interactive
(no confirmer) → `DONT_ASK` base** (except `bypass`). Per-tool rule behavior (from the unchanged
`PermissionPolicy` table; `interactive=false` downgrades ASK→DENY):

| risk \ pig mode | plan (EXPLORE) | ask (DEFAULT) | auto (ACCEPT_EDITS) | bypass (BYPASS) |
|---|---|---|---|---|
| READ_ONLY | ALLOW | ALLOW | ALLOW | ALLOW |
| WRITE | DENY | ASK→(chan)DENY | ALLOW | ALLOW |
| NETWORK | DENY | ASK→(chan)DENY | ALLOW | ALLOW |
| EXEC | DENY | ASK→(chan)DENY | ASK→(chan)DENY | ALLOW |
| MCP_ADMIN | DENY | ALLOW (→D-SEC) | ALLOW (→D-SEC) | ALLOW |
| tool in `allowlist.tools` | DENY (plan wins) | ALLOW | ALLOW | ALLOW |

Rules are `ruleContent=null` (match all calls of that tool), keyed by tool name; native precedence is
deny > ask/built-in > allow > mode-fallback.

**javap-confirmed API surprises:**
1. **`McpClientBuilder` unchanged** — the skill prose's `.stdio()/.streamableHttp()/.sse()` factory
   methods do NOT exist; the real 2.0 API is `create(name)` + `.stdioTransport(...)`/`.sseTransport(url)`/
   `.streamableHttpTransport(url)`/`.buildSync()` — byte-identical to pig's `McpManager.connect`. No change.
2. **`ToolBase.checkPermissions(Map, PermissionContextState)`** — the skill prose said `(Map,
   ToolExecutionContext)`; javap shows `PermissionContextState`. (Not used by pig's reflection tools.)
3. **EXPLORE enforces read-only as a built-in check keyed on `tool.isReadOnly()`, ABOVE allow rules**
   (empirically: an explicit allow rule for a non-readonly tool does NOT override EXPLORE). So for `plan`
   to permit reads, read-only `@Tool` methods MUST declare `readOnly=true` — pig's did not. Fixed:
   `readFile`/`listDirectory`/`listSkills`/`loadSkill`/`tool_search`/`listMcpServers`/`testMcpServer`
   (tools) + all read-only compute tools + `webSearch` (plugin-builtin). This also enables native
   `readOnlyHint` auto-allow (matching pig's "READ_ONLY always ALLOW"), so it is not a weakening.

**Security self-review (invariants preserved):**
- **plan truly read-only:** every mutating tool gets a tier-1 DENY rule in plan (unbeatable), incl.
  allowlisted (plan wins over allowlist — tested). Reads permitted via `readOnly=true` + EXPLORE + allow
  rule. ✓
- **channel/autonomous fail-closed:** `interactive=false` → base `DONT_ASK` **and** per-tool ASK→DENY, so
  any tool that would prompt is denied when there is no confirmer (tested channel ask/auto). ✓
- **MCP_ADMIN via D-SEC (no double-prompt):** `addMcpServer`/`removeMcpServer`→ALLOW rule in ask/auto
  (delegated to the unchanged `McpTool` D-SEC door), DENY in plan. ✓
- **DENY + dangerous-path hold under BYPASS:** pig `bypass` generates no deny rules (allow-all, matching
  prior pig behavior); native `ToolDangerousPathConstants` still apply even under BYPASS — a *hardening*
  vs old pig bypass (pure allow-all), never a weakening. ✓
- **No READ_ONLY tool wrongly promoted:** every tool flagged `readOnly=true` was already "always ALLOW"
  under pig's READ_ONLY policy, so native auto-allow changes nothing at runtime. ✓

**Honest limitations / Phase-4 open items:**
- **No enforcement is wired yet.** `PermissionContextFactory` produces the context; installing it via
  `ReActAgent.Builder.permissionContext(...)` + runtime `setPermissionMode(...)` + the HITL confirmer flow
  (`RequireUserConfirmEvent`/`ConfirmResult.suggestedRules` ↔ `AllowlistWriter`/`readerRef`) is Phase-4
  (cli `AgentBootstrap`/`AgentWiring`, which still reference the deleted classes and are not on 2.0). A
  **live-model permission IT** ("model receives DENIED and continues", plan denies a write end-to-end) is
  required there — cannot be exercised offline.
- **Command-granular allowlist (`allowlist.commands`) deferred.** Native precedence deny>ask>allow makes a
  tool-level ASK rule shadow a command-level ALLOW rule, and reflection `executeCommand`'s `matchRule` is
  not controllable; current behavior is fail-closed (an allowlisted command still asks). Restoring it needs
  `executeCommand` as a `ToolBase` overriding `checkPermissions`/`matchRule` (Phase-4).
- **Plan Mode as a HarnessAgent feature** (`enablePlanMode()`, `plan_enter/write/exit` + HITL exit gate) is
  Phase-4 (agent is built in cli). Phase 2 maps pig `plan`→`EXPLORE` at the permission layer only.
- **`loop-detection`** stays on the legacy hook bridge + its own `LoopDetectedTool` sentinel (a guard
  orthogonal to permission; unchanged this phase).

**Acceptance (single-threaded surefire, 2.0):** `pig-agent-tools` **309** / `pig-agent-mcp` **14** (1 skip)
/ `pig-agent-plugin` **18** / `pig-agent-plugin-builtin` **132** / `pig-agent-skills-builtin` **12** /
`pig-agent-model` **21** (1 skip) / `pig-agent-onboarding` (no unit tests) — **506 tests, 0 failures,
0 errors, 5 skipped.**

## 10. Phase 3 execution log (branch `av2/20260716-session-state`)

**Base:** `av2/20260716-foundation-main`. **Not merged** (lead merges into the v2 line). Scope: rewrite
`pig-agent-session` onto native 2.0 state; touch `pig-agent-core` (`PigAgent`/`AgentKernel`/factories)
minimally to thread a per-session `RuntimeContext`; keep `cli`/`web`/`channel` on Phase 4.

### The native state model (javap-confirmed, `agentscope-core-2.0.0`)

`io.agentscope.core.state.AgentStateStore` (interface): `save(userId, sessionId, stateKey, State)` (+
list overload) · `<T> Optional<T> get(userId, sessionId, stateKey, Class<T>)` · `boolean exists(userId,
sessionId)` · `void delete(userId, sessionId)` · `Set<String> listSessionIds(userId)` · `default close()`.
Impls: `InMemoryAgentStateStore()` (tests) · `JsonFileAgentStateStore()` (default root
`~/.agentscope/state/`) / `JsonFileAgentStateStore(Path root)` (custom; writes `<userId>/<sessionId>/…`,
anonymous → `__anon__`).

`io.agentscope.core.agent.RuntimeContext`: `empty()` = `(userId=null, sessionId=null)`; `builder()
.userId(String).sessionId(String).build()`; `getUserId()`/`getSessionId()`/`getAgentState()`.

`ReActAgent` state internals (the caching contract that makes the sidecar design safe): a
`ConcurrentHashMap<slotKey, AgentState> stateCache`; `getAgentState(userId, sessionId)` =
`stateCache.computeIfAbsent(slotKey(userId,sessionId), loadOrCreateAgentStateForSlot(...))` — **caches
per slot, load-or-create from the store**, so repeated reads return the same instance and mutations to
`getAgentState(u,s).contextMutable()` are seen by the next same-slot turn. `getAgentState()` (no-arg) =
`getAgentState(null, defaultSessionId)`. `saveAgentState(userId, sessionId)` persists the **cached** slot
to the store (no-op if the slot was never activated). `call(List, RuntimeContext)` /
`streamEvents(Msg, RuntimeContext)` activate the slot for the ctx, run, and auto-save at the end of the
call. `slotKey(userId, sessionId)` = `<userId or __anon__>:<sessionId>`.

### RuntimeContext threading design

- **`PigAgent` (core).** Added session-aware, additive-only: `call(Msg, sessionId)` /
  `stream(Msg, sessionId)` build `RuntimeContext(userId="pig", sessionId)` (null/blank → `empty()` =
  default session, so the Phase-0 no-arg path is unchanged); `clearConversation(sessionId)`,
  `copyConversation(from,to)` (backs `/session fork`, no-op when from==to), `getMemory(sessionId)`
  (session-scoped `ConversationMemory` view for a future per-session compression path). Kept: `call(Msg)`,
  `stream(Msg)`, `clearMemory()`, `getMemory()`, `saveTo(sessionId)`, `loadIfExists(sessionId)`. Partition:
  session turns live under `("pig", sessionId)`; the default path under `(null, defaultSessionId)` — disjoint.
- **`AgentKernel` (core).** Added `chat(agentId, msg, sessionId)` (threads into
  `instance.agent().stream(msg, sessionId)`); `chat(agentId, msg)` now delegates with `sessionId=null`.
  Interrupt-turn registration + `CHAT_STARTED` emission unchanged.
- **`ConversationMemory` (core).** Added a `(agent, userId, sessionId)` constructor viewing a specific
  slot via `getAgentState(userId,sessionId)`; the no-arg view (default session) is unchanged.
- **Store seam.** `AgentFactory` and `AgentInstanceFactory` gained an optional `AgentStateStore` (threaded
  to `PigAgent.Builder.stateStore`); `null` ⇒ per-agent `InMemoryAgentStateStore` (Phase-0 behavior).
  Passing **one shared `JsonFileAgentStateStore`** to every rebuilt agent is what makes per-session
  conversation survive a model switch (new agent, same store) and a restart — the CLI wires this in Phase 4.

### `SessionManager` = metadata sidecar over native state

Deleted the `io.agentscope.core.session.Session agentSession` field/param and every
`saveTo(agentSession,id)` / `loadIfExists(agentSession,id)` / `clearMemory()`-then-load call. The manager
no longer loads or clears the agent's conversation on a switch — the native store does it per turn. It now
owns only pig's differentiators: the `Session` record (name/timestamps/model binding/**compression
lineage**) via `FileSystemSessionRepository` (`meta.json`), the per-session temp-memory file, and
`current-session-id` restore + `SessionManager.lineageOf`. `activate` = save-current → `ensureModel(...)`
→ point the temp-memory tier at the target → record the id (+ config). `fork` copies the conversation via
`PigAgent.copyConversation`; `clearConversation` via `PigAgent.clearConversation`; `saveCurrent` via
`PigAgent.saveTo`. POM: `io.agentscope:agentscope` (1.x all-in-one) → `agentscope-core`, removing the
dual-jar classpath that only masked the removed 1.x `session` package.

### Compaction / memory: kept on the bridge (native adoption deferred to Phase 4)

`CompressionService` + `CompositeLongTermMemory`/`ExtractingLongTermMemory` are **unchanged** and still
compile+run green via the `ConversationMemory` adapter over `AgentState`. Native `CompactionConfig` /
`MemoryConfig` (a `HarnessAgent`-build-time concern) are adopted in Phase 4 when the agent is assembled in
the CLI; the session-scoped `PigAgent.getMemory(sessionId)` seam is provided now so Phase-4 compression can
target the right conversation. Compression lineage (`SessionLineageWriter`) is untouched and still passes.

### Data-migration note (existing `workspace/sessions/{id}/`)

Old session dirs held 1.x-`JsonSession` conversation state; the native `JsonFileAgentStateStore` uses a
different layout (`<root>/pig/<sessionId>/…` when rooted at the sessions dir, or a dedicated `state/` root)
and format. **No auto-conversion** — best-effort, fault-tolerant: on first use a pre-existing session's
conversation "starts fresh" if the native store has no `("pig", sessionId)` slot, while the pig `meta.json`
sidecar (name/timestamps/model/lineage) is read unchanged, so sessions still list and switch correctly.
The Phase-4 CLI chooses the store root (recommend a dedicated `workspace/state/` to avoid mixing native
state files with the `meta.json`/`temp-memory.md` sidecar under `workspace/sessions/{id}/`).

### Acceptance (single-threaded surefire, 2.0)

`mvn -pl pig-agent-core,pig-agent-session -am compile` green. `mvn -pl pig-agent-session -am test`:
**pig-agent-session 45 / 0 fail / 0 error / 0 skip** (`FileSystemSessionRepositoryTest` 9,
`SessionManagerTest` 22, `SessionTest` 11, `SessionLineageWriterTest` 3) and **pig-agent-core 234 / 0 / 0 /
0** (unchanged — the additive core changes broke nothing). Only `SessionManagerTest` needed porting: it
dropped `import io.agentscope.core.session.JsonSession`, the `new JsonSession(sessionsDir)` local, and the
`agentSession` constructor arg — every assertion (lifecycle/model-switch/temp-memory/lineage) is about the
metadata sidecar and passed unchanged; `SessionTest`/`FileSystemSessionRepositoryTest`/
`SessionLineageWriterTest` had zero AgentScope-`session` coupling and were untouched.

### Phase-4 open items

- **CLI wiring:** build one shared `JsonFileAgentStateStore` in `AgentBootstrap`, pass it to
  `AgentFactory`/`AgentInstanceFactory`, and call `kernel.chat(activeId, msg, sessionManager
  .getCurrentSessionId())`; choose the store root; remove the last references to the deleted 1.x session
  API in `AgentBootstrap`/`FullLinkAgentIT`.
- **Native compaction/memory adoption:** move `CompressionService`/two-tier memory onto
  `CompactionConfig`/`MemoryConfig` at agent-build time (or keep the bridge + point compression at
  `getMemory(sessionId)`); decide per-session vs per-`IsolationScope` memory semantics.
- **`channel`:** thread a channel-owned session id through the new `stream(Msg,sessionId)` seam.
- **Live-model IT:** a `*IT` confirming multi-turn conversation actually persists + restores across a
  session switch and a simulated restart on a `JsonFileAgentStateStore` (offline tests use `InMemory`).

## 11. Phase 4 execution log (branch `av2/20260716-frontends`)

**Base:** `av2/20260716-foundation-main` (which already carries Phase 2 native permission + Phase 3
native state). **Not merged** (lead merges into the v2 line). Scope: get the **whole reactor** compiling
+ unit-tests GREEN on 2.0 by wiring native permission + native state + the `AgentEvent` stream into the
three frontends (`cli`, `web`, `channel`) and re-expressing the `*IT`s. Stays on the `ReActAgent`-based
`PigAgent` (HarnessAgent adoption = Phase 5).

### Permission wiring (C-1, CRITICAL — closed) — javap-confirmed signatures

`ReActAgent.Builder.permissionContext(io.agentscope.core.permission.PermissionContextState)`;
`ReActAgent.setPermissionMode(RuntimeContext, PermissionMode)` / `setPermissionMode(String userId,
String sessionId, PermissionMode)`; `ToolUseBlock.getName()` (no `getSuggestedRules()` — that is
server-side `ToolBase.generateSuggestions`); `ConfirmResult(boolean, ToolUseBlock)` /
`ConfirmResult(boolean, ToolUseBlock, List<PermissionRule>)`; `Msg.METADATA_CONFIRM_RESULTS` +
`Msg.builder().metadata(...)`.

- **Core seams (additive, back-compatible).** `PigAgent.Builder.permissionContext(PermissionContextState)`
  → `reactBuilder.permissionContext(...)`; `PigAgent.setPermissionMode(nativeMode, sessionId)` →
  `reactAgent.setPermissionMode(contextFor(sessionId), mode)`. `AgentFactory` gained a
  `Supplier<PermissionContextState>` (re-evaluated on every `create()`, so a model-switch/MCP rebuild
  picks up the current mode). `AgentInstanceFactory` gained a `PermissionContextProvider(spec, toolkit)`.
- **All 4 `ToolPermissionHook` sites replaced** (`AgentBootstrap`): interactive (supplier, `interactive=true`),
  per-agent factory (provider, agent mode override else global, `interactive=true`), autonomous
  digital-employee (`interactive=false` → DONT_ASK + ASK→DENY fail-closed), channel
  (`resolveChannelMode()`, `interactive=false`). `PermissionDeniedTool`/`ToolPermissionHook` refs deleted;
  `AgentWiring.toolkitFor` dropped the sentinel-preservation; `LoopDetectionHook` ignore-set now holds only
  the loop sentinel; `AgentWiringTest` updated.
- **ASK → HITL** in `AgentRepl.renderTurn`: a suspended turn returns its `RequireUserConfirmEvent`; the
  REPL prompts per tool (y/a/N, `readerRef`), builds `List<ConfirmResult>`, and resumes via
  `kernel.chat(id, resumeMsg, sessionId)` with `Msg.METADATA_CONFIRM_RESULTS` (round-cap guarded). "always"
  persists the tool to `permissions.allowlist.tools` (future builds auto-allow). No reader → fail-closed.
- **Runtime `/permission mode`** (`PermissionCommand`): persists config + `PigAgent.setPermissionMode` on
  the active session slot (guarded when no live agent, for the isolated command test). Javadoc updated (L-2).
  Known limit: per-tool rules are re-derived at agent (re)build; `setPermissionMode` flips the base mode
  (→plan/→bypass exact, →ask/→auto fail-safe).

### State wiring (Phase-3 CLI item — closed)

One shared `JsonFileAgentStateStore(workspace/state/)` built in `AgentBootstrap`, passed to the interactive
`AgentFactory`, the per-agent `AgentInstanceFactory`, and the channel `AgentFactory` (channels use their own
`(pig, "channel:<id>")` slots). `runTurn` threads `sessionManager.getCurrentSessionId()` into
`kernel.chat(activeId, msg, sessionId)`. The last `io.agentscope.core.session.JsonSession` refs were removed
from `AgentBootstrap` (+ `Services.agentSession`/`shutdownCommon`) and `FullLinkAgentIT`; `SessionManager`
now uses its Phase-3 (no-`agentSession`) constructor.

### Event-model rewrite (typed `AgentEvent`)

`AgentRepl.renderStream(Flux<AgentEvent>)` aggregates typed events — answer from
`TextBlockDeltaEvent.getDelta()` (→ `StreamingMarkdownPrinter`), tool blocks from `ToolResultTextDeltaEvent`
+ `ToolResultEndEvent` (`getState()==DENIED` shown as a denial) via `ToolCallFormatter`, `⋯ thinking`
spinner on `ModelCallStartEvent`/`ThinkingBlockStartEvent`, `ExceedMaxIters`/`AllToolsDenied` notices — and
returns any `RequireUserConfirmEvent` for the HITL loop. **CC-REPL preserved**: `MarkdownAnsiRenderer`,
`StreamingMarkdownPrinter`, `ToolCallFormatter` (+ credential redaction), `StatusLine`, `InlineSelector`,
slash completion are untouched. **Interrupt preserved**: mid-turn Ctrl-C still calls
`kernel.interruptCurrent()` (native `ReActAgent.interrupt` under `InterruptController`/`TurnHandle`) and
disposes the subscription. `pig-agent-web` `ChatHandler` and `pig-agent-channel` `ChannelAgentBridge` use
the same typed-event aggregation (web SSE frames; channel accumulates `TextBlockDeltaEvent`).

### Security findings closed

- **C-1 (CRITICAL)** permission enforcement wired (above). **H-1 (HIGH)** `webSearch` READ_ONLY→NETWORK in
  `ToolRiskClassifier` + `readOnly=true` removed from `BraveWebSearchTool`. **M-1** startup WARN when
  `permissions.allowlist.commands` non-empty (command-granular allowlist still Phase-5). **M-2** `McpManager`
  `setToolsChangedCallback` fires on runtime add/remove/enable/disable → `AgentBootstrap` rebuilds the
  interactive + channel agents so the permission context re-snapshots the new tool set (existing slots stay
  fail-safe on the base mode). **M-3** checklist classified (`createChecklist`/`completeItem`→WRITE,
  `showChecklist`→READ_ONLY) + `@Tool(readOnly=true)` on `showChecklist`. **L-1** defunct
  `PermissionVetoSpikeIT` (sentinel mechanism) deleted. **L-2** `PermissionCommand` Javadoc updated.
- **Digital-employee `DeniedActionRecorder`** re-wired onto native DENY: `AgentRunner` scans the finished
  conversation for `ToolResultBlock`s with `ToolResultState.DENIED` (post-hoc, fault-tolerant) since native
  permission has no build-time denial callback.

### Tests rewritten (+ why)

- `AgentReplTurnTest` / `AgentReplInterruptTest` / `AgentReplErrorPrintTest`: old `io.agentscope.core.agent.Event`
  → typed `AgentEvent`; turn now streams via `kernel.chat(id, msg, sessionId)` (+ a DENIED-result render test).
- `AgentWiringTest`: dropped the `PermissionDeniedTool` sentinel assertions; added `effectiveMode`.
- `ChannelAgentBridgeTest`: `AgentEvent` + verifies the channel-owned session id `"channel:<id>"`.
- `web/ChatHandlerTest`: `AgentEvent` frames (`ModelCallStartEvent`→reasoning, `TextBlockDeltaEvent`→answer).
- `ToolRiskClassifierTest`: added H-1/M-3 assertions.
- `PermissionEnforcementIT`: re-expressed on `PermissionContextFactory` + `permissionContext(...)` (H-2).
- `FullLinkAgentIT`: dropped `JsonSession` + old `SessionManager` ctor.
- `PermissionVetoSpikeIT`: deleted (L-1, defunct sentinel). `MultiAgentSwitchTest`/`DigitalEmployeeScheduledRunTest`
  needed no change (additive core ctors preserved).

### Phase-5 open items (unchanged scope)

HarnessAgent wrap; native compaction/memory/subagent/plan-mode adoption; hook→middleware
(`EphemeralMemoryContextHook`, `LoopDetectionHook`); native Gateway for channels; delete
`RetryingModel`/`InterruptibleModel` (native `.maxRetries`/`.fallbackModel`/`interrupt`); drop the 1.x
`agentscope` dep + `agentscope.version` property; command-granular allowlist as a `ToolBase`
`checkPermissions`/`matchRule`; live-model IT run (permission end-to-end, multi-turn persistence). Also: the
pre-existing `AgentRegistry` default-instance vs `AgentHolder` staleness on holder-only rebuilds (model
switch + M-2) — the interactive kernel path reads the registry instance; reconcile so a holder rebuild
updates the active instance too.

## 12. Phase 5a execution log (branch `av2/20260716-cleanup`)

**Base:** `av2/20260716-foundation-main` (whole reactor already GREEN on 2.0 after Phase 4). **Not
merged** (lead merges into the v2 line). Three steps, each committed separately and kept
reactor-green.

### Step 1 — delete `RetryingModel` + `InterruptibleModel` → native (commit `refactor(av2): Phase 5a step 1`)

**Native retry — DECISION: delete pig's classifier too.** `javap` on `agentscope-core-2.0.0` proves the
native model-layer retry already distinguishes transient vs permanent, so `RetryingModel`,
`RetryPolicy` **and** `TransientErrorClassifier` are all deleted (not just the decorator):
- `ExecutionConfig.RETRYABLE_ERRORS` (a `Predicate<Throwable>`) matches `HttpTransportException` /
  `ModelHttpException` with `isRetryableHttpStatus()` (**429 or 5xx**), `TimeoutException`, and
  `IOException` → retryable; everything else (4xx/auth) → not retryable. This is a **superset** of
  pig's old classifier (which also treated 4xx as permanent + 5xx/timeout/IO as transient) — it adds
  429 rate-limit handling.
- `ReActAgent.Builder.maxRetries(int)` feeds `ModelConfig.maxRetries` → `ExecutionConfig.maxAttempts` →
  `ModelUtils.applyTimeoutAndRetry` = `Retry.backoff(maxAttempts-1, initialBackoff).maxBackoff(...)
  .jitter(...).filter(RETRYABLE_ERRORS)`. `DEFAULT_MAX_RETRIES = 3`. Retry is applied **inside the
  model extension impls** (e.g. `OpenAIChatModel` calls `applyTimeoutAndRetry`), i.e. on a fresh
  `model.stream` HTTP call inside one agent invocation — never by re-subscribing the single-flight
  agent (the exact property pig's decorator hand-rolled).
- Wiring: `PigAgent.Builder.maxRetries(int)` + `fallbackModel(Model)` → `reactBuilder.maxRetries/
  .fallbackModel`. `AgentBootstrap` maps config `model.retry`: `enabled` → `maxRetries = max(1,
  max-retries)`; `disabled` → `maxRetries = 1` (one attempt, no retries). Applied to the interactive,
  per-agent, channel, and autonomous build paths.
- **Documented trade-offs (native has no equivalent):** the inline user-facing `[retry k/N]` notice is
  gone (native has no per-retry callback); pig's client-side "per-attempt timeout" and "pre-emission
  guard" are gone (native uses a plain `Retry.backoff` on the model stream + a model-layer timeout —
  transient errors on well-behaved SDKs surface at/near connection, before deltas). Because retry now
  lives in the model impls, it can't be exercised with a fake `Model` offline; `NativeRetryWiringTest`
  asserts the config reaches the native `ModelConfig` (`getModelConfig().maxRetries()/.fallbackModel()`),
  and the real transient-vs-permanent behavior is a live-model IT.

**Native interrupt — KEEP `InterruptController`/`TurnHandle`, delete the decorator.**
`InterruptibleModel` is deleted; `AgentKernel.chat` now (a) registers a `TurnHandle` whose interrupt
action calls native `ReActAgent.interrupt(RuntimeContext)` (via `PigAgent.interrupt(sessionId)`) for a
clean cooperative abort — no half-finished result persisted — and (b) wraps the event stream in
`takeUntilOther(handle.onInterrupt() → TurnInterruptedException)` so the frontend regains control
immediately even if the model stalls. `AgentFactory`/`AgentInstanceFactory` no longer decorate the
model; the controller lives only in the kernel. **Trade-off:** native interrupt is *cooperative*
(checked at reasoning-step boundaries via `AgentState.interruptControl()`), so a genuinely stuck model
call is only reclaimed at the model-layer HTTP timeout (bounded, minutes) rather than immediately as the
old decorator did — acceptable, and the frontend is never blocked. `AgentKernelInterruptTest` was
re-expressed on the native path (+ a `GracefulShutdownManager.resetForTesting()` teardown, since
interrupting the artificial never-completing test model leaves a native request registered).

**Tests:** deleted the pure-decorator units (`RetryingModelTest`, `RetryPolicyTest`,
`TransientErrorClassifierTest`, `PerAttemptTimeoutTest`, `InterruptibleModelSpikeTest`,
`InterruptRetryCompositionTest`, `ModelRetryWiringTest` — 7 files, the retry/interrupt decorator
coverage); added `NativeRetryWiringTest` (4). `InterruptControllerTest` unchanged (kept `begin()`).

### Step 2 — hooks → native middleware (commit `refactor(av2): Phase 5a step 2`)

All pig hooks moved off the deprecated `io.agentscope.core.hook.*` bridge to native `MiddlewareBase`.
**`git grep` confirms no pig source imports `io.agentscope.core.hook.*` (only javadoc `{@code}` text
naming the old classes remains).**

- **`EphemeralMemoryContextHook` → `EphemeralMemoryMiddleware`.** Prefix-cache semantics preserved
  **exactly**: injection is on `onReasoning`, appending the retrieved memory as a trailing USER message
  to a **new** `ReasoningInput` handed to `next` — the incoming list (`AgentState.getContext()` at
  runtime) is never mutated, so nothing is persisted; `onSystemPrompt` is identity, so the system
  prompt stays byte-stable across turns. The **record half** is preserved via an `onAgent` post-phase
  (`next.apply(input).concatWith(record)`) that records the finished conversation
  (`RuntimeContext.getAgentState().getContext()`) to the session tier — the 2.0 equivalent of the 1.x
  `PostCallEvent`. `CachingLongTermMemory` wrap kept (N per-turn reads → 1). **Proof:** the existing
  end-to-end `MemoryUserSideInjectionTest` (6 tests, drives the whole agent via a `CapturingModel`)
  passes unchanged via the middleware path — memory user-side not in the system prompt, system prompt
  byte-stable as memory changes, injection non-accumulating, `record` to the session tier, `/memory
  off` no-op. `EphemeralMemoryMiddlewareTest` (4) also green.
- **`LoopDetectionHook` → `LoopDetectionMiddleware`.** Semantics preserved: sliding-window signature +
  warn@3 / stop@5 (the pure `LoopDetector` is unchanged). **STOP maps to the sentinel-rewrite, not a
  synthetic DENIED result** — `onActing` rewrites the offending `ToolUseBlock` to the read-only
  `loopDetected` sentinel in a **new** `ActingInput` handed to `next`, reusing the proven acting
  machinery (the sentinel tool runs, the model gets "stop and answer" as a normal tool result). This
  is deliberately chosen over constructing DENIED events by hand: it reuses the framework's tool-result
  wiring and avoids fragile synthetic events. WARN records a nudge; `onReasoning` does per-turn reset
  (USER-message count) + injects the nudge ephemerally. **Ordering matters:** the loop middleware is
  placed **before** the memory middleware (which `PigAgent.build` appends last) so its `onReasoning`
  counts the raw conversation before memory injection — mirroring the old hook priority (10 < 50).
  Ported `LoopDetectionHookTest` → `LoopDetectionMiddlewareTest` (5 tests; dropped the now-N/A
  `priority()` assertion — middleware order is list position).
- **`LoggingHook`/`ToolCallLoggingHook` → `LoggingMiddleware`/`ToolCallLoggingMiddleware`** (DEBUG
  observers on `onReasoning`/`onActing`, gated on `isDebugEnabled()`).
- **Plugin SPI:** `PluginContext.addHook(Hook)`/`addHooks(...)` → `addMiddleware(MiddlewareBase)`/
  `addMiddlewares(...)`; `CollectingPluginContext.hooks()` → `middlewares()`; `PluginRegistry.Result
  .hooks` → `middlewares`. No shipped plugin contributed a hook, so this is a type change only (tests
  updated).
- **Core seam:** `PigAgent.Builder.hooks(List<Hook>)` → `middlewares(List<MiddlewareBase>)` →
  `reactBuilder.middlewares(...)`; `AgentFactory` `hooks`→`middlewares`; `AgentInstanceFactory
  .HooksProvider` → `MiddlewareProvider`. `AgentBootstrap` builds the native middleware lists.

### Step 3 — drop the 1.x `agentscope` dep (fully-2.0 milestone) (commit `chore(av2): Phase 5a step 3`)

The parent POM's legacy `io.agentscope:agentscope` **depMgmt entry** + the `agentscope.version`
property were removed (the 2.0 `agentscope2.version`/artifacts stay). No module ever *declared* the 1.x
all-in-one — the depMgmt only pinned its version — so this is a clean removal. `git grep` confirms no
`${agentscope.version}` usage and no `<artifactId>agentscope</artifactId>` declaration remains.
`mvn dependency:tree -pl pig-agent-cli -am -Dincludes=io.agentscope:agentscope` (reactor resolution)
shows the 1.x jar **absent** from the classpath. (A bare `-pl pig-agent-cli` tree can still show a stale
1.x jar — that resolves an *installed* pre-migration `pig-agent-core` from the local repo, which the
reactor build never uses; the current source `pig-agent-core` POM declares only `agentscope-core` +
`agentscope-harness`.)

### Acceptance (single-threaded surefire, 2.0)

`mvn clean test -DskipITs` — **whole reactor GREEN, all 17 modules** (BUILD SUCCESS). `pig-agent-core`
209 → **208** (net: −29 deleted decorator/retry/interrupt units +4 `NativeRetryWiringTest`; −6 loop
hook test +5 loop middleware test; −1 dropped `priority()` case). Removed tests are exactly the
subjects that were deleted (retry/interrupt decorators, loop hook adapter). No
`io.agentscope.core.hook.*` imports remain; the 1.x `agentscope` dep + `agentscope.version` property are
gone.

### Remaining Phase-5b / 6 open items

`HarnessAgent` wrap (`HarnessAgent.Builder.fromAgent(ReActAgent)`); native compaction
(`CompactionConfig`) / memory (`MemoryConfig`, with a dedicated cheap model) / subagent / plan-mode
adoption; native Gateway for channels; command-granular allowlist as a `ToolBase`
`checkPermissions`/`matchRule`; **live-model ITs** (permission DENY end-to-end, multi-turn persistence,
and the native transient-vs-permanent retry behavior that unit tests can no longer exercise offline).
Optional cleanup: the now-unused vendor-SDK depMgmt entries + `*.version` properties in the parent POM
(kept this pass — they no longer affect the classpath since the model extensions bundle the SDKs
transitively), and re-`install` to refresh the stale local-repo `pig-agent-core` jar.

## 13. Phase 5b execution log (branch `av2/20260716-harness-adopt`)

**Base:** `av2/20260716-foundation-main` (whole reactor GREEN on pure 2.0 after Phase 5a). **Not
merged** (lead merges into the v2 line). Scope: adopt the `HarnessAgent` **vehicle** for every
`PigAgent` and turn on native **tool-result eviction** (the one capability pig lacked), while KEEPING
pig's differentiated compaction + memory (do NOT adopt native compaction/memory).

### The wrap design — direct `HarnessAgent.builder()`, NOT `fromAgent` (javap-grounded)

`PigAgent.Builder.build()` now produces a `HarnessAgent` via `HarnessAgent.builder()…build()` and stores
both the `HarnessAgent` (the vehicle) and `harness.getDelegate()` (the underlying `ReActAgent`). Turn
methods (`call`/`stream`/`streamEvents`, `setPermissionMode`) run through the **vehicle** so its native
eviction applies; conversation-state ops (`getAgentState`/`saveAgentState`, `interrupt(RuntimeContext)`,
`ConversationMemory`, and the maxIters/retry unit-test seam `getReactAgent()`) run through the **delegate**
— the exact instance the vehicle uses, so state stays consistent. `PigAgent`'s public surface is
unchanged, so cli/web/channel/kernel are untouched.

**Why `builder()` directly instead of `fromAgent(ReActAgent)` (both were sanctioned):** `javap -c` on
`agentscope-harness-2.0.0` proves the decisive facts:
- `HarnessAgent$Builder.build()` does `toolkit.copy()` at offset 0 — **exactly** as `ReActAgent$Builder
  .build()` does (also `toolkit.copy()` at offset 0). So a direct `HarnessAgent.builder().toolkit(shared)`
  yields **one** copy of the shared toolkit, byte-for-byte the same toolkit-sharing semantics the
  Phase-5a `ReActAgent` interactive agent already had (MCP hot-add/reveal already ride the M-2
  rebuild-on-MCP-change, which re-copies the current shared toolkit).
- `fromAgent(ReActAgent)` would instead build a throwaway `ReActAgent` (copy #1 of the shared toolkit),
  then `getToolkit().copy()` (copy #2), then `build()` copies again (copy #3) — triple-copy + a throwaway
  agent — **and** it routes middlewares through `filterCopyableMiddlewares(...)`, which could silently
  drop pig's middlewares. Direct builder avoids both. All `HarnessAgent$Builder` setters
  (`name/sysPrompt/model/toolkit/middlewares/stateStore/permissionContext/maxRetries/fallbackModel/
  maxIters`) delegate to an inner `ReActAgent$Builder`, so pig's config reaches the delegate unchanged
  (`toolkit(null)` is tolerated → the setter creates an empty `Toolkit`). Verified by
  `HarnessAgentWrapTest`: the delegate's `getToolkit().getToolNames()` carries pig's custom tool and
  `getMiddlewares()` contains pig's `EphemeralMemoryMiddleware`; `getReactAgent() == getHarnessAgent()
  .getDelegate()`.

### Which natives are disabled, and why (the KEEP-differentiation decision)

The north star outsources *undifferentiated plumbing* (session/permission/retry/interrupt/events — done
in Phases 2–5a) but KEEPS pig's differentiators. So at build we disable every batteries-included harness
extra pig already owns and turn ON only eviction. javap-confirmed builder toggles + rationale:

| `disableXxx()` | Why pig keeps its own |
|---|---|
| `disableFilesystemTools()` | pig's guarded `FileSystemTools` (credential-file blacklist) |
| `disableShellTool()` | pig's `ShellTools` + command sandbox (denylist/output-cap/env-scrub) |
| `disableMemoryTools()` + `disableMemoryHooks()` | pig's **ephemeral prefix-cache memory** (`EphemeralMemoryMiddleware` + `CompositeLongTermMemory`) + **A4 extraction** — native STATIC memory does NOT do ephemeral prefix-cache injection |
| `disableCompaction()` | pig's **A5 context-engineering** (`CompressionService`: three-tier budget / recursive-summary / importance / verbatim / consistency) — richer than native trigger+summary |
| `disableSubagents()` + `disableDynamicSubagents()` | Phase 6 adapts multi-agent onto native subagents |
| `disableWorkspaceContext()` + `disableAtPathExpansion()` | pig assembles its own system prompt (`AGENT.md + INFO.md + TOOL_GUIDANCE`); native workspace-context injection would break byte-stability (prefix-cache) and native @path expansion would rewrite user messages |
| `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()` | pig's `SkillsTool` + `SkillProvider` SPI built-ins |
| `disableToolsConfig()` | pig manages its own `Toolkit` (no `tools.json`) |
| `disableSessionPersistence()` | pig's `AgentStateStore` (Phase 3) is the **single** persistence mechanism — this disables only the harness *session-log* layer, NOT the core `ReActAgent` per-`(userId,sessionId)` auto-save (which is set via `.stateStore()` → inner, independent of this flag; consumed in `HarnessAgentBuilderSupport`, never touching `inner.stateStore()`). Verified by `HarnessAgentWrapTest.perSessionStatePersistsAndRestoresThroughTheWrap`: a second agent sharing the same `JsonFileAgentStateStore` restores a prior session's conversation through the wrap. |

Builder-default notes (javap): the builder ctor pre-sets `compactionConfig=CompactionConfig.builder()
.build()` and `memoryConfig=MemoryConfig.defaults()`, but `build()` only installs the memory/compaction
middlewares when the config's model is non-null (a bare default has a null model) — still, we
`disableCompaction()`/`disableMemory*()` explicitly. `WorkspaceManager.validate()` only *warns* on a
missing workspace dir (no throw/create), so a bare-builder `PigAgent` in tests is safe; when no
workspace is supplied `PigAgent` uses one lazily-created shared temp dir (OS temp, never the repo tree).

### System prompt stays pig's, byte-stable

With `disableWorkspaceContext()`, the `HarnessAgent` passes pig's assembled `sysPrompt` straight to the
inner `ReActAgent` with no workspace/persona injection. Proven by the unchanged full-agent
`MemoryUserSideInjectionTest` (6 tests): system prompt == the pig constant across turns as memory
changes, memory injected on the *user* side, injection ephemeral/non-accumulating.

### Tool-result eviction (the gap pig lacked) — config + proof

New config surface `tools.result-eviction` (`PigAgentConfig.ResultEvictionConfig`): `enabled`
(**default true**), `threshold` (**default 80000** chars, mapped to `ToolResultEvictionConfig
.maxResultChars`), `preview-chars` (default 2000), `dir` (default `/large_tool_results`). `AgentBootstrap
.buildEvictionConfig(...)` builds the native `ToolResultEvictionConfig` (or `null` when disabled) from
it and threads it — plus the pig workspace root — through `AgentFactory`/`AgentInstanceFactory` →
`PigAgent.Builder.toolResultEviction(...)`/`.workspace(...)` to every build path (interactive,
per-agent, channel, autonomous). Model at the core level: **non-null config → eviction ON with it;
`null` → `disableToolResultEviction()`** (so a bare-builder test defaults OFF, while the *product*
default is ON because `AgentBootstrap` builds a config from the default-true config). javap: `build()`
adds the `ToolResultEvictionMiddleware(abstractFilesystem, config)` iff `!disableToolResultEviction &&
config != null`; the `AbstractFilesystem` is always resolved from `.workspace(path)` regardless of
`disableFilesystemTools()`, so eviction has a spool target. Proven by `ToolResultEvictionTest` (offline,
fake model + no-arg tool + `@TempDir` workspace, threshold 500): a 4007-char tool result is spooled to
`workspace/large_tool_results/…` and the **retained conversation** (`AgentState.getContext()`) is
rewritten to the read-back placeholder (`"Tool output was too large … use read_file …"` + a short
preview), with the full blob gone — preventing context bloat on future turns.

### javap signatures used (agentscope-harness-2.0.0)

`io.agentscope.harness.agent.HarnessAgent implements io.agentscope.core.agent.Agent, AutoCloseable`:
`getDelegate():ReActAgent`, `streamEvents(Msg):Flux<AgentEvent>` / `streamEvents(Msg,RuntimeContext)`,
`call(List<Msg>,RuntimeContext):Mono<Msg>`, `setPermissionMode(RuntimeContext,PermissionMode)`,
`getToolkit()`, `getModel()`, `getStateStore()`, `interrupt()`/`interrupt(Msg)`, `close()`,
`enterPlanMode/exitPlanMode/isPlanModeActive`, `gateway()`, `getSubagentAgentManager()`. ·
`HarnessAgent$Builder`: `builder()`, `fromAgent(ReActAgent)` (static→Builder), `name/description/
sysPrompt/model/toolkit/maxIters/middleware(s)/stateStore/defaultSessionId/permissionContext/maxRetries/
fallbackModel/stopOnReject`, `workspace(Path|String)`, `toolResultEviction(ToolResultEvictionConfig)`,
`disableToolResultEviction()`, `compaction/disableCompaction`, `memory/disableMemoryTools/
disableMemoryHooks`, `disableFilesystemTools/disableShellTool`, `disableSubagents/disableDynamicSubagents`,
`disableWorkspaceContext/disableAtPathExpansion/disableDynamicSkills/disableDefaultWorkspaceSkills/
disableToolsConfig/disableSessionPersistence`, `enablePlanMode`, `build()`. ·
`io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig`: `DEFAULT_MAX_RESULT_CHARS=80000`,
`DEFAULT_PREVIEW_CHARS=2000`, `DEFAULT_EVICTION_PATH="/large_tool_results"`,
`DEFAULT_EXCLUDED_TOOLS={read_file,write_file,edit_file,grep_files,glob_files}`; `defaults()`,
`builder().maxResultChars/previewChars/evictionPath/excludedToolNames`.

### Nothing regressed (verified GREEN through the wrap)

CC-REPL streaming (`AgentReplTurnTest`), mid-turn interrupt (`AgentReplInterruptTest`,
`AgentKernelInterruptTest`), native permission veto (`PermissionVetoPocTest`), per-session state
(`session` 45 + `HarnessAgentWrapTest` persistence), pig compaction (core compression tests) + ephemeral
memory (`MemoryUserSideInjectionTest`) + loop-detection (`LoopDetectionMiddlewareTest`) + multi-agent
(`MultiAgentSwitchTest`, `AgentKernelTest`), web SSE (`ChatHandlerTest`), channel (`ChannelAgentBridgeTest`)
— all green on the wrap.

### Acceptance (single-threaded surefire, 2.0)

`mvn test` (skipITs) — **whole reactor GREEN, all modules** (BUILD SUCCESS). Total **999 tests, 0
failures, 0 errors, 5 skipped** (≈995 baseline **+4** from the two new test classes:
`HarnessAgentWrapTest` 3 + `ToolResultEvictionTest` 1; `pig-agent-core` 208→**212**). `mvn -q -pl
pig-agent-cli -am compile` green. No other test changed (the wrap preserved every existing behavior).

### Remaining Phase-6 open items

Native subagents multi-agent adapt (`disableSubagents()` today; re-map `AgentRegistry`/`/agent use` onto
`GatewayBootstrap` peers + `SubagentDeclaration`, moving per-agent permission to `PermissionMode`/
`tools.json`); native Gateway for channels (Telegram/Discord/Slack stay custom `Channel`s);
`enablePlanMode()` HITL (Phase-2 only maps pig `plan`→`EXPLORE` at the permission layer); command-granular
allowlist as a `ToolBase` `checkPermissions`/`matchRule`; **live-model ITs** (permission DENY end-to-end,
multi-turn persistence + restart on `JsonFileAgentStateStore`, native transient-vs-permanent retry, and a
real >80K tool-result eviction round-trip). Lifecycle nit: rebuild-driven paths (model switch / MCP
change) do not `close()` the superseded `HarnessAgent` — kept out of scope (the `ReActAgent` path never
closed agents either; the disabled-extras vehicle holds few resources).

## 14. Phase 6a execution log (branch `av2/20260716-subagents`)

**Base:** `av2/20260716-foundation-main` (whole reactor GREEN on 2.0 after Phase 5b; the `PigAgent`
`HarnessAgent` vehicle currently `disableSubagents()`). **Not merged** (lead merges into the v2 line).
Scope: adopt **native subagent delegation** on the vehicle — the "多 agent 编排 = 子 agent" north-star —
while keeping pig's **peer** multi-agent (`AgentRegistry` + `/agent use`) intact and un-conflated.

### The two orthogonal concepts (LOCKED intent — implemented + documented)

pig now has TWO distinct, non-conflated agent concepts:

| | **Peer agent** (KEPT) | **Subagent** (ADDED, native) |
|---|---|---|
| What | The *active* agent you switch to | A transient *child* the active agent delegates a subtask to |
| Driver | `AgentRegistry` / `AgentSpec` / `AgentInstanceFactory` + `/agent use <id>` | Native `agent_spawn`/`agent_send`/`agent_list` + `task_*` tools |
| Lifetime | Long-lived, switchable | Ephemeral, returns a result and is discarded |
| Storage | `workspace/agents/<id>.md` (`AgentSpecRepository`) | built-in `general-purpose` + `workspace/subagents/<id>.md` |

`/agent` peer-switching is **untouched** (no `AgentRegistry`/`AgentSpec`/`AgentInstanceFactory` deletion) —
verified by the unchanged `MultiAgentSwitchTest`/`AgentKernelTest`.

### Enable design (javap-grounded)

`PigAgent.Builder` gained `subagents(boolean)` + `subagentDeclarations(List<SubagentDeclaration>)`. When
enabled, `build()` **stops** calling `disableSubagents()`/`disableDynamicSubagents()` (and passes any
code-declared subagents via `hb.subagents(...)`); when disabled (bare-builder default) it keeps both
disable calls, so behavior is **byte-for-byte pre-6a**. Enabling makes the vehicle register the built-in
`general-purpose` subagent + the `agent_spawn`/`agent_send`/`agent_list`/`task_output`/`task_cancel`/
`task_list` tools and discover `<workspace>/subagents/<id>.md`. **Key javap fact:** in
`HarnessAgent$Builder.build()`, `HarnessAgentBuilderSupport.buildSubagentsMiddleware(...)` /
`buildSubagentEntries(...)` (which loads `AgentSpecLoader.loadFromDirectory(<workspace>/subagents, …)`)
are gated **only** on `disableSubagents`, using a `WorkspaceManager` resolved from `.workspace(path)` —
**independent of `disableWorkspaceContext()`** (which only gates `WorkspaceContextMiddleware`). So pig's
`disableWorkspaceContext()` (byte-stable system prompt) does NOT suppress workspace-subagent discovery.
The built-in child is `HarnessAgent.builder().name("general-purpose-subagent").model(<parent model
instance>)…asLeafSubagent()` — it inherits the **parent's `Model` instance** (offline-testable) and the
3-level recursion cap is a native invariant (`asLeafSubagent()`).

### AgentSpec → SubagentDeclaration mapping (decision + field table)

**Decision:** keep peers and subagents *separate*, and provide `AgentSpecSubagentMapper` (pure,
unit-tested) so a pig-declared peer `AgentSpec` can *also* be surfaced as a spawnable
`SubagentDeclaration` — "both a switchable peer AND delegable as a child" without conflating them.
`AgentBootstrap` wires it on the **default interactive agent**: peer specs (from `AgentSpecRepository
.findAll()`, excluding `default` + autonomous) are mapped and passed to the interactive `AgentFactory`.
Switched-to peers (built by `AgentInstanceFactory`) get the built-in + workspace surface only (a registry
back-reference for their peer list is deferred — documented). Field mapping (grounded in
`AgentSpecLoader.parse`, i.e. how native `subagents/<id>.md` is parsed):

| `SubagentDeclaration` | ← `AgentSpec` | notes |
|---|---|---|
| `name` | `id()` | the `agent_id` passed to `agent_spawn` |
| `description` | `name()` + first prompt line | **required** by native; never blank |
| `inlineAgentsBody` | `sysPrompt()` | the child's persona/system-prompt body |
| `tools` | `toolNames()` (if a subset) | the child's tool allowlist (empty = inherit all) |
| `inheritParentPermissions` | `true` (requested) | see the permission caveat below |
| `permissionMode` | *(not mappable)* | native has no per-agent permission field |

### Permission inheritance — VERIFIED, and it is a 2.0.0 GAP (the headline finding)

The task asked to "verify permission inheritance (parent DENY rules propagate to child)". **Verified —
and it does NOT hold in AgentScope 2.0.0.** `SubagentDeclaration.inheritParentPermissions` is *declared
but inert*: a whole-jar `javap` scan shows **no harness class reads `isInheritParentPermissions()`** (only
`SubagentDeclaration`/its Builder reference it). The offline test
`SubagentDelegationTest.spawnedChildDoesNotInheritParentDenyRule_native2_0_0Gap` proves the consequence
empirically: a parent with a `PermissionContextState` DENY rule for `secretTool` spawns a
`general-purpose` child that then **executes `secretTool` (state=SUCCESS)** — the parent's DENY does not
reach the child. i.e. enabling subagents today is a **permission-escape**: a spawned child runs under its
own (permissive) default context, bypassing the parent's DENY rules and pig's permission mode. The child
*does* build with the parent's `Model` instance (verified), just not its permission context. **This is why
`subagents.enabled` defaults OFF** (see config below). pig's mapper still *requests*
`inheritParentPermissions(true)` so it is ready when the runtime honors it. Wiring real inheritance (a
custom `subagentFactory` that injects the parent's `PermissionContextState` into the child build) is the
top **Phase-6b** item.

### CC-REPL forwarding of subagent streaming

Synchronous local children forward their events onto the parent's `streamEvents` stream tagged with a
`source` path (`AgentEvent.getSource()` → e.g. `"main/reviewer"`; `null` at the parent). `AgentRepl.onEvent`
now routes any `source != null` event to `onChildEvent`, which renders it via a new pure
`io.pigagent.cli.render.SubagentEventRenderer` as a **dim, nested, source-labeled** line
(`  └ [reviewer] …`) — distinct from the parent's answer — reusing `ToolCallFormatter`'s credential
redaction + truncation (child output can never leak secrets or flood the REPL). Child answer text is
accumulated per source and flushed on the child's `TextBlockEndEvent`/`AgentEndEvent` (and any remainder at
stream completion). `SubagentExposedEvent` is handled minimally (a `[subagent exposed: <label>]` note; the
full expose-to-user-via-Channel bridge is Phase-6b). Background (`timeout_seconds=0`) tasks are **not**
forwarded — their completion arrives as a `<system-reminder>` the parent reasons over, surfacing naturally
as parent answer text (no special event handling needed). **CC-REPL preserved**: `MarkdownAnsiRenderer`/
`StreamingMarkdownPrinter`/`ToolCallFormatter`/`StatusLine`/`InlineSelector`/slash-completion untouched;
mid-turn Ctrl-C interrupt path untouched.

### Config default + rationale

New block `subagents` (`PigAgentConfig.SubagentsConfig`), single flag `enabled`, **default `false`
(conservative)**. Two reasons, safety-first: **(1) permission-escape (dominant):** 2.0.0 does not propagate
the parent's permission context to spawned children (above), so enabling grants children authority the
parent lacks — an explicit opt-in until Phase-6b wires inheritance. **(2) token cost:** enabling adds the
six-tool `agent_spawn` toolset to the model schema every turn. `enabled: false` (default) = today's
behavior exactly (no subagent tools in the schema). `enabled: true` turns on the built-in `general-purpose`
+ workspace `subagents/*.md` + pig peer-subagent mapping, on the **interactive** tracks only (default +
`/agent` peers); channel + autonomous tracks stay OFF (fail-closed posture). (The north-star favors ON, but
the permission gap makes OFF the responsible default for this permission-conscious codebase; flip to `true`
once Phase-6b inheritance lands.)

### javap signatures used (agentscope-harness-2.0.0)

`HarnessAgent$Builder`: `subagent(SubagentDeclaration)`, `subagents(List<SubagentDeclaration>)`,
`subagentFactory(String, Function<String,Agent>)`, `disableSubagents()`, `disableDynamicSubagents()`,
`buildSubagentEntries(Path[, SandboxBackedFilesystem])`, `asLeafSubagent()` (pkg-private). ·
`io.agentscope.harness.agent.subagent.SubagentDeclaration` (+ `Builder`): `name/description/
workspace(Path)/workspaceMode(WorkspaceMode{ISOLATED,SHARED})/inlineAgentsBody(String)/model(String)/
maxIters/steps/temperature/topP/variant/mode(Mode{PRIMARY,SUBAGENT,ALL})/hidden/persistSession/
inheritParentPermissions(boolean)/exposeToUser(Boolean)/tools(List)/skills(List)/url/headers`;
getters incl. `isInheritParentPermissions()`. · `AgentSpecLoader.loadFromDirectory(Path,Path)` /
`parse(String,String,Path)` (front-matter → declaration; body → `inlineAgentsBody`; `description`
required). · built-in subagent tool `@Tool` names: `agent_spawn`(params `agent_id`,`task`,`label`,
`timeout_seconds`,`expose_to_user`)/`agent_send`/`agent_list` (`AgentSpawnTool`),
`task_output`/`task_cancel`/`task_list` (`TaskTool`); built-in agent id `general-purpose`. ·
`io.agentscope.core.event.SubagentExposedEvent`: `getSubagentId()/getAgentId()/getSessionId()/getLabel()`;
`AgentEvent.getSource()` + `withSource(String)` (fluent). · **`inheritParentPermissions` is read by NO
harness class** (whole-jar `javap` scan) — the inert-field finding above.

### Acceptance (single-threaded surefire, 2.0)

`mvn test` (skipITs) — whole reactor GREEN, all 17 modules (BUILD SUCCESS). Total **1014 tests, 0
failures, 0 errors, 5 skipped** (999 Phase-5b baseline **+15**: `SubagentDelegationTest` 4 +
`AgentSpecSubagentMapperTest` 6 + `SubagentEventRendererTest` 4 + `AgentReplTurnTest` +1; `pig-agent-core`
212→222, `pig-agent-cli` 81→86). `mvn -q -pl pig-agent-cli -am compile` green. No existing
test changed behavior (subagents default OFF → the reactor is byte-for-byte the pre-6a build unless
`subagents.enabled=true`). The new offline tests prove: subagent tools present when enabled / absent when
disabled; a spawned `general-purpose` child runs on a fake model and its result returns to the parent; the
2.0.0 permission-inheritance gap (regression-guarded); and the CC-REPL renders a `source`-tagged child event
nested + labeled, distinct from the parent.

### Remaining Phase-6b items

**DONE in Phase-6b (see §15):** permission-inheritance wiring + `subagents.enabled` default ON;
command-granular allowlist as a `ToolBase`; and the prerequisite P0 fix (the contract guard was
bypassing native permission). **Deferred to Phase-6c:** Native Gateway for channels (`GatewayBootstrap`)
+ native channel adapters (DingTalk/Feishu/WeCom/GitHub/GitLab); Telegram/Discord/Slack stay custom
`Channel`s. · `expose_to_user` → Channel bridge (`agent.channel(...)` + `chat.sendToSubagent(...)`) so the
operator can converse with an exposed subagent directly. · `enablePlanMode()` HITL. · surface
peer-subagent declarations to switched-to peers (a registry back-reference in `AgentInstanceFactory`) +
refresh on `/agent new`. · **live-model ITs**: delegation end-to-end (spawn → child → result), background
auto-push-back, and child permission-inheritance DENY.

## 15. Phase 6b execution log (branch `av2/20260716-subagent-perms`)

**Base:** `av2/20260716-foundation-main` (whole reactor GREEN on 2.0 after Phase 6a). **Not merged.**
Scope: the two permission/security completions — (1) subagent permission inheritance (close the 2.0.0
gap → flip subagents default ON), (2) command-granular allowlist (deferred M-1). Both hinge on a P0
finding surfaced while wiring them.

### P0 (prerequisite, security): the contract guard was bypassing native permission — FIXED

**Finding (javap + a real-engine test).** The native `PermissionEngine` is only consulted when the ReAct
acting phase resolves a tool to a `ToolBase`: `ReActAgent$CallExecution` does
`tool = toolkit.getTool(name); if (tool instanceof ToolBase) …checkPermission(tool,…) else return
PermissionVerdict(ALLOW)` — a **non-`ToolBase` tool is auto-ALLOWed**. pig's `ToolContractGuard.install`
wraps *every* registered tool in `GuardedAgentTool`, which implemented only `AgentTool` (not `ToolBase`),
and `Toolkit.getTool` returns the stored wrapper unchanged. So in production **every guarded tool bypassed
the permission engine** — pig's entire Phase-4 native permission enforcement (plan-denies-mutating,
ask-confirms, channel/autonomous fail-closed) was inert on the real (guarded) toolkit; the existing
Phase-4 tests only exercised the engine/factory in isolation or on raw (unguarded) toolkits, so it went
uncaught. Proven by `GuardedToolPermissionTest` (a guarded tool + a real `PermissionEngine` DENY rule):
RED before the fix (guard not a `ToolBase` → `ClassCastException`/`isInstanceOf` fail), GREEN after.

**Fix.** `GuardedAgentTool extends ToolBase` (snapshotting name/description/parameters/readOnly/… from the
delegate) and **delegates the built-in permission hooks** — `checkPermissions`/`matchRule`/
`generateSuggestions` forward to the wrapped tool when it is a `ToolBase`, else fall back to the default.
A guarded tool is therefore still a `ToolBase`, so name-based deny/ask/allow rules **and** a tool's own
`checkPermissions` (the command allowlist below) both apply again. Installed before MCP attach, so guarded
tools are never MCP tools (mcp flags default false). This restores intended Phase-4 behavior in production
and is the enabler for both 6b features.

### 1. Subagent permission inheritance (the seam + how parent DENY binds the child)

**Seam (javap-grounded): `HarnessAgent.Builder.subagentFactory(String name, Function<String,Agent>)`.**
`HarnessAgentBuilderSupport.buildSubagentEntries` builds the entries list as *built-in general-purpose →
declared subagents → custom `subagentFactory` entries*, and `DefaultAgentManager`'s ctor folds them into
its `agentFactories` map with `Map.put` (**last-put wins**); `createAgent(id, ctx)` resolves the spawn
against that map and returns the factory's `Agent` **unwrapped** (no native re-permissioning). So a
pig-registered custom factory named `general-purpose` (or a peer id) **overrides** the native factory for
that id. `SubagentDeclaration.inheritParentPermissions` stays inert (no harness class reads it) — pig does
not rely on it.

**Mechanism.** When `subagents` is enabled, `PigAgent.Builder.build()` registers a custom
`subagentFactory` for `general-purpose` and for every declared peer subagent. Each factory builds — *per
spawn* — a **leaf** child `HarnessAgent` (`disableSubagents()`/`disableDynamicSubagents()` → the native
3-level cap is moot, a child cannot spawn) carrying the parent's model, a fresh isolated `Toolkit.copy()`
(narrowed to the declaration's tool subset for peers) + ephemeral `InMemoryAgentStateStore`, and the
derived child permission context. `DefaultAgentManager.createAgent` then hands the model a child that runs
under that context. Because the child's tools are the parent's guarded `ToolBase`s (P0 fix), its
`PermissionEngine` actually gates them.

**Derivation — `SubagentPermissions.deriveChildContext(parent)` (fail-closed).** A spawned child has no
confirmer, so: base mode `EXPLORE`→`EXPLORE` (plan-mode parent → read-only child), `BYPASS`→`BYPASS`
(explicit trust; DENY still binds), everything else (`DEFAULT`/`ACCEPT_EDITS`/`DONT_ASK`)→`DONT_ASK`
(unruled tools ask→deny); **parent DENY rules copied verbatim** (the headline guarantee); **inherited ASK
rules downgraded to DENY**; ALLOW rules + working-dirs preserved (never widens authority). A `null` parent
→ minimal `DONT_ASK` no-rules context.

**Proof (genuine spawn path).** `SubagentDelegationTest.spawnedChildInheritsParentDenyRule_andCannotRunDeniedTool`
(rewritten from the old gap test): a parent with a `DENY secretTool` rule spawns a real `general-purpose`
child that *attempts* `secretTool`; the assertion flips to **`secret.invoked == 0`** — the child cannot
execute the parent-denied tool. `SubagentPermissionsTest` (8 cases) unit-covers the derivation, incl.
child inherits parent EXPLORE/plan read-only and channel/autonomous (`DONT_ASK`) → child fail-closed.

**Default flip → ON, and why it's now safe.** With the escape closed (parent DENY provably binds the
child) and the P0 guard fix (enforcement actually runs), `PigAgentConfig.SubagentsConfig.enabled` defaults
**`true`**. The toggle remains. Only the **interactive** tracks (default + `/agent` peers) get subagents;
the channel + autonomous factories do not pass `subagentsEnabled` (subagent tools absent there), and any
child they *could* spawn is still bound by its fail-closed derived context. `enabled: false` restores the
byte-for-byte pre-6a schema.

### 2. Command-granular allowlist (M-1) — a `ToolBase` `checkPermissions`

**Native precedence is `deny > ask > allow > tool-check`** (verified in `PermissionEngine.checkPermission`:
deny rules, then **ask rules short-circuit**, then the tool's `checkPermissions`, then allow rules → mode
default). A per-tool ASK rule therefore *shadows* any command-level ALLOW. Fix: (a) `PermissionContextFactory`
**no longer emits an ASK rule for `executeCommand`** (it still emits DENY under plan and ALLOW under bypass)
— so the tool check governs; (b) a new `CommandPermissionTool extends ToolBase` wraps the reflective
`executeCommand` (schema + execution unchanged; the exec-sandbox `CommandGuard` still runs *after* the
permission decision) and overrides `checkPermissions` to return **ALLOW** when the command's normalized
first token (`CommandKeys.of`) is in the live `permissions.allowlist.commands`, else **PASSTHROUGH**
(→ mode default: interactive ASK / non-interactive fail-closed DENY). Wired in `AgentBootstrap` just before
`ToolContractGuard.install`, so the (now `ToolBase`) guard delegates the check. The interim "startup WARN if
`allowlist.commands` non-empty" is removed (commands are honored now). This also finally makes the
autonomous `commandAllowlist` (folded into `allowlist.commands`) effective. `matchRule` matches a
command-scoped rule by normalized key. Normalization is **not bypassable**: path-prefix/quoting/`;`-glued
first tokens (`/usr/bin/git`, `"git"`, `git;rm`) simply don't equal a plain allowlisted command → no
spurious ALLOW (and a command that legitimately starts with an allowlisted token is still subject to the
exec-sandbox denylist — defense in depth). Proof: `CommandPermissionToolTest` (incl. an end-to-end test
through the production `CommandPermissionTool → GuardedAgentTool` chain + a real `PermissionEngine`),
`CommandKeysTest` (+trick keys), `PermissionContextFactoryTest.executeCommandHasNoAskRule…`.

### Acceptance (single-threaded surefire, 2.0)

`mvn test` — whole reactor GREEN, all 17 modules (BUILD SUCCESS). Total **1034 tests, 0 failures, 0 errors,
5 skipped** (1014 Phase-6a baseline **+20**: `GuardedToolPermissionTest` 2, `CommandPermissionToolTest` 7,
`SubagentPermissionsTest` 8, `CommandKeysTest` +2, `PermissionContextFactoryTest` +1; the reused
`SubagentDelegationTest` gap test was rewritten in place). `mvn -q -pl pig-agent-cli -am compile` green.

## 16. Plan Mode execution log (branch `av2/20260717-plan-mode`)

Adopt AgentScope 2.0's **native Plan Mode** ("think read-only → write `PLAN.md` → HITL-approve → execute")
as a first-class pig capability, integrated with pig's UX. The headline finding: it drops in with **zero
per-tool changes** because native enforcement keys off `AgentTool.isReadOnly()`, which pig's tools already
report correctly.

### Enable design (javap-grounded, `agentscope-harness-2.0.0`)

Plan Mode is a `HarnessAgent`-only feature, so it rides the existing Phase-5b vehicle. `PigAgent.Builder`
gained a `planMode(PlanModeSettings)` knob; when `enabled`, `build()` calls the three native builder
methods on the vehicle:

- `HarnessAgent$Builder.enablePlanMode()` / `enablePlanMode(boolean)` — installs the plan trio +
  `PlanModeMiddleware`.
- `HarnessAgent$Builder.planFileDirectory(String)` — plan-file root (workspace-relative; default `plans`).
- `HarnessAgent$Builder.allowShellInPlanMode(boolean)` — opt-in shell during plan (default false).

Runtime API on the instance (javap-confirmed): `HarnessAgent.enterPlanMode(RuntimeContext)` /
`exitPlanMode(RuntimeContext)` / `isPlanModeActive(RuntimeContext)` (+ `(String userId, String sessionId)`
overloads). `PigAgent` exposes session-keyed `enterPlanMode/exitPlanMode/isPlanModeActive(String sessionId)`
delegating through `contextFor(sessionId)` (mirrors `setPermissionMode`). **Gotcha (javap-verified by a
failing test):** unlike `call`/`stream` (which resolve `RuntimeContext.empty()`), the plan methods route
through `getAgentState(userId, sessionId)` which requires a **non-null sessionId** — the REPL always supplies
`SessionManager.getCurrentSessionId()`, so this is a non-issue in production and the `/plan` command +
status-badge reads are wrapped in try/catch (degrade to "inactive").

**How read-only is enforced (the crux, javap-decompiled).** `PlanModeMiddleware.onActing` permits a tool iff
`ALWAYS_ALLOWED.contains(name)` (`plan_enter`/`plan_write`/`plan_exit`/`todo_write`) `||`
`additionalAllowed.contains(name)` (`execute` only when `allowShellInPlanMode`) `||`
`readOnlyResolver.test(name)`. The builder wires the resolver as a lambda over the (copied) toolkit:
`name -> toolkit.getTool(name).isReadOnly()`. So Plan Mode enforces read-only **per tool's
`AgentTool.isReadOnly()`** — and pig already declares `@Tool(readOnly = true)` on its read-only tools
(`readFile`/`listDirectory`/`listSkills`/`loadSkill`/`tool_search`/compute tools/`mcp` list+test) while
mutating tools default `false` (`writeFile`/`executeCommand`/`webSearch`/`fetchUrl`/…), and
`GuardedAgentTool` **snapshots `delegate.isReadOnly()`**. Result: read-only pig tools pass, mutating pig
tools are denied, **no per-tool change**. `HarnessAgent$Builder.toolkit(Toolkit)` **copies** the toolkit
(`Toolkit.copy()`) and registers the plan trio into that copy before building the delegate — so a model-switch
rebuild never duplicate-registers into pig's shared toolkit, and pig's per-agent `Toolkit.copy()` chain is
unaffected.

### `/plan` UX + config

Config `plan-mode` block (`PigAgentConfig.PlanModeConfig`, all optional/default-safe): `enabled`
(default **false**), `plan-dir` (default `plans`), `allow-shell` (default false). `AgentBootstrap` maps it to
a `PlanModeSettings` value object and threads it into the **interactive** `AgentFactory` + the per-agent
`AgentInstanceFactory` (so `/plan` works on any switchable peer). The **channel + autonomous** tracks stay
OFF by construction (no confirmer → a `plan_exit` HITL would fail-closed and strand the run).

`/plan enter|exit|status` (`PlanCommand`, mirrors `/permission`'s "act on the active agent via the holder"
pattern — no kernel change): `enter` is **gated on `plan-mode.enabled`** (with Plan Mode off the vehicle has
no plan tools + no enforcer, so entering would be an unenforced flag flip — the command refuses and points at
the config); `status` shows enabled/active/plan-dir; `exit` is the operator's own exit (the operator IS the
human approver, so it does **not** trigger HITL — that gate is reserved for the model's `plan_exit` tool).

**Default OFF rationale.** Enabling installs the plan trio + `PlanModeMiddleware` (whose `onSystemPrompt`
appends a plan hint), which changes the byte-stable system prompt. Keeping it OFF by default = zero behavior
change / prefix-cache stable, and is the honest posture given the open question of whether a *small* model
reliably self-drives `plan_enter`/`plan_write` (needs a live-model IT — see Limitations). Flipping to ON is a
one-line config change once validated.

### HITL exit — reuses the existing native-permission confirm path

`PlanModeTools$PlanExitTool.checkPermissions(...)` returns `PermissionDecision.ask(...)`, so the model's
`plan_exit` surfaces as a **`RequireUserConfirmEvent`** — exactly what `AgentRepl.renderTurn`/`confirm`
already handle. So HITL exit works for free through the existing confirm/resume loop; the only addition is a
plan-aware prompt ("Approve the plan and exit Plan Mode to begin execution? (y=approve / N=stay in plan)").
Approve → resume with `ConfirmResult(true)` → `plan_exit` runs, mode flips to build. Reject → resume with
`ConfirmResult(false)` → stays in plan mode. Both proven offline in `PlanModeTest`.

### EXPLORE (`/permission mode plan`) vs native Plan Mode — reconciliation

Two **orthogonal** read-only mechanisms that compose coherently:

- `/permission mode plan` → native `PermissionMode.EXPLORE` (permission-engine read-only: every mutating tool
  DENY via per-tool rules). A quick, permission-layer toggle.
- `/plan enter` → native Plan Mode (structured plan file + HITL exit; read-only enforced independently by
  `PlanModeMiddleware`).

Entering/exiting Plan Mode does **not** change the permission mode, and vice versa — no conflicting states.
Plan-mode read-only holds **regardless** of the permission mode (the middleware wraps `onActing` and denies
before the permission engine even runs — so even under BYPASS, the plan phase stays read-only). After an
approved `plan_exit`, execution proceeds under whatever permission mode is active (a user who *also* set
`/permission mode plan` would still be read-only via EXPLORE — the two are independent knobs, documented).

### CC-REPL indicator

`StatusLine` gained a `planActive` overload appending a distinct `⏸ PLAN` badge (warn-colored) when the
current session is plan-active; `AgentRepl` computes it per-prompt via `agentHolder.get().isPlanModeActive(sid)`
(guarded → never breaks the prompt). The written plan renders through the existing tool-call formatter
(`⏺ plan_write / └ …`); the HITL exit prompt is the plan-aware confirm line above. All existing CC-REPL
rendering is preserved.

### Subagent plan inheritance — still holds (moot by construction)

pig's leaf children (`PigAgent.Builder.buildLeafChild`) deliberately do **not** enable Plan Mode. This is safe
because `agent_spawn` is **not** read-only → it is denied by `PlanModeMiddleware` during the plan phase, so a
subagent **cannot be spawned while the parent is plan-active** — the "child doesn't inherit plan mode" gap
noted in the upstream doc is therefore moot for pig. (Children still inherit the parent's DENY permission
context via `SubagentPermissions.deriveChildContext`, unchanged from Phase-6b.)

### Design-pattern notes

`PlanModeSettings` (immutable value object, `disabled()` baseline + blank-dir normalization) carries the
three knobs so the factories don't grow three loose primitives. `PlanCommand` follows the existing
picocli-subcommand + `ReplContext` pattern. No new SPI/abstraction invented — Plan Mode is a native harness
feature; pig only wires + surfaces it.

### javap signatures used (agentscope-harness-2.0.0)

```
HarnessAgent$Builder.enablePlanMode() : HarnessAgent$Builder
HarnessAgent$Builder.enablePlanMode(boolean) : HarnessAgent$Builder
HarnessAgent$Builder.planFileDirectory(String) : HarnessAgent$Builder
HarnessAgent$Builder.allowShellInPlanMode() / allowShellInPlanMode(boolean) : HarnessAgent$Builder
HarnessAgent.enterPlanMode(RuntimeContext) / (String,String) : void
HarnessAgent.exitPlanMode(RuntimeContext) / (String,String) : void
HarnessAgent.isPlanModeActive(RuntimeContext) / (String,String) : boolean
PlanModeTools.PLAN_ENTER / PLAN_WRITE / PLAN_EXIT : String   (tool names)
PlanModeTools$PlanExitTool.checkPermissions(Map, PermissionContextState) : Mono<PermissionDecision>  (ASK → HITL)
PlanModeMiddleware(PlanModeManager, Predicate<String> readOnlyResolver, Set<String> additionalAllowed)
  // readOnlyResolver wired as: name -> toolkit.getTool(name).isReadOnly()
PlanModeManager.writePlan(RuntimeContext, AgentState, String) : String   (→ <workspace>/<plan-dir>/PLAN.md)
```

### Honest limitations

- Read-only enforcement is proven; whether a **small** model reliably *chooses* to call
  `plan_enter`/`plan_write` (vs narrating a plan) is model-dependent — needs a live-model `*IT` (offline tests
  drive scripted tool calls). Config-gated OFF by default reflects this.
- `allow-shell=true` is wired faithfully to `allowShellInPlanMode`, but it adds the **native** tool name
  `execute` to the plan allow-list; pig disables the native shell and uses its own `executeCommand` (name
  mismatch, and non-read-only), so for pig `allow-shell` is effectively a **no-op** (pig's shell stays denied
  in the plan phase — the read-only guarantee is *stronger*, not weaker). Documented in `PlanModeConfig`.
- Plan state is per-`(userId,sessionId)` on the native `AgentState`; a `/plan enter` with no current session
  is caught and reported (production always has a session).

### Acceptance (single-threaded surefire, 2.0)

`mvn test` — whole reactor GREEN. **+13 tests** over the plan-mode baseline: `PlanModeTest` (8 —
toggle on/off, `PlanModeSettings.disabled()`, programmatic enter/status/exit, read-only enforced
(read-only tool allowed / mutating tool denied), `plan_write`→`PLAN.md`, `plan_exit` HITL approve→exit &
reject→stay), `PlanCommandTest` (3 — status, enter gated-when-disabled, enter/exit drive state),
`StatusLineTest` (+1 — plan badge), `ReplCommandsTest`/help (existing, `/plan` registered). All offline
(scripted fake models + `@TempDir`). `mvn -q -pl pig-agent-cli -am compile` green.
Security tests are genuine — real `PermissionEngine` + a real subagent spawn path, not mocked.

### Files

`pig-agent-tools`: `contract/GuardedAgentTool` (→ `ToolBase`), `permission/CommandPermissionTool` (new),
`permission/CommandKeys` (`COMMAND_TOOL_NAME`), `permission/PermissionContextFactory` (skip exec ASK rule).
`pig-agent-core`: `agent/SubagentPermissions` (new), `agent/PigAgent` (custom subagent factories + leaf
child build). `pig-agent-config`: `PigAgentConfig.SubagentsConfig.enabled` default `true`. `pig-agent-cli`:
`AgentBootstrap` (wrap executeCommand in `CommandPermissionTool`; drop interim WARN).

## 16. Proactive-outreach port (branch `av2/20260716-outreach-port`)

**Base:** `av2/20260716-foundation-main` (whole reactor GREEN on 2.0 after Phase 6b). **Not merged.**
Scope: bring the **proactive-outreach (主动外呼)** feature — which shipped on v1 `main` (`feat/20260716-proactive-outreach`, commits `8e64810` feat + `8dca614` archive) *after* the v2 line forked — onto the v2 line so it reaches feature parity with v1 `main`. No new design; a straight port of the v1 feature, re-integrated against the v2 (native permission / `HarnessAgent` / native state) wiring.

### Merge outcome
`git merge feat/20260716-proactive-outreach` (base `main@86f2323` = the same fork point as the v2 line, so the diff replays cleanly). Only **2 real conflicts** — `AgentBootstrap` and `ToolRiskClassifierTest`; everything else (the `outreach/*` value types + service seams, `NotifyUserTool`, `NotifyCommand`, `PigAgentConfig.outreach`, `StrategyHttpChannel.send`, `ToolContext`, `CLAUDE.md`, openspec) auto-merged.

### Conflicts resolved
- **`ToolRiskClassifier` (dedupe, keep one `notifyUser`).** The v2 base did **not** actually carry a `notifyUser` entry (only the H-1/M-3 `webSearch`=NETWORK + checklist reclassifications); the outreach side added `notifyUser`=NETWORK. Git merged both cleanly in the *main* class (one entry, no duplicate). The `ToolRiskClassifierTest` conflict was combining the v2 assertions (webSearch/checklist) with the outreach assertion (notifyUser) in the same method — resolved to keep both.
- **`AgentBootstrap` (integrate outreach into the v2 wiring).** Six hunks: kept the v2 harness imports (`ToolResultEvictionConfig`/`SubagentDeclaration`) **and** added the channel-outreach imports; added the `notificationService` + `outreachRegistry` fields/ctor-params/return-args to `Services`; **dropped the ported `JsonSession agentSession`** field/param (v2 Phase-3/4 replaced `JsonSession` with the native `AgentStateStore` — no `JsonSession` on this line); and in the channel-agent hunk kept the v2 **native** `channelPermCtx` (`Supplier<PermissionContextState>`) while **dropping the ported legacy `ToolPermissionHook`**, inserting the scheduled-briefing block ahead of it. The outreach helper methods (`buildOutreachPolicy`, `briefingNotification`) and the report-push `CompositeReportWriter` wiring were kept as ported.
- **`AgentReplTurnTest` (post-merge fix, non-conflict).** Two **v2-only** tests (`renderStream_showsDeniedToolResult`, `…SourceTaggedChildEventNested` — exercising av2 DENIED-result / source-tagged subagent events the outreach branch never had) still constructed `AgentRepl` with the pre-outreach 13-arg list; the merged main constructor gained the 14th `NotificationService` param, so these two calls went stale. Fixed by adding the trailing `null` (the sibling tests already carried it). No behavior change — the two tests pass `notificationService=null`.

### Outreach under the v2 permission model (verified)
`notifyUser` is a reflective `@Tool` registered via SPI (`NotifyUserToolProvider`), so it flows through the same v2 path as every other built-in tool: `ToolContractGuard.install` wraps it in `GuardedAgentTool` (now `extends ToolBase`, per the Phase-6b P0 fix), so the native `PermissionEngine` **actually gates it**. It is classified `NETWORK` in `ToolRiskClassifier`; `PermissionContextFactory` + `PermissionPolicy.decide` therefore map it: **plan/EXPLORE → DENY** (denied), **ask → ASK** (interactive confirm), auto → ALLOW, bypass → ALLOW — i.e. denied-in-plan / confirmed-in-ask, consistent with `fetchUrl`. It is `ToolAvailability`-gated on `outreach.enabled` (hidden from the schema when off), returns the canonical `{"error"}` on real failure, and never echoes the recipient. The anti-nag guardrails (`OutreachPolicy`/`OutreachGate`: rate-limit + de-dup + quiet-hours, URGENT bypass, de-dup always) and the `NotificationService`/`OutboundChannel`/`OutreachScheduler` seams are ported intact; default **disabled** → zero behavior change.

### Send seam (unchanged from v1, native migration deferred)
The `send` seam stays on **pig's custom channels** — `StrategyHttpChannel.send` dispatches DingTalk/Feishu over their custom-robot webhooks (best-effort). Migrating outbound to a **native 2.0 Gateway/adapter** is a documented later item and was **not** attempted here; the ported code compiles + runs on the v2 line's Phase-4-migrated channel layer as-is.

### Acceptance (single-threaded surefire, 2.0)
`mvn test` — whole reactor GREEN, all 17 modules (BUILD SUCCESS). Total **1093 tests, 0 failures, 0 errors, 5 skipped** (1034 Phase-6b baseline **+59** ported outreach unit tests: core `Notification`/`NotificationResult`/`OutreachGate`/`OutreachPolicy` (22), `channel.outreach` routing/renderer/report-push/scheduled/outbound (19), `CompositeReportWriter` (2), `NotifyUserTool`(+provider) (9), `OutreachConfig` (4), `NotifyCommand` (3)). The ported feat commit's "~61" nets to **+59** because its `ToolRiskClassifierTest` change was an added *assertion*, not a new test method — no test lost to dedup. `mvn -q -pl pig-agent-cli -am compile` green. Credentials/recipients never logged (guardrails + `/notify status` masks the recipient as `(set)`).

## 17. Native Gateway enhancement (branch `av2/20260716-gateway`)

**Base:** `av2/20260716-foundation-main` (whole reactor GREEN on 2.0 after the outreach port, §16). **Not
merged.** Scope: the deferred **Phase-6c** — adopt the native AgentScope 2.0 **`Gateway`/`ChatUiChannel`
channel kernel** + the native platform adapters + `expose_to_user`, building pig's channel layer **on**
the native kernel ("在其上盖房子") while **keeping every working path** (CC-REPL, permission/state/events,
digital-employee, `/agent`, proactive-outreach, and pig's custom Telegram/Discord/Slack/Webhook/Stdin +
`StrategyHttpChannel` DingTalk/Feishu channels). **Config-gated, default OFF** → byte-for-byte the prior
channel behavior unless `channel-gateway.enabled=true`.

### Where the native kernel lives (javap-confirmed) — and what is resolvable offline

The native gateway core is in **`agentscope-harness` (2.0.0), which is already a `pig-agent-core`
dependency** — so it is fully offline-usable + offline-testable:
`io.agentscope.harness.agent.gateway.{Gateway, GatewayBootstrap, HarnessGateway, SubagentGatewayBridge}`
· `…gateway.channel.{Channel, ChannelConfig, InboundMessage, RouteResult, OutboundAddress, Peer}` ·
`…gateway.channel.chatui.{ChatUiChannel, SendOptions}`. The subagent-expose event
`io.agentscope.core.event.SubagentExposedEvent` is in `agentscope-core`. **The native platform ADAPTERS
are NOT resolvable offline** — the local repo (`D:/env/apache-maven-3.9.10/repository/io/agentscope/`)
has `agentscope-core`/`agentscope-harness`/`agentscope-extensions-model-*` only; **no
`agentscope-extensions-channel-*`**. So the DingTalk/Feishu/GitHub/GitLab/WeCom adapter classes cannot be
compiled against or unit-tested by construction — they are loaded **reflectively** and degrade gracefully
when absent (see the adapter matrix).

**Verified javap signatures used:**
- `HarnessAgent.channel(T extends Channel):T` (lazily creates the agent's internal gateway, registers the
  agent, injects the gateway into the channel — the documented `expose_to_user` enabler), `gateway():HarnessGateway`.
- `Gateway`: `bindMainAgent(HarnessAgent)`, `registerAgent(String, HarnessAgent)` (default),
  `run(MsgContext, List<Msg>[, OutboundAddress]):Mono<Msg>`, `runStream(...):Flux<AgentEvent>`,
  `runSubagent(String, List<Msg>)` / `runSubagentStream(...)`.
- `GatewayBootstrap.builder().agent(id, HarnessAgent) | agent(id, Consumer<HarnessAgent$Builder>) |
  mainAgent(id) | channel(Channel...) | configureAllAgents(...) | distributedStore(DistributedStore) |
  build()`; `.gateway()`, `.chatUiChannel([ChannelConfig])`, `.gatewayBridge():SubagentGatewayBridge`,
  `.start()`, `.stop()`.
- `ChatUiChannel.create() | create(ChannelConfig) | create(Gateway) | create(Gateway, ChannelConfig) |
  perPeer()`; `send(SendOptions, String):Mono<Msg>`, `sendStream(SendOptions, String):Flux<AgentEvent>`,
  `sendToSubagent(String, String):Mono<Msg>`, `sendToSubagentStream(String, String):Flux<AgentEvent>`,
  `deliver(OutboundAddress, List<Msg>)`, `pollOutbound()`, `dispatch/dispatchStream`, `CHANNEL_ID`.
- `SendOptions` (record `(userId, sessionId, agentId)`): `userId(String)`, `of(userId, sessionId)`,
  `withAgentId(String)`.
- `Channel` (interface): `channelId()`, `config():ChannelConfig`, `init(Gateway)`, `start()`, `stop()`,
  `dispatch(InboundMessage):Mono<Msg>`, `dispatchStream(...):Flux<AgentEvent>`,
  `deliver(OutboundAddress, List<Msg>)`, `applyRoutingConfig(ChannelConfig)`.
- `ChannelConfig.of(id) | of(id, defaultAgentId) | builder(id)` (record `(channelId, defaultAgentId,
  DmScope, List<ChannelBinding>)`). `OutboundAddress.direct(channelId, to) | withAccount(...)`.
- `SubagentExposedEvent`: `getSubagentId()`, `getAgentId()`, `getSessionId()`, `getLabel()` (extends
  `AgentEvent`). `SubagentGatewayBridge.expose(String, String, Agent, OutboundAddress):ExposeResult`;
  `ExposeResult.subagentId()`.

### Design — the kernel sits BEHIND pig's channel seam (all in `pig-agent-channel`, `io.pigagent.channel.gateway`)

**`GatewayChannelKernel`** is the adoption point. Built over a pig `HarnessAgent`
(`PigAgent.getHarnessAgent()`) via `mainAgent.channel(ChatUiChannel.create())` — this single native call
gives (a) the `ChatUiChannel` routing engine (native session management + single-session fair queuing +
agent routing), (b) the agent's internal `Gateway` (peers registered via `registerAgent`, native adapters
attached via `Channel.init`/`start`), and (c) the auto-wired subagent-gateway bridge that makes
`expose_to_user` functional. It exposes `send`/`sendStream` + `sendToSubagent`/`sendToSubagentStream`, all
threaded by `SendOptions(userId, sessionId, agentId)` — **consistent with the Phase-3 per-`(userId,sessionId)`
`AgentStateStore`**, so each channel user/session persists in its own slot and single-session concurrency
is fairly queued by the native gateway (proven offline by `GatewayChannelKernelTest.sendOptionsThreadSessionPerUser`:
same user continues, different user isolated).

**Behind the façade (frontend seam unchanged).** `ChannelAgentBridge` gained an optional
`GatewayChannelKernel` constructor arg: with it (opt-in), inbound routes through
`kernel.sendStream(SendOptions.of("pig", "channel:<id>"), text)` (native routing); without it (default),
the prior direct `agentHolder.get().stream(msg, sessionId)` path — the answer-delta aggregation +
outbound delivery is shared, so the bridge/kernel-façade contract is unchanged. `PigAgentCli.startChannels`
builds the kernel only when `channel-gateway.enabled`, keeping default byte-identical (proven by
`ChannelAgentBridgeTest`: the existing 3 tests unchanged; a new `gatewayRoutingBypassesTheDirectAgentStream`
verifies the gateway path bypasses the direct call and delivers the reply).

### Native-vs-custom adapter matrix

| Platform | pig `channels.<id>` | native class (FQCN, needs-verify) | artifact | transport | Spring? | status |
|---|---|---|---|---|---|---|
| DingTalk | `dingtalk` | `io.agentscope.extensions.channel.dingtalk.DingTalkChannel` | `agentscope-extensions-channel-dingtalk` | WebSocket Stream | no | **native available, opt-in** — POM add + **live-verify** (artifact absent offline) |
| Feishu/Lark | `feishu` | `…channel.feishu.FeishuChannel` | `…-channel-feishu` | HTTP callback | yes | **native available, opt-in** — POM add + **live-verify** |
| GitHub | `github` | `…channel.github.GitHubChannel` | `…-channel-github` | webhook | (likely) | **native available, opt-in** — POM add + **live-verify** |
| GitLab | `gitlab` | `…channel.gitlab.GitLabChannel` | `…-channel-gitlab` | note hook | (likely) | **native available, opt-in** — POM add + **live-verify** |
| WeCom | `wecom` | `…channel.wecom.WeComChannel` | `…-channel-wecom` | encrypted callback | yes | **native available, opt-in** — POM add + **live-verify** |
| Telegram / Discord / Slack / Webhook / Stdin | `telegram`/`discord`/`slack`/`webhook`/`stdin` | *(none)* | — | — | — | **stay pig custom** (no native adapter) |

The FQCN follows the model-extension convention (`io.agentscope.extensions.model.<p>.<P>ChatModel` ⇒
`io.agentscope.extensions.channel.<platform>.<Platform>Channel`) and the doc-confirmed factory
`XxxChannel.fromProperties(String id, ChannelConfig config, Map<String,String> props)`; both are
**documented in `NativeChannelType` and must be verified against the real artifact** (needs a live endpoint
regardless). `NativeChannelFactory` loads the class reflectively and, when it is not on the classpath
(**always, offline**), logs "add `<artifact>`" and returns empty → pig falls back to the custom adapter.
This is why native adapters are **opt-in behind `channels.<id>.native: true`** and the custom channels stay
the default. Platform credentials live under `channels.<id>.props.<key>` (dingtalk: `appKey`/`appSecret`/
`robotCode`; feishu: `appId`/`appSecret`; github: `token`/`webhookSecret`; gitlab: `token`; wecom:
`corpId`/`agentId`/`secret`/`token`/`encodingAesKey`).

### `expose_to_user` — functional + offline-proven (the headline result)

The subagent→user Channel bridge is **not** just a note anymore (Phase-6a left it minimal). Because
`GatewayChannelKernel` binds the agent via `agent.channel(...)`, the native gateway auto-assembles the
subagent bridge, so a subagent the model spawns with `expose_to_user=true` (through the existing Phase-6b
subagent path) is registered as a user-addressable entry point and the gateway emits a
`SubagentExposedEvent` (carrying a `subagentId`) onto the stream; the client then talks to it directly via
`GatewayChannelKernel.sendToSubagent(subagentId, …)`, bypassing the parent. **Proven offline** by
`GatewayExposeToUserTest` (scripted fake model, `@TempDir`): a parent turn through the gateway spawns +
exposes a `general-purpose` child (log: `Exposed subagent … as subagentId=sub-…`, tool result
`status: exposed`), the `SubagentExposedEvent` is captured off `sendStream`, and a `sendToSubagent(id,
"FOLLOWUP …")` reaches the exposed child which answers — all without a live model. This composes with the
Phase-6b pig-enforced subagent permission inheritance (the exposed child is still built under the derived
fail-closed context).

### Proactive-outreach send-seam retargeted onto the native kernel (§16 follow-up)

`GatewayOutboundChannel` adapts pig's `OutboundChannel` (the thin "push an unsolicited `Notification`"
seam) onto a native `Channel.deliver(OutboundAddress, List<Msg>)`, realizing the "native lacks proactive
push, pig fills it" story against the native kernel. The routing/guardrail/trigger stack above
(`ChannelNotificationService`/`OutreachGate`/`ScheduledOutreach`) is unchanged — only the terminal
transport moves. It is also an outbound-only pig `Channel`, so it slots into the existing outreach registry
+ notification-service lookup (`instanceof OutboundChannel`) with **no change to that routing**; when the
gateway path is enabled, `startChannels` registers one per attached native adapter. pig's custom
`StrategyHttpChannel.send` (DingTalk/Feishu webhooks) stays the default target. Contract-faithful: never
throws, returns `false` on transport failure, never echoes the recipient (proven by
`GatewayOutboundChannelTest`).

### Config

New `channels.<id>.native` (bool, default false) + `channels.<id>.props` (map) opt a channel into its
native adapter; new top-level `channel-gateway.{enabled (default false), main-agent-id (default "default")}`
gates adopting the native gateway kernel at all. All optional/default-safe → **default off = today's custom
channels, byte-for-byte** (unknown-field-tolerant loader unaffected).

### Honest limitations / live-verification-required

- **Native adapters cannot be exercised offline** (artifacts absent). Enabling one requires adding the
  `agentscope-extensions-channel-*` dependency to a POM (channel or cli) **and** a real platform endpoint;
  `NativeChannelFactory`'s FQCN + `fromProperties` signature are the doc-convention and **must be confirmed
  against the real jar**. `NativeChannelFactoryTest` covers the opt-in gate, the graceful-degradation
  (artifact-absent) path, and the pure props mapping — the only offline-observable behavior.
- **Model-switch rebinding:** the native gateway binds the `HarnessAgent` at kernel-build time; a runtime
  model switch that rebuilds the channel agent leaves the kernel on the old vehicle. The opt-in path does
  not auto-rebind yet (documented; a `bindMainAgent(...)` refresh on model switch is a follow-up).
- **REPL expose UX:** the `expose_to_user` plumbing + send-path are functional/tested, but the REPL still
  drives the interactive agent via `AgentKernel.chat` (not the `ChatUiChannel`); surfacing an exposed
  subagent as an addressable target in the REPL (e.g. a `/subagent` command) is a follow-up — the kernel
  seam for it is in place.

### Acceptance (single-threaded surefire, 2.0)
`mvn -o test` — **whole reactor GREEN, all 17 modules (BUILD SUCCESS). Total 1109 tests, 0 failures, 0
errors, 5 skipped** (1093 §16 baseline **+16**: `pig-agent-channel` 116→**132** — `GatewayChannelKernelTest`
4, `GatewayExposeToUserTest` 1, `GatewayOutboundChannelTest` 3, `NativeChannelFactoryTest` 7,
`ChannelAgentBridgeTest` +1 gateway-routing). `mvn -o -q -pl pig-agent-cli -am compile` green. New/changed
tests + why: the four new gateway test classes prove routing wiring / `SendOptions` session threading /
`expose_to_user`→`SubagentExposedEvent`→`sendToSubagent` (fake model) / native-adapter opt-in +
graceful-degradation + props mapping; the `ChannelAgentBridgeTest` addition proves the native-gateway route
bypasses the direct path while the three pre-existing tests (unchanged) prove the custom-channel default is
unchanged. Files: `pig-agent-channel` `gateway/{GatewayChannelKernel, NativeChannelType, NativeChannelFactory,
GatewayOutboundChannel}` + modified `ChannelAgentBridge`; `pig-agent-config` `PigAgentConfig.{ChannelConfig
.native/.props, ChannelGatewayConfig}`; `pig-agent-cli` `PigAgentCli.startChannels` (gateway-aware, gated).
