## 1. Spike —— 确认共享检索原语可独立索引工具元数据（承重，前置）

- [x] 1.1 小离线验证：`ToolDocument(id=工具名, text=名+描述+关键词) implements SearchDocument` + `Bm25Index.index` + `score(query)` 断言"更相关工具得分更高"。→ `ToolDocumentSpikeTest.bm25IndexesToolDocumentsAndRanksRelevantFirst` 绿。
- [x] 1.2 CJK 命中：中文描述工具以中文关键词 `score(...)` 得分高于无关工具。→ `ToolDocumentSpikeTest.cjkQueryMatchesChineseDescription` 绿。
- [x] 1.3 `HybridRanker.rank(bm25, Map.of(), 1.0, 0, 0, K)`（空向量 → BM25-only 降级）归一化 top-K 与直接 BM25 排序一致。→ `ToolDocumentSpikeTest.hybridRankerBm25OnlyProducesNormalisedTopK` 绿。
- [x] 1.4 记录结论：原语脱离记忆语料可用（`Bm25Index.index(List<? extends SearchDocument>)` 泛型 + 既有 `Bm25IndexTest.indexesAnyCustomSearchDocumentImplementation`）；走主路径，无回退。

## 2. `tool_search`/登记表排序换 hybrid（同源复用 R0，`pig-agent-tools`）

- [x] 2.1 新增内部 `ToolDocument implements io.pigagent.core.search.SearchDocument`（`id`=工具名、`text`=名+描述+关键词）；仅 import `io.pigagent.core.search`。
- [x] 2.2 `DeferredToolRegistry.search` 改为 `Bm25Index.index` → `bm25.score(query)` → `HybridRanker.rank(bm25, 空向量, 1.0, 0, 0, limit)` 取 top-K 回映；删除朴素 `score()`/`Scored`。
- [x] 2.3 既有语义保持：空/空白 query 空表、无 in-vocab 匹配空表（调用方出提示）、揭示后经 `markRevealed` 移出"仍延迟"→不再被搜、`synchronized` 快照后建索引（并发安全）。
- [x] 2.4 `ToolSearchTool` `@Tool`/入参/提示/`ToolErrors` 逐字不变；`ToolRiskClassifier` 中 `tool_search` 仍 `READ_ONLY`（未改）。
- [x] 2.5 单测：更相关排前（既有 `moreRelevantToolRanksHigher` 绿）、中文命中（新增 `chineseKeywordMatchesChineseDescription`）、无匹配→提示（`ToolSearchToolTest` 绿）、揭示后不再被搜（既有绿）。
- [x] 2.6 `mvn -pl pig-agent-tools -am compile` 绿。

## 3. 默认「随规模智能开」+ backward-safe 逐字节无感（`pig-agent-config` + `pig-agent-cli`）

- [x] 3.1 `DeferredToolsConfig.enabled` 默认 `false → true`；javadoc 更新为随规模智能开 + backward-safe + 显式关闭逃生口。
- [x] 3.2 `AgentBootstrap`：启用时仍在 contract-guard 前注册 `tool_search` + 设 MCP 分组 namer（MCP 工具入 active 组，schema-neutral）；MCP attach 后算 `DeferralPlan`。
- [x] 3.3 backward-safe 命门 `AgentBootstrap.applyDeferral`：计划为空 → `removeTool("tool_search")`、不停用任何组 → 初始 schema 与 `enabled=false` 逐字节等价；非空 → `DeferredToolGate.applyTo`。
- [x] 3.4 单测 `AgentBootstrapDeferralTest`：空计划移除 `tool_search` 且 schema 与无特性基线等价；非空计划隐藏被延迟工具、保留 `tool_search`。
- [x] 3.5 `DeferredToolPlannerTest`（既有，逻辑未改）覆盖 enabled 超/未超阈值 + 禁用空计划；`DeferredToolsConfigTest` 加默认智能开 + 显式关闭用例。

## 4. 揭示状态跨 Toolkit.copy() 一致（承重修复）

- [x] 4.1 spike 定形：探针证实 `Toolkit.copy()` 分组 active 态**独立**（base 激活不影响 copy，反之亦然）；旧 `reveal(Toolkit,…)` 绑死单一 toolkit → 复现 bug。采用"广播到所有已注册 toolkit"方案。
- [x] 4.2 实施：新增内核 `io.pigagent.core.tool.RevealTargets`（弱引用集合 + 广播 `activateGroup`）；`DeferredToolGate.reveal(RevealTargets, registry)` 广播变体；`PigAgent.Builder.revealTargets` 让子agent 工厂注册每个 child copy；`AgentFactory`/`AgentInstanceFactory` 透传；`AgentBootstrap` 注册 base + peer copy。
- [x] 4.3 单测：`DeferredToolRevealBroadcastTest`（揭示到达已注册 copy、旧单 toolkit seam 不到达 copy=回归护栏、未注册 copy 不受影响不报错）+ `RevealTargetsTest`（广播/null 安全/去重）。
- [x] 4.4 回归：peer 空白名单返回共享 `full`（同对象，注册无害去重）；揭示仅翻组 flag，MCP `mcpClientName`/热移除不受影响；核心全量 348 测试绿。

## 5. 验收（离线，硬约束=全绿）

- [x] 5.1 `mvn -pl pig-agent-tools -am test` 全绿：**414 tests, 0 失败, 0 错误**（3 pre-existing POSIX skip）。
- [x] 5.2 现有 `deferred-tools` + `shared-retrieval` 单测保持绿：`mvn -pl pig-agent-core test` **348 tests, 0 失败**（含 `Bm25Index`/`HybridRanker`/`Tokenizer`/`AgentFactory`/`AgentInstanceFactory`/`PigAgent`）。
- [x] 5.3 `mvn -pl pig-agent-cli -am compile` 绿（覆盖下游 import）。（全量 `mvn test` 按 coordinator 指示延后到合并/itest 阶段统一做。）
- [x] 5.4 `CLAUDE.md`「Deferred tools + tool_search」段落更新：排序同源复用 `io.pigagent.core.search`、默认随规模智能开（阈值制 backward-safe）、揭示跨 `Toolkit.copy()` 一致（移除旧限制）；模块表补 `ToolDocument`/`RevealTargets`。
