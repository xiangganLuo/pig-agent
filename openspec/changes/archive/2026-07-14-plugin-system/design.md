## Context

扩展当前分散在多个 SPI：工具 `io.pigagent.tool.spi.ToolProvider`（由 `ToolRegistrar` 经 `ServiceLoader` 自动发现，含手动兜底/覆盖/失败隔离）、Hook `io.agentscope.core.hook.Hook`（在 `AgentFactory` 的 hooks 列表里装配）、协议 `ProtocolRegistry`、渠道 `ChannelRegistry`。缺少一个「一个外部插件同时贡献工具 + Hook」的统一入口。本 spec 引入 Hermes 式插件模型：单一 `register(ctx)` 入口 + 多发现源，**组合**既有 `ToolProvider` 自动注册。

## Goals / Non-Goals

**Goals:**
- 统一插件入口 `Plugin.register(PluginContext)`，一处贡献工具与 Hook。
- 多发现源：classpath `ServiceLoader<Plugin>`（完整）+ 外部 `workspace/plugins/` 目录 jar（`URLClassLoader`+`ServiceLoader`，完整、容错）。
- 工具注册复用 `ToolRegistrar` 的去重/覆盖 plumbing，规则与既有一致。
- 单个插件/源失败被隔离（fail-safe），不影响其它。
- 无插件时行为零变化（backward-compat）。

**Non-Goals:**
- 不替换既有 `ToolProvider`/Hook/`ProtocolRegistry`/`ChannelRegistry` SPI，仅叠加。
- 不改权限 veto / 可用性门控 / 返回契约 / MCP 动态注册。
- 不做插件热插拔/卸载、依赖解析、版本协商、签名校验（安全模型见 R2，留作后续 spec）。
- 命令（picocli）/渠道贡献不在本 spec 的注册面内（见 D5 延后）。

## Decisions

- **D1 — 单一入口 `Plugin.register(PluginContext)`**：`Plugin` 只有 `register(ctx)`（+ 默认 `id()` 返回类名，用于日志/去重）。`PluginContext` 暴露 `toolContext()`（复用既有 `ToolContext` 取运行期依赖）、`addTool/addTools`（贡献带 `@Tool` 的实例）、`addHook/addHooks`（贡献 Hook）。收集式实现 `CollectingPluginContext` 累积贡献，供 `PluginRegistry` 读取。理由：对齐 Hermes「一个 register 贡献多类扩展」，且底层完全复用既有 plumbing。
- **D2 — 多发现源 `PluginSource`**：`PluginSource.discover()` 返回 `List<Plugin>`（`default name()` 便于测试用 lambda）。`ServiceLoaderPluginSource` 用 `ServiceLoader.load(Plugin.class, cl)`（classpath，完整）；`DirectoryPluginSource` 扫 `plugins/*.jar` → `URLClassLoader` → `ServiceLoader`（完整实现）。两源发现的插件按源顺序、id 去重（重复 id 记 warn 保留先到）。
- **D3 — 组合既有自动注册，规则一致**：装配顺序「内建工具 SPI 自动注册 → 插件注册」。插件工具经**新增的** `ToolRegistrar.registerTools(toolkit, tools)` 注册，走与自动阶段相同的 `registerOne(manual=false)`：同名冲突 first-wins（内建先注册故内建胜出，插件同名被跳过记 warn，不静默叠加），复用既有去重 plumbing 而非另写一套。手动 > 自动的既有规则不变。
- **D4 — 失败隔离（fail-safe）**：每个插件 `register(ctx)` 包 try/catch，抛异常者记 warn（含 id/原因）并跳过，其**部分贡献被丢弃**（用 per-plugin 独立 context，成功才并入聚合），其它插件照常注册；每个 `PluginSource.discover()` 亦包 try/catch，坏源跳过。与既有「坏文件跳过」容错风格一致。
- **D5 — 注册面范围（工具 + Hook）**：本 spec 的 `PluginContext` 只暴露工具与 Hook 两个注册面——它们能干净复用既有 `Toolkit`/`ToolContext`/`AgentFactory` hooks plumbing 且可离线单测。命令（picocli，在 `pig-agent-cli`）与渠道（`ChannelRegistry`）贡献会把上层/前端耦合拉进插件模块，**延后**为后续扩展点（不在本 spec，文档标注）。
- **D6 — 模块落位 `pig-agent-plugin`**：插件同时需要 `ToolContext`（在 `pig-agent-tools`）与 `Hook`（agentscope）。`pig-agent-tools` 依赖 `pig-agent-core`，故 core 不能反依赖 tools；新建 `pig-agent-plugin` 依赖 `pig-agent-tools`（传递带 core + agentscope），`pig-agent-cli` 依赖 `pig-agent-plugin`。无环，耦合低。
- **D7 — 装配接入点**：`AgentBootstrap` 在内建工具注册后、可用性门控前加载插件——插件工具并入可用性门控输入、并在 `ToolContractGuard.install` 前进 toolkit（自动获得契约守卫包装）；插件 Hook 追加到交互 agent 的 hooks 列表尾部（Hook 由 `priority()` 排序，追加不打乱优先级）。

## Risks / Trade-offs

- **R1 — 外部 jar 端到端加载的测试成本**：`DirectoryPluginSource` 的真实代码路径（扫 jar → `URLClassLoader` → `ServiceLoader`）完整实现，但「从真实外部 jar 加载已编译插件类」的端到端往返需构建 fixture jar，离线单测成本高/易脆。→ 目录源以**发现/容错**层面单测（null/缺失/空/非 jar/坏 jar → 空、不崩，且真实构建一个坏 service jar 走完 `URLClassLoader`+`ServiceLoader` 路径验证容错）；「插件被发现→注册工具/Hook」的端到端由 classpath 源（同一 `ServiceLoader` 机制）用测试 services 文件充分覆盖。
- **R2 — 外部插件是任意代码，无沙箱**：加载 `plugins/` 目录的 jar 等于执行任意代码，本 spec 不做签名/权限沙箱（超出范围）。缓解：目录源默认对空/缺失目录静默返回空（无插件零成本）；插件工具仍受既有权限 veto/可用性/契约约束；文档明示「只放可信 jar」。真正的插件安全模型留作后续 spec。
- **R3 — id 去重误伤**：两源可能返回同 id 插件（如既在 classpath 又在 jar）。→ 按源顺序 first-wins 并记 warn，避免同一插件注册两次。
- **R4 — 与自动注册的顺序耦合**：插件工具「内建 first-wins」意味着插件无法覆盖内建工具（有意的安全默认——外部代码不应静默遮蔽内建工具）。若未来需要「可信插件覆盖」，可显式加 manual 语义（不在本 spec）。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 单一 `register(ctx)` 入口贡献工具 + Hook | D1；task 1.x；`Plugin`/`PluginContext`/`CollectingPluginContext` | 已实现 |
| classpath `ServiceLoader` 发现源 | D2；task 2.x；`ServiceLoaderPluginSource` | 已实现 |
| 外部 `plugins/` 目录 jar 发现源 | D2；task 2.x；`DirectoryPluginSource`（完整代码路径，发现/容错单测） | 已实现（端到端外部 jar 往返未单测，见 R1） |
| 组合既有自动注册、去重/覆盖规则一致 | D3；task 3.x；`ToolRegistrar.registerTools` | 已实现 |
| 单插件/源失败隔离 fail-safe | D4；task 3.x/2.x | 已实现 |
| 命令/渠道注册面 | D5；延后（不在本 spec） | 延后 |
| 装配接入（工具并入门控/守卫、Hook 追加） | D7；task 4.x；`AgentBootstrap` | 已实现 |
| 无插件零行为变更 | task 3.x/5.x（backward-compat 单测） | 已实现 |
