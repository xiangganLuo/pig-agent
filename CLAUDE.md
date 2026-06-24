# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Pig Agent is a terminal-based AI agent framework for Java, built on **AgentScope Java** (`io.agentscope:agentscope` 1.0.10). It wraps AgentScope's `ReActAgent` and exposes it through a JLine3 REPL, with pluggable LLM providers, built-in tools, MCP integration, task scheduling, and message channels.

The README (in Chinese) is the canonical, detailed reference for architecture and extension points. This file covers what's needed to build and navigate the code.

## Build & Run

```bash
mvn compile                       # compile all modules
mvn test                          # run all tests
mvn install                       # build + install to local repo
mvn exec:java -pl pig-agent-cli   # run the CLI REPL (main: io.pigagent.cli.PigAgentCli)
```

Run a single module's tests, or a single test class/method:

```bash
mvn test -pl pig-agent-task
mvn test -pl pig-agent-config -Dtest=ConfigurationManagerTest
mvn test -pl pig-agent-task -Dtest=TaskTest#methodName
```

Requires **Java 17** and Maven 3.9+. At least one LLM provider API key must be set via environment variable (e.g. `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `DASHSCOPE_API_KEY`, `GEMINI_API_KEY`, `MIMO_API_KEY`; Ollama needs none). On first run the CLI bootstraps `~/.pig-agent/workspace/` and launches an onboarding wizard if no key is configured.

> Note: README build commands show `mvn ... -s ` with a trailing settings flag — that is a leftover; a custom Maven settings file is no longer used (see commit "移除Maven镜像配置"). Use the plain commands above.

## Module Layout

Maven multi-module project (`io.pigagent`, version `0.1.0-SNAPSHOT`). Each module is one bounded responsibility; `pig-agent-cli` is the only entry point and depends on all others.

| Module | Responsibility | Key types |
|--------|---------------|-----------|
| `pig-agent-core` | Agent wrapper, lifecycle hooks, memory, provider SPI | `PigAgent`, `LoggingHook`, `ToolCallLoggingHook`, `FileMemory`, `AgentOnboardingProvider`, `ProviderCredentials` |
| `pig-agent-providers` | LLM provider implementations + registry | `AnthropicProvider`, `OpenAiProvider`, `OllamaProvider`, `GeminiProvider`, `DashScopeProvider`, `ProviderRegistry` |
| `pig-agent-tools` | `@Tool`-annotated built-in tools | `ShellTools`, `FileSystemTools`, `SmartWebFetchTool`, `BraveWebSearchTool`, `TaskTool`, `CheckListTool`, `SkillsTool`, `ToolDiscovery` (Lucene) |
| `pig-agent-task` | Task model, scheduling, file persistence | `Task` (record), `TaskManager`, `TaskScheduler`, `FileSystemTaskRepository` |
| `pig-agent-mcp` | MCP client management (stdio/SSE/streamable-http) | `McpManager` |
| `pig-agent-workspace` | `~/.pig-agent/workspace/` layout | `WorkspaceManager` |
| `pig-agent-config` | YAML config + change listeners | `PigAgentConfig`, `ConfigurationManager`, `ConfigurationChangedEvent` |
| `pig-agent-channel` | Channel abstraction + agent bridge | `Channel`, `ChannelAgentBridge`, `ChatChannel`, `TelegramChannel`, `DiscordChannel`, `ChannelRegistry` |
| `pig-agent-onboarding` | First-run setup wizard | `OnboardingWizard` |
| `pig-agent-cli` | JLine3 REPL, wiring, graceful shutdown | `PigAgentCli` |

## Architecture Essentials

**Request flow:** user input → JLine3 REPL → `Msg(USER)` → `PigAgent.stream(msg)` → AgentScope `ReActAgent` reasoning loop. The loop emits lifecycle events (`PreReasoningEvent`, `PostReasoningEvent`, `PreActingEvent`, `PostActingEvent`) intercepted by `Hook` implementations; tool execution dispatches to `@Tool`-annotated methods registered in the AgentScope `Toolkit`.

**Wiring happens in `PigAgentCli`** — providers, tools, hooks, channels, and MCP servers are all registered there. This is the first file to read when tracing how a component is plugged in.

**Extension is via AgentScope SPIs, not core edits:**
- New tool: a class with `@Tool`/`@ToolParam` methods → `toolkit.registration().tool(new MyTool()).apply()`.
- New hook: implement `io.agentscope.core.hook.Hook` (lower `priority()` = runs earlier).
- New LLM provider: implement `AgentOnboardingProvider`, register on `ProviderRegistry`.
- New channel: implement `Channel`, register on `ChannelRegistry`; `ChannelAgentBridge` routes channel messages through the agent.
- MCP servers are config-only (`application.yaml`); their tools auto-register into the Toolkit.

**Configuration** lives at `~/.pig-agent/workspace/application.yaml` (model, agent, channels, mcp sections), parsed by Jackson YAML into `PigAgentConfig`. `ConfigurationManager` supports change listeners for runtime updates.

**Tasks** persist as Markdown at `workspace/tasks/{date}/{id}.md` (recurring under `tasks/recurring/`); `TaskScheduler` runs ONCE/CRON/DELAYED schedules on a `ScheduledExecutorService`.

## Conventions

- **Immutability is enforced** project-wide. Core domain types (`Task`, `ProviderCredentials`, config events) are records; mutate via `withXxx()` copy methods rather than setters. See `.claude/rules/common/coding-style.md`.
- Tests use **JUnit 5 + Mockito + AssertJ**, AAA structure. Test classes exist for `config`, `core` (provider credentials), `task`, and `workspace` modules.
- Async/reactive APIs from AgentScope use Project Reactor (`Mono`); hooks return `Mono.just(event)`.

## Project Rules

`.claude/rules/common/` contains enforced standards (coding style, code review, testing ≥80% coverage, security, git/dev workflow, agent orchestration). Notable points: prefer many small files (<800 lines, functions <50 lines), conventional-commit messages (`feat:`, `fix:`, `refactor:`, etc.), and run security checks before commits touching auth/input/filesystem/external calls.
