## Context

Wave-2 记忆检索的两块基座均已在 main：

- **E0（`embedding-model-layer`）**：`StoredModel.kind{CHAT,EMBEDDING}` + `ModelStore.getDefaultEmbeddingModelId`（独立于默认 chat 指针）+ `ModelManager.test` 按 kind 分派 `/embeddings` 探针 + `AgentBootstrap.resolveEmbedder(embedderModelId, modelManager)`（约 `AgentBootstrap.java:969`）——**解析顺序**：config `embedder-model-id` 非空可解析 → 用之；否则 `modelManager.getDefaultEmbeddingModelId()` 可解析 → 用之；否则 `null`（BM25-only）。用精确 `findById` 建 `OpenAiCompatibleEmbedder(baseUrl, apiKey, modelName)`，**不硬要求 `kind==EMBEDDING`**（R5 向后兼容），异常/不可解析 → `null`（容错、不抛、key 不入日志）。E0 spec 的 Non-Goal 明确「**不翻默认检索路径**」。
- **R0（`shared-retrieval`）**：ranker 核心上提到内核共享包 `io.pigagent.core.search`（`SearchDocument`/`Bm25Index`/`HybridRanker`/`Tokenizer`）；记忆域私有类型（`MemoryDocument`/`MemoryCorpusLoader`/`MemorySearchIndex`/`MemorySearchConfig`）与向量/嵌入层（`VectorStore`/`InMemoryVectorStore`/`Embedder`/`DeterministicEmbedder`/`OpenAiCompatibleEmbedder`）留在 `io.pigagent.core.memory.search`。
- **`hybrid-memory-search`（已归档、在 main）**：`MemorySearchIndex` 混合门面（`search(query, topK) → List<MemoryDocument>`，无嵌入器 → BM25-only 优雅降级、永不抛）；`HybridMemorySearchTool`（`@Tool name="memory_search" readOnly=true`）。装配（`AgentBootstrap.java:460`）：`if (searchCfg.isHybridEnabled())` 才 `buildMemorySearchIndex(...)`（内部 `resolveEmbedder`）+ 注册 pig `memory_search`。`PigAgent.Builder` 检测到 toolkit 已含 `memory_search` 即 `disableMemoryTools()`（顶替原生搜索工具，保留 flush/consolidation hooks）。config `SearchConfig.hybridEnabled` 是 `boolean`，默认 `false`。
- **M-A（`memory-retrieval-injection`，已归档、在 main）**：`buildMemoryInjection` **也调 `resolveEmbedder`** 建一个 `MemorySearchIndex`，其 query-aware 注入经 `MemorySearchIndex.search` 门面消费检索——**没有独立 `hybrid-enabled` 门**，只被 `memory.injection.enabled` 门控，启用时「有嵌入器就走向量、否则 BM25」。

**痛点（M-B 要修的不对称）**：M-A 的注入路径**已经**「配了嵌入器就走混合」（无独立开关），但 `hybrid-memory-search` 的 **`memory_search` 工具路径**却卡在「显式 `hybrid-enabled=true`」——用户配好嵌入模型，检索工具仍是原生纯关键词。M-B 让工具路径与 M-A 对齐：**配了嵌入模型即默认走混合**。

## Goals / Non-Goals

**Goals**
- 把 `hybrid-memory-search` 的生效从「显式 config」升级为「**配了嵌入模型（E0 `resolveEmbedder` 解析出可用嵌入器）即默认开混合**」；显式 `hybrid-enabled` 仍可覆盖。
- **无嵌入模型 → BM25-only / 原生关键词检索**（今日行为，逐字节零回归）。
- 明确「混合顶替原生记忆工具」的取舍现在会**默认触发**，给出保留说明与逃生舱。
- 真实 `/embeddings` 往返落地为 `*IT`（延后 `/ls:itest`）；离线覆盖请求/响应纯逻辑（维度/L2 归一化/错误分类）。

**Non-Goals**
- 改 E0 `resolveEmbedder` 的解析顺序、`StoredModel.kind`/store 指针、`/model`/onboarding UX（E0 已交付，M-B 只消费）。
- 改 `MemorySearchIndex`/`Bm25Index`/`HybridRanker`/`InMemoryVectorStore`/`OpenAiCompatibleEmbedder` 的检索/嵌入**逻辑**（复用不改；仅可能补离线测试与 hybrid 装配的 embedder 复用）。
- 改 M-A（`memory-retrieval-injection`）代码——M-A 经同一门面 + 同一 `resolveEmbedder` **自动受益**（见 §交叉依赖）。
- 真嵌入质量 / 真 `/embeddings` 网络往返的离线验证（属 live-model，交 `/ls:itest`）。
- 为 anthropic/gemini/ollama/dashscope 各自原生嵌入端点（限 OpenAI 兼容 `/embeddings`，沿用 E0 D2 范围）。
- 多嵌入模型运行时热切换 / 混合权重按 query 动态调（YAGNI）。

## Spike（承重 — 离线可证 vs live 延后，结论落此，不过不进编码）

> **承重问题**：「配了嵌入模型即默认 hybrid、否则 BM25-only」这条派生 + 装配，能否**离线确定性验证**（不碰真网络）？以及真实嵌入往返为何必须延后。

**S1 — 派生 + 装配离线可证（主证据）**
- E0 `resolveEmbedder` 返回一个 `Embedder`（可注入 `DeterministicEmbedder` 或 fake）或 `null`——**是否解析出嵌入器与真网络无关**（`DeterministicEmbedder` = token 哈希 + tf 加权 + L2 归一化，纯本地确定性）。
- effective-hybrid 是**纯函数**：`effective = explicitOverride 若已设，否则 (embedder != null)`——无 I/O、确定性。
- `MemorySearchIndex.vectorEnabled()` 已公开（返回 `embedder != null`），且 `HybridRanker` 的混合融合用 fake 嵌入器已被 `hybrid-memory-search` 全离线覆盖。
- **结论**：「配了嵌入模型即默认 hybrid、否则 BM25-only、显式覆盖」**全部离线确定性可测**——用 `DeterministicEmbedder`/fake `Embedder`（非 null）与 `null` 驱动派生，断言：注册/不注册 pig `memory_search`、`vectorEnabled()` 真/假、混合 vs BM25-only 排序。**这是 tasks 第 1 组的承重内容。**

**S2 — 真实嵌入往返为何延后**
- `OpenAiCompatibleEmbedder` 的裸 `/embeddings` 网络往返离线不可验（OD7 既有结论：AgentScope 2.0 无 embedding Model API，任何嵌入调用是 live HTTP）。其**纯逻辑**（`buildRequestBody`/`parseEmbedding`/`embeddingsUrl` + `Vectors.l2normalize`）已可经注入 `HttpPost` seam 喂 canned JSON 离线测——M-B **补齐维度/归一化/错误分类三类断言**（若既有覆盖不足）。
- 真实 `/embeddings` 网络往返 + 真嵌入**召回质量** → `*IT`（`/ls:itest`）。**诚实延后**：内环默认模型此前对 `/embeddings` 返回 403，真机验证须在配好可用嵌入端点的凭据后由外环跑。

**Spike 净结论**：走**主路径**——派生 + 装配 + 顶替取舍全离线确定性可测（fake/`DeterministicEmbedder`）；唯一延后件 = 真实嵌入器的 live 网络往返 + 召回质量（`/ls:itest`），其纯逻辑离线已/将覆盖。**不 ship 未测的默认翻转**：派生逻辑离线全测，真网络那一段本就无法离线验、且不影响「无嵌入器→零回归」的默认安全性。

## Decisions

- **D1 —（承重）`hybrid-enabled` 升级为三态，默认 auto=派生自嵌入器存在。** 推荐 config 建模为**可空 `Boolean`**（`memory.search.hybrid-enabled`）：**缺字段 → `null`=auto**（Jackson 缺省即 null）；**显式 `true`/`false` → 原样读回**（向后兼容——既有配置里写死的 `true`/`false` 语义不变：`true`=强制启用、`false`=强制关闭）。effective-hybrid = 纯函数 `explicitOverride != null ? explicitOverride : embedderPresent`。**备选**：保留 `boolean hybrid-enabled` + 新增 `hybrid-mode: auto|on|off` 枚举——**否决**（两字段语义重叠易冲突；且枚举无法无缝读回老 `true`/`false` 布尔值，需自定义反序列化）。可空 `Boolean` 一字段同时表达「未设/显式真/显式假」且原样读回老值，最简。（getter 由 `isHybridEnabled():boolean` 调整为可空访问 + 一个派生辅助；具体签名以编码期最小改动为准，spec 只约束**可观察行为**。）
- **D2 — 装配点：解析一次嵌入器 → 纯派生 → 注册 + 复用嵌入器。** `AgentBootstrap`（约 `:460`）改为：先 `Embedder embedder = resolveEmbedder(searchCfg.getEmbedderModelId(), modelManager)`（E0，**解析一次**）→ `boolean effectiveHybrid = derive(searchCfg.hybridOverride(), embedder != null)` → `if (effectiveHybrid)` 时把**已解析的 `embedder`** 传入 `buildMemorySearchIndex(...)`（重载/传参，避免二次 `resolveEmbedder`）+ 注册 `HybridMemorySearchTool`。**备选**：装配点各自再调 `resolveEmbedder`——否决（二次解析、二次 warn 日志、易漂移）。
- **D3 — 顶替原生记忆工具的取舍现默认触发（记 R2），机制不变、逃生舱明确。** 混合启用（现含默认路径）→ `PigAgent.Builder` 检测 toolkit 内 `memory_search` → `disableMemoryTools()`（沿用 `hybrid-memory-search` 的自协调，**无新 builder 参数**）→ 关闭 `memory_get`/`memory_save`/`session_search`（含 `session_list`/`session_history`）。**保留**：flush/consolidation **hooks** 仍开（`MEMORY.md`/日志层照写、durable 事实自动落盘）——检索质量升、写路径不损。**取舍权衡**：`memory_save` 被 flush 自动落盘覆盖；`memory_get`/`session_*` 为次要工具；召回质量收益 > 这三者暴露。**逃生舱**：显式 `hybrid-enabled: false` 恢复原生纯关键词 `memory_search` + 全部原生记忆工具，供需要 `session_search` 的用户选择。
- **D4 — 真实嵌入往返延后 IT，离线补齐维度/归一化/错误分类。** `OpenAiCompatibleEmbedder` 逻辑不改；离线单测（注入 `HttpPost` 喂 canned JSON）MUST 覆盖：请求体 `{model,input}` + `/embeddings` URL 拼接；`data[0].embedding` 解析为 `float[]`、**维度**与响应向量长度一致、**L2 归一化**后模长≈1；**错误分类**：无 `data`/空向量/非 2xx → 抛携带类型（不含凭据）异常 → `MemorySearchIndex` 捕获降级 BM25-only。真网络往返 + 召回质量 → `*IT`。
- **D5 — 默认安全 / 零回归。** 无嵌入器（无 `kind=EMBEDDING` 条目、无默认嵌入指针、`embedder-model-id` 空）且 `hybrid-enabled` 未设 → effective-hybrid=false → 不注册 pig `memory_search` → 原生纯关键词检索原封不动，逐字节等于 M-B 引入前。既有显式 `hybrid-enabled: true`（无嵌入器）→ 仍注册工具、混合退化 BM25-only 排序（与 `hybrid-memory-search` 引入时的显式启用行为一致）。
- **D6 — 与 M-A 无代码耦合（记 R3）。** M-A `buildMemoryInjection` 已调同一 `resolveEmbedder` + 同一 `MemorySearchIndex.search` 门面 → 配了嵌入模型时其 query-aware 注入**已自动走混合**，M-B **不改 M-A**；只在文档/design 登记这层「同门面自动受益」的关系。

## Architecture

```
memory.search.hybrid-enabled  ∈ { 未设=auto | true=强制 | false=强制关 }   ← pig-agent-config（可空 Boolean）
        │  AgentBootstrap（约 :460）
        ▼
Embedder embedder = resolveEmbedder(cfg.embedderModelId, modelManager)   ← E0（解析一次：config id → store 默认嵌入 → null）
        │
        ▼
boolean effectiveHybrid = (override != null) ? override : (embedder != null)   ← 纯函数、确定性、离线可证
        │
   ┌────┴─────────────────────────┐
  true                          false
   │                              │
   ▼                              ▼
buildMemorySearchIndex(cfg, ..., embedder)     原生纯关键词 memory_search（今日行为，零回归）
 + toolkit.register(HybridMemorySearchTool)     全部原生记忆工具保留
   │
   ▼
PigAgent.Builder: toolkit 含 memory_search → disableMemoryTools()
   （原生 flush/consolidation HOOKS 保留 → MEMORY.md 照写；memory_get/memory_save/session_search 顶替下线）

—— M-A（memory-retrieval-injection，独立开关 memory.injection.enabled）——
buildMemoryInjection 亦调同一 resolveEmbedder + MemorySearchIndex.search 门面
   → 配了嵌入模型时其 query-aware 注入自动走混合（同门面受益，M-B 不改 M-A）
```

## Risks / Trade-offs

- **R1 —（承重）派生 + 默认翻转的正确性**：翻错默认会让「无嵌入模型的用户」意外改变检索行为。→ D1/D5：派生纯函数、`null`=auto、无嵌入器→false→零回归；Spike S1 全离线确定性测（fake/`DeterministicEmbedder` 与 null 双路径）。真网络那段不参与默认安全性判定。
- **R2 —（默认触发的取舍）顶替原生记忆工具现默认发生**：配了嵌入模型的用户默认丢 `memory_get`/`memory_save`/`session_search`。→ D3：flush hooks 保留（写路径不损）；`memory_save` 被自动落盘覆盖、其余为次要工具；**逃生舱 `hybrid-enabled: false`** 恢复全部原生工具。spec 显式登记「默认触发」+ 保留说明 + 逃生舱。
- **R3 —（交叉依赖，供给/受益）E0 供给、M-B 翻默认、M-A 自动受益**：三者边界须清。→ E0 只解析出 `Embedder` 不翻默认（E0 spec Non-Goal）；M-B 翻 `hybrid-memory-search` 工具路径默认；M-A 经同一门面已「配嵌入即混合」，M-B **不改 M-A**。若同期落地，**E0 先合**、M-B rebase 到 E0 的 `resolveEmbedder`/store。
- **R4 — 真嵌入质量 / 真 `/embeddings` 往返未离线验**：→ D4 延后 `*IT`（`/ls:itest`）；离线覆盖请求/响应纯逻辑（维度/归一化/错误分类）；默认无嵌入器→BM25-only，故未验证的真网络路径不进「无嵌入模型」用户的默认行为。**诚实延后**：默认模型此前 `/embeddings` 403。
- **R5 — 向后兼容：既有显式 `hybrid-enabled` 值**：老配置里写死 `true`/`false` 的用户语义 MUST 不变。→ D1 可空 `Boolean` 原样读回 `true`/`false`；仅「缺字段」升级为 auto（派生）。
- **R6 — 历史用法：`embedder-model-id` 指向 chat 模型**：E0 `resolveEmbedder` 不硬要求 `kind==EMBEDDING`。→ M-B 复用 E0 该宽松语义：指向 chat 模型的 `embedder-model-id` 仍解析出嵌入器 → 触发默认混合（凭据形状相同，行为一致）；不额外收紧。
- **R7 — 与工具体系边界**：`memory_search`=READ_ONLY 已在 `ToolRiskClassifier`（`hybrid-memory-search` 引入）；M-B 不新增工具、不改 risk 分类；嵌入模型是模型层实体、非工具（沿用 E0 R4）。

## 交叉依赖（显式登记）

- **建在 E0（已合并）上**：M-B **消费** E0 `resolveEmbedder`。**边界（谁翻默认）**：E0 只**供给**解析出的 `Embedder`、**不翻默认**（其 spec Non-Goal + 「本能力不翻默认检索路径」场景）；**M-B 才翻默认**——把「配了嵌入模型」这一信号接成 `hybrid-memory-search` 的默认启用。E0 主 spec 因此**不被 M-B 改写**（保持诚实：E0 供给、M-B 翻默认）。同期落地则 E0 先合，M-B rebase。
- **建在 R0（已合并）上**：混合排序核心（`Bm25Index`/`HybridRanker`/`Tokenizer`）已在 `io.pigagent.core.search`；M-B 不动 ranker 核心，只改「何时启用混合」的装配与 config。
- **与 M-A（检索即注入，已合并）**：M-A 的 query-aware 注入经 `MemorySearchIndex.search` **门面**消费检索结果，且 `buildMemoryInjection` 已调**同一** `resolveEmbedder`。故 M-B 升级 hybrid 后，**M-A 自动受益**（配了嵌入模型 → M-A 注入的 top-K 也走混合排序）——**同一门面、无需改 M-A**。M-B 仅在此登记该受益关系。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 工具路径卡在显式开关、配了嵌入模型仍原生关键词（不对称） | Context 痛点；Goals；D1/D2 | 落 tasks 2/3 |
| 承重 spike：派生+装配能否离线确定性验、真往返为何延后 | Spike S1/S2 + 净结论；D1/D4 | 落 tasks 1（spike，已定论） |
| `hybrid-enabled` 升三态（auto 派生 / 显式覆盖），可空 Boolean | D1；R5 | 落 tasks 2 |
| 装配：解析一次嵌入器 → 纯派生 → 注册 + 复用嵌入器 | D2 | 落 tasks 3 |
| 配了嵌入模型即默认混合、否则 BM25-only 零回归 | D5；Spike S1 | 落 tasks 3；断言字节等价 tasks 3 |
| 显式 `false` 覆盖派生 / 显式 `true` 无嵌入器退化 BM25-only | D1/D5 | 落 tasks 2/3 |
| 顶替原生记忆工具取舍现默认触发 + 保留说明 + 逃生舱 | D3；R2 | 落 tasks 3（装配）+ spec 登记 |
| 真实嵌入往返离线覆盖（维度/L2 归一化/错误分类） | D4；Spike S2 | 落 tasks 4 |
| 真 `/embeddings` live 往返 + 召回质量延后 IT | D4；R4 | 落 tasks 5（IT，延后） |
| E0 供给、M-B 翻默认、E0 spec 不被改写 | 交叉依赖；R3 | 已登记（边界） |
| M-A 经同一门面自动受益、不改 M-A | 交叉依赖；D6；R3 | 已登记（受益关系） |
| `embedder-model-id` 指向 chat 模型仍触发（沿用 E0 宽松） | R6 | 已登记（向后兼容） |
| 与 `ToolRiskClassifier`/工具体系无关（模型层非工具） | R7 | 已登记（边界） |
