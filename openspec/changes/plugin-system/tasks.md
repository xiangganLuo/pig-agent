## 1. 插件 SPI 与注册面

- [x] 1.1 新建 `pig-agent-plugin` 模块（依赖 `pig-agent-tools`，传递带 core + agentscope），登记进父 POM `<modules>` 与 `<dependencyManagement>`。
- [x] 1.2 定义 `Plugin`（`register(PluginContext)` + 默认 `id()`）。
- [x] 1.3 定义 `PluginContext`（`toolContext()`/`addTool`/`addTools`/`addHook`/`addHooks`）与收集式实现 `CollectingPluginContext`。
- [x] 1.4 单测：`CollectingPluginContext` 累积工具/Hook 与 `toolContext()` 透传。

## 2. 多发现源

- [x] 2.1 定义 `PluginSource`（`discover()` + 默认 `name()`）。
- [x] 2.2 `ServiceLoaderPluginSource`：classpath `ServiceLoader<Plugin>` 发现，坏条目容错跳过。
- [x] 2.3 `DirectoryPluginSource`：扫 `plugins/*.jar` → `URLClassLoader` → `ServiceLoader`，null/缺失/空目录容错返回空。
- [x] 2.4 单测：classpath 源经测试 services 文件发现测试插件；目录源对 null/缺失/空/非 jar/坏 jar 容错返回空且不崩。

## 3. 组合既有自动注册 + 失败隔离

- [x] 3.1 `ToolRegistrar` 新增公开 `registerTools(toolkit, tools)`：复用 `registerOne(manual=false)` 注册已实例化工具（first-wins 去重、fail-safe）。
- [x] 3.2 `PluginRegistry.loadAndRegister(sources, toolContext, toolkit)`：跨源发现（id 去重）→ 逐插件 `register`（per-plugin 独立 context，成功才并入）→ 工具经 `registerTools` 进 toolkit → 返回 `Result`（loaded/failed/toolsRegistered/toolsSkipped/toolInstances/hooks）。
- [x] 3.3 单测：插件贡献的工具进 toolkit、Hook 被收集；同名工具与内建冲突时内建 first-wins、插件被跳过记录；单个插件 `register` 抛异常被隔离、其它插件仍注册；无插件时 `Result` 空且 toolkit 不变（backward-compat）。

## 4. 装配接入

- [x] 4.1 `pig-agent-workspace` 新增 `getPluginsDir()` 并在 `initialize()` 创建目录。
- [x] 4.2 `pig-agent-cli` 依赖 `pig-agent-plugin`；`AgentBootstrap` 在内建工具注册后加载插件（classpath + `plugins/` 目录源），把插件工具并入可用性门控输入、Hook 追加到交互 agent hooks。

## 5. 验收

- [x] 5.1 `mvn -q test` 单线程绿（读 surefire XML 计数）；既有 tool-autoregister/权限/可用性/契约单测回归不破。
- [x] 5.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 5.3 `CLAUDE.md` 「Extension is via SPIs」补充 `New plugin` 扩展点说明。
