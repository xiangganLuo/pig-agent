## Why

Wave-2 的 **E0（嵌入模型层，已合并 main）** 已把「嵌入模型」升为模型层一等公民——`StoredModel.kind{CHAT,EMBEDDING}` + `ModelStore.getDefaultEmbeddingModelId` + `ModelManager.test` 按 kind 分派 `/embeddings` 探针 + `AgentBootstrap.resolveEmbedder`（解析顺序：config `embedder-model-id` → store 默认嵌入模型 → `null`）。但 E0 **只供给不翻默认**：即使用户配好了嵌入模型，记忆检索仍停在 **BM25-only / 原生纯关键词检索**——因为 `hybrid-memory-search` 的 pig `memory_search` 工具目前仅在**显式** `memory.search.hybrid-enabled=true` 时才注册。用户配了嵌入模型却拿不到语义召回，是一层白铺的基座。

本 spec 是内核路线图 **Wave-2 的 M-B（真嵌入 + 混合默认路径）**，**建在 E0 之上**：把 `hybrid-memory-search` 的生效从「显式 config」升级为「**配了嵌入模型即默认开混合**」（显式 config 仍可覆盖），并把真实 `/embeddings` 往返落地为延后的 `*IT`。**谁翻默认的边界**：E0 只提供解析出的 `Embedder`，**M-B 才翻默认检索路径**。

## What Changes

- **配置了嵌入模型时默认开混合**：把 `memory.search.hybrid-enabled` 从二态 `boolean`（默认 false）升级为**三态**——**未设（默认 auto）= 由 E0 `resolveEmbedder` 是否解析出可用嵌入器派生**（解析出 → 默认注册 pig `memory_search` 走混合 BM25+向量；未解析出 → BM25-only / 原生关键词检索，逐字节零回归）；**显式 `true`** = 强制启用（无嵌入器则退化 BM25-only 排序）；**显式 `false`** = 强制关闭（走原生检索）。`AgentBootstrap` **解析一次嵌入器**，据此计算 effective-hybrid，为真时注册 `HybridMemorySearchTool` 并把已解析的嵌入器复用于索引构建（不重复解析）。派生决策为纯逻辑、确定性，离线（fake/确定性嵌入器）可证。
- **处理 hybrid 顶替原生的取舍现默认触发**：混合路径注册 pig `memory_search` 时 `PigAgent.Builder.disableMemoryTools()` 顶替原生 → 丢 `memory_get`/`memory_save`/`session_search`（含 `session_list`/`session_history`）。M-B 让这个（`hybrid-memory-search` 已文档化的 off-by-default）取舍在「配了嵌入模型」时**默认发生**。保留说明：原生 flush/consolidation **hooks** 仍保留（`MEMORY.md`/日志层照常写、durable 事实自动落盘），检索质量提升而写路径不受损；逃生舱：显式 `hybrid-enabled: false` 恢复原生关键词检索 + 全部原生记忆工具。
- **真实 `/embeddings` 往返落地**：`OpenAiCompatibleEmbedder` 的真实网络往返做成 `*IT`（真模型，延后 `/ls:itest`——默认模型此前 403，属诚实延后）；离线仍覆盖请求/响应纯逻辑（请求体/URL 构造、`data[0].embedding` 解析 + **维度/L2 归一化**、**错误分类**：无 data / 空向量 / 非 2xx）。
- **config `memory.search` 默认值调整**：`hybrid-enabled` 默认语义由「关」改为「auto（派生）」；`bm25-weight`/`vector-weight`/`candidate-multiplier`/`min-score`/`top-k`/`rebuild-throttle-seconds` 不变。既有显式 `true`/`false` 读回语义不变（向后兼容）。

**非破坏边界**：**无可用嵌入器且未显式启用**（无 `kind=EMBEDDING` 条目、无默认嵌入指针、config `embedder-model-id` 空、`hybrid-enabled` 未设）→ 不注册 pig `memory_search`、原生纯关键词检索原封不动 = 逐字节等于 M-B 引入前。翻默认仅由「用户配了嵌入模型」这一显式信号触发。

## Capabilities

### New Capabilities
<!-- 无新增能力。M-B 是既有 hybrid-memory-search 能力之上的默认路径翻转，不引入新的检索/工具契约。 -->

### Modified Capabilities
- `hybrid-memory-search`: 混合检索的**生效开关**由「显式 config」升级为「配了嵌入模型即默认开」（三态 `hybrid-enabled`：auto 派生 / 显式覆盖）；补充「顶替原生记忆工具的取舍现默认触发 + 逃生舱」与「真实嵌入器往返离线覆盖 + live 延后」两条要求。检索算法/权重/分词/降级/`memory_search` `@Tool` 名与签名不变。

<!-- 不改写 embedding-model-layer（E0 主 spec 保持「只供给不翻默认」的诚实表述——翻默认是 M-B 的职责，与 E0 spec 的 Non-Goal 一致）；不改写 memory-retrieval-injection（M-A 经同一 MemorySearchIndex.search 门面 + 同一 resolveEmbedder 消费检索，配了嵌入模型时其 query-aware 注入已自动走混合，M-B 无需改 M-A——见 design 交叉依赖）。 -->

## Impact

- **代码**：
  - `pig-agent-config`：`SearchConfig.hybridEnabled` 由 `boolean`（默认 false）改为**三态**（推荐 `Boolean` 可空：`null`=auto / `true`/`false`=显式，Jackson 缺字段 → null、显式值原样读回；设计见 `design.md` D1）；`isHybridEnabled` 读取点相应改为三态语义（getter 返回可空/枚举，或新增派生辅助）。
  - `pig-agent-cli`：`AgentBootstrap` 的 hybrid 装配（约 `:460`）——先 `resolveEmbedder`（E0，解析一次）→ 纯派生 `effectiveHybrid = explicitOverride 若已设否则 embedderPresent` → 为真时注册 `HybridMemorySearchTool`，并把已解析嵌入器传入 `buildMemorySearchIndex`（避免二次解析）。
  - **不改**：`HybridMemorySearchTool`（工具体不变）；`MemorySearchIndex`/`Bm25Index`/`HybridRanker`/`InMemoryVectorStore`/`OpenAiCompatibleEmbedder` 的检索/嵌入逻辑（复用不改，仅可能补离线测试）；E0 `resolveEmbedder` 的解析顺序；`memory-retrieval-injection`（M-A）代码；`ToolRiskClassifier`（`memory_search`=READ_ONLY 已存在）；权限/沙箱/渠道 fail-closed。
- **测试**：离线单测——**Group 1 承重 spike**（离线可证）：`resolveEmbedder` 解析出（fake/`DeterministicEmbedder`）嵌入器 → 派生激活混合（`MemorySearchIndex.vectorEnabled()` 真、注册工具）；无嵌入器 → BM25-only；显式 `false` 覆盖派生 → 走原生；显式 `true` 无嵌入器 → 混合退化 BM25-only。`OpenAiCompatibleEmbedder` 请求/响应纯逻辑（canned JSON）：请求体/URL、维度/L2 归一化、错误分类（无 data/空向量/非 2xx）。默认无嵌入器 → 不注册工具、逐字节等价。**延后 live**：真 `/embeddings` 网络往返（Doubao/DashScope/OpenAI-compat 至少一家 + 维度/归一化/错误分类）+ 混合召回质量 → `*IT`（`/ls:itest`），本 spec 不跑。
- **文档**：`CLAUDE.md` 记忆检索段落新增「配了嵌入模型即默认走混合（M-B）」+ 顶替原生工具默认触发 + 逃生舱；`memory.search.hybrid-enabled` 三态语义同步。
