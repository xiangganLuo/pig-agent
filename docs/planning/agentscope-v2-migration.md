# AgentScope Java 1.0.12 → 2.0 Migration Map

> **Status: Phase 0-redux + Phase 1 + Phase 2 (tools framework + native permission) + Phase 3
> (session/state rewrite) + Phase 4 (frontends: cli + web + channel) + Phase 5a (delete self-built
> decorators → native retry/interrupt, hooks → native middleware, drop the 1.x `agentscope` dep) COMPLETE.**
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
