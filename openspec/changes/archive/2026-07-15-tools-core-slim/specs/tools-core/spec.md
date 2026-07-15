## ADDED Requirements

### Requirement: 核心工具模块只承载高可用零配置核心工具

`pig-agent-tools` MUST 仅承载「高可用、零外部配置、无重量级第三方依赖」的核心工具及工具框架。核心工具 SHALL 为：`ShellTools`（executeCommand）、`FileSystemTools`（读/写/列目录）、`TaskTool`、`SkillsTool`、`McpTool`、`PermissionDeniedTool`；框架 SHALL 为 `permission/`、`contract/`、`availability/`、`spi/`。凡需外部凭据/服务/网络、或属非基本操作的工具 MUST NOT 驻留于 `pig-agent-tools`。核心工具的 `ToolProvider` 自动发现、`META-INF/services` 声明与 `ToolRiskClassifier` 默认分级 MUST 保持不变。

#### Scenario: 核心工具经 SPI 自动注册且行为不变
- **WHEN** `AgentBootstrap` 装配工具集（`ToolRegistrar.registerAll`）
- **THEN** 上述核心工具经既有 `ToolProvider` + `META-INF/services` 被注册进 `Toolkit`，其风险分级与可用性/权限/契约门控与今天一致

#### Scenario: 非核心工具不在核心模块
- **WHEN** 检视 `pig-agent-tools` 的 `@Tool` 工具与其 `ToolProvider` 声明
- **THEN** 不含 web 搜索/抓取、清单等非核心工具（它们由内置插件模块承载）

### Requirement: 核心工具模块不含死代码与重依赖

`pig-agent-tools` MUST NOT 包含未被任何 `@Tool`/`ToolProvider`/装配/调用引用的死代码，MUST NOT 因此背负重量级第三方依赖。基于 Apache Lucene 的工具全文搜索 `ToolDiscovery`（经核查确证零引用）SHALL 被删除，`lucene-core`/`lucene-queryparser` 依赖 SHALL 从 `pig-agent-tools` 移除。

#### Scenario: Lucene 不再出现在核心工具模块
- **WHEN** 构建并检视 `pig-agent-tools` 的依赖与运行期 classpath
- **THEN** 不含 `lucene-core`/`lucene-queryparser`，且无 `ToolDiscovery` 类

#### Scenario: 删除后构建与既有测试不破
- **WHEN** 运行 `mvn -pl pig-agent-cli -am compile` 与全量单测
- **THEN** 编译通过、既有测试回归绿（`ToolDiscovery` 的删除对运行时零影响）

### Requirement: 非核心工具经内置插件模块承载并保留行为

被抽取的非核心工具 `webSearch`（Brave 搜索）、`fetchUrl`（web 抓取）、`checklist`（清单）MUST 迁入内置插件模块，各由一个经 `ServiceLoader` 发现的 `Plugin`（复用 `AbstractToolPlugin` 模板方法 + 工厂方法骨架）贡献 `@Tool` 工具。迁移后行为 MUST 逐字保留：
- `webSearch` MUST 仍经 `ToolAvailability` 门控 `BRAVE_API_KEY`——缺失时从模型 schema 隐藏；
- `fetchUrl` MUST 仍经 SSRF 出口守卫并遵守 `tools.web.allowed-hosts`（配置经 `PluginContext.toolContext()` 注入）；
- 三者的风险分级 MUST 保持正确（`webSearch`=READ_ONLY、`fetchUrl`=NETWORK），可经中央 `ToolRiskClassifier` 目录按工具名登记（与工具物理所在模块解耦）；
- 因内置插件模块为 `pig-agent-cli` 运行时依赖，三者 MUST 经 `ServiceLoaderPluginSource` 开箱发现并注册（与今天等价）。

#### Scenario: 抽取工具开箱被发现并注册
- **WHEN** 内置插件模块在 classpath 上，经 `ServiceLoaderPluginSource` + `PluginRegistry` 加载
- **THEN** `webSearch`、`fetchUrl`、`checklist` 三个工具名被注册进 `Toolkit`

#### Scenario: webSearch 无凭据时被可用性门控隐藏
- **WHEN** `BRAVE_API_KEY` 未设置，`ToolAvailabilityGate` 对插件贡献的工具实例求值
- **THEN** `webSearch` 被从 `Toolkit` 移除（不进模型 schema），门控理由只含变量名不含凭据值

#### Scenario: fetchUrl SSRF 与主机白名单随迁保留
- **WHEN** 调用 `fetchUrl` 指向私网/回环/元数据地址，或指向不在非空 `tools.web.allowed-hosts` 的主机
- **THEN** 请求被拒（SSRF 守卫 + 白名单逻辑随工具一并迁移、行为不变）

### Requirement: 内置插件模块正名为 pig-agent-plugin-builtin

承载内置插件的模块 SHALL 命名为 `pig-agent-plugin-builtin`（原 `pig-agent-plugin-collection`），其 `<artifactId>`、Java 包（`io.pigagent.plugin.builtin`）与插件 id 前缀（`builtin:`）MUST 一致。正名 MUST NOT 改变任何插件的对外行为；原有 6 个纯计算插件（time/uuid/base64/hash/json/random）MUST 全部保留。

#### Scenario: 正名后插件集完整且可发现
- **WHEN** 经 `ServiceLoaderPluginSource` 发现 `pig-agent-plugin-builtin` 的插件
- **THEN** 6 个计算插件 + 3 个抽取插件（websearch/webfetch/checklist）齐全，id 以 `builtin:` 为前缀，`PluginCatalog` 与 `META-INF/services` 声明一致
