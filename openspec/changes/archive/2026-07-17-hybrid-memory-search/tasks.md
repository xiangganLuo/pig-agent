## 0. Spike（阻塞前置 — 已完成，结论落 design.md §Spike）

- [x] 0.1 嵌入模型可用性：核验 AgentScope 2.0 core 无 embedding Model API、pig 模型层仅 chat、Doubao `/embeddings` 仅 live HTTP → 向量层置 `Embedder` seam（fake 离线 / 真实延后 IT）。
- [x] 0.2 向量库（Windows）：核验 `sqlite-vec` 本地不存在（仅 `sqlite-jdbc`）、原生加载离线不可行 → 拒绝 sqlite-vec，改纯 Java 内存暴力 cosine（`VectorStore` seam）。
- [x] 0.3 OD7 定案：本地纯 Java 后端（离线全绿）；结论 + 理由记 `design.md §Spike`。

## 1. 语料加载（`pig-agent-core.memory.search`）

- [x] 1.1 `MemoryDocument`（record：`id`/`sourceLabel`/`text`）——一段记忆语料 chunk。
- [x] 1.2 `Tokenizer`：latin lowercased word token + CJK 逐字 unigram + 相邻 bigram（BM25/向量共用）。
- [x] 1.3 `MemoryCorpusLoader`：读 `MEMORY.md` + `memory/*.md` + `USER.md`，按 Markdown 标题/空行分块（单块字符上限），容错（缺失/不可读→跳过，不抛）。
- [x] 1.4 单测：`TokenizerTest`（latin/CJK unigram+bigram）、`MemoryCorpusLoaderTest`（覆盖三类文件、缺失容错、分块）。

## 2. BM25 + 向量后端（`pig-agent-core.memory.search`）

- [x] 2.1 `Bm25Index`：Okapi BM25（`k1=1.2`/`b=0.75`），`index(docs)` + `score(query) → Map<docId,double>`；纯 Java、确定性。
- [x] 2.2 `Embedder`（`@FunctionalInterface float[] embed(String)`）+ `DeterministicEmbedder`（token 哈希 + tf 加权 + L2 归一化，离线确定性）。
- [x] 2.3 `VectorStore`（seam：`upsert(id,vec)` + `search(queryVec,k) → List<Scored>`）+ `InMemoryVectorStore`（暴力 cosine，L2 归一化）。
- [x] 2.4 单测：`Bm25IndexTest`（相关文档排前、TF 饱和、CJK 命中）、`DeterministicEmbedderTest`（确定性/维度/归一化）、`InMemoryVectorStoreTest`（cosine 排序、空/topK）。

## 3. 真实嵌入器（延后 live 验证，纯逻辑离线测）

- [x] 3.1 `OpenAiCompatibleEmbedder implements Embedder`：POST `{baseUrl}/embeddings`（`model`/`input`），解析 `data[0].embedding` → `float[]` + L2 归一化；网络置可注入 `HttpJson` seam；构造/解析纯逻辑离线可测；`Authorization: Bearer <key>` 不回显。
- [x] 3.2 单测：`OpenAiCompatibleEmbedderTest`（请求体构造 + canned JSON 响应解析 + 归一化，fake `HttpJson`，无网络）。

## 4. 混合排序 + 编排 + 配置

- [x] 4.1 `HybridRanker`：min-max 归一化各成分 → `bm25Weight·bm25 + vectorWeight·cos` → 去重（按 docId）→ `min-score` 过滤 → top-K；纯函数。
- [x] 4.2 `MemorySearchConfig`（core 不可变值对象：weights/candidateMultiplier/minScore/topK/rebuildThrottle；非法值 clamp）。
- [x] 4.3 `MemorySearchIndex`：懒/增量构建（mtime 快照 + 节流重建）；`search(query, topK)` → BM25 + （嵌入器存在则）向量 → `HybridRanker` → `List<MemoryDocument>`；嵌入器 null → BM25-only。
- [x] 4.4 `pig-agent-config`：`MemoryConfig` 内嵌 `SearchConfig`（`hybrid-enabled` 默认 **false**、`bm25-weight` 0.7、`vector-weight` 0.3、`embedder-model-id` 空、`candidate-multiplier` 4、`min-score` 0.0、`top-k` 8、`rebuild-throttle-seconds` 5）；getter/setter null-tolerant。
- [x] 4.5 单测：`HybridRankerTest`（0.7/0.3 融合、去重、topK、归一化、BM25-only 降级）、`MemorySearchIndexTest`（混合 fake 嵌入器融合、BM25-only、节流/mtime 重建、topK、覆盖三文件）、`MemorySearchConfigTest`（config 默认/YAML/clamp）。

## 5. 工具 + 接线（`pig-agent-tools` + `pig-agent-cli`）

- [x] 5.1 `HybridMemorySearchTool`（`@Tool memory_search(query, [limit])` → 索引 → 格式化 Markdown 片段；空 query/失败 → `{"error"}`；不回显凭据）。
- [x] 5.2 `PigAgent.Builder.build()`：`memoryConfig != null` 且 toolkit 已含 `memory_search` → 追加 `hb.disableMemoryTools()`（保留 flush/consolidation hooks，取代搜索工具）。
- [x] 5.3 `AgentBootstrap`：`memory.search.hybrid-enabled` 时建 `MemorySearchIndex`（语料 = workspace `MEMORY.md`/`memory/`/`USER.md`；`InMemoryVectorStore`；`embedder-model-id` → `resolveStoredModel` → `OpenAiCompatibleEmbedder` 或 null → BM25-only）+ 显式注册 pig `memory_search` 工具（在 `ToolContractGuard.install` 前）；默认关 → 不注册。
- [x] 5.4 单测：`HybridMemorySearchToolTest`（返回片段、空 query `{"error"}`、fake 索引、限制条数）、`ToolRiskClassifierTest` 确认 `memory_search`=READ_ONLY（已存在）。

## 6. 集成测试（真模型 `*IT`，外环）

- [ ] 6.1 `HybridMemorySearchIT`（真嵌入模型）：`OpenAiCompatibleEmbedder` 对真实 `/embeddings` 返回向量，混合检索对「换个说法」的查询召回相关记忆；断言召回质量（延后 `/ls:itest`）。

## 7. 验收 + 文档

- [x] 7.1 `mvn -q test` whole reactor 全绿（报告数目 + 新增测试数）。
- [x] 7.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 7.3 `CLAUDE.md` 记忆段落新增「混合检索 BM25+向量」说明 + 配置段 `memory.search` 同步。
- [x] 7.4 `openspec validate hybrid-memory-search --strict` 通过；归档时（`/ls:archive`）同步主 spec → `openspec/specs/hybrid-memory-search/`。
