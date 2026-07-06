# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Pig Agent is a terminal-based AI agent framework for Java, built on **AgentScope Java** (`io.agentscope:agentscope` 1.0.12). It wraps AgentScope's `ReActAgent` and exposes it through a picocli + JLine3 REPL, with pluggable LLM providers, multi-model management, independent conversation sessions, a two-tier memory model, automatic context compression, built-in tools, MCP integration, task scheduling, and message channels.

The README (in Chinese) is a detailed reference for the original architecture; this file is the source of truth for the current build and module layout.

> Status note: the `pig-agent-session`, `pig-agent-model`, compression, and CLI-refactor code were added rapidly and have **not been verified to compile/run in this environment** (no local Maven). Run `mvn -pl pig-agent-cli -am compile` first and treat the first build as a verification pass.

## Build & Run

```bash
mvn compile                       # compile all modules
mvn test                          # run all tests
mvn install                       # build + install to local repo
mvn exec:java -pl pig-agent-cli   # run the CLI REPL (main: io.pigagent.cli.PigAgentCli)
mvn -pl pig-agent-cli -am compile # compile the CLI and everything it depends on
```

Run a single module's tests, or a single test class/method:

```bash
mvn test -pl pig-agent-model
mvn test -pl pig-agent-config -Dtest=ConfigurationManagerTest
mvn test -pl pig-agent-task -Dtest=TaskTest#methodName
```

Requires **Java 17** and Maven 3.9+. Models are configured **interactively on first run** (the onboarding wizard writes `~/.pig-agent/workspace/models.json`) and stored there going forward — API keys are no longer read only from the environment, though env vars (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `DASHSCOPE_API_KEY`, `GEMINI_API_KEY`, `MIMO_API_KEY`; Ollama needs none) still work as a fallback. With no usable model, the wizard runs and cannot be skipped.

> The README's build commands show a trailing `-s ` settings flag — that is a stale leftover; no custom Maven settings file is used. Use the plain commands above.

### Protocol client SDKs
The provider module is organized by **protocol standard**, not vendor: 5 `ModelProtocol` implementations (`openai` / `anthropic` / `gemini` / `ollama` / `dashscope`). Any OpenAI-compatible vendor (Xiaomi mimo, DeepSeek, Kimi, Qwen-compat, …) is reached through the `openai` protocol + that vendor's base URL — no per-vendor class. AgentScope declares the SDKs as **optional**, so they are added explicitly in `pig-agent-providers/pom.xml` (versions matched to AgentScope 1.0.12 in the parent POM properties): `com.anthropic:anthropic-java` 2.14.0, `com.openai:openai-java` 4.28.0 (covers the `openai` protocol incl. all compatible vendors), `com.google.genai:google-genai` 1.45.0, `com.alibaba:dashscope-sdk-java` 2.22.9. A missing one surfaces as `NoClassDefFoundError`/unresolved import (e.g. `com.anthropic.client.AnthropicClient`) when that protocol builds its model.

## Module Layout

Maven multi-module project (`io.pigagent`, version `0.1.0-SNAPSHOT`), 12 modules. Each is one bounded responsibility; `pig-agent-cli` is the only entry point and depends on the rest.

| Module | Responsibility | Key types |
|--------|---------------|-----------|
| `pig-agent-core` | Agent wrapper + rebuildable agent, multi-agent registry, hooks, two-tier memory, compression, protocol SPI | `PigAgent`, `AgentHolder`, `AgentFactory`, `AgentSpec`, `AgentRegistry`, `AgentInstance`, `AgentInstanceFactory`, `AgentSpecRepository`, `LoggingHook`, `ToolCallLoggingHook`, `FileSystemLongTermMemory`, `CompositeLongTermMemory`, `compression/CompressionService`, `protocol/ModelProtocol`, `protocol/ModelSpec`, `ProviderCredentials` |
| `pig-agent-providers` | Model protocol impls + registry (depends on the protocol SDKs) | `OpenAiProtocol`, `AnthropicProtocol`, `GeminiProtocol`, `OllamaProtocol`, `DashScopeProtocol`, `ProtocolRegistry` |
| `pig-agent-model` | Multiple saved model configs, connectivity test, runtime switching | `StoredModel`, `ModelStore`, `JsonModelStore`, `ModelManager` |
| `pig-agent-session` | Independent conversation sessions + per-session temp memory | `Session`, `SessionManager`, `SessionRepository`, `FileSystemSessionRepository`, `AgentModelSwitcher` |
| `pig-agent-tools` | `@Tool`-annotated built-in tools | `ShellTools`, `FileSystemTools`, `SmartWebFetchTool`, `BraveWebSearchTool`, `TaskTool`, `CheckListTool`, `SkillsTool`, `ToolDiscovery` (Lucene) |
| `pig-agent-task` | Task model, scheduling, file persistence | `Task` (record), `TaskManager`, `TaskScheduler`, `FileSystemTaskRepository` |
| `pig-agent-mcp` | Dynamic MCP server management (stdio/SSE/streamable-http) + JSON store | `McpManager`, `McpServerSpec`, `McpStore`, `JsonMcpStore` |
| `pig-agent-workspace` | `~/.pig-agent/workspace/` layout | `WorkspaceManager` |
| `pig-agent-config` | YAML config + change listeners | `PigAgentConfig`, `ConfigurationManager`, `ConfigurationChangedEvent` |
| `pig-agent-channel` | Channel abstraction + agent bridge | `Channel`, `ChannelAgentBridge`, `ChatChannel`, `TelegramChannel`, `DiscordChannel`, `ChannelRegistry` |
| `pig-agent-onboarding` | Interactive first-run model setup | `OnboardingWizard` |
| `pig-agent-cli` | picocli + JLine3 + Jansi REPL, wiring, graceful shutdown | `PigAgentCli`, `Ansi`, `repl/{AgentRepl, ReplContext, ReplCommands}` |

## Architecture Essentials

**Wiring happens in `PigAgentCli.main`** — workspace, config, providers, model store/manager, the agent, sessions, compression, channels, and MCP are all constructed and connected there. Read it first to trace how a component plugs in.

**The agent is swappable at runtime.** AgentScope's `Model` is fixed at build time, so to switch models without restarting, the `PigAgent` is built via `AgentFactory.create(model)` and stored in a mutable `AgentHolder`. Everything (REPL, channels, session manager, compression) reads the current agent through the holder. `ModelManager.ensureModel(...)` rebuilds the agent on a model switch; the session layer reloads the conversation afterward.

**Multiple agents (`agent-management`, phase 1).** The single `AgentHolder` is driven by an `AgentRegistry` (`Map<agentId, AgentInstance>`): the holder is a **live view of the active instance**, so every existing reader stays unchanged. Each agent is a declarative, immutable `AgentSpec` — its own model (`modelId` → `ModelManager.modelFor`, fault-tolerant fallback to default), tool subset (`AgentWiring.toolkitFor` = `Toolkit.copy()` + `removeTool` by whitelist), and permission mode (per-agent `ToolPermissionHook` mode-override) — persisted at `workspace/agents/{id}.md` (`AgentSpecRepository`, YAML front-matter + prompt body) and built by `AgentInstanceFactory` (per-agent toolkit/hooks/memory; NOT the shared `AgentFactory`). On an empty `agents/`, a `default` instance equivalent to the old single agent is bootstrapped, so single-agent behavior is unchanged. Operate via `/agent list|use|new|model`. Design: `docs/design/agent-management-design.md`; change: `openspec/changes/multi-agent-kernel/`.

**REPL flow:** `AgentRepl` builds a JLine `Terminal` (jna provider, `jansi(false)`) and a picocli command tree (`ReplCommands`) via `picocli-shell-jline3`. A line starting with `/` is dispatched to a picocli subcommand (`/model`, `/agent`, `/session`, `/memory`, `/compress`, `/tasks`, `/help`, …); anything else is streamed to the agent (`PigAgent.stream` → `ReActAgent`). Output is colored with **Jansi as a string builder only** — `AnsiConsole.systemInstall()` is intentionally NOT called (it double-wraps `System.out` and garbles output on Windows when JLine owns the terminal). See `cli/Ansi.java`.

**Sessions** (`SessionManager`): each conversation is isolated. Conversation history persists via AgentScope's `JsonSession` under `workspace/sessions/{id}/`; lightweight metadata (`Session` record) is stored as `meta.json` by `FileSystemSessionRepository`. Switching = save current → `clearMemory` → `loadIfExists(target)` (no agent rebuild unless the model differs). `current-session-id` in config restores the last session on startup.

**Two-tier memory** (`CompositeLongTermMemory`, a `LongTermMemory`): a shared **global** store (`workspace/context/memory.md`) plus a swappable **per-session** temp store (`sessions/{id}/temp-memory.md`). `retrieve` merges both with source labels; `record` writes only to the session tier; a global enable flag (`/memory on|off`) gates all of it.

**Context compression** (`CompressionService`): operates only on the in-memory conversation (never on persisted history or memory). When an estimated token budget (config `compression`) is exceeded it summarizes older turns via a throwaway agent on the current model and keeps the most recent turns; `/compress now|status|off|on`.

**Dynamic MCP management** (`McpManager` + `JsonMcpStore`): MCP servers are CRUD-managed at runtime and persist to `workspace/mcp.json` (the source of truth, keyed by name). On first run, legacy `application.yaml`'s `mcp.servers` are imported once into `mcp.json`. `McpManager` holds the `Toolkit` plus a `name→McpClientWrapper` map and registers/unregisters MCP tools live. Concurrency uses a **fine-grained lock (E1)**: network/process connect + `listTools` run *outside* the lock; only the short collision-check + `registerMcpClient` + `map.put` critical section is `synchronized(this)`. Tool namespace is **flat (D-NS)**: adding a server whose tool name collides with an already-registered tool is refused. Operate via `/mcp list|add|remove|edit|enable|disable|test` (operator-facing, full trust — may add stdio servers). The agent can self-manage MCP via the `McpTool` (`@Tool`), gated by the **D-SEC** security door (`mcp.agent-management`): `allow-add`/`allow-remove` default **off**; when add is enabled, only URL servers whose host is in `allowed-hosts`, after human confirmation, are accepted (never stdio/command); `list`/`test` are always allowed; `env`/`headers` are redacted in tool output to avoid credential echo.

**Tool permissions** (`io.pigagent.tool.permission`, gated by `permissions` config): a high-priority `ToolPermissionHook` (`PreActingEvent`, `priority()=0`) vetoes tool calls per a global **mode** — `plan` (read-only: deny all mutating tools so the agent only produces a plan), `ask` (default; confirm each mutating tool y/a/N), `auto` (auto-allow edits/network, still confirm exec/mcp-admin), `bypass` (allow all). Tools are risk-classified (`ToolRiskClassifier`: READ_ONLY/WRITE/EXEC/NETWORK/MCP_ADMIN; unknown → EXEC fail-safe; `tool-overrides` can reclassify). The **veto mechanism** (proven by `PermissionVetoSpikeIT`): the hook rewrites the pending `ToolUseBlock` to a read-only `PermissionDeniedTool` sentinel — the real tool never runs and the model gets the denial as a tool result and continues. `a`(always) persists to `permissions.allowlist` (tools + normalized command keys for EXEC, first-token). MCP_ADMIN is delegated to the existing **D-SEC** door (no double-prompt). Operate via `/permission status|mode|channel-mode|allow|revoke|reset|list`. Default `ask` changes prior behavior (was effectively bypass) — `/permission mode bypass` restores it. Channels run through a **separate channel agent** whose hook uses `channel-mode` (default `auto`) with no confirmer (ASK fails closed); `ModelManager.attachChannel` rebuilds it on model switch so channels still follow the active model. Pure logic (`PermissionPolicy`/`PermissionResolver`) is unit-tested; the hook is a thin adapter.

**Extension is via SPIs, not core edits:**
- New tool: a class with `@Tool`/`@ToolParam` methods → `toolkit.registration().tool(new MyTool()).apply()`.
- New hook: implement `io.agentscope.core.hook.Hook` (1.x event model: `PreReasoningEvent`/`PostReasoningEvent`/`PreActingEvent`/`PostActingEvent`; lower `priority()` runs earlier; return `Mono.just(event)`).
- New model protocol: implement `ModelProtocol` (`io.pigagent.core.protocol`) — note `protocolId()` + `createModel(ModelSpec spec)` (apiKey + optional baseUrl + modelName), plus `supportsBaseUrl()`/`requiresApiKey()` — and register on `ProtocolRegistry` in `PigAgentCli`. Adding a new *vendor* on an existing protocol (e.g. another OpenAI-compatible endpoint) needs **no code** — the user just picks the protocol and enters its base URL.
- New channel: implement `Channel`, register on `ChannelRegistry`; `ChannelAgentBridge` (holds an `AgentHolder`) routes channel messages through the agent.
- MCP servers are managed dynamically (see below); their tools auto-register into the Toolkit and can be hot-added/removed without restart.

**Configuration** is split: model configs (+ `defaultModelId`) live in `workspace/models.json` (`JsonModelStore`); everything else (`agent`, `channels`, `mcp.agent-management`, `compression`, `current-session-id`, `memory-enabled`) lives in `workspace/application.yaml`, parsed by Jackson YAML into `PigAgentConfig`. `ConfigurationManager.updateConfig(Consumer)` persists and notifies listeners. The legacy `model:` block in YAML is kept for display/back-compat but `models.json` is the source of truth; likewise the legacy `mcp.servers` block is imported once then `mcp.json` is the source of truth.

**Tasks** persist as Markdown at `workspace/tasks/{date}/{id}.md` (recurring under `tasks/recurring/`); `TaskScheduler` runs ONCE/CRON/DELAYED schedules on a `ScheduledExecutorService`.

## Conventions

- **Immutability is enforced.** Domain types (`Task`, `Session`, `StoredModel`, `ProviderCredentials`, config events) are records; mutate via `withXxx()` copy methods. See `.claude/rules/common/coding-style.md`.
- File-backed repositories are **fault-tolerant**: a single corrupt file (bad `meta.json` / `models.json`) is skipped or backed up, never crashes the listing. Follow that pattern for new persistence.
- Tests use **JUnit 5 + Mockito + AssertJ**, AAA structure (test classes in `config`, `core`, `task`, `workspace`, `session`, `model`, and `cli`).
- Async/reactive APIs from AgentScope use Project Reactor (`Mono`/`Flux`); streaming consumes a `Flux<Event>` and filters on `EventType`.

## Project Rules

`.claude/rules/common/` contains enforced standards (coding style, code review, testing ≥80% coverage, security, git/dev workflow, agent orchestration). Notable points: prefer many small files (<800 lines, functions <50 lines), conventional-commit messages (`feat:`, `fix:`, `refactor:`, etc.), and run security checks before commits touching auth/input/filesystem/external calls.

## AI 开发流水线（`/ls:*`）

> **强制规约**：实现任何需求/特性/修复 MUST 走此流水线，不得跳阶段/越人工门/自行拍板拆分。硬性规则见 [`.claude/rules/common/ls-pipeline.md`](.claude/rules/common/ls-pipeline.md)。

一条半自动的 AI 开发流水线，把既有能力串成标准流程（命令定义在 `.claude/commands/ls/`，复用 `/opsx:*` + `.claude/rules/common/`，不重造）。

```
需求澄清 → 特性分支(feat/bug/docs/opt) → spec 设计 → [编码⇄单测 内环] → 集成测试 外环 → openspec 归档
```

| 命令 | 阶段 | 门 | 职责 |
|------|------|-----|------|
| `/ls:clarify` | 需求澄清 + 拆分 + 建分支 | ⏸ 人工 | AskUserQuestion 问清需求；**大需求拆成多个可独立上线的 spec（人工审拆分）**；每个 spec 从 `origin/main` 拉 `<type>/<name>` 分支（可并行） |
| `/ls:spec` | spec 设计 | ⏸ 人工审批 | 委托 `/opsx:propose` 生成 proposal/design/tasks + delta spec，`openspec validate --strict` |
| `/ls:code` | 编码⇄单测（**内环**） | 自动 | 逐 task TDD：测试→实现→`mvn test`→勾选；组后 `mvn compile` |
| `/ls:itest` | 集成测试（**外环**） | 自动 | 跑 `*IT` 真模型测试；失败回喂 `/ls:code`；连续 3 轮无进展升级人工 |
| `/ls:archive` | openspec 归档 | ⏸ 人工确认 | 委托 `/opsx:archive`：同步主 spec + 移到 `changes/archive/` |
| `/ls:status` | 进度汇报 | 只读 | 跨 澄清/设计/规格/任务 维度统计所有活跃 spec（多 spec 并行视图 + 卡点） |
| `/ls:dev` | 总控 | 半自动 | 端到端串联五阶段，尊重上述人工门 |

**两层 loop engine**：内环 = 编码⇄单测（`/ls:code`，快、离线）；外环 = 任务→内环→集成测试（`/ls:itest`，慢、真模型），失败回环至绿。**半自动**：澄清/spec/归档人工把门，编码+测试自动推进。**多 spec**：大需求在 `/ls:clarify` 拆成多个可独立上线的 spec（拆分须人工审），各自 `feat/<name>` 分支——**依赖允许时并行，否则按依赖顺序推进**（后者在前者归档后才细化 tasks），`/ls:status` 汇总进度。**分支前缀**：`feat`/`bug`/`docs`/`opt`（`bug/` 分支的提交信息仍用 conventional-commit `fix:`）。完整指南 + 实战复盘见 `docs/ai-dev-pipeline.md`；规约基座见 `.claude/rules/common/development-workflow.md`。

## Skills

Project-specific slash commands live in `.claude/commands/` (`/ls:*` AI dev pipeline, `/opsx:*` openspec workflow). See the AI 开发流水线 section above and `docs/ai-dev-pipeline.md`.
