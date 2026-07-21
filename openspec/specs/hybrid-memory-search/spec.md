# hybrid-memory-search Specification

## Purpose
在 `pa-memory-native` 的记忆库（`MEMORY.md` + `memory/*.md`）与 `user-profile` 的 `USER.md` 之上，提供 OpenClaw 蓝本的**混合 BM25+向量检索**（`0.7·BM25 + 0.3·cosine`，可配、候选×N、归一化、去重、top-K），取代 2.0 原生纯关键词 `memory_search`，让记忆检索「换个说法也能找到」。BM25 为纯 Java、确定性、全离线可测；向量层置于可 mock 的 `Embedder` seam（离线 fake、真实嵌入器延后 live 验证）+ 可插拔 `VectorStore`（内存暴力 cosine 默认、零原生依赖）之后；config 门控、**默认关**（今日关键词检索不变），启用时以稳定的 `memory_search` @Tool 名接入。
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

系统 SHALL 提供 `memory.search` 配置块，全部字段可选、默认安全。`hybrid-enabled` MUST 为**三态**开关：**未设（默认）= auto**——系统 SHALL 由「是否已解析出可用嵌入器」派生启用（见「配置嵌入模型即默认启用混合检索」）；**显式 `true`** = 强制启用（即使无嵌入器亦注册 pig `memory_search`，此时混合退化为 BM25-only 排序）；**显式 `false`** = 强制关闭（SHALL NOT 注册 pig `memory_search`、SHALL 保留原生纯关键词检索）。当**无可用嵌入器且未显式启用**（`hybrid-enabled` 未设、无嵌入模型）时，系统 SHALL NOT 注册 pig `memory_search`、SHALL 保留原生纯关键词检索，行为 MUST 逐字节等于本能力引入前。系统 MUST 向后兼容既有配置：既有显式 `hybrid-enabled: true`/`false` MUST 原样读回为「强制启用/强制关闭」，语义与本能力引入前一致；仅「缺字段」升级为 auto（派生）。启用时权重（`bm25-weight`/`vector-weight`）、候选倍数、`min-score`、`top-k`、重建节流 MUST 可配。

#### Scenario: 无嵌入器且未显式启用即今日行为
- **WHEN** 未配置任何可用嵌入器，且 `hybrid-enabled` 未设（默认 auto）
- **THEN** 不注册 pig `memory_search`，原生纯关键词检索原封不动（逐字节等于本能力引入前）

#### Scenario: 显式关闭强制原生检索
- **WHEN** `hybrid-enabled` 显式设为 `false`（即便已配置嵌入模型）
- **THEN** 不注册 pig `memory_search`，保留原生纯关键词检索与全部原生记忆工具

#### Scenario: 显式启用即使无嵌入器
- **WHEN** `hybrid-enabled` 显式设为 `true` 且无可用嵌入器
- **THEN** 注册 pig `memory_search`，混合检索以 BM25-only 排序工作（向量层缺失优雅降级）

#### Scenario: 既有显式布尔值原样读回
- **WHEN** 读取一份既有配置，其 `hybrid-enabled` 显式写为 `true` 或 `false`
- **THEN** 读回为「强制启用/强制关闭」，语义与本能力引入前一致（不因升级为三态而改变）

#### Scenario: 缺配置块用默认
- **WHEN** 配置无 `memory.search` 块
- **THEN** `hybrid-enabled` 取 auto（派生）、权重默认 `0.7/0.3`、其余取默认值

#### Scenario: 启用后权重可配
- **WHEN** 混合检索启用并设定 `bm25-weight`/`vector-weight`
- **THEN** 混合排序按配置权重融合 BM25 与向量分

### Requirement: 配置嵌入模型即默认启用混合检索

当 `hybrid-enabled` 为 auto（未显式设定）时，系统 SHALL 由嵌入器解析（`resolveEmbedder`，解析顺序：config `embedder-model-id` → store 默认嵌入模型 → 无）是否得到一个**可用嵌入器**派生混合检索的启用：**得到嵌入器（即用户已配置嵌入模型）→ SHALL 默认注册 pig `memory_search` 走混合 BM25+向量检索**（无需显式开开关）；**未得到嵌入器（无嵌入模型）→ SHALL 保持 BM25-only / 原生纯关键词检索**（今日行为，零回归）。派生决策 MUST 为纯逻辑、确定性（`effective = 显式覆盖若已设，否则 embedderPresent`），MUST 离线（fake/确定性嵌入器）可验证，MUST NOT 依赖真实网络。嵌入器 MUST 只解析一次并复用于索引构建（不重复解析）。显式 `hybrid-enabled` MUST 覆盖派生结果。翻默认检索路径为本能力（M-B）职责；嵌入器**供给**方（嵌入模型层）MUST NOT 因本要求而改变其「只供给不翻默认」的语义。

#### Scenario: 配置嵌入模型即默认走混合
- **WHEN** `hybrid-enabled` 未设（auto），且嵌入器解析得到一个可用嵌入器
- **THEN** 系统默认注册 pig `memory_search`，记忆检索走混合 BM25+向量（无需显式开开关）

#### Scenario: 无嵌入模型默认 BM25-only 零回归
- **WHEN** `hybrid-enabled` 未设（auto），且无可用嵌入器（`embedder-model-id` 空且无默认嵌入模型）
- **THEN** 不注册 pig `memory_search`，保留原生纯关键词检索，逐字节等于本能力引入前

#### Scenario: 显式开关覆盖派生
- **WHEN** 已配置嵌入模型但 `hybrid-enabled` 显式设为 `false`
- **THEN** 派生结果被覆盖，不注册 pig `memory_search`（走原生检索）

#### Scenario: 派生为纯逻辑离线可证
- **WHEN** 以一个确定性/fake 嵌入器（非空）与「无嵌入器」分别驱动派生决策
- **THEN** 非空 → 混合激活（索引 `vectorEnabled()` 为真且注册工具）、无 → BM25-only，全程无真实网络

#### Scenario: 嵌入器只解析一次
- **WHEN** 在装配阶段决定是否启用混合并随后构建索引
- **THEN** 嵌入器仅解析一次，且被复用于索引构建（不重复解析、不重复告警）

### Requirement: 混合顶替原生记忆工具的取舍默认触发

当混合检索启用时（现包含「已配置嵌入模型」的默认路径），pig 以同名 `memory_search` 取代原生搜索工具，其 `disableMemoryTools()` MUST 同时关闭原生 `memory_get`/`memory_save`/`session_search`（含 `session_list`/`session_history`）——本取舍 SHALL 在「已配置嵌入模型且 `hybrid-enabled` 为 auto」时**默认触发**（不再仅限显式开关）。原生 flush/consolidation **hooks** MUST 保留（`MEMORY.md`/日志层照常写、durable 事实自动落盘），使检索质量提升而记忆写路径不受损。系统 SHALL 提供逃生舱：显式 `hybrid-enabled: false` MUST 恢复原生纯关键词 `memory_search` 与全部原生记忆工具，供需要 `session_search` 等工具的用户选择。

#### Scenario: 默认路径下顶替原生记忆工具
- **WHEN** 已配置嵌入模型、`hybrid-enabled` 为 auto，混合检索默认启用
- **THEN** 模型看到的 `memory_search` 为 pig 混合工具，原生 `memory_get`/`memory_save`/`session_search` 不再暴露

#### Scenario: 记忆写路径不受影响
- **WHEN** 混合默认路径顶替了原生记忆工具
- **THEN** 原生 flush/consolidation hooks 仍把 durable 事实写入 `MEMORY.md`/日志层（写路径不受损）

#### Scenario: 逃生舱恢复原生工具集
- **WHEN** 用户需要 `session_search` 等原生工具，显式设 `hybrid-enabled: false`
- **THEN** 恢复原生纯关键词 `memory_search` 与全部原生记忆工具

### Requirement: 真实嵌入器往返离线覆盖与 live 延后

系统对真实嵌入器（OpenAI 兼容 `/embeddings` HTTP）的**请求构造/响应解析纯逻辑** MUST 离线可测（经可注入 HTTP seam 喂 canned JSON）：MUST 覆盖 (a) 请求体 `{model,input}` 构造与 `/embeddings` URL 拼接（容忍 base URL 尾部斜杠）；(b) 解析 `data[0].embedding` 为向量并 **L2 归一化**，且解析出的**维度**与响应向量长度一致；(c) **错误分类**——无 `data`/空向量/非 2xx 响应 MUST 抛出携带异常类型（不含凭据）的错误，并由检索门面捕获降级为 BM25-only（不崩溃、不抛给调用方）。真实 `/embeddings` **网络往返**与真实嵌入**召回质量** MAY 延后到 live-model 集成测试（`/ls:itest`）；本能力的离线正确性 MUST NOT 依赖真实网络。凭据 MUST NOT 出现在异常、日志或输出中。

#### Scenario: 请求/响应纯逻辑离线可测
- **WHEN** 以 canned `/embeddings` JSON 响应喂给注入了 fake HTTP seam 的嵌入器
- **THEN** 嵌入器正确构造请求体、拼接 `/embeddings` URL、解析出向量并 L2 归一化，维度与响应一致，全程无真实网络

#### Scenario: 错误分类离线可测
- **WHEN** canned 响应无 `data`、或向量为空、或 HTTP 非 2xx
- **THEN** 嵌入器抛出携带异常类型（不含凭据）的错误，检索经门面降级为 BM25-only，不崩溃

#### Scenario: 真往返延后集成测试
- **WHEN** 需要验证真实 `/embeddings` 网络往返与召回质量
- **THEN** 该验证在 live-model 集成测试（`/ls:itest`）进行，不在本能力的离线单测内

