# hybrid-memory-search Specification

## Purpose
TBD - created by archiving change hybrid-memory-search. Update Purpose after archive.
## Requirements
### Requirement: 记忆库上的混合 BM25+向量检索

系统 SHALL 对记忆语料——固化层 `MEMORY.md` + 日志层 `memory/*.md` + 用户画像 `USER.md`——提供**混合检索**：`score = bm25Weight·BM25 + vectorWeight·cosine`（默认 `0.7·BM25 + 0.3·cosine`，MUST 可配）。各成分 MUST 先各自归一化再加权求和（量纲可比）；结果 MUST 去重（同一文档只出现一次）、MUST 按 top-K 截断。BM25 打分 MUST 为纯 Java、确定性、离线可计算。

#### Scenario: BM25 把相关文档排在无关文档之上
- **WHEN** 语料含一个与查询词高度相关的文档和若干无关文档，用查询词检索
- **THEN** 相关文档的 BM25 分高于无关文档，出现在结果前列

#### Scenario: 混合排序融合 BM25 与向量
- **WHEN** 提供一个（fake 确定性）嵌入器，对同一查询做混合检索
- **THEN** 最终排序按 `bm25Weight·BM25 + vectorWeight·cosine`（各归一化后）融合，权重可配

#### Scenario: 索引覆盖三类记忆文件
- **WHEN** `MEMORY.md`、`memory/*.md`、`USER.md` 各含可命中的内容
- **THEN** 三类文件的内容都进入索引、都可被检索命中

#### Scenario: 去重与 top-K
- **WHEN** 检索命中数超过 top-K，或同一文档被多路（BM25+向量）命中
- **THEN** 结果按文档去重且只返回前 K 条（按融合分排序）

### Requirement: 向量层置于可 mock 的嵌入器 seam 之后

向量检索所需的**嵌入模型调用** MUST 位于一个**可 mock 的 `Embedder` seam** 之后（离线可测）：离线测试注入确定性 fake 嵌入器；真实嵌入器（OpenAI-compatible `/embeddings` HTTP）的真实网络往返 MAY 延后到 live-model 集成测试。当**没有可用嵌入器**时，混合检索 MUST 优雅降级为 **BM25-only**（不报错、不抛异常）。向量后端 MUST 为可插拔的 `VectorStore`（默认纯 Java 内存实现，零原生依赖，离线可用）；系统 MUST NOT 依赖原生向量扩展（如 sqlite-vec）作为离线可行前提。

#### Scenario: 嵌入器缺失降级为 BM25-only
- **WHEN** 未配置嵌入器（`embedder-model-id` 为空或不可解析）而混合检索启用
- **THEN** 检索仅用 BM25 排序返回结果，不抛异常、不报错

#### Scenario: 真实嵌入器纯逻辑离线可测
- **WHEN** 用一个 fake 的 HTTP seam 喂给真实嵌入器一段 canned `/embeddings` JSON 响应
- **THEN** 嵌入器正确构造请求体、解析出 `float[]` 向量并 L2 归一化，全程无真实网络

#### Scenario: 向量后端无原生依赖
- **WHEN** 在离线环境构建与运行
- **THEN** 向量检索经纯 Java 内存 `VectorStore`（暴力 cosine）工作，MUST NOT 要求 sqlite-vec 等原生扩展

### Requirement: 以稳定的 `memory_search` 工具名接入检索路径

启用混合检索时，系统 SHALL 以一个 **`@Tool` 名为 `memory_search`**（与原生保持一致，模型接口零变化）的 pig 工具**取代**原生纯关键词 `memory_search`；取代时原生记忆的 **flush/consolidation 写路径（hooks）MUST 保留**（`MEMORY.md`/日志层照常写）。该工具 MUST 分类为 **READ_ONLY**。失败 MUST 返回 canonical `{"error":"<reason>"}`（凭据脱敏），MUST NOT 抛异常，MUST NOT 回显凭据。

#### Scenario: 同名取代、模型接口不变
- **WHEN** 混合检索启用
- **THEN** 模型仍看到一个名为 `memory_search` 的工具（签名/名称不变），其结果由混合检索排序

#### Scenario: 保留记忆写路径
- **WHEN** pig 的 `memory_search` 取代了原生搜索工具
- **THEN** 原生 flush/consolidation 仍将事实写入 `MEMORY.md`/日志层（仅搜索工具被替换，写路径不受影响）

#### Scenario: READ_ONLY 与错误契约
- **WHEN** 查询 `memory_search` 的风险级别，或工具内部出错
- **THEN** 风险级别为 READ_ONLY；出错时返回 `{"error":"<reason>"}`（脱敏）而非抛异常

### Requirement: 懒/增量索引构建（节流）

索引 SHALL 懒构建（首次查询时）并在语料文件变化时增量重建；重建 MUST 节流（最小间隔），使高频查询不反复重建。索引构建 MUST 容错（缺失/不可读文件跳过，不崩溃、不抛）。

#### Scenario: 首次查询触发构建
- **WHEN** 索引尚未构建，第一次调用检索
- **THEN** 索引按当前语料构建后返回结果

#### Scenario: 文件变化触发重建（节流）
- **WHEN** 语料文件在最小间隔之外发生变化后再次检索
- **THEN** 索引重建以反映变化；若在最小间隔之内连续检索则复用上次索引（不反复重建）

### Requirement: 混合检索开关与向后兼容

系统 SHALL 提供 `memory.search` 配置块，全部字段可选、默认安全。`hybrid-enabled` MUST **默认关闭**——关闭时系统 SHALL NOT 注册 pig `memory_search`、SHALL 保留原生纯关键词检索，行为 MUST 逐字节等于本能力引入前。启用时权重（`bm25-weight`/`vector-weight`）、候选倍数、`min-score`、`top-k`、重建节流 MUST 可配。

#### Scenario: 默认关即今日行为
- **WHEN** `memory.search.hybrid-enabled=false`（默认）
- **THEN** 不注册 pig `memory_search`，原生纯关键词检索原封不动（与引入前一致）

#### Scenario: 缺配置块用默认
- **WHEN** 配置无 `memory.search` 块
- **THEN** 混合检索默认关、权重默认 `0.7/0.3`、其余取默认值

#### Scenario: 启用后权重可配
- **WHEN** 配置 `hybrid-enabled=true` 并设定 `bm25-weight`/`vector-weight`
- **THEN** 混合排序按配置权重融合 BM25 与向量分

