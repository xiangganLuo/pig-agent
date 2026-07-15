## Why

`pig-agent-tools` 现在是一个「什么都往里塞」的大杂烩：既有真正的核心工具（shell/文件/任务/技能/MCP/权限），又混进了**非核心、需外部配置或有外部依赖**的工具——`BraveWebSearchTool`（需 `BRAVE_API_KEY`）、`SmartWebFetchTool`（联网 + SSRF）、`CheckListTool`（有状态小工具），还压着一坨**死代码 + 重依赖**：`ToolDiscovery`（基于 Apache Lucene 的工具全文搜索）**从未被任何 `@Tool`/SPI/装配引用**，却让核心工具模块背上两条 Lucene 依赖。

与此同时 `plugin-system` 已提供成熟的插件运行时（`Plugin.register(ctx)` + `ServiceLoader`/目录发现 + `PluginRegistry`），`pig-agent-plugin-collection` 已用 GoF 骨架（模板方法 + 工厂方法 + SPI 策略 + 注册表）承载了 6 个内置计算插件。**非核心工具的正确归宿就是内置插件模块**，而不是核心工具模块。

本次是**面向设计模式（而非功能）的结构瘦身**：让 `pig-agent-tools` 只留「高可用、零外部配置」的核心工具 + 框架，把非核心工具抽成内置插件，删掉死代码与重依赖，并把内置插件模块从「collection（集合）」正名为「builtin（内置）」以准确表达其职责。

## What Changes

- **`pig-agent-tools` 瘦身为核心**：保留核心工具 `ShellTools` / `FileSystemTools` / `TaskTool` / `SkillsTool` / `McpTool` / `PermissionDeniedTool` 及框架（`permission/` / `contract/` / `availability/` / `spi/`），其 `ToolProvider` + `META-INF/services` 条目 + `ToolRiskClassifier` 默认分级保持不变。
- **删除死代码 + 重依赖**：删除未被引用的 `ToolDiscovery`，并从 `pig-agent-tools/pom.xml`（及父 POM 管理与 `lucene.version` 属性）移除 `lucene-core` / `lucene-queryparser`。
- **模块正名 `pig-agent-plugin-collection` → `pig-agent-plugin-builtin`**：重命名目录、`<artifactId>`、Java 包（`io.pigagent.plugin.collection` → `io.pigagent.plugin.builtin`）、插件 id 前缀（`collection:` → `builtin:`）；同步父 POM `<modules>`/`<dependencyManagement>`、`pig-agent-cli` 依赖、`META-INF/services` 与 `CLAUDE.md` 模块表。保留 6 个计算插件（time/uuid/base64/hash/json/random），一个不删。
- **抽取 3 个非核心工具为内置插件**：把 `BraveWebSearchTool`（`webSearch`）、`SmartWebFetchTool`（`fetchUrl`，含 `SsrfGuard`）、`CheckListTool`（`checklist`）从 `pig-agent-tools` 移入 `pig-agent-plugin-builtin`，各由一个 `AbstractToolPlugin` 子类（`WebSearchPlugin`/`WebFetchPlugin`/`ChecklistPlugin`）经 `ServiceLoader` 贡献。删除其在 `pig-agent-tools` 的 `ToolProvider` + service 行。
- **行为逐字保留**：`webSearch` 仍经 `ToolAvailability` 门控 `BRAVE_API_KEY`（缺失即从模型 schema 隐藏）；`fetchUrl` 仍经 `SsrfGuard` + `tools.web.allowed-hosts`（配置经 `PluginContext.toolContext().webAllowedHosts()` 注入）；三者经 `ServiceLoaderPluginSource` 开箱发现（模块为 cli 依赖），可用性/权限/契约/守卫门控叠加不变。`ToolRiskClassifier.DEFAULTS` 仍保留 `webSearch`(READ_ONLY)/`fetchUrl`(NETWORK) 的正确分级（该表是「按工具名的中央风险目录」，与工具物理所在模块解耦）。

无 **BREAKING**：核心工具集不变；抽取的 3 个工具经内置插件模块随发行版开箱注册，工具名/风险/可用性/SSRF 行为逐字保留；`ToolDiscovery` 从未对外可见（无 `@Tool`），删除对运行时零影响。

## Capabilities

### New Capabilities
- `tools-core`: `pig-agent-tools` 仅承载「高可用、零外部配置」核心工具 + 工具框架；非核心工具（web 搜索/抓取、清单）经内置插件模块以 `Plugin` SPI 承载并开箱注册（保留可用性/SSRF/风险分级）；核心工具模块不含死代码与重依赖（移除 `ToolDiscovery` + Lucene）；内置插件模块正名为 `pig-agent-plugin-builtin`。

### Modified Capabilities
- `plugin-collection`: 「模块缺席零行为变更」要求中的模块名由 `pig-agent-plugin-collection` 更新为 `pig-agent-plugin-builtin`（正名，行为契约不变）。

<!-- 不改：plugin-system / tool-autoregister / tool-permissions / tool-availability / tool-json-contract / tool-sandbox 的行为契约。tool-sandbox 的 `fetchUrl` SSRF + allowed-hosts 契约随 `SsrfGuard` 一并迁移、行为逐字保留，故无需 delta。 -->

## Impact

- **代码（`pig-agent-tools`）**：删除 `discovery/ToolDiscovery`；移出 `websearch/BraveWebSearchTool`、`webfetch/SmartWebFetchTool`、`webfetch/SsrfGuard`、`checklist/CheckListTool` 及其 3 个 `ToolProvider`；`META-INF/services/io.pigagent.tool.spi.ToolProvider` 删除 3 行；`pom.xml` 移除 Lucene 两依赖。
- **代码（`pig-agent-plugin-builtin`，原 collection）**：目录/artifactId/包/id 正名；新增 `WebSearchPlugin`/`WebFetchPlugin`/`ChecklistPlugin` + 移入的 4 个类（含 `SsrfGuard`）；`PluginCatalog` 扩到 9 个；`META-INF/services/io.pigagent.plugin.Plugin` 扩到 9 行。
- **代码（`pig-agent-cli`）**：`AgentBootstrap` 删除 3 个已移出类的 import，手动兜底清单只留核心工具（3 个工具改由插件路径注册，与 auto-register 标志无关）；`FullLinkAgentIT` 的 `CheckListTool` import 改指新包。
- **装配（父 POM）**：`<modules>`/`<dependencyManagement>` 更名 artifactId；移除 `lucene.version` 属性与 Lucene 依赖管理。`pig-agent-cli` 依赖更名。
- **协作/不改**：`PluginRegistry` 加载路径、`ToolRegistrar` 去重/覆盖、`ToolAvailabilityGate`（对 `pluginResult.toolInstances` 一并门控）、权限 veto、返回契约 + 分发守卫、`SsrfGuard` 逻辑，全部不变，叠加于抽取后的插件工具之上。
- **测试**：移入的 `SsrfGuardTest`/`BraveWebSearchToolAvailabilityTest` 随类到新包并保持绿；新增端到端断言 3 个抽取工具经 `ServiceLoaderPluginSource` + `PluginRegistry` 发现注册（`webSearch`/`fetchUrl`/`checklist` 名齐全）、`webSearch` 无 `BRAVE_API_KEY` 时被可用性门控隐藏、`SsrfGuard` 仍拦私网、`CheckListTool` 往返；`pig-agent-tools` 断言不再声明 Lucene（`ToolDiscovery` 已删）。
- **文档**：`CLAUDE.md` 模块表 `pig-agent-tools` 行删去移出工具 + `ToolDiscovery`(Lucene)，`pig-agent-plugin-collection` 行正名为 `pig-agent-plugin-builtin` 并补入抽取插件；`README.md` 的 Lucene/`ToolDiscovery` 陈述同步修正。
