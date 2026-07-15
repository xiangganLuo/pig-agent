## Why

当前扩展点是分散的多个 SPI：工具走 `ToolProvider`（`ToolRegistrar` 自动发现）、Hook 实现 `io.agentscope.core.hook.Hook`、协议走 `ProtocolRegistry`、渠道走 `ChannelRegistry`。一个「插件」若想同时贡献工具 + Hook，得分别在两处声明，缺少一个统一入口。借鉴 Hermes 的插件模型：外部工具/Hook 由一个插件通过单一 `register(ctx)` 入口贡献，并支持多个发现源（classpath + 外部目录），**组合**既有 `ToolProvider` 自动注册而非替换它。

## What Changes

- **插件 SPI**：新增 `Plugin` 接口，唯一入口 `register(PluginContext ctx)`；`PluginContext` 暴露注册面（`addTool`/`addTools` 贡献带 `@Tool` 的工具实例、`addHook`/`addHooks` 贡献 Hook），底层复用既有 `Toolkit` + `ToolContext` + Hook 装配。
- **多发现源**：`PluginRegistry` 经多个 `PluginSource` 发现插件——(a) classpath `ServiceLoader<Plugin>`（`META-INF/services`，完整实现）；(b) 外部 `workspace/plugins/` 目录下的 jar（`URLClassLoader` + `ServiceLoader`，完整实现，容错发现）。
- **组合既有自动注册**：在内建工具 SPI 自动注册**之后**运行插件注册；工具冲突/覆盖规则与 `ToolRegistrar` 一致（内建先注册即 first-wins，插件同名工具被跳过并记录；单个插件 `register` 抛异常被隔离跳过 fail-safe）。
- **装配接入**：`AgentBootstrap` 在内建工具注册后加载插件，把插件工具并入可用性门控/契约守卫，把插件 Hook 追加到交互 agent 的 Hook 链。

无 **BREAKING**：无插件时行为与今天完全一致；`Plugin` 是叠加能力，既有 `ToolProvider`/Hook/协议/渠道 SPI 均不变。

## Capabilities

### New Capabilities
- `plugin-system`: 外部工具/Hook 经统一 `Plugin.register(ctx)` 入口 + 多发现源（classpath `ServiceLoader` + 外部 `plugins/` 目录 jar）贡献进运行时；组合既有 `ToolProvider` 自动注册（内建先注册 first-wins、插件同名被跳过、单插件失败被隔离 fail-safe），无插件时零行为变更。

### Modified Capabilities
<!-- 无：不改 tool-autoregister / tool-permissions / tool-availability / tool-json-contract 契约；本能力只新增「插件如何贡献扩展并进入既有装配」这一维度。 -->

## Impact

- **代码**：新增模块 `pig-agent-plugin`（`Plugin`/`PluginContext`/`PluginSource`/`ServiceLoaderPluginSource`/`DirectoryPluginSource`/`PluginRegistry`）；`pig-agent-tools` 的 `ToolRegistrar` 新增公开 `registerTools(...)`（复用既有去重/覆盖 plumbing 注册已实例化工具）；`pig-agent-workspace` 新增 `getPluginsDir()`；`pig-agent-cli` 的 `AgentBootstrap` 接入插件加载与 Hook 追加。
- **协作/不改**：`ToolPermissionHook`（veto）、`tool-availability`（可见性门控）、`tool-json-contract`（返回契约 + 分发守卫）等维度叠加于插件工具之上不变；MCP 动态工具注册路径不变。
- **测试**：classpath 源经测试 services 文件发现插件并注册工具/Hook；同名工具去重（内建/先到 first-wins）；单个插件 `register` 抛异常被隔离，其它插件仍注册；外部目录源对缺失/空/非 jar/坏 jar 容错返回空且不崩；无插件时行为不变（backward-compat）。
- **文档**：`CLAUDE.md` 扩展章节「Extension is via SPIs」补充「New plugin: 实现 `Plugin` + `register(ctx)`，classpath 或 `plugins/` 目录发现，组合既有 ToolProvider 自动注册」。
