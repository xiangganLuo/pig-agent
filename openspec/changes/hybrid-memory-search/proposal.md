## Why

pig 现在**记得住事实**（`pa-memory-native` 的工作区级 `MEMORY.md` 跨会话）并有一份**用户画像**（`user-profile` 的 `USER.md`），但记忆检索仍是 **2.0 原生 `memory_search` 的纯关键词扫描**（子串命中、无相关度排序、无语义召回）。行业蓝本 OpenClaw 用**混合检索 BM25+向量**（本地 sqlite-vec + FTS5，`0.7/0.3` 加权）显著提升记忆召回质量——「问的词换个说法也能找到」。

本 spec 是「个人助理核心」拆分的**第 4 个（最后一个）spec**（设计事实源 `docs/design/personal-assistant-core-design.md` §6.3 / §9 / OD7），**依赖 #1 `pa-memory-native`（已归档、在 main）**——需要其记忆库（`MEMORY.md` + `memory/*.md`）可被索引；顺带覆盖 #2 `user-profile` 的 `USER.md`。用户已批准 **OD7：混合 BM25+向量、本地优先**，但明确「后端 spec-4 再定」——故本 spec **先做 spike** 定后端。

## Spike 结论（阻塞前置，已在 `design.md` §Spike 记录）

- **嵌入模型**：AgentScope 2.0 core **无 embedding Model API**（只有 chat 模型 `Model`/`ChatModelBase`）；pig 模型库（`StoredModel`/`ModelManager`）也只建模 chat 模型。Doubao/OpenAI-compatible 的 `/embeddings` 端点**仅能经 live HTTP 调用**、离线不可验证。→ 向量层 MUST 置于**可 mock 的 `Embedder` seam** 之后：离线测试用**确定性 fake 嵌入器**；**真实嵌入器交 `/ls:itest`**（live-model 验证）。
- **向量后端（Windows）**：`sqlite-vec`（原生 JNI 向量扩展）**本地仓库不存在**（仅有纯 JDBC 的 `sqlite-jdbc`，无向量扩展），且离线在 Windows 上加载原生向量扩展不可行。→ **拒绝 sqlite-vec**（遵循 spike 指令「加载不干净就不强上」），改用**纯 Java 暴力 cosine**（`float[]` 向量、`VectorStore` seam + 内存默认实现）——个人助理级语料量极小，重建/查询开销可忽略，**零原生依赖、离线全绿**。
- **OD7 定案**：混合检索后端 = **本地、纯 Java**（内存向量索引 + 真实嵌入器置于 seam 之后、config 门控、延后 live 验证）。**非 sqlite-vec、非外接后端**（本地优先）。

## What Changes

- **`MemorySearchIndex`（Strategy 编排）**：对记忆语料（`MEMORY.md` + `memory/*.md` + `USER.md`）做**混合检索**：
  - **BM25 关键词打分**（纯 Java、Okapi BM25 `k1=1.2/b=0.75`、CJK 逐字+bigram 分词——语料多中文）——**完全离线可测、确定性**。
  - **向量层**置于 `Embedder` seam（离线 fake、真实延后 IT）+ 可插拔 `VectorStore`（内存暴力 cosine 默认）。
  - **混合排序 = `0.7·BM25 + 0.3·cosine`**（可配；候选×N、各自归一化、按 `min-score` 过滤、去重、top-K）。嵌入器缺失时**优雅降级为 BM25-only**（仍优于子串扫描）。
  - **懒/增量索引**：首次查询或语料文件 mtime 变化时（节流）重建，个人助理级语料重建极廉价。
- **接入记忆检索路径**：新增 pig `memory_search` @Tool（**保持 `@Tool` 名不变** → 模型接口零变化，`ToolRiskClassifier` 已分类 READ_ONLY），启用时**取代**原生纯关键词 `memory_search`（`PigAgent.Builder` 检测到 toolkit 内已有 `memory_search` 即对 vehicle `disableMemoryTools()`——保留原生 flush/consolidation **hooks** 仍写 `MEMORY.md`，仅替换搜索工具）。失败返回 canonical `{"error"}`，凭据脱敏。
- **Config `memory.search` 块**（全可选、默认安全）：`hybrid-enabled`（**默认 false** = 今日原生关键词检索、逐字节不变，直到真实嵌入器接入——保守，见 design）、`bm25-weight`(0.7)/`vector-weight`(0.3)、`embedder-model-id`（空 → 无嵌入器 → BM25-only）、`candidate-multiplier`(4)、`min-score`、`top-k`、`rebuild-throttle-seconds`。
- **真实嵌入器**：`OpenAiCompatibleEmbedder`（HTTP POST `{baseUrl}/embeddings`，凭据取自 `StoredModel`）——网络调用置于可注入 seam 之后，其**请求构造/响应解析纯逻辑离线单测**（canned JSON），**真实网络往返延后 `/ls:itest`**。

**非破坏**：`memory.search.hybrid-enabled=false`（默认）→ 不注册 pig `memory_search`、原生纯关键词检索原封不动 = 逐字节等于本 spec 引入前。

## Capabilities

### New Capabilities
- `hybrid-memory-search`: 记忆库（`MEMORY.md` + `memory/*.md` + `USER.md`）上的**混合 BM25+向量检索**（`0.7/0.3` 加权、候选×N、去重、top-K），BM25 纯 Java 全离线可测，向量层置于可 mock 的 `Embedder` seam + 可插拔 `VectorStore`（内存默认）之后；config 门控，默认关（今日关键词检索不变），启用时以稳定的 `memory_search` @Tool 名取代原生纯关键词检索——检索「换个说法也能找到」。

### Modified Capabilities
<!-- 归档时（/ls:archive）在 openspec/specs/ 新增 hybrid-memory-search 主 spec；不改写既有 pa-memory-native 主 spec（本能力是其上的独立叠加：复用其记忆库为索引源、保留其 flush/consolidation 写路径，仅在启用时以同名 @Tool 取代原生搜索工具）。 -->

## Impact

- **代码**：`pig-agent-core`（新 `memory/search/{MemoryDocument, MemoryCorpusLoader, Tokenizer, Bm25Index, Embedder, DeterministicEmbedder, OpenAiCompatibleEmbedder, VectorStore, InMemoryVectorStore, HybridRanker, MemorySearchConfig, MemorySearchIndex}`；`PigAgent.Builder` 在 memoryConfig 存在且 toolkit 已含 `memory_search` 时 `disableMemoryTools()`）；`pig-agent-tools`（新 `memory/HybridMemorySearchTool`；`ToolRiskClassifier` 的 `memory_search`=READ_ONLY 已存在，无需改）；`pig-agent-config`（`MemoryConfig` 内嵌 `SearchConfig` 块）；`pig-agent-cli`（`AgentBootstrap`：`hybrid-enabled` 时建索引 + 显式注册 pig `memory_search` 工具；解析 `embedder-model-id` → `OpenAiCompatibleEmbedder` 或 null → BM25-only）。
- **不改**：`pa-memory-native` 记忆库/写路径（复用不改）；`user-profile`；A5 压缩；权限/沙箱/渠道 fail-closed。
- **测试**：离线单测——BM25 把相关文档排在无关文档之上（含中文分词）；混合排序融合 BM25+向量（fake 嵌入器、确定性）；索引覆盖 `MEMORY.md`+`memory/*`+`USER.md`；去重 + top-K；嵌入器缺失→BM25-only；节流/mtime 重建；`hybrid-enabled=false`→不注册工具（关键词检索不变）；`OpenAiCompatibleEmbedder` 请求/响应纯逻辑（canned JSON）；工具 `{"error"}` 契约 + READ_ONLY。**真实嵌入质量/网络往返 → `*IT`（`/ls:itest`）**。
- **文档**：`CLAUDE.md` 记忆段落新增「混合检索」说明 + 配置段 `memory.search` 同步。
