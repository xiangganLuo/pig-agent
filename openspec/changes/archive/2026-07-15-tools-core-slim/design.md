## Context

`pig-agent-tools` 是「核心工具 + 工具框架」的家，但随着功能堆叠，混进了非核心工具与死代码。`plugin-system` + `pig-agent-plugin-collection` 已提供成熟的内置插件承载机制（GoF 骨架 + SPI 发现）。本 spec 做一次**面向设计模式的结构重整**：按「设计属性」而非「功能」重新划分——核心工具留核心模块，非核心工具下沉为内置插件，死代码/重依赖清除，模块名与职责对齐。

不是新增功能，是**归位 + 瘦身**：对外可见行为（工具名、风险分级、可用性、SSRF、返回契约）逐字保留。

## Goals / Non-Goals

**Goals:**
- `pig-agent-tools` 只保留「高可用、零外部配置」核心工具 + 框架（permission/contract/availability/spi）。
- 删除确证未被引用的 `ToolDiscovery` 及其 Lucene 重依赖。
- 内置插件模块正名 `collection` → `builtin`（目录/artifactId/包/id），保留全部 6 个计算插件。
- 抽取 `webSearch`/`fetchUrl`/`checklist` 3 个非核心工具为内置插件，复用 `AbstractToolPlugin`/SPI 骨架。
- 抽取后行为逐字保留：可用性门控（`BRAVE_API_KEY`）、SSRF + allowed-hosts、风险分级、开箱发现注册。

**Non-Goals:**
- 不改 `plugin-system`/`tool-autoregister`/`tool-permissions`/`tool-availability`/`tool-json-contract`/`tool-sandbox` 的行为契约。
- 不新增任何工具能力，不改任何工具的对外语义。
- 不引入插件热插拔/依赖解析/版本协商（沿用 `plugin-system` Non-Goals）。
- 不动 `pig-agent-web` 等无关模块。

## Decisions

### D1 — 核心工具「保留」判据：高可用 + 零外部配置 + 无重依赖
判据（设计属性，非功能清单）：一个工具**留在 `pig-agent-tools`** 当且仅当它 (a) 开箱即用、无需任何外部凭据/服务/网络；(b) 是 agent 的基本操作面（读写文件、执行命令、任务、技能、MCP 自助管理、权限拒绝哨兵）；(c) 不拖入重量级第三方依赖。据此**保留**：`ShellTools`（executeCommand，EXEC）、`FileSystemTools`（read/write/list）、`TaskTool`、`SkillsTool`、`McpTool`、`PermissionDeniedTool`，以及框架 `permission/`、`contract/`、`availability/`、`spi/`。这些的 `ToolProvider` + `META-INF/services` 条目 + `ToolRiskClassifier` 默认分级**原样不动**。

### D2 — 「抽取」判据：需外部配置 / 联网 / 非基本操作 → 内置插件
- `BraveWebSearchTool`（`webSearch`）：**需 `BRAVE_API_KEY`**，非零配置 → 抽取。
- `SmartWebFetchTool`（`fetchUrl`）：**联网**（+ SSRF 出口面），非基本操作 → 抽取。
- `CheckListTool`（`checklist`）：**有状态的便利小工具**，非核心基本操作 → 抽取。
它们下沉到 `pig-agent-plugin-builtin`，各由一个 `AbstractToolPlugin` 子类经 `ServiceLoader` 贡献。因该模块是 `pig-agent-cli` 的运行时依赖，仍**开箱可用**（与今天等价）。

### D3 — 「删除」判据：死代码 + 重依赖 → 移除
`ToolDiscovery` 经全仓核查（`grep` main/test/`META-INF/services`）**仅被自身与文档引用**：无 `@Tool` 注解、无 `ToolProvider`、未在 `AgentBootstrap` 装配、无任何调用点、无测试。判为死代码，**删除**，并从 `pig-agent-tools/pom.xml`（及父 POM `<dependencyManagement>` + `lucene.version` 属性）移除 `lucene-core`/`lucene-queryparser` 两条重依赖。（fail-safe 前置条件：核查确证无运行期使用才删；若发现真实用途则保留并在此说明——本次核查为「确证无引用」。）

### D4 — 抽取遵循既有插件骨架（模板方法 + 工厂方法 + SPI 策略）
3 个抽取插件复用 `AbstractToolPlugin`：`register(PluginContext)` 为 final 骨架（模板方法），`createTools(ToolContext)` 为工厂方法。
- `WebSearchPlugin`（`super("builtin:websearch")`）→ `createTools` 返回 `new BraveWebSearchTool()`。
- `WebFetchPlugin`（`super("builtin:webfetch")`）→ `createTools` 从 `context.webAllowedHosts()` 取白名单，返回 `new SmartWebFetchTool(allowedHosts)`——**这正是 `PluginContext` 暴露 `ToolContext` 的用途**，把原 `SmartWebFetchToolProvider` 的配置注入逻辑逐字搬到工厂方法里。
- `ChecklistPlugin`（`super("builtin:checklist")`）→ `createTools` 返回 `new CheckListTool()`。
`PluginCatalog.all()` 扩为 9 个（6 计算 + 3 抽取），`META-INF/services/io.pigagent.plugin.Plugin` 同步 9 行；一致性单测守卫两者不漂移。

### D5 — 模块正名 `collection` → `builtin`（彻底、一致）
彻底重命名：目录 `pig-agent-plugin-collection` → `pig-agent-plugin-builtin`；`<artifactId>` 同名；Java 包 `io.pigagent.plugin.collection[.tool]` → `io.pigagent.plugin.builtin[.tool]`；**插件 id 前缀 `collection:` → `builtin:`**（一致性：`builtin` 模块的插件 id 用 `collection:` 前缀会自相矛盾；id 仅用于运行期 dedup/日志、无持久化、无外部契约，重命名零风险）。同步父 POM `<modules>`/`<dependencyManagement>`、`pig-agent-cli` 依赖、`CLAUDE.md`。`PluginCatalogTest` 的前缀断言随之改为 `builtin:`。**保留 capability 名 `plugin-collection`**（capability 名 ≠ 模块名；重命名 capability 需 REMOVE+ADD 全部要求，是无谓 churn；仅对其「模块缺席」要求做 MODIFIED 更新模块名）。

### D6 — 风险分级：`ToolRiskClassifier.DEFAULTS` 是「按工具名的中央风险目录」，与模块解耦
`webSearch`(READ_ONLY)/`fetchUrl`(NETWORK) 的分级**保留在 `pig-agent-tools` 的 `DEFAULTS` 表**，工具本体虽移到 builtin 模块。理由：该表**已经**为物理住在别模块的工具（plugin-collection 的 `currentDateTime`/`generateUuid`/… 计算工具名）登记分级——这是既有设计，`DEFAULTS` 是「工具名 → 风险」的中央目录，用字符串键、无编译期反向依赖、与工具所在模块无关。沿用这一模式即可精确保留分级，比「插件自带 override」更简单、与现状一致；未登记也仅 fail-safe 落 `EXEC`。故本次**不动 `ToolRiskClassifier`**（`webSearch`/`fetchUrl` 条目留在原处）。

### D7 — `AgentBootstrap` 装配收敛
- 删去 `BraveWebSearchTool`/`SmartWebFetchTool`/`CheckListTool` 三个 import。
- **手动兜底清单**（`-Dpigagent.tools.auto-register=false` 分支）只留核心工具（`TaskTool`/`ShellTools`/`FileSystemTools`/`SkillsTool`/`PermissionDeniedTool`）；3 个抽取工具**不再进兜底清单**——因为插件加载（`PluginRegistry.loadAndRegister`）**无条件运行**、与 auto-register 标志无关，抽取工具在两种模式下都由插件路径注册。
- 可用性门控无需改动：`gatedTools = builtinTools + pluginResult.toolInstances` 本就把插件工具实例纳入 `ToolAvailabilityGate.applyTo`，故 `webSearch` 无 key 时照样被隐藏。

### D8 — 依赖方向与新增依赖：零新增
抽取的 4 个类只用：`io.pigagent.tool.contract.ToolErrors`、`io.pigagent.tool.availability.{Availability,ToolAvailability}`（均在 `pig-agent-tools`，builtin 模块已依赖）、`io.agentscope.core.tool.{Tool,ToolParam}`（agentscope，已依赖）、JDK `HttpClient`/`InetAddress`（无依赖）。故 `pig-agent-plugin-builtin` **无需新增任何依赖**。无环。

## Risks / Trade-offs

- **R1 — 插件 id 前缀变更（`collection:`→`builtin:`）**：内部标识变更。→ id 无持久化、仅 dedup/日志用；受影响的仅本仓 `PluginCatalogTest` 断言（同批更新）。零外部风险。
- **R2 — `ToolRiskClassifier` 保留移出工具名的分级 → tools 模块「知道」builtin 工具名**：仅字符串耦合，无编译依赖。→ 与既有（tools 表已登记 collection 计算工具名）现状完全一致，可接受（D6）。
- **R3 — 手动兜底清单去掉 3 工具后行为是否等价**：需确认插件路径在 `auto-register=false` 下仍跑。→ `AgentBootstrap` 中插件加载在 auto-register 分支**之外**、无条件执行，故两模式下抽取工具都注册，等价。
- **R4 — 移出类的 test-seam 可见性**：`BraveWebSearchTool(Function<String,String> env)` 为包私有测试缝。→ 其可用性测试随类移到同包 `io.pigagent.plugin.builtin.tool`，保持包内可见。
- **R5 — 删 `ToolDiscovery` 误伤**：若实为运行期依赖则会破坏构建。→ D3 已确证零引用（无 `@Tool`/SPI/装配/调用/测试）；删除后 `mvn -pl pig-agent-cli -am compile` + 全量 `mvn test` 单线程绿即为验证。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 核心工具保留判据（高可用/零配置/无重依赖） | D1；tools-core R1；task 2.x | 已实现 |
| 死代码 + 重依赖移除（ToolDiscovery + Lucene） | D3；tools-core R2；task 3.x | 已实现 |
| 抽取判据（需配置/联网/非基本操作） | D2；tools-core R3；task 4.x | 已实现 |
| 抽取遵循 AbstractToolPlugin/SPI 骨架 | D4；tools-core R3；task 4.x/5.x | 已实现 |
| 模块正名 collection→builtin（含 id 前缀） | D5；tools-core R4；plugin-collection MODIFIED；task 1.x | 已实现 |
| 风险分级保留（webSearch/fetchUrl 留 DEFAULTS） | D6；tools-core R3；task 4.x | 已实现 |
| 可用性门控保留（webSearch 无 key 隐藏） | D7；tools-core R3；task 6.x（单测） | 已实现 |
| SSRF + allowed-hosts 保留（随 SsrfGuard 迁移） | D2/D4；tool-sandbox 不变；task 6.x（单测） | 已实现 |
| AgentBootstrap 装配收敛（import/兜底清单） | D7；task 5.x | 已实现 |
| 零新增依赖 | D8；task 4.x | 已实现 |
| 文档同步（CLAUDE.md/README.md） | proposal Impact；task 7.x | 已实现 |
