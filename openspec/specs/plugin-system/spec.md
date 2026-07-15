# plugin-system Specification

## Purpose
让外部工具/Hook 经统一的 `Plugin.register(ctx)` 入口 + 多发现源（classpath `ServiceLoader` 与外部 `plugins/` 目录 jar）贡献进运行时，组合既有 `ToolProvider` 自动注册（内建 first-wins、同名插件工具跳过、单插件失败隔离 fail-safe），无插件时零行为变更。

## Requirements
### Requirement: 统一插件入口贡献扩展

系统 SHALL 提供 `Plugin` SPI，其唯一入口 `register(PluginContext ctx)` 让一个插件在一处同时贡献工具与 Hook。`PluginContext` SHALL 暴露注册面：`addTool`/`addTools`（贡献带 `@Tool` 方法的工具实例）、`addHook`/`addHooks`（贡献 `io.agentscope.core.hook.Hook`），并 SHALL 经 `toolContext()` 向插件透传既有运行期依赖（`ToolContext`）。底层 MUST 复用既有 `Toolkit` / `ToolContext` / Hook 装配，而非另建一套。

#### Scenario: 插件经单一入口贡献工具与 Hook
- **WHEN** 一个插件在 `register(ctx)` 中调用 `ctx.addTool(...)` 与 `ctx.addHook(...)`
- **THEN** 其工具被注册进 `Toolkit`、其 Hook 被收集供 agent 装配使用

#### Scenario: 运行期依赖经上下文透传
- **WHEN** 插件在 `register` 中读取 `ctx.toolContext()`
- **THEN** 得到与内建工具相同的 `ToolContext`（如 taskManager / skillsDir / workspaceRoot）

### Requirement: 多发现源

系统 SHALL 经多个发现源加载插件：(a) classpath `ServiceLoader<Plugin>`（`META-INF/services` 声明）；(b) 外部 `workspace/plugins/` 目录下的 jar（经 `URLClassLoader` + `ServiceLoader` 加载）。目录源 MUST 对目录不存在/为空/无 jar 的情形容错返回空且不抛异常。跨源发现的插件 SHALL 按 id 去重（重复保留先到并记录）。

#### Scenario: classpath 声明的插件被发现
- **WHEN** 一个插件在 `META-INF/services/io.pigagent.plugin.Plugin` 中声明并启动
- **THEN** 该插件被 classpath 源发现并执行其 `register`

#### Scenario: 外部目录缺失或为空
- **WHEN** `plugins/` 目录不存在或不含任何 jar
- **THEN** 目录源返回空插件列表，不抛异常，运行时行为等同无插件

#### Scenario: 坏 jar 被容错跳过
- **WHEN** `plugins/` 目录含一个无法解析出有效插件的 jar
- **THEN** 目录源记录并跳过它，返回其余可用插件（或空），不中断加载

### Requirement: 组合既有工具自动注册

插件注册 SHALL 在内建工具 SPI 自动注册**之后**运行，并 SHALL 复用 `ToolRegistrar` 既有的去重/覆盖 plumbing 注册插件工具。工具同名冲突 SHALL 遵循与既有一致的规则：内建工具先注册故 first-wins，插件贡献的同名工具被跳过并记录；MUST NOT 静默叠加两个同名工具。既有「手动 > 自动」覆盖规则 MUST 保持不变。

#### Scenario: 插件工具进入 Toolkit
- **WHEN** 一个插件贡献一个新名字的工具
- **THEN** 该工具被注册进 `Toolkit`，与内建工具并存

#### Scenario: 插件工具与内建同名时内建胜出
- **WHEN** 一个插件贡献的工具名与某已注册内建工具相同
- **THEN** 保留内建实现、跳过该插件工具并记录，不静默叠加

### Requirement: 单个插件失败被隔离

某个插件的 `register` 抛异常，或某个发现源加载失败时，系统 MUST 捕获并记录，且 MUST NOT 因此中断其它插件/源的发现与注册（fail-safe）。抛异常插件的部分贡献 MUST 被丢弃（不半注册）。

#### Scenario: 抛异常的插件被隔离
- **WHEN** 一个插件在 `register` 中抛异常
- **THEN** 系统记录该失败、丢弃其部分贡献并继续注册其它插件，`Toolkit` 正常可用

#### Scenario: 发现源失败不影响其它源
- **WHEN** 某个 `PluginSource` 在发现时失败
- **THEN** 系统记录并跳过该源，其它源正常发现

### Requirement: 无插件时零行为变更

当没有任何插件被发现时，系统行为 MUST 与未引入插件系统时完全一致：`Toolkit` 不新增工具、agent 的 Hook 链不新增 Hook。

#### Scenario: 无插件
- **WHEN** 无任何发现源返回插件
- **THEN** 注册结果为空，`Toolkit` 与 Hook 链保持不变

