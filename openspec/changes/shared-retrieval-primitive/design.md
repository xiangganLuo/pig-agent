## Context

pig 的离线混合检索（capability `hybrid-memory-search`，已在 main）实现于 `io.pigagent.core.memory.search`，由一组类组成：

- **通用原语（无记忆语义）**：`Bm25Index`（纯 Java Okapi BM25，`k1=1.2/b=0.75`）、`HybridRanker`（纯静态 `0.7·BM25+0.3·cosine` 混合、归一化、去重、top-K）、`Tokenizer`（CJK 逐字 unigram+相邻 bigram + latin 词元）、`VectorStore`/`InMemoryVectorStore`（暴力 cosine）、`Embedder`/`DeterministicEmbedder`/`OpenAiCompatibleEmbedder`（`String→float[]` seam）、`Vectors`（L2 归一化工具）。
- **记忆域类型（记忆线私有）**：`MemoryDocument`（`record(id, sourceLabel, text)`）、`MemoryCorpusLoader`（从 `MEMORY.md`+`memory/*.md`+`USER.md` chunk 出文档）、`MemorySearchIndex`（编排门面，`search(query,topK)→List<MemoryDocument>`）、`MemorySearchConfig`（权重/topK/节流值对象）。

内核路线图 Wave-1 将新增两条检索线：**Tool-OS T2**（`tool_search` 从关键词升级为同款混合排序）与 **Skills S2**（按 query 排 `SKILL.md`）。三线若各抄一套评分逻辑 → **评分漂移**。R0 的职责：把上面「通用原语」抽成内核共享基座 `io.pigagent.core.search`，三线同源。**纯重构、零行为变化**，与并行 M-A 同期时 R0 先合（门面先稳定）。

## Goals / Non-Goals

**Goals**
- 抽出最小通用 `SearchDocument` 契约（`{id, text}`），`MemoryDocument implements` 之。
- `Bm25Index` 泛型化到 `List<? extends SearchDocument>`；`HybridRanker`/`Tokenizer`/向量+嵌入 seam 上提到 `io.pigagent.core.search`，三线可直接 import 复用。
- `MemorySearchIndex` 公共门面**逐字稳定**（M-A 的依赖锚点）。
- 零行为变化：现有 `hybrid-memory-search` 全部单测保持绿（硬约束）。

**Non-Goals**
- 不引入工具检索/技能匹配的**消费方**代码（那是 Tool-OS T2 / Skills S2 各自的 spec；R0 只交付被复用的原语与契约）。
- 不改检索算法/权重/分词/降级语义（纯搬运 + 抽接口，不调优）。
- 不泛化 `MemorySearchConfig`（见 D5）。
- 不动 `MemoryCorpusLoader` 的记忆语料来源/chunk 策略、`HybridMemorySearchTool` 的 `@Tool` 契约、`AgentBootstrap` 接线。
- 不做持久化向量后端、rerank 二阶段、跨模块 DI 重构（未来）。

## Spike（前置 Task 0 —— 确认抽象成本可控）

> 本 spec 是**低风险纯重构**，但 R0 是三线真源，抽象形状定错会拖累 T2/S2/M-*。spike 目标：以证据确认 (a) `HybridRanker` 已纯泛型、(b) `Bm25Index` 对 `MemoryDocument` 的耦合唯一且最小、(c) 通用接口只需 `{id,text}`、(d) blast radius 有界。以下结论已在本机代码核验（`grep` + 逐类阅读）。

**S1 — `HybridRanker` 已是纯泛型静态函数（零业务耦合，不动）**
- `HybridRanker.rank(Map<String,Double> bm25, Map<String,Double> vector, double bm25W, double vectorW, double minScore, int topK) → List<Scored>`；`Scored(String id, double score)`；`normalize(Map<String,Double>)` 包级静态。
- 全类**无任何 `MemoryDocument` 引用**（`grep MemoryDocument` 在 `HybridRanker.java` 零命中）。输入/输出只是 `Map<String,Double>` + `String id` → 与文档类型完全解耦。
- **结论**：`HybridRanker` **逐字不动**，仅随包上提（改 `package` 行）。

**S2 — `Bm25Index` 对 `MemoryDocument` 的耦合唯一且最小**
- 全仓库 `grep MemoryDocument`（去 javadoc `{@link}`）：代码级引用仅在 `MemoryDocument.java`（定义）、`MemoryCorpusLoader.java`（记忆域生产者）、`MemorySearchIndex.java`（门面，映射 id→doc）、**`Bm25Index.java` 第 29/36 行**。
- `Bm25Index` 内耦合点 = `index(List<MemoryDocument> docs)`，方法体只调用 `doc.text()`（分词）与 `doc.id()`（记 docId）；`score(String)→Map<String,Double>` 已按 id 解耦、无文档类型。
- **结论**：通用接口只需暴露 `id()` + `text()` 两个访问器；`Bm25Index` 是**唯一**需改签名的原语。

**S3 — blast radius 有界**
- `MemoryDocument.sourceLabel()`/`text()` 的读取方仅 `HybridMemorySearchTool`（拼结果）+ `MemorySearchIndexTest`/`HybridMemorySearchToolTest`。`AgentBootstrap` **不引用 `MemoryDocument`/`sourceLabel`**（`grep` 零命中）——只按类型构造 `MemorySearchIndex`/`MemoryCorpusLoader`/工具。
- **结论**：`sourceLabel` 属记忆域，**不进** `SearchDocument`；门面继续返回 `MemoryDocument`（带 `sourceLabel`），消费方零改动。

**Spike 净结论**：抽象成本 = **1 个 2 方法接口 + `MemoryDocument implements` + `Bm25Index.index` 一处签名放宽（`? extends`）+ 通用原语包移动**。`HybridRanker`/`Tokenizer`/向量+嵌入类逐字不动（仅 `package`）。低风险、可控，走主路径（不需要回退方案）。

## Decisions

- **D1 — `SearchDocument` = 最小接口 `{ String id(); String text(); }`，而非泛型 `Bm25Index<D>`**。spike S2 证实 `Bm25Index` 内部只用这两个访问器，且 `score()` 返回 `Map<String,Double>`（按 id 解耦），保留具体文档类型无收益 → 泛型化 `Bm25Index<D>` 属 speculative generality（违 YAGNI）。用**接口 + 通配符**更简：三线各自的文档类型 `implements SearchDocument` 即可喂同一 `Bm25Index`。`sourceLabel` 不进接口（S3，记忆域私有）。
- **D2 — `Bm25Index.index(List<? extends SearchDocument>)`（向后兼容命门）**。Java 泛型不变性下 `List<MemoryDocument>` **不能**传给 `List<SearchDocument>` 形参，但**能**传给 `List<? extends SearchDocument>`。用通配符保证既有调用点零改动通过：`MemorySearchIndex.rebuild()` 传 `List<MemoryDocument>`、`Bm25IndexTest` 传 `List.of(new MemoryDocument(...))`（推断为 `List<MemoryDocument>`）。这是"现有测试逐字绿 + 零行为变化"的技术关键，显式记录。
- **D3 — 包边界：`io.pigagent.core.search` 收通用原语，`io.pigagent.core.memory.search` 留记忆域**。迁入新包：`SearchDocument`(新)、`Bm25Index`、`HybridRanker`、`Tokenizer`、`VectorStore`、`InMemoryVectorStore`、`Embedder`、`DeterministicEmbedder`、`OpenAiCompatibleEmbedder`、`Vectors`。留原包：`MemoryDocument`（+`implements SearchDocument`）、`MemoryCorpusLoader`、`MemorySearchIndex`、`MemorySearchConfig`。理由：原语=三线共享；语料来源/chunk/`sourceLabel`/门面=记忆线私有。这条边界让 T2/S2/M-* 只 import `core.search`，不牵扯记忆域。
- **D4 — `MemorySearchIndex` 门面稳定契约（M-A 依赖锚点）**。公共表面**逐字不变**：`List<MemoryDocument> search(String query, int topK)`、`boolean vectorEnabled()`、`void invalidate()`、构造器 `(MemoryCorpusLoader, VectorStore, Embedder, MemorySearchConfig)`。内部改为 import `core.search` 原语，但返回类型、异常、降级语义（无嵌入器→BM25-only、fault-tolerant、never throws、空查询→空表、懒/节流重建）全部保持。**R0 与 M-A 同期落地时 R0 先合**——门面先冻结，M-A 只对着稳定门面开发。
- **D5 — `MemorySearchConfig` 不进 R0 范围（YAGNI + 保测试绿）**。其字段虽通用（权重/candidateMultiplier/minScore/topK/throttle），但 (1) 当前无跨线共享 config 的需求，(2) 移动/改名会打破 `MemorySearchIndexTest`（引用 `MemorySearchConfig.defaults()`/`new MemorySearchConfig(...)`）违反硬约束。保留原位、原名。若未来三线要共享 ranker 参数配置，另开 spec。
- **D6 — 测试处理：门面测试逐字不变（证明稳定），原语测试随类迁移（行为等价）**。
  - **逐字不改**：`MemorySearchIndexTest`、`HybridMemorySearchToolTest` —— 一行不动仍绿 = 门面/工具契约未破的直接证据。
  - **随迁**：`Bm25IndexTest`、`HybridRankerTest` 迁到 `io.pigagent.core.search` 测试包。`HybridRankerTest` 仅改 `package`/import（只用 `Map<String,Double>`）；`Bm25IndexTest` 文档夹具从 `MemoryDocument` 换为通用测试记录 `record TestDoc(String id, String text) implements SearchDocument`，**断言与排序结果逐字相同**（含中文"罗湘赣"用例、空索引、无命中）。取向：最小改动 + 行为等价。
- **D7 — `Tokenizer` 通用直接复用**。CJK unigram+bigram + latin 词元，`String→List<String>` 纯静态，无记忆语义。仅随包上提（改 `package`）；BM25 与嵌入器共用它的一致性保持不变。

## 交叉依赖（R0 = 三线检索的唯一真源）

R0 交付的**通用检索原语契约**是 Wave-1 后续 spec 的复用基座，delta spec 把它定清以供引用：

| 后续 spec（Wave-1） | 如何复用 R0 |
|---|---|
| **Tool-OS T2**（`tool_search` 升级为混合排序） | 定义 `ToolDocument implements SearchDocument`（`id`=工具名、`text`=名+描述+关键词），喂 `Bm25Index` + `HybridRanker`，替换现关键词匹配。**不再自造评分**。 |
| **Memory M-A**（memory-retrieval-injection，**并行**） | 仅依赖 `MemorySearchIndex.search(...)` **稳定门面**（D4）注入检索结果；不碰原语内部。**R0 先合**。 |
| **Memory M-B / M-D**（后续记忆增强） | 复用 `SearchDocument`/`Bm25Index`/`HybridRanker`（顺序依赖，R0 归档后细化）。 |
| **Skills S2**（技能匹配） | 定义 `SkillDocument implements SearchDocument`（`text`=`SKILL.md` 元数据/`when-to-use`），同源排序。 |

契约要点（供后续引用）：**(1)** `SearchDocument = {String id(); String text();}`——`id` 全局唯一、`text` 参与打分的正文；域特有字段（如 `sourceLabel`）留在各自实现类。**(2)** `Bm25Index.index(List<? extends SearchDocument>)` + `score(String)→Map<String,Double>`。**(3)** `HybridRanker.rank(bm25Map, vectorMap, w1, w2, minScore, topK)→List<Scored>` 纯静态、跨线一致。**(4)** 记忆线门面 `MemorySearchIndex.search(String,int)→List<MemoryDocument>` 语义稳定。

## Risks / Trade-offs

- **R1 —（硬约束）现有 `hybrid-memory-search` 单测必须全绿**。风险：包移动/签名改动漏改 import 或破坏泛型推断。→ D2 通配符保证既有调用零改动；D6 门面/工具测试逐字不变作为回归护栏；tasks 验收 = `mvn -pl pig-agent-core -am test` + `mvn -pl pig-agent-tools test` + 全量 `mvn test` 全绿。
- **R2 — 与 M-A 并行的门面漂移**。风险：R0 若顺手改了门面签名，M-A 返工。→ D4 冻结门面（逐字不变）+ 明确 **R0 先合**；`MemorySearchIndexTest` 不改即门面未变的证据。
- **R3 — 抽象形状定小/定大**。→ spike（S1-S3）以代码证据定形 `{id,text}`；`sourceLabel` 明确排除。若 T2/S2 后续发现需要额外通用字段，走"接口默认方法/新增窄接口"扩展而非改现有契约（向后兼容）。
- **R4 — 包移动的下游 import 断裂**（`HybridMemorySearchTool`、`AgentBootstrap` 等）。→ spike S3 证实 blast radius 有界（`AgentBootstrap` 不引用 `MemoryDocument`；`HybridMemorySearchTool` 只经门面 + 读 `MemoryDocument` 访问器，`MemoryDocument` 不迁包）；tasks 收尾 `mvn -pl pig-agent-cli -am compile` 兜底。
- **R5 — `Bm25IndexTest` 夹具改写引入隐性行为变化**。→ D6 要求断言/期望值逐字相同、只换文档类型；用通用 `TestDoc` record 保证 `id()`/`text()` 语义与 `MemoryDocument` 一致。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 三线检索若各抄评分 → 漂移 | Why；D1/D3 抽 `SearchDocument` + 上提原语 | 落 tasks 2/3 |
| `HybridRanker` 已纯泛型、零耦合 | Spike S1；D1 | 落 tasks 1（确认）/3（随包移动） |
| `Bm25Index` 唯一耦合 = `index()` 的 `id/text` | Spike S2；D1/D2 | 落 tasks 1（确认）/3（签名放宽） |
| 通用接口只需 `{id,text}`，`sourceLabel` 排除 | Spike S3；D1 | 落 tasks 2 |
| 向后兼容命门 = `List<? extends SearchDocument>` | D2 | 落 tasks 3 + 验收 tasks 5 |
| `MemorySearchIndex` 门面逐字稳定（M-A 锚点） | D4；R2 | 落 tasks 4；门面测试不改（tasks 5） |
| R0 与 M-A 并行 → R0 先合 | Context；D4；R2 | 记为发布顺序约束 |
| `MemorySearchConfig` 不泛化 | D5；Non-Goals | 记为 R0 范围外（延后/另 spec） |
| `Tokenizer` 通用直接复用 | Spike；D7 | 落 tasks 3 |
| blast radius 有界（`AgentBootstrap` 不碰 `MemoryDocument`） | Spike S3；R4 | 落 tasks 1（枚举消费方）/6（编译兜底） |
| 现有单测全绿（硬约束） | R1；D6 | 落 tasks 5（门面测试不改）+ tasks 7（全量验收） |
| 原语测试随类迁移、行为等价 | D6；R5 | 落 tasks 5 |
| 供 T2/S2/M-B/M-D 复用的契约 | 交叉依赖表 | 落 delta spec + tasks 2 |
