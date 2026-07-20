## 1. Spike —— 确认共享检索原语可独立索引工具元数据（承重，前置）

- [ ] 1.1 写一个小离线验证：构造若干 `ToolDocument(id=工具名, text=名+描述+关键词) implements io.pigagent.core.search.SearchDocument`，用 `Bm25Index.index(List.of(...))` + `bm25.score(query)` 打分，断言"与 query 更相关的工具得分更高"。证明原语脱离记忆语料可用。
- [ ] 1.2 验证 CJK 命中：一个描述为中文的 `ToolDocument`，以对应中文关键词 `score(...)`，断言其得分高于无关工具（`Tokenizer` unigram+bigram 生效）。
- [ ] 1.3 验证经 `HybridRanker.rank(bm25Map, Map.of(), bm25Weight, 0, minScore, topK)`（空向量图 → BM25-only 降级）得到归一化 + top-K 的排序，与直接按 BM25 分排序一致。
- [ ] 1.4 记录 spike 结论（design.md「Spike」已含证据：`Bm25Index.index(List<? extends SearchDocument>)` 泛型化 + 既有 `Bm25IndexTest.indexesAnyCustomSearchDocumentImplementation`）；确认走主路径、无需回退。

## 2. `tool_search`/登记表排序换 hybrid（同源复用 R0，`pig-agent-tools`）

- [ ] 2.1 在 `io.pigagent.tool.deferred` 新增内部 `ToolDocument implements io.pigagent.core.search.SearchDocument`（`id()`=工具名、`text()`=名+描述+关键词拼接）；仅 import `io.pigagent.core.search`，不牵扯 `io.pigagent.core.memory.search` 记忆域。
- [ ] 2.2 `DeferredToolRegistry.search(query, limit)` 改为：对"仍延迟"集合建 `Bm25Index`（懒建/按集合变更重建），`bm25.score(query)` → `HybridRanker.rank(bm25Map, 空向量图, bm25Weight, 0, minScore, limit)` 取 top-K，按结果 `id` 回映为 `DeferredTool`。删除朴素 `score(...)` 计分。
- [ ] 2.3 保持既有语义：空/空白 query 返回空表（调用方 `ToolSearchTool` 出提示）；揭示后经 `markRevealed` 移出"仍延迟"集合 → 不再被搜为待发现；线程安全（`search` 与揭示并发）不回退。
- [ ] 2.4 `ToolSearchTool` 的 `@Tool` 名/签名/入参/无匹配提示/`ToolErrors` 容错**逐字不变**；`ToolRiskClassifier` 中 `tool_search` 仍为 `READ_ONLY`（不改）。
- [ ] 2.5 单测：更相关工具排前、中文关键词命中中文描述、无匹配→提示不报错、揭示后不再被搜、并发搜索/揭示不崩。
- [ ] 2.6 编译 `pig-agent-tools`：`mvn -pl pig-agent-tools -am compile` 绿。

## 3. 默认「随规模智能开」+ backward-safe 逐字节无感（`pig-agent-config` + `pig-agent-cli`）

- [ ] 3.1 `PigAgentConfig.DeferredToolsConfig`：`enabled` 默认 `false → true`（`tools`/`auto-defer-mcp`/`threshold` 默认不变）；javadoc 更新为「默认随规模智能开：超阈值才延迟 MCP 工具；计划为空时逐字节无感；`enabled=false` 完全禁用」。
- [ ] 3.2 `AgentBootstrap` 接线调整：启用时仍在 contract-guard 前注册 `tool_search`（保持被 guard 包裹）+ 设 MCP 分组 namer（MCP 工具分入 `mcp:<server>` **active** 组，schema-neutral）；MCP attach 后算 `DeferralPlan`。
- [ ] 3.3 backward-safe 命门：当 `DeferralPlan` 为空时 `removeTool("tool_search")`、不停用任何组、不隐藏任何工具 → 初始 schema 与 `enabled=false` 逐字节等价；计划非空时保留 `tool_search` + 停用被延迟工具的组（今日行为）。
- [ ] 3.4 单测：默认配置 + 工具 ≤ 阈值 + 无显式清单 → 最终 schema 无 `tool_search`、无隐藏工具（与 `enabled=false` 逐字节等价，回归护栏）；工具 > 阈值 → MCP 工具移出初始 schema + `tool_search` 在。
- [ ] 3.5 `DeferredToolPlanner` 纯函数单测补充：`enabled=true` 超阈值→延迟 MCP、未超→空计划、`enabled=false`→空计划（并集逻辑不变）。

## 4. 揭示状态跨 Toolkit.copy() 一致（承重修复）

- [ ] 4.1 前置 spike 定形：核实 `Toolkit.copy()` 的分组 active 态复制语义 + `ToolSearchTool`/`DeferredToolGate.reveal` 闭包捕获的是原始 toolkit（复现 bug）；在 design.md D5 的三条候选机制中择一（倾向"揭示对发起 agent 运行的 toolkit 生效"）。
- [ ] 4.2 实施修复：使 `tool_search` 揭示作用于该 agent 实际运行的 toolkit 实例（peer 经 `AgentWiring.toolkitFor` copy、子agent 经 `PigAgent.childToolkit` copy 且含该延迟工具时）。
- [ ] 4.3 单测：peer 实例（白名单含某延迟工具、经 copy）揭示后该工具在该 peer 可见可调用；子agent 实例（继承全部工具、经 copy）揭示后该工具在该子agent 可见可调用；且揭示不静默丢失到原始实例。
- [ ] 4.4 回归：peer 空白名单（返回共享 `full`、无 copy）路径行为不变；揭示不破坏 MCP 工具的 `mcpClientName`/热移除。

## 5. 验收（离线，硬约束=全绿）

- [ ] 5.1 `mvn -pl pig-agent-tools -am test` + `mvn -pl pig-agent-cli -am test` 全绿：新增排序/默认/揭示用例通过。
- [ ] 5.2 现有 `deferred-tools`（planner/gate/registry/tool_search）与 `shared-retrieval`（`Bm25Index`/`HybridRanker`/`Tokenizer`）全部单测保持绿——排序升级/默认调整未破坏既有契约。
- [ ] 5.3 `mvn -pl pig-agent-cli -am compile` 绿（覆盖下游 import）；`mvn test` 全量绿。
- [ ] 5.4 `CLAUDE.md`「Deferred tools + tool_search」段落更新：排序同源复用 `io.pigagent.core.search`、默认随规模智能开（阈值制 backward-safe）、揭示状态跨 `Toolkit.copy()` 一致；移除「evaluated once at bootstrap ... don't share reveal state」中已修复的那条限制表述。
