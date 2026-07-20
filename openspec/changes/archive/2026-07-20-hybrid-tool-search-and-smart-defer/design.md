## Context

pig 的延迟工具层（capability `deferred-tools`，已在 main，`io.pigagent.tool.deferred`）由三块组成：

- **决策**：`DeferredToolPlanner`（纯函数）据 `tools.deferred`（`enabled`/显式 `tools` 清单/`auto-defer-mcp`+`threshold`）产出 `DeferralPlan`；`enabled` 默认 **false**。
- **应用**：`DeferredToolGate` 用 AgentScope 原生 tool group 把选中工具移入 **inactive 分组**（内置/插件走"重注册当前 `AgentTool` 到 per-tool 分组"，MCP 走"停用 attach 时的 `mcp:<server>` 分组"），并填充 `DeferredToolRegistry`；`reveal(toolkit, registry)` 是揭示 seam（激活分组 + `markRevealed`）。
- **搜索**：`ToolSearchTool`（`@Tool name="tool_search"`, `READ_ONLY`）→ `DeferredToolRegistry.search(query, limit)`。当前 `search` 用**朴素关键词重叠**打分（`score(...)`：整串子串命中 +5、关键词命中 +3、名称含 token +2、描述含 token +1）。

与此同时，记忆检索线（`hybrid-memory-search`）用的是 R0 已上提到 `io.pigagent.core.search` 的通用 ranker：`SearchDocument{id(),text()}` 契约、`Bm25Index.index(List<? extends SearchDocument>)` + `score(String)→Map<String,Double>`、`HybridRanker.rank(bm25Map, vectorMap, w1, w2, minScore, topK)`、CJK `Tokenizer`。R0 立项时明确：Tool-OS T2 应"定义 `ToolDocument implements SearchDocument`（`id`=工具名、`text`=名+描述+关键词），喂 `Bm25Index` + `HybridRanker`，替换现关键词匹配，**不再自造评分**"。本 spec 就是这一承诺的兑现，外加两处收尾：默认策略随规模智能开、揭示状态跨 `Toolkit.copy()` 一致。

`pig-agent-tools` 的 `pom.xml` 已依赖 `pig-agent-core`（现成消费 `io.pigagent.tool.contract` 之外的核心类），故复用 `io.pigagent.core.search` **无需新增模块依赖**。

## Goals / Non-Goals

**Goals**
- `tool_search`/`DeferredToolRegistry` 的排序**同源复用** `io.pigagent.core.search`（BM25，可选叠加 `HybridRanker` 混合），与 `memory_search`/技能匹配一套 ranker、一套分词，杜绝评分漂移。
- 延迟工具默认从「关」改为「随规模智能开」（`enabled` 默认 true），并以**阈值制**保证小工具集用户**逐字节无感**（backward-safe）。
- 修复揭示状态不随 `Toolkit.copy()` 传播的已知限制：揭示在发起 `tool_search` 的 agent 运行的 toolkit 实例上真正生效。
- 零对外契约变化：`tool_search` 的 `@Tool` 名/签名/只读分级、揭示/MCP 生命周期语义全部保持；现有单测保持绿。

**Non-Goals**
- 不改 `tool_search` 的风险分级（仍 `READ_ONLY`，`ToolRiskClassifier` 已登记）。
- 不改 `shared-retrieval`（R0）的任何 REQUIREMENT——只消费其契约。
- 不给工具检索接**向量嵌入**（R0 的向量/嵌入层仍留 `memory.search`、仅记忆线消费）：工具元数据短、条目少，BM25 已足够；`HybridRanker` 以"空向量 → BM25-only"降级消费即可，向量层按 YAGNI 待真有需求再上（见 D2）。
- 不改延迟机制本身（inactive group park/reveal）、`DeferralPlan`/`DeferredToolPlanner` 的**并集逻辑**（显式清单 ∪ 阈值规则）、`DeferredTool` 元数据字段、MCP attach-时分组策略。
- 不做工具的持久化索引、rerank 二阶段、跨会话揭示记忆（未来）。

## Spike（前置 Task 组 1 —— 承重：确认原语可脱离记忆语料独立索引工具元数据）

> 本 spec 的承重假设是"R0 的 `Bm25Index`/`Tokenizer` 能**脱离记忆语料**、独立索引工具元数据"。若这条不成立，整个"同源复用"落不了地。以下结论已在本机代码核验（读 R0 源 + 既有测试）。

**结论：成立。** 依据：

- **S1 — `Bm25Index` 已与文档类型解耦**：R0 后 `Bm25Index.index(List<? extends SearchDocument>)` 只读 `doc.text()`（分词）+ `doc.id()`（记 docId）；`score(String)→Map<String,Double>` 按 `id` 计分、不含任何文档类型。故任何 `implements SearchDocument` 的类型（含非记忆的工具文档）都能索引 + 打分。
- **S2 — 已有测试实证泛型可用**：`io.pigagent.core.search.Bm25IndexTest` 含 `indexesAnyCustomSearchDocumentImplementation`——用一个**匿名、非 `MemoryDocument`** 的 `SearchDocument` 实现喂 `Bm25Index`，能索引并按 `id` 打分。这正是"工具文档"场景的预演。
- **S3 — 分词对工具元数据 + 中文可用**：`Tokenizer` 对 latin 词元小写化、对 CJK 做 unigram+bigram（`Bm25IndexTest` 有"罗湘赣"中文用例）。工具名/描述常中英混排，分词天然覆盖。
- **S4 — 无模块依赖新增**：`pig-agent-tools` 已依赖 `pig-agent-core`，`import io.pigagent.core.search.*` 即可。

**Task 组 1 的验收动作**（在 `/ls:code` 阶段执行）：写一个小离线验证——构造若干 `ToolDocument(id=工具名, text=名+描述+关键词) implements SearchDocument`，`Bm25Index.index(...)` + 某 query `score(...)`/`HybridRanker.rank(...)`，断言"更相关的工具排前、中文 query 命中中文描述"。走主路径，无需回退方案。

## Decisions

- **D1 — 排序复用 `io.pigagent.core.search`，`DeferredToolRegistry` 内部建 `ToolDocument implements SearchDocument`**。`id`=工具名（`DeferredTool.name()`，与揭示/回映一致），`text`=名 + 描述 + 关键词拼接（关键词已由 `Keywords.from` 从名+描述派生，纳入 `text` 提升召回）。`search(query, limit)`：对当前仍延迟的工具集建 `Bm25Index`（或维护并按集合变更重建），`bm25.score(query)` 取分，`HybridRanker.rank(bm25Map, emptyVectorMap, w, 0, minScore, limit)` 归一化 + top-K（空向量图 → BM25-only 降级，R0 已保证）。**替换** `score(...)` 朴素计分。*备选*：保留朴素计分做 fallback——否决，理由 = 两套评分正是要消灭的漂移源（违 R0 立项初衷）。
- **D2 — 工具检索只用 BM25（+`HybridRanker` 空向量降级），不接嵌入**。工具条目少（几十个量级）、`text` 短，BM25 的词频/IDF 已足够区分；接 `/embeddings` 会引入网络/凭据/延迟，收益不明（违 YAGNI）。用 `HybridRanker` 而非直接取 `Bm25Index.score` 排序，是为了**与记忆线走同一融合/归一化出口**（口径一致），只是向量权重传 0 / 向量图传空 → 等价 BM25-only。若未来工具语义检索有需求，走 R0 的向量层上提，另 spec。
- **D3 — 默认策略「随规模智能开」= `enabled` 默认 `false → true`，阈值制托底 backward-safe**。`auto-defer-mcp`（默认 true）+ `threshold`（默认 25）不变。语义：`enabled=true` 时 `DeferredToolPlanner` 照常产出计划——**只有**工具总数 `>` 阈值才把 MCP 工具纳入延迟；未超阈值且显式清单为空 → 计划为空。*备选*：不动 `enabled`、只在文档里"建议开启"——否决，达不到"默认智能开"目标；或默认 `auto-defer-mcp=false`——否决，那样默认等于没开。
- **D4 — backward-safe 命门：计划为空时"逐字节无感"，`tool_search` 不注册、不分组、不隐藏**。这是 D3 能默认开的关键。现状接线（`AgentBootstrap`）在 MCP attach **之前**就注册 `tool_search`（为让 `ToolContractGuard` 包住它、且不重注册 MCP 工具），但计划要 attach **之后**才算得出（依赖 MCP 工具计数）。因此接线调整为：**先按现状注册 `tool_search`（保持被 guard 包裹）+ 设 MCP 分组 namer**（把 MCP 工具分入 `mcp:<server>` **active** 组——active 组不改 schema，schema-neutral），attach 后算计划；**若计划为空 → `removeTool("tool_search")` + 不停用任何组**，使初始 schema 与引入本能力前逐字节一致；**若计划非空 → 保留 `tool_search` + 停用被延迟工具的组**（今日行为）。判据：小工具集（无 MCP 或工具 ≤ 25）默认零可见变化；MCP 膨胀到 > 25 才见 `tool_search` + MCP 工具移出初始 schema（净省 token）。*备选*：`enabled=true` 就无条件注册 `tool_search`——否决，破坏"逐字节无感"（凭空多一个工具 + 其 guidance）。
- **D5 — 揭示状态跨 `Toolkit.copy()` 一致（承重修复）**。现状：`DeferredToolGate.reveal(toolkit, registry)` 闭包捕获**原始** `toolkit`；`ToolSearchTool` 单例被 `Toolkit.copy()` 按引用共享，故 peer/子agent 上下文里调用 `tool_search` 时，激活的是**原始** toolkit 的组，而该 agent 实际运行在 **copy** 上（组 active 态独立）→ 揭示对它无效（"揭示了却仍不可见/不可调用"）。**受影响面**：仅"经 copy 派生**且**仍含该延迟工具（parked 在 inactive 组）"的实例——即子agent `childToolkit(parent, allowed)` 继承全部工具（`allowed` 空）时、以及 peer `toolkitFor(full, names)` 的白名单**恰含**某延迟工具时；peer 白名单为空 → 返回共享 `full`（同一对象，无此 bug）。**修复方向（择一，`/ls:code` 前 spike 定形）**：(a) 揭示时对"该延迟工具存在的所有活跃 toolkit 实例"广播激活（登记表持有实例弱引用）；(b) 让 `tool_search` 经运行期上下文拿到"当前 agent 的 toolkit"再激活其组（reveal 不再绑死原始实例）；(c) per-agent toolkit 构造时共享底层组 active 态。倾向 (b)/(c)——语义最直：揭示在"发起搜索的 agent 自己的 toolkit"上生效。**spec 只约束可观察结果**（揭示在该 agent 运行的 toolkit 上真正生效、不因 copy 丢失），机制留 design + 承重 spike。
- **D6 — 揭示后重建索引 = 与登记表"仍延迟"集合同源**。`markRevealed` 已把工具移出"仍延迟"集合；混合排序只索引"仍延迟"集合，故揭示后该工具自然不再被搜为待发现（既有语义保持）。索引可懒建 + 按"仍延迟"集合变更重建（条目少，重建成本可忽略，无需节流；与记忆线的 mtime 节流不同源但同思路）。
- **D7 — prefix-cache 取舍：默认智能开改初始 schema、`tool_search` 中途 reveal 改 schema，均"会话内稳定"可接受**。"默认智能开"仅在**工具总数超阈值**时改变初始 tool schema，且该 schema 在会话内稳定（工具集不变）→ prefix-cache 友好。`tool_search` 中途揭示工具会使后续回合 schema 变化（一次 cache-miss），与记忆固化（`MEMORY.md` consolidation 后一次 cache-miss）**同性质**——是"按需扩展能力"的固有代价，换来的是初始每回合省 token；净收益为正。design 明确记录此取舍，不追求"揭示零 cache 影响"。

## 交叉依赖（本 spec = R0「Tool-OS T2 同源复用」的兑现）

| 依赖对象 | 关系 |
|---|---|
| **R0 `shared-retrieval`（已合并）** | 本 spec 消费其 `SearchDocument`/`Bm25Index`/`HybridRanker`/`Tokenizer` 做工具排序，**不改**其任何 REQUIREMENT。R0 delta spec 的"后续检索线只依赖通用包做 BM25 排序"场景，本 spec 是其首个工具侧消费方（定义 `ToolDocument implements SearchDocument`，只 import `io.pigagent.core.search`、不牵扯 `io.pigagent.core.memory.search` 记忆域）。 |
| **M-A `memory-retrieval-injection`（记忆线）** | 与本 spec **同源消费**一套 ranker/分词 → 同一 query 在工具/记忆里排序口径一致，这正是 R0 消灭"三套评分漂移"的目标。二者无代码耦合（各自定义 `SearchDocument` 实现），可独立推进。 |
| **S2 技能匹配（后续）** | 第三条消费线（`SkillDocument implements SearchDocument`）；本 spec 为其树立"新检索线只 import `core.search`"的范式。顺序上 S2 在其依赖 spec 归档后细化。 |

契约要点（供后续引用）：工具侧定义 `ToolDocument implements SearchDocument`（`id`=工具名、`text`=名+描述+关键词），经 `Bm25Index.index(List<? extends SearchDocument>)` + `HybridRanker.rank(...)`（向量权重 0 → BM25-only）排序；**不自造评分**。

## Risks / Trade-offs

- **R1 — 混合排序质量下降或行为回归**。风险：BM25 对极短 query / 单 token 的排序与朴素子串命中不同（如查工具全名）。→ `text` 纳入名+描述+关键词提升召回；`Tokenizer` 对名做 camelCase+CJK 切分；`ToolSearchTool` 的"无匹配 → 列出可搜索工具名"提示语义保持（BM25 空结果同样触发）；tasks 加"更相关工具排前 + 中文命中"用例护栏。可接受的小差异：排序细节变化（非契约）。
- **R2 —（backward-safe 命门）默认智能开破坏小工具集"逐字节无感"**。风险：`enabled=true` 后凭空注册 `tool_search` / 分组改变可见工具。→ D4 计划为空时 `removeTool("tool_search")`、不停用任何组、MCP 分入 active 组（schema-neutral）；tasks 加"工具 ≤ 阈值时初始 schema 无 `tool_search`、与 `enabled=false` 逐字节等价"回归测试。
- **R3 — 揭示跨 copy 修复触及框架 `Toolkit.copy()` 语义，成本不确定**。→ D5 列三条候选机制 + 承重 spike 先定形；spec 只约束可观察结果（揭示在该 agent 运行的 toolkit 生效）；受影响面已界定（仅 copy 且含该延迟工具的实例，peer 空白名单走共享 `full` 无 bug）。若 (b)/(c) 成本过高，(a) 广播激活为兜底。
- **R4 — 默认开后 MCP 密集用户的行为变化**。风险：MCP 工具超阈值后被移出初始 schema，模型需先 `tool_search` 才能用——多一步。→ 这是**预期收益**（省每回合 token、抑制幻觉调用），且仅在 > 25 工具时触发；`tool_search` 的 guidance 明确"需要没看到的能力就先搜"；`enabled=false` 逃生口保留。
- **R5 — 与 M-A 的"同源"仅停留在 import 层，实际权重/降级各自配置漂移**。→ 二者都经 `HybridRanker` 同一融合出口；工具侧固定 BM25-only（D2），记忆侧沿用其 config——口径一致由"同一 ranker + 同一 `Tokenizer`"保证，不引入工具侧独立权重 config（YAGNI）。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| `tool_search` 排序与记忆线漂移 | Why；D1/D2；交叉依赖 | 落 tasks 组 2（排序换 hybrid） |
| 原语可脱离记忆语料索引工具元数据（承重） | Spike S1–S4 | 落 tasks 组 1（spike 验证） |
| 复用 R0、不新增模块依赖 | Spike S4；D1 | 落 tasks 组 2 |
| 工具检索不接嵌入（BM25-only） | D2；Non-Goals；R5 | 落 tasks 组 2（`HybridRanker` 空向量） |
| 默认从关改随规模智能开 | D3 | 落 tasks 组 3（config 默认 + planner） |
| 计划为空时逐字节无感（backward-safe 命门） | D4；R2 | 落 tasks 组 3（接线 + 回归测试） |
| `tool_search` 只读分级不变 | Non-Goals | 落 delta spec（保留 READ_ONLY 场景） |
| 揭示状态跨 `Toolkit.copy()` 一致 | D5；R3 | 落 tasks 组 4（spike 定形 + 修复） |
| 揭示后不再被搜为待发现（既有语义保持） | D6 | 落 tasks 组 2 |
| prefix-cache 取舍（默认开/中途 reveal） | D7 | 记为已接受取舍（design 说明） |
| 现有 `deferred-tools`/`shared-retrieval` 单测全绿（硬约束） | Goals | 落 tasks 组 5（验收全量测试） |
