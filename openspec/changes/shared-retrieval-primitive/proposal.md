## Why

pig 目前只有**一套**离线检索原语（BM25 + 向量混合 ranker），它藏在 `io.pigagent.core.memory.search` 里、绑死在**记忆检索**这一条线上。内核路线图 Wave-1 会新增另外两条要检索的线——**工具检索**（Tool-OS T2 的 `tool_search`，从关键词匹配升级为同款 BM25+向量排序）与**技能匹配**（Skills S2，按 query 排 `SKILL.md`）。若各自再抄一套评分逻辑，必然出现**三套评分漂移**（同一 query 在记忆/工具/技能里排序口径不一致、权重/归一化/分词各修各的），既违反 DRY 又难维护。

本 spec 把这套检索原语**泛型化提升**为通用 `io.pigagent.core.search`，让三线**同源消费一套 ranker**。这是纯重构、**零行为变化**——不是新功能，而是把既有能力从"记忆私有"抽成"内核共享基座"，供 Wave-1 后续 spec 直接复用。它是三线检索的**唯一真源**，因此排在 Wave-1 前置：与并行的 M-A（memory-retrieval-injection）同期落地时 **R0 先合**。

## What Changes

- **新增通用 `SearchDocument` 抽象**（`io.pigagent.core.search`）：一个最小接口 `{ String id(); String text(); }`——正是 `Bm25Index.index(...)` 索引一个文档所需的全部（spike 证实，见 design.md）。**不含** `sourceLabel`（属记忆域，留在 `MemoryDocument`）。三线各自的文档类型（现在的 `MemoryDocument`、未来的工具/技能文档）只需 `implements SearchDocument` 即可喂给同一个 ranker。
- **`MemoryDocument` 实现 `SearchDocument`**：现有 3 字段 record（`id`/`sourceLabel`/`text`）新增 `implements SearchDocument`——`id()`/`text()` 已是其访问器，零字段改动、零语义改动。
- **`Bm25Index.index(...)` 改吃 `List<? extends SearchDocument>`**（原为 `List<MemoryDocument>`）：内部本就只用 `doc.id()` + `doc.text()`（spike 证实是**唯一**的 `MemoryDocument` 耦合点）。通配符 `? extends` 是向后兼容命门——保证既有调用点（`MemorySearchIndex` 传 `List<MemoryDocument>`、`Bm25IndexTest` 传 `List.of(new MemoryDocument(...))`）**零改动**通过（详见 design.md D2）。
- **通用原语上提到 `io.pigagent.core.search`**：`Bm25Index`、`HybridRanker`、`Tokenizer`（CJK unigram+bigram，保持通用直接复用）、`VectorStore`、`InMemoryVectorStore`、`Embedder`、`DeterministicEmbedder`、`OpenAiCompatibleEmbedder`、`Vectors` 迁入新包（这些已零业务耦合）。**`HybridRanker.rank(...)` 已是纯静态泛型函数（`Map<String,Double>`→ranked），完全不动**（已核实）。
- **记忆域类型留在 `io.pigagent.core.memory.search`**：`MemoryDocument`、`MemoryCorpusLoader`（语料来源 `MEMORY.md`/`memory/*.md`/`USER.md` + chunk 策略）、`MemorySearchIndex`（记忆检索门面）、`MemorySearchConfig`——它们是记忆线私有编排，import 变更消费新包的原语，对外契约不变。
- **`MemorySearchIndex` 公共门面逐字稳定**（关键约束）：`List<MemoryDocument> search(String, int)` / `vectorEnabled()` / `invalidate()` / 构造器签名**语义不变**。M-A 只依赖这个门面，故它必须先稳定。

无 **BREAKING**：这是纯内部重构（包移动 + 接口抽取 + 一处签名放宽），**无任何行为/配置/模型接口变化**。`memory.search.hybrid-enabled` 语义、`memory_search` 工具、BM25-only 降级、fault-tolerant/never-throws 语义全部逐字保持。硬约束：现有 `hybrid-memory-search` 全部单测保持绿。

## Capabilities

### New Capabilities
- `shared-retrieval`: 内核级**通用离线检索原语**——`SearchDocument`（`{id,text}` 契约）+ 复用 `Bm25Index`（吃 `List<? extends SearchDocument>`）/ `HybridRanker`（纯泛型 0.7·BM25+0.3·cosine 混合）/ `Tokenizer`（CJK）/ `VectorStore`+`Embedder` seam，供记忆检索、工具检索（Tool-OS T2）、技能匹配（Skills S2）三线**同源消费一套 ranker**，杜绝评分漂移。记忆检索门面 `MemorySearchIndex.search(...)` 契约稳定。

### Modified Capabilities
<!-- 不改写既有 hybrid-memory-search 主 spec 的任何 REQUIREMENT：本能力是把其内部实现的检索原语抽成内核共享基座，行为逐字不变、对外契约（memory_search @Tool、门面 search、默认关的 hybrid-enabled）全部保持。故此处为空——纯重构不产生 spec 级行为变化。归档时（/ls:archive）在 openspec/specs/ 新增 shared-retrieval 主 spec。 -->

## Impact

- **代码（纯重构，零行为变化）**：
  - `pig-agent-core` 新增包 `io.pigagent.core.search`：新增 `SearchDocument` 接口；从 `io.pigagent.core.memory.search` 迁入 `Bm25Index`/`HybridRanker`/`Tokenizer`/`VectorStore`/`InMemoryVectorStore`/`Embedder`/`DeterministicEmbedder`/`OpenAiCompatibleEmbedder`/`Vectors`。
  - `pig-agent-core` `io.pigagent.core.memory.search` 保留 `MemoryDocument`（新增 `implements SearchDocument`）/`MemoryCorpusLoader`/`MemorySearchIndex`/`MemorySearchConfig`，import 更新指向新包。
  - `Bm25Index.index` 签名 `List<MemoryDocument>` → `List<? extends SearchDocument>`（唯一签名变更）。
- **不改**：`HybridRanker`（已纯泛型，逐字不动）；`MemorySearchIndex` 公共门面签名/语义；`HybridMemorySearchTool`（`@Tool name="memory_search"`，仍消费门面 + 读 `hit.sourceLabel()`/`hit.text()`）；`AgentBootstrap` 接线（按类型构造 `MemorySearchIndex`/`MemoryCorpusLoader`/工具，不触碰 `MemoryDocument`——已核实无引用）；`MemorySearchConfig`（不进 R0 范围，见 design.md D5）；配置、权限、渠道语义。
- **测试（离线，硬约束=全绿）**：门面/工具测试（`MemorySearchIndexTest`、`HybridMemorySearchToolTest`）**逐字不改**并保持绿——即"门面稳定"的证据；原语测试（`Bm25IndexTest`、`HybridRankerTest`）随被测类迁到 `io.pigagent.core.search` 测试包，`HybridRankerTest` 仅改包声明、`Bm25IndexTest` 用通用 `SearchDocument` 夹具，**断言与 BM25 排序结果逐字相同**（含中文"罗湘赣"用例）。新增小测：`MemoryDocument instanceof SearchDocument` + `Bm25Index` 直接吃自定义 `SearchDocument` 实现。
- **交叉依赖（供后续 spec 引用）**：R0 是 Tool-OS T2、Memory M-A/M-B/M-D、Skills S2 的唯一检索真源，delta spec 定清「`SearchDocument` 契约 + 稳定门面」供其复用（见 design.md「交叉依赖」）。
- **文档**：`CLAUDE.md` 的 `hybrid-memory-search` 段落增补一句「检索原语已上提为 `io.pigagent.core.search` 通用基座，三线共享」。
