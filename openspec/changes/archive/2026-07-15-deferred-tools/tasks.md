## 1. 配置面（pig-agent-config）

- [x] 1.1 `PigAgentConfig.ToolsConfig` 新增 `DeferredToolsConfig`（`enabled` 默认 false、`tools` 默认空、`auto-defer-mcp` 默认 true、`threshold` 默认 25）+ getter/setter，`@JsonProperty` 命名。
- [x] 1.2 单测：默认值（禁用、清单空、阈值 25）；YAML 反序列化 `tools.deferred.*`。（`DeferredToolsConfigTest` 4 例）

## 2. 元数据 + 关键词 + 登记表（pig-agent-tools `io.pigagent.tool.deferred`）

- [x] 2.1 `Keywords.from(name, description)`：驼峰/分隔符/描述切词 → 小写、去短词的关键词集合（DRY 单点）。
- [x] 2.2 `DeferredTool`（record：name/description/keywords/groupName）+ `DeferredToolRegistry`（线程安全：`add`/`search(query,limit)` 只搜仍延迟者、按匹配度排序/`find`/`markRevealed` 连带同组/`all`/`deferredNames`/`revealedNames`）。
- [x] 2.3 单测：`Keywords` 切词（驼峰 `webSearch`→`web,search`；描述词）；`search` 关键词命中排序、空查询、无匹配空表；`markRevealed` 后不再被搜索且连带同 group 一并揭示。（`KeywordsTest` 5 例、`DeferredToolRegistryTest` 9 例）

## 3. 决策纯函数（Strategy）

- [x] 3.1 `ToolInfo`（record：name/description/keywords/mcpGroup 可空）+ `DeferralPlan`（record：Set<String>）+ `DeferredToolPlanner.plan(enabled, explicit, autoDeferMcp, threshold, allTools)`：禁用→空；否则显式清单命中 ∪（超阈值且 autoDeferMcp 时全部 mcpGroup!=null 者）；恒排除 `tool_search`。
- [x] 3.2 单测：禁用→空；显式清单命中（不存在的名忽略）；超阈值自动延迟全部 MCP；未超阈值 + 空清单→空；`tool_search` 永不入计划；并集。（`DeferredToolPlannerTest` 7 例）

## 4. Gate + 揭示 seam（真实 Toolkit）

- [x] 4.1 `DeferredToolReveal`（函数式 `boolean reveal(String)`）+ `DeferredToolGate.applyTo(...)`（+ 填充传入 registry 的重载）：内置（mcpGroup==null）建 per-tool inactive 分组 + 重注册当前 `AgentTool` 到该组；MCP（mcpGroup!=null）收集其分组后一次性 `updateToolGroups(...,false)`；填充 registry（groupName）。
- [x] 4.2 单测（真实 `Toolkit` + 样例 @Tool）：延迟内置工具后 `getToolSchemas()` 排除它、`getToolNames()` 仍在（仍注册）、未延迟工具仍在；对预置 active `mcp:s` 分组的工具计划延迟后该分组停用、工具被排除；揭示（激活分组）后重新进 schema；空计划 no-op；缺失工具 fail-safe。（`DeferredToolGateTest` 6 例 + `DeferredSampleTools` 夹具）

## 5. tool_search 工具 + 风险分级

- [x] 5.1 `ToolSearchTool`（`@Tool("tool_search")`，只读）：`query→registry.search→对每个匹配 reveal→拼 名称+描述+已启用 文本`；空/无匹配→提示 + 可搜索工具名清单；try/catch→`ToolErrors.message`。
- [x] 5.2 `ToolRiskClassifier` 默认表加 `tool_search → READ_ONLY`。
- [x] 5.3 单测：命中→返回名称+描述且 reveal 被调用；空/无匹配→提示且 reveal 未调用；空登记表→提示；异常→{"error"}；MAX_RESULTS 限制；`classify("tool_search")==READ_ONLY`。（`ToolSearchToolTest` 6 例、扩充 `ToolRiskClassifierTest`）

## 6. McpManager 可选分组（pig-agent-mcp）

- [x] 6.1 `McpManager` 加可选分组函数（默认 null → 零行为变化）+ `managedToolGroups()`；`attach` 经 `registerClient(...)`：分组函数非空时确保 `mcp:<server>` 分组存在（active）+ 经 `registration().mcpClient(client).group(g).apply()` 注册 + 记录分组名；否则走旧 `registerMcpClient(...).block(TIMEOUT)`。
- [x] 6.2 单测：默认（不注入分组函数）路径 `managedToolGroups()` 为空、既有 `McpManagerTest` 不回归；注入分组函数后无连接时仍空（连接路径依赖真实 MCP，留手动冒烟）。（扩充 `McpManagerTest`）

## 7. 接线（pig-agent-cli `AgentBootstrap`）

- [x] 7.1 读 `tools.deferred`；启用时：建 registry + reveal lambda → 在 `ToolContractGuard.install` 前注册 `tool_search` → 给 `McpManager` 注入 `name->"mcp:"+name` → MCP attach 后据 `toolkit.getToolSchemas()`+`managedToolGroups()`+`getToolGroup().getTools()` 构 `List<ToolInfo>`（`buildToolInventory`）→ planner→gate（填充共享 registry）。未启用整段跳过。

## 8. 验收

- [x] 8.1 `mvn -q -T 1 test` 单线程全绿：815 测试 0 失败 0 错误 2 跳过（既有 IT 跳过）；`McpManagerTest`/`ToolAvailabilityGateTest`/`ToolContractGuardTest`/`ToolRiskClassifierTest` 不回归。
- [x] 8.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 8.3 `CLAUDE.md` 增补「Deferred tools + tool_search」段落。
