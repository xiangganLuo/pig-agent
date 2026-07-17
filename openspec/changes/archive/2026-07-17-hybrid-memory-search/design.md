## Context

pig 记忆现状（`pa-memory-native` 已在 main）：工作区级两层记忆——日志层 `memory/YYYY-MM-DD.md`（flush 追加）→ 固化层 `MEMORY.md`（consolidation 重写、注入 system prompt）。检索经 **2.0 原生 `memory_search`**（`MemorySearchTool.memorySearch(RuntimeContext, String)`，`@Tool name="memory_search" readOnly=true`）——**纯关键词子串扫描** `listMemoryFilePaths`（`MEMORY.md`+`memory/*.md`），≤30 命中、无相关度排序、无语义。原生记忆工具由 `HarnessAgent` 在 build 时装到 `toolkit.copy()`（pig 传入的 toolkit 的副本），受 `disableMemoryTools()` 开关。`user-profile` 另有工作区根 `USER.md`。

OpenClaw 蓝本：混合 **BM25+向量**（sqlite-vec + FTS5，`0.7/0.3`、候选×4）本地优先，检索质量显著优于纯关键词。设计事实源 `docs/design/personal-assistant-core-design.md` §6.3 / OD7：**采用混合、本地优先，后端 spec-4 再定**。

## Goals / Non-Goals

**Goals**
- 记忆库（`MEMORY.md` + `memory/*.md` + `USER.md`）上的混合 **BM25+向量** 检索：`score = bm25Weight·BM25 + vectorWeight·cosine`（默认 `0.7/0.3`，可配），候选×N、归一化、`min-score` 过滤、去重、top-K。
- BM25 纯 Java、确定性、**全离线可测**（含 CJK 分词）。
- 向量层置于**可 mock 的 `Embedder` seam**（离线 fake、真实延后 IT）+ 可插拔 `VectorStore`（内存暴力 cosine 默认）。
- 接入记忆检索路径：pig `memory_search` @Tool（**名不变**）启用时取代原生纯关键词检索；`{"error"}` 契约、凭据安全。
- 懒/增量索引（首次查询 or 文件 mtime 变化时节流重建）。
- Config `memory.search` 块，**默认关**（今日关键词检索不变）。

**Non-Goals**
- 真实嵌入质量/网络往返的离线验证（属 live-model，交 `/ls:itest`）。
- sqlite-vec / LanceDB / 外接 mem0·bailian 后端（spike 已排除，见下）。
- 改 `pa-memory-native` 记忆库/写路径、`user-profile`、A5 压缩、权限/沙箱/渠道语义。
- 多用户/多租户；重排（rerank）二阶段（未来）。

## Spike（阻塞前置 Task 0 — 本 spec 有真实技术风险）

> 向量检索需要 (a) 嵌入模型 + (b) 向量库。提交前必须验证二者的离线可行性，并选**离线全绿**的路径。以下结论经真机核验（`D:\env\apache-maven-3.9.10\repository` = 有效本地仓库；2.0 jar 经该仓库 + 私有 Aliyun 源解析）。

**S1 — 嵌入模型可用性**
- `javap`/`jar tf` 核验 `agentscope-core-2.0.0.jar` 的 `io/agentscope/core/model/*`：只有 **chat 模型**（`Model`、`ChatModelBase`、`ChatResponse`、`ModelRegistry` …），**无任何 `Embedding` 类** → AgentScope 2.0 core **不提供 embedding Model API**。
- pig 模型层（`StoredModel`/`ModelManager`/协议 SPI）只建模 chat 模型，无 embedding 概念。
- Doubao/OpenAI-compatible 供应商**确有** `/embeddings` 端点（如 `doubao-embedding-*`），可用 `StoredModel` 的 `apiKey`/`baseUrl`/`modelName` 经 HTTP 直呼；DashScope SDK 亦带 `com.alibaba.dashscope.embeddings.*`。但**任何嵌入调用都是 live 网络往返、离线不可验证**。
- **结论**：向量层 **MUST 置于可 mock 的 `Embedder` seam**。离线测试注入**确定性 fake 嵌入器**（`DeterministicEmbedder`，token 哈希 + tf 加权 + L2 归一化——非语义、仅供确定性验证混合融合）。真实嵌入器（`OpenAiCompatibleEmbedder`）**其请求构造/响应解析纯逻辑离线单测**（canned JSON，网络 I/O 置于可注入 `HttpJson` seam），**真实往返延后 `/ls:itest`**。

**S2 — 向量库（Windows/JDK）**
- 全仓库搜索：**无 `sqlite-vec`**（原生 JNI 向量扩展）jar；仅有 `sqlite-jdbc-3.47.1.0.jar`（纯 JDBC，**无向量扩展**）。私有源亦不含 sqlite-vec。离线在 Windows 上下载/加载原生向量扩展 = 不可行。
- 另有 `lucene-core-9.12.0`（`tools-core-slim` 已弃用的旧依赖）——引入 Lucene 做 BM25 属重依赖，个人助理级语料无必要。
- **结论**：**拒绝 sqlite-vec**（遵循 spike 指令「加载不干净就不强上」）。采用**纯 Java 暴力 cosine**：`float[]` 向量存入可插拔 `VectorStore`，默认 `InMemoryVectorStore`（暴力 cosine）。个人助理级语料（`MEMORY.md` + 少量 `memory/*.md` + `USER.md`，至多数百段）暴力检索 O(N·d) 可忽略；重建随文件 mtime 变化、节流。**零原生依赖 → 离线全绿。**

**S3 — OD7 定案（后端选择）**
- 混合检索后端 = **本地、纯 Java**：BM25（纯 Java）+ 内存向量索引（暴力 cosine）+ 真实嵌入器置 seam 之后（config 门控、延后 IT）。**非 sqlite-vec、非外接后端**（本地优先、离线可行）。

**Spike 净结论**：走**主路径**（非「都不可行」回退）——BM25 + 混合 ranker（fake 嵌入器全离线测）+ `Embedder` seam + 内存 `VectorStore`。唯一延后件 = **真实嵌入器的 live 网络验证**（`/ls:itest`），这是任何网络客户端都无法离线验的标准延后项，且其纯逻辑已离线测。**不 ship 破损/未测的向量路径**：向量融合逻辑用 fake 嵌入器全测、真实嵌入器纯逻辑用 canned JSON 全测，仅裸 socket I/O 延后。

## Decisions

- **D1 — 混合权重 `0.7·BM25 + 0.3·cosine`（可配），保守偏 BM25**。任务指令明确 `0.7·BM25 + 0.3·cosine`；设计事实源 §6.3 记的是 OpenClaw 向量偏重 `vector·0.7 + keyword·0.3`。二者相反。取**任务指令的 BM25 偏重**为默认，理由：(1) 嵌入器离线延后，BM25 是唯一离线可证的可靠成分——默认倚重它更稳；(2) 可配，接入真实嵌入器 + IT 验证后可翻到向量偏重。归一化后加权求和（各成分先 min-max 归一化到 `[0,1]` 再加权），使两个不同量纲的分数可比。
- **D2 — 后端本地纯 Java（OD7，S3）**：`VectorStore` seam + `InMemoryVectorStore`（暴力 cosine）。非 sqlite-vec（S2）。可插拔以便未来换持久化后端而不动 ranker。
- **D3 — `Embedder` seam（S1）**：`@FunctionalInterface float[] embed(String)`。`DeterministicEmbedder`（离线/测试）；`OpenAiCompatibleEmbedder`（真实、HTTP、网络置 `HttpJson` seam、延后 IT）。嵌入器 null → **BM25-only** 优雅降级（非报错）。镜像 `pa-memory-native` 的 `ProfileDistiller`/`ModelProfileDistiller` 廉价模型 seam 套路。
- **D4 — 接入路径 = 同名 @Tool 取代（保持模型接口不变）**：pig `HybridMemorySearchTool`（`@Tool name="memory_search"`）委托索引。启用时如何避免与原生 `memory_search` 撞名？`PigAgent.Builder.build()` **自协调**：`memoryConfig != null` 时，若传入 toolkit 已含名为 `memory_search` 的工具（= pig 在 `hybrid-enabled` 时注册的），则对 vehicle 追加 `disableMemoryTools()`——原生记忆**工具**全关（含 `memory_search`），但原生 flush/consolidation **hooks** 仍开（`MEMORY.md` 照写）。**无新 builder 参数、无 factory 改动**（自协调纯靠 toolkit 名探测），interactive/channel/peer 一致生效。**权衡（记 R3）**：hybrid 路径下 `memory_get`/`memory_save`/`session_search` 不再暴露（默认关，属可接受的 spec-4 取舍——flush 自动落盘覆盖持久化）。
- **D5 — 懒/增量索引 + 节流**：`MemorySearchIndex` 记录语料文件 mtime 快照；`search()` 时若快照变化且距上次重建 ≥ `rebuild-throttle-seconds` 则重建，否则复用。首次查询构建。语料量小，重建廉价。
- **D6 — 默认关（保守）**：`hybrid-enabled=false` → `AgentBootstrap` 不注册 pig `memory_search` → 原生纯关键词检索原封不动。理由：真实嵌入器未经 live 验证前，默认改动检索行为不稳妥；且离线测试全覆盖启用后逻辑，翻开关一步即可。**任务 Config 指令**：默认保守 = 今日关键词检索。
- **D7 — CJK 分词**：语料多中文。`Tokenizer` 对 latin 发 lowercased word token，对 CJK 连续段发**逐字 unigram + 相邻 bigram**——使「罗湘赣」类查询能 BM25 命中。BM25 与向量（fake 嵌入器）共用同一 tokenizer，保证一致性。
- **D8 — `{"error"}` 契约 + 凭据安全**：工具失败返回 `ToolErrors.message(...)`（canonical `{"error"}`），绝不抛；结果片段不回显凭据（嵌入器/HTTP 错误只报类型不带 key）。READ_ONLY（`ToolRiskClassifier` 已有 `memory_search`=READ_ONLY，同名继承）。

## Architecture

```
memory.search.hybrid-enabled=true
        │  AgentBootstrap
        ▼
MemorySearchIndex(settings)                       ← io.pigagent.core.memory.search
  ├─ MemoryCorpusLoader(MEMORY.md, memory/*.md, USER.md) → List<MemoryDocument>   (fault-tolerant, chunked)
  ├─ Bm25Index(Tokenizer)         → Map<docId,score>   (pure Java, offline)
  ├─ Embedder (seam)              → float[] per doc/query   (Deterministic offline | OpenAiCompatible live/IT)
  ├─ VectorStore (seam)           → cosine top-N          (InMemoryVectorStore brute-force)
  └─ HybridRanker(0.7/0.3)        → normalize + blend + dedup + top-K → List<MemoryDocument>
        │
        ▼
HybridMemorySearchTool  @Tool name="memory_search"  (READ_ONLY, {"error"} contract)   ← io.pigagent.tool.memory
        │  registered into toolkit when enabled
        ▼
PigAgent.Builder.build(): memoryConfig!=null && toolkit has "memory_search" → hb.disableMemoryTools()
        (native flush/consolidation HOOKS stay on → MEMORY.md still written; native search TOOL replaced)
```

## Risks / Trade-offs

- **R1 — 真实嵌入质量/网络未离线验**：`OpenAiCompatibleEmbedder` 的裸网络往返离线不可测。→ 纯逻辑（请求构造/响应解析/L2 归一化）用 canned JSON 全测；真实往返延后 `/ls:itest`；默认关 + 嵌入器缺失→BM25-only，故未验证的路径不进默认行为。
- **R2 — 混合权重方向与设计事实源相反**：§6.3 记向量偏重 `0.7`，任务指令记 BM25 偏重 `0.7`。→ D1 取任务指令为默认（保守、离线可证），可配翻转，design 显式记录分歧。
- **R3 — hybrid 路径丢 `memory_get`/`memory_save`/`session_search`**：D4 的 `disableMemoryTools()` 关掉全部原生记忆工具。→ 默认关；flush hooks 仍自动落盘（持久化不受损）；这三个为次要工具，记为可接受取舍 + 后续可由 pig 补薄封装（未来）。
- **R4 — 内存向量索引不持久**：每次进程启动/文件变化重建。→ 语料量小、重建廉价（节流）；`VectorStore` 可插拔，未来可换持久化后端而不动 ranker。
- **R5 — CJK 分词朴素**：逐字+bigram 非分词器级精度。→ 对个人助理级中文记忆足够；BM25 语义在 D7 已明确；可后续接入更好分词而不动 ranker/index 契约。
- **R6 — 语料 chunk 粒度**：过粗则片段不聚焦、过细则上下文丢失。→ 按 Markdown 标题/空行分块、单块字符上限；chunk 策略在 loader 内可调，不影响契约。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 原生 `memory_search` 仅关键词、无相关度/语义 | Context；D4 同名取代 | 落 tasks 5 |
| 嵌入器离线不可用（2.0 无 embedding API、Doubao 仅 live HTTP） | Spike S1；D3 `Embedder` seam | 落 tasks 0/3（前置验证 + seam） |
| sqlite-vec 本地不可用 / Windows 原生加载不可行 | Spike S2；D2 纯 Java 内存向量 | 落 tasks 0/2（后端定案 + 内存 store） |
| OD7 后端「spec-4 再定」 | Spike S3；D2 本地纯 Java | 已定案（本地纯 Java） |
| 混合 `0.7·BM25 + 0.3·cosine`（任务指令）vs §6.3 向量偏重 | D1；R2 | 落 tasks 4（默认 BM25 偏重、可配） |
| BM25 纯 Java、离线可测、含 CJK | D7；tasks 2 | 落 tasks 2 |
| 真实嵌入器延后 IT、纯逻辑离线测 | R1；D3 | 落 tasks 3/6 |
| 默认关 = 今日关键词检索不变 | D6；任务 Config 指令 | 落 tasks 4/5 |
| 懒/增量索引 + 节流 | D5 | 落 tasks 4 |
| `{"error"}` 契约 + READ_ONLY + 凭据安全 | D8 | 落 tasks 5 |
| hybrid 路径丢 memory_get/save/session_search | R3；D4 | 记为可接受取舍（默认关） |
| 依赖 #1 记忆库为索引源、覆盖 #2 `USER.md` | Goals；MemoryCorpusLoader | 落 tasks 1/2 |
