## ADDED Requirements

### Requirement: 通用检索文档契约 SearchDocument

系统 SHALL 提供一个内核级通用接口 `SearchDocument`，作为记忆检索、工具检索、技能匹配三线共享的"可被检索文档"契约。该接口 MUST 只暴露两个访问器：`id()`（全局唯一标识，排序结果按它回映）与 `text()`（参与打分的正文）。域特有字段（例如记忆的 `sourceLabel`）MUST NOT 进入该通用接口，而留在各自的实现类中。既有 `MemoryDocument` MUST 实现 `SearchDocument`，且其 `id`/`text`/`sourceLabel` 字段与语义保持不变。

#### Scenario: MemoryDocument 是 SearchDocument
- **WHEN** 持有一个 `MemoryDocument(id, sourceLabel, text)`
- **THEN** 它是 `SearchDocument` 的实例，经接口取到的 `id()`/`text()` 与其 record 访问器逐字一致，且 `sourceLabel` 仍可从 `MemoryDocument` 取到

#### Scenario: 通用接口不含域特有字段
- **WHEN** 审视 `SearchDocument` 的方法集
- **THEN** 仅有 `id()` 与 `text()`，不含 `sourceLabel` 等任何记忆域字段

### Requirement: BM25 索引消费任意 SearchDocument

通用 `Bm25Index` SHALL 能对**任意** `SearchDocument` 实现（不限于 `MemoryDocument`）建立索引。其索引入口 MUST 接受 `List<? extends SearchDocument>`，以使既有以 `List<MemoryDocument>` 为实参的调用点在不做任何改动的情况下继续通过（Java 泛型不变性下的向后兼容保证）。打分入口 MUST 保持 `score(String query) → Map<String, Double>`（按文档 `id` 计分、与具体文档类型解耦）。索引与打分的排序行为 MUST 与本能力引入前逐字相同（含 CJK 分词命中）。

#### Scenario: 索引自定义 SearchDocument 实现
- **WHEN** 用一批非 `MemoryDocument` 的自定义 `SearchDocument` 实现调用 `Bm25Index.index(...)` 后以某查询打分
- **THEN** 返回按这些文档 `id` 计分的 `Map<String,Double>`，相关文档得分高于无关文档

#### Scenario: 既有 List<MemoryDocument> 调用零改动通过
- **WHEN** 以 `List<MemoryDocument>`（如 `List.of(new MemoryDocument(...))`）调用放宽后的 `index(List<? extends SearchDocument>)`
- **THEN** 编译通过且索引/打分结果与放宽前逐字相同

#### Scenario: 中文查询命中含该名字的文档
- **WHEN** 索引若干中文文档后以一个中文名（如"罗湘赣"）打分
- **THEN** 含该名字的文档得分高于不含者（CJK unigram+bigram 分词行为不变）

### Requirement: 混合 ranker 保持纯泛型且跨线一致

通用 `HybridRanker` SHALL 是与文档类型完全解耦的纯静态函数：`rank(bm25Scores, vectorScores, bm25Weight, vectorWeight, minScore, topK)` 只接受两个 `Map<String, Double>` 并返回按 `id` 排序的结果，MUST NOT 引用任何文档类型。其融合语义（各成分先 min-max 归一化再加权求和、按 `id` 去重、`min-score` 过滤、top-K、空向量→BM25-only 降级）MUST 与本能力引入前逐字相同，从而记忆/工具/技能三线消费同一 ranker 时评分口径一致。

#### Scenario: 归一化加权融合不变
- **WHEN** 以既有权重（如 `0.7/0.3`）对给定的 BM25 与向量分数图调用 `rank(...)`
- **THEN** 输出的融合分数、去重、top-K 与本能力引入前逐字相同

#### Scenario: 无向量分数时降级为 BM25-only
- **WHEN** 向量分数图为空调用 `rank(...)`
- **THEN** 返回仅由归一化后 BM25 决定的排序（不报错）

### Requirement: 通用原语与记忆域的包边界

通用、无记忆语义的 **ranker 核心** SHALL 位于内核共享包 `io.pigagent.core.search`（`SearchDocument`、`Bm25Index`、`HybridRanker`、`Tokenizer`）。记忆线私有的类型（`MemoryDocument`、语料装载器 `MemoryCorpusLoader`、记忆检索门面 `MemorySearchIndex`、记忆检索配置 `MemorySearchConfig`）以及当前仅记忆线消费的向量/嵌入层（`VectorStore`/`InMemoryVectorStore`/`Embedder`/`DeterministicEmbedder`/`OpenAiCompatibleEmbedder`/向量工具）SHALL 留在 `io.pigagent.core.memory.search`——保证既有记忆门面/工具单测逐字不改；向量层的上提待真有跨线消费方时按 YAGNI 再做，届时不影响 ranker 核心契约。工具检索（Tool-OS）与技能匹配（Skills）等后续能力 MUST 能只依赖 `io.pigagent.core.search` 复用 ranker 核心做 BM25 排序，而不牵扯记忆域类型。

#### Scenario: 后续检索线只依赖通用包做 BM25 排序
- **WHEN** 一条新检索线（工具或技能）定义自己的 `SearchDocument` 实现并做 BM25/混合排序
- **THEN** 它只需 import `io.pigagent.core.search` 的 ranker 核心（`SearchDocument`/`Bm25Index`/`HybridRanker`/`Tokenizer`）即可完成 BM25 排序，无需 import 任何 `io.pigagent.core.memory.search` 记忆域类型

#### Scenario: 记忆域类型仍在原包
- **WHEN** 查找 `MemoryDocument` 与记忆检索门面
- **THEN** 它们仍位于 `io.pigagent.core.memory.search`，对外可见性不变

### Requirement: 记忆检索门面契约稳定

记忆检索门面 `MemorySearchIndex` 的公共表面 SHALL 保持稳定：检索方法 MUST 为 `search(String query, int topK) → List<MemoryDocument>`，并保留 `vectorEnabled()`、`invalidate()` 及既有构造器签名与语义。其行为语义——无嵌入器时降级为 BM25-only、fault-tolerant 且检索永不抛异常、空/空白查询返回空列表、懒建与按语料变更节流重建——MUST 与本能力引入前逐字相同。依赖此门面的其它能力（如记忆检索注入）MUST 不因本次原语重构而需要改动。

#### Scenario: 门面签名与返回类型不变
- **WHEN** 调用方经 `MemorySearchIndex.search(query, topK)` 检索记忆
- **THEN** 返回 `List<MemoryDocument>`（每项带 `sourceLabel`），签名、异常与降级语义与重构前一致

#### Scenario: 门面单测逐字不改仍绿
- **WHEN** 运行既有 `MemorySearchIndex` 与 `memory_search` 工具的单测且不修改测试代码
- **THEN** 全部通过，证明门面/工具对外契约未被原语重构破坏

### Requirement: 纯重构零行为变化

本能力 SHALL 是纯内部重构（包移动 + 接口抽取 + 一处索引签名放宽），MUST NOT 引入任何行为、配置或模型接口变化。`memory.search.hybrid-enabled` 语义、`memory_search` `@Tool`、检索算法/权重/分词/降级语义 MUST 全部保持。既有 `hybrid-memory-search` 能力的全部单测 MUST 保持绿。

#### Scenario: 现有检索单测全绿
- **WHEN** 重构完成后运行全量单测
- **THEN** `hybrid-memory-search` 相关全部单测（BM25、混合 ranker、记忆检索门面、`memory_search` 工具）0 失败 0 错误

#### Scenario: 对外契约无变化
- **WHEN** 对比重构前后的公开 API（门面 `search`、`memory_search` 工具名/签名、配置项）
- **THEN** 逐字一致，无新增/删除/改名的对外契约
