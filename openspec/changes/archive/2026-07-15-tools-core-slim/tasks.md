## 1. 模块正名 collection → builtin

- [x] 1.1 `git mv pig-agent-plugin-collection pig-agent-plugin-builtin`；再把 `src/{main,test}/java/io/pigagent/plugin/collection` 移到 `.../plugin/builtin`（`tool` 子包随迁）。
- [x] 1.2 `pig-agent-plugin-builtin/pom.xml`：`<artifactId>` → `pig-agent-plugin-builtin`，`<name>`/`<description>` 同步。
- [x] 1.3 父 POM `<modules>` 与 `<dependencyManagement>` 的 artifactId 更名；`pig-agent-cli/pom.xml` 依赖更名。
- [x] 1.4 全模块 Java 包声明与 import `io.pigagent.plugin.collection` → `io.pigagent.plugin.builtin`；插件 id 前缀 `collection:` → `builtin:`（6 个计算 `*Plugin`）。
- [x] 1.5 `META-INF/services/io.pigagent.plugin.Plugin` 与 `PluginCatalog` 的 FQCN 更名到新包；`PluginCatalogTest` 前缀断言改 `builtin:`；`PluginCollectionDiscoveryTest` 包/引用更名。

## 2. 核心工具留守（不动，仅确认）

- [x] 2.1 确认 `pig-agent-tools` 保留 `ShellTools`/`FileSystemTools`/`TaskTool`/`SkillsTool`/`McpTool`/`PermissionDeniedTool` + `permission/`/`contract/`/`availability/`/`spi/`，其 `ToolProvider` + service 条目 + `ToolRiskClassifier` 默认分级不变。

## 3. 删除死代码 + 重依赖（TDD：先加断言）

- [x] 3.1 单测（tools 模块）：断言 `pig-agent-tools` 运行期无 Lucene（`ByteBuffersDirectory` 等类不可加载 / 不在依赖树）——RED。
- [x] 3.2 删除 `pig-agent-tools/src/main/java/io/pigagent/tool/discovery/ToolDiscovery.java`（及空 `discovery/` 包）。
- [x] 3.3 `pig-agent-tools/pom.xml` 移除 `lucene-core`/`lucene-queryparser`；父 POM 移除对应 `<dependencyManagement>` 两条 + `lucene.version` 属性——GREEN。

## 4. 抽取 3 个非核心工具为内置插件（TDD）

- [x] 4.1 `git mv` 移动 `BraveWebSearchTool`/`SmartWebFetchTool`/`SsrfGuard`/`CheckListTool` 4 个类到 `pig-agent-plugin-builtin/.../builtin/tool/`；改包声明为 `io.pigagent.plugin.builtin.tool`。
- [x] 4.2 `pig-agent-tools`：删除 3 个 `ToolProvider`（Brave/SmartWebFetch/CheckList）+ `META-INF/services/io.pigagent.tool.spi.ToolProvider` 对应 3 行。
- [x] 4.3 新增 `WebSearchPlugin`/`WebFetchPlugin`/`ChecklistPlugin`（`AbstractToolPlugin` 子类，`builtin:websearch|webfetch|checklist`）；`WebFetchPlugin.createTools` 从 `ctx.webAllowedHosts()` 注入白名单（搬运原 `SmartWebFetchToolProvider` 逻辑）。
- [x] 4.4 `PluginCatalog.all()` 扩到 9 个；`META-INF/services/io.pigagent.plugin.Plugin` 扩到 9 行。
- [x] 4.5 确认 `ToolRiskClassifier.DEFAULTS` 仍含 `webSearch`(READ_ONLY)/`fetchUrl`(NETWORK)（保留在 tools，D6）。

## 5. AgentBootstrap 装配收敛

- [x] 5.1 删除 `AgentBootstrap` 对 `BraveWebSearchTool`/`SmartWebFetchTool`/`CheckListTool` 的 import；手动兜底清单只留核心 5 工具（`TaskTool`/`ShellTools`/`FileSystemTools`/`SkillsTool`/`PermissionDeniedTool`）。
- [x] 5.2 `FullLinkAgentIT` 的 `CheckListTool` import 改指 `io.pigagent.plugin.builtin.tool.CheckListTool`。

## 6. 迁移 + 新增测试（TDD 验收）

- [x] 6.1 `SsrfGuardTest`、`BraveWebSearchToolAvailabilityTest` 随类移到 `io.pigagent.plugin.builtin.tool` 并保持绿。
- [x] 6.2 新增发现/注册端到端单测：经 `ServiceLoaderPluginSource` + `PluginRegistry` + 真实 `Toolkit`，`webSearch`/`fetchUrl`/`checklist` 三名齐全（连同 6 计算工具）。
- [x] 6.3 新增单测：`webSearch` 无 `BRAVE_API_KEY` 时经 `ToolAvailabilityGate` 从 `Toolkit` 隐藏；有 key 时保留。
- [x] 6.4 新增单测：`CheckListTool` 创建/勾选/展示往返正常。

## 7. 文档 + 构建验收

- [x] 7.1 `CLAUDE.md` 模块表：`pig-agent-tools` 行删去移出工具 + `ToolDiscovery`(Lucene)；`pig-agent-plugin-collection` 行正名 `pig-agent-plugin-builtin` 并补入抽取插件；模块数不变（15）；插件扩展点段的 collection 引用同步。
- [x] 7.2 `README.md` 修正 Lucene/`ToolDiscovery` 陈述（表格「工具发现」行 + 依赖行）。
- [x] 7.3 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 7.4 `mvn -q test`（单线程 `-DforkCount=1 -Dsurefire.rerunFailingTestsCount=0`）绿，读 surefire XML 计数确认。
