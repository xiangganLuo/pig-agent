## Context

pig 的技能读栈（capability `builtin-skills` + `composite-skill`，已在 main，`io.pigagent.tool.skills`）由几块组成：

- **句柄**：`Skill`（`name()` + 惰性 `content()` + 廉价 `metadata()` + `supportingFiles()`）；`SkillMetadata`（record，含 `name`/`description`/`keywords`/`version`）。
- **多源合并**：`SkillRegistry` 组合若干 `SkillSource`（内置 `ClasspathSkillSource` + 工作区 `WorkspaceSkillSource` + S1 的 `NativeRepositorySkillSource`），按名去重、工作区/高优先源覆盖。
- **暴露**：`SkillsTool`（`@Tool listSkills()` 无参、`@Tool loadSkill(String)`）。`listSkills` **只读 `metadata()`**（渐进加载，不读正文），把技能**按名排序全量平铺**：`- <name>` 或 `- <name> — <description>`，空集合 `"No skills found."`。`SkillMetadata.keywords`/`description` **完全不参与排序**。

同时 R0（`shared-retrieval`，已合并）已把检索原语上提到 `io.pigagent.core.search`：`SearchDocument{id(),text()}`、`Bm25Index.index(List<? extends SearchDocument>)` + `score(String)→Map<String,Double>`、`HybridRanker.rank(bm25Map, vectorMap, w1, w2, minScore, topK)`、CJK `Tokenizer`。T2（`hybrid-tool-search-and-smart-defer`，已合并）已用 `ToolDocument implements SearchDocument`（`id`=工具名、`text`=名+描述+关键词）落地工具检索的同源排序范式（见 `pig-agent-tools/.../deferred/ToolDocument.java` + `DeferredToolRegistry.search`）。R0 立项时明确：Skills S2 应定义 `SkillDocument implements SearchDocument` 复用同一 ranker、不再自造评分。本 spec 就是这一承诺的兑现。

`pig-agent-tools` 的 `pom.xml` 已依赖 `pig-agent-core`（T2 的 `ToolDocument` 即 `import io.pigagent.core.search.*`），故技能侧复用 **无需新增模块依赖**。

## Goals / Non-Goals

**Goals**
- 技能匹配**同源复用** `io.pigagent.core.search`（BM25，可选叠加 `HybridRanker`），与 `memory_search`/`tool_search` 一套 ranker、一套分词，杜绝评分漂移（兑现 R0「三线同源」）。
- 经**独立只读工具** `skill_search(query)` 返回 top-K 最相关技能（补齐 `memory_search`/`tool_search`/`skill_search` 同源三件套），**config 开关默认关 → 不注册、技能读栈逐字节不变**（backward-safe）。
- 零对外契约回归：`listSkills`/`loadSkill` 的 `@Tool` 名/签名/schema/返回语义在**任何配置下**永远逐字不变（技能匹配以独立新工具暴露，不动既有两个读栈工具；与 S1 的 `@Tool` 字节稳定红线一致）。
- 排序**只读廉价 metadata**（名+描述+keywords），MUST NOT 读技能正文——渐进加载不回归。

**Non-Goals**
- 不给技能匹配接**向量嵌入**（R0 的向量/嵌入层仍留 `memory.search`、仅记忆线消费）：技能条目少（内置 7 + 少量工作区）、`text` 短，BM25 已足够；`HybridRanker` 以"空向量 → BM25-only"降级消费即可，向量层按 YAGNI 待真有需求再上（见 D3）。
- 不改 `SkillRegistry` 的多源合并/去重/工作区覆盖语义、`Skill`/`SkillMetadata`/`SkillSource` 契约、`loadSkill` 行为。
- 不做 S3 的技能 **usage/老化/晋级**（正交，见「交叉依赖」）——本 spec 只管**检索相关性排序**，不管技能新陈代谢。
- 不改 `shared-retrieval`（R0）的任何 REQUIREMENT——只消费其契约。
- 不做技能持久化索引、rerank 二阶段、语义 query 改写（未来）。

## Spike（前置 Task 组 1 —— 承重：确认共享检索原语可脱离记忆/工具语料独立索引技能元数据）

> 本 spec 的承重假设是"R0 的 `Bm25Index`/`Tokenizer` 能**脱离记忆语料**、独立索引技能元数据"。若这条不成立，整个"同源复用"落不了地。以下结论已在本机代码核验（读 R0 源 + T2 落地 + 既有测试）。

**结论：成立。** 依据：

- **S1 — `Bm25Index` 已与文档类型解耦**：R0 后 `Bm25Index.index(List<? extends SearchDocument>)` 只读 `doc.text()`（分词）+ `doc.id()`（记 docId）；`score(String)→Map<String,Double>` 按 `id` 计分、不含任何文档类型。故任何 `implements SearchDocument` 的类型（含技能文档）都能索引 + 打分。
- **S2 — T2 已用非记忆文档实证泛型可用**：`ToolDocumentSpikeTest`（`pig-agent-tools`）用 `ToolDocument implements SearchDocument`（`id`=工具名、`text`=名+描述+关键词，**非记忆语料**）喂 `Bm25Index`，断言"更相关排前 + 中文命中中文描述 + `HybridRanker` 空向量 BM25-only top-K"。技能文档与之**同范式**，只需把 `DeferredTool` 换成 `Skill.metadata()`。
- **S3 — 分词对技能元数据 + 中文可用**：`Tokenizer` 对 latin 词元小写化、对 CJK 做 unigram+bigram（`Bm25IndexTest` 有"罗湘赣"中文用例、`ToolDocumentSpikeTest` 有"查询城市天气预报"中文用例）。技能名/描述常中英混排（内置技能英文名 + 用户工作区可能中文描述），分词天然覆盖。
- **S4 — 无模块依赖新增**：`pig-agent-tools` 已依赖 `pig-agent-core`，`import io.pigagent.core.search.*` 即可（T2 已如此）。

**Task 组 1 的验收动作**（在 `/ls:code` 阶段执行，镜像 `ToolDocumentSpikeTest`）：写一个小离线验证 `SkillDocumentSpikeTest`——构造若干 `SkillDocument(id=技能名, text=名+描述+keywords) implements SearchDocument`，`Bm25Index.index(...)` + 某 query `score(...)`/`HybridRanker.rank(...)`，断言"更相关技能排前、中文技能名/描述以中文 query 命中"。走主路径，无需回退方案。

## Decisions

- **D1 — `SkillDocument implements io.pigagent.core.search.SearchDocument`（镜像 T2 `ToolDocument`）**。`id`=技能名（`Skill.name()`，与 `listSkills` 行/`loadSkill` 解析一致），`text`=名 + 描述 + keywords 拼接（描述/keywords 均取自 `Skill.metadata()`——`keywords` 携带名字派生不出的触发词，提升召回，正如 T2 的 `ToolDocument` 用 keywords 承载 camelCase/CJK 切分）。仅 `import io.pigagent.core.search`，不牵扯 `io.pigagent.core.memory.search`。**关键约束（渐进加载）**：`text` 只从 `metadata()` 构造，MUST NOT 触 `Skill.content()`——列举/排序永不读正文（省 token，与 `listSkills` 今日渐进契约一致）。
- **D2 —（暴露方式，coordinator 定稿）独立只读工具 `skill_search(query)`，config 门控默认关不注册；`listSkills`/`loadSkill` 完全不动**。语义：
  - 新增 `@Tool name="skill_search"`（`readOnly=true`）：输入 query → 经共享 ranker 返回 top-K 最相关技能（行格式 `- <name>` / `- <name> — <desc>` 与 `listSkills` 平铺一致）。镜像 `ToolSearchTool`（`tool_search`）的形态与契约。
  - `skills.matching.enabled=false`（默认）→ `AgentBootstrap` **不注册** `skill_search` → 它不进模型 schema，初始工具 schema 与引入本能力前**逐字节一致**（镜像 `tool_search` 的条件注册）；`enabled=true` → 注册。
  - **红线兑现（最强向后兼容）**：`listSkills`/`loadSkill` 的 `@Tool` 名/签名/schema/返回语义**在任何配置下逐字不变**（技能匹配是一个全新工具，不碰既有两个读栈工具）；默认关时模型面零变化。
  - **决策理由（coordinator）**：R0 的前提是「三线同源一套 ranker」，而 `memory_search`/`tool_search` 都是**独立检索工具**，`skill_search` 补齐对称三件套更一致、更干净，且 `listSkills` **100% 保持不动**（向后兼容最强）。
  - *此前备选（已被 coordinator 否决，记录以备追溯）*：在 `listSkills` 上加可选 `query` 入参（config 二选一注册无参/带参变体）。否决理由：AgentScope `@Tool` schema 是静态注解，"默认关 schema 逐字节不变 + 名不变 + 开启才多一个可选入参"需要注册期二选一同名变体，较独立工具更绕；且独立工具与 `memory_search`/`tool_search` 命名对称、`listSkills` 零改动，向后兼容更强。
  - **落点**：`SkillSearchTool`（`pig-agent-tools`，镜像 `ToolSearchTool`）持一个 `SkillRegistry` + `top-k`/`min-score`；`SkillsTool` 增一个 `registry()` 访问器（非 `@Tool`，供 `skill_search` 与 `listSkills` **共享同一 registry**、排序集与列举集同源）；`AgentBootstrap` 在 `ToolContractGuard.install` **前**条件注册（被 guard 包裹、READ_ONLY 分级生效）。
- **D3 — 技能匹配只用 BM25（+`HybridRanker` 空向量降级），不接嵌入**（镜像 T2 D2）。技能条目少（几十量级）、`text` 短，BM25 的词频/IDF 已足够区分；接 `/embeddings` 会引入网络/凭据/延迟，收益不明（违 YAGNI）。用 `HybridRanker.rank(bm25Map, 空向量图, 1.0, 0.0, minScore, topK)` 而非直接取 `Bm25Index.score` 排序，是为了**与记忆/工具线走同一融合/归一化出口**（口径一致），向量权重传 0 / 向量图传空 → 等价 BM25-only（R0 已保证降级）。
- **D4 — 新 capability `skill-matching`，不 MODIFIED `builtin-skills`**（镜像 S1 `native-skill-engine-bridge` / S3 `skill-curator-and-graded-promotion` 各自新 capability 的做法）。理由：本能力是**默认关的正交叠加**，不改写 `builtin-skills` 既有 REQUIREMENT（`listSkills` 合并去重/渐进加载/工作区覆盖等在默认关时逐字保持）；把「`@Tool` 字节稳定红线」「默认关零回归」作为**新能力自身的 REQUIREMENT** 承载，delta 自洽、不牵动 `builtin-skills` 主 spec。*备选*：MODIFIED `builtin-skills` 的「listSkills 合并去重」——否决，会把一条 opt-in 能力的行为塞进 always-on 主 spec，且需重述整条既有 requirement（openspec MODIFIED 要求全量重贴），得不偿失。
- **D5 — config `skills.matching` 子块，默认关、字段全可选/默认安全**。`enabled`（默认 **false**）、`top-k`（默认 10）、`min-score`（默认 0.0）。挂在既有 `PigAgentConfig.SkillsConfig` 下（与 `autonomous`/`native`/`curator` 并列）。`enabled=false` → `AgentBootstrap` **不注册** `skill_search`、不装配任何匹配 seam。null/缺块安全（getter 归默认）、非法值 clamp（`top-k<1`→默认、`min-score` 负→0）。不引入向量/权重 config（D3，YAGNI）。
- **D6 — 索引懒建 + 按"当前读栈"集合重建，无需节流**。技能集小、变更少（不像记忆语料按 mtime 频繁变），每次非空-query `skill_search` 对 `SkillRegistry.all()` 快照建一次 `Bm25Index`（镜像 `DeferredToolRegistry.search` 的"每次搜索建索引"，条目少、重建成本可忽略），避免维护跨调用缓存的复杂度与失效问题。排序输入始终与 `listSkills` 平铺看到的同一技能集**同源**（同一 `registry.all()`），故 top-K 是平铺集合的子集、去重/覆盖语义一致。
- **D7 — 无匹配/空 query 的降级：回退平铺全部（不报错、不空）**。`skill_search` 的 query 非空但无 in-vocab 命中、或 query 为空时，回退为**平铺全部**（而非 `"No skills found."` 或空）——保证技能始终可被模型发现、不因排序而"藏起来"（对小技能集这是最安全的 UX）。复用与 `listSkills` 一致的平铺格式，边界字符串（`"No skills found."` 仅在**技能集本身为空**时）保持。

## 交叉依赖

| 依赖对象 | 关系 |
|---|---|
| **R0 `shared-retrieval`（已合并）** | 本 spec 消费其 `SearchDocument`/`Bm25Index`/`HybridRanker`/`Tokenizer` 做技能排序，**不改**其任何 REQUIREMENT。是 R0 delta「后续检索线只 import `core.search` 做 BM25 排序」场景的**第三个消费方**（定义 `SkillDocument implements SearchDocument`，只 import `io.pigagent.core.search`、不牵扯记忆域），兑现 R0「三线同源」——memory_search（M-A）/ tool_search（T2）/ skill matching（本 spec）**一套 ranker、一套分词**，同一 query 在三线排序口径一致、杜绝评分漂移。 |
| **T2 `hybrid-tool-search-and-smart-defer`（已合并）** | **范式先例**：`SkillDocument` 逐点镜像 `ToolDocument`（`id`=名、`text`=名+描述+keywords、`HybridRanker` 空向量 BM25-only、CJK 分词）。无代码耦合（各自定义 `SearchDocument` 实现），仅复用同一内核包。 |
| **S3 `skill-curator-and-graded-promotion`（已合并）** | **正交**：S3 管技能 **usage 分析 / 老化归档 / 分级晋级**（技能"新陈代谢"），本 spec 管**检索相关性匹配**。二者可叠加而不冲突：S3 的 `SkillUsageRecorder`/归档影响的是"哪些技能在读栈里"（`SkillRegistry.all()`），本 spec 只对"读栈里的技能"按 query 排序。**未来可组合排序**（相关性 × usage/新鲜度加权）是自然的下一步，但**本 spec 不做**（YAGNI，待真有需求）——现在只按相关性排。 |
| **S1 `native-skill-engine-bridge` 的 `@Tool` 字节稳定红线** | 与 S1 一致：`SkillsTool` 的 `@Tool` 面（`listSkills`/`loadSkill`）在任何配置下字节稳定——本 spec 以**独立新工具** `skill_search` 暴露匹配、不动既有两个读栈工具，红线满足最强（`listSkills` 100% 不动）。本 spec 把这条红线作为自身 REQUIREMENT 明确承载。 |

契约要点（供后续引用）：技能侧定义 `SkillDocument implements SearchDocument`（`id`=技能名、`text`=名+描述+keywords，源自廉价 `metadata()`、不读正文），经 `Bm25Index.index(List<? extends SearchDocument>)` + `HybridRanker.rank(...)`（向量权重 0 → BM25-only）排序；**不自造评分**。

## Risks / Trade-offs

- **R1 — 排序质量 / 召回不足**。风险：BM25 对极短 query / 单 token 的排序对小技能集可能区分度低；纯关键词无语义（"调试崩溃" 未必命中 `systematic-debugging` 英文名）。→ `text` 纳入名+描述+**keywords**（内置技能 front-matter 已带 keywords，可补中文触发词提升召回）；`Tokenizer` 对名做 camelCase+CJK 切分；D7 无匹配回退平铺全部（永不"藏"技能）；tasks 加"更相关排前 + 中文命中"用例护栏。可接受的小差异：排序细节（非契约）。
- **R2 —（backward-safe 命门）默认开或默认路径破坏初始 schema 字节稳定**。→ D2/D5：`enabled` 默认 **false** + 默认**不注册** `skill_search`（schema 无该工具，与今日 100% 相同）；`listSkills`/`loadSkill` 是独立既有工具、根本不被本 spec 触碰；tasks 加"默认关时 schema 无 `skill_search` + `listSkills`/`loadSkill` 逐字不变"回归测试。
- **R3 — 渐进加载回归（排序误读正文）**。风险：为排序去读 `Skill.content()` → 列举不再廉价（违 `builtin-skills` 渐进契约）。→ D1 硬约束 `SkillDocument.text` 只从 `metadata()` 构造；tasks 加"排序路径不触 `content()`"护栏（用会抛/被断言不应调用的 `content()` 探针）。
- **R4 — 与 T2/M-A 的"同源"仅停留在 import 层，权重/降级各自漂移**。→ 三线都经 `HybridRanker` 同一融合出口；技能侧固定 BM25-only（D3，向量权重 0），不引入技能侧独立向量/权重 config（YAGNI）——口径一致由"同一 ranker + 同一 `Tokenizer`"保证。
- **R5 — 每次搜索重建索引的成本**。风险：大技能集下每次非空-query 重建 `Bm25Index` 有成本。→ 技能集小（几十量级），重建成本可忽略（D6，与 `DeferredToolRegistry.search` 同思路）；若未来技能数量级暴涨再引入按读栈变更节流的缓存（另 spec）。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 技能匹配与记忆/工具线漂移（三线同源） | Why；D1/D3；交叉依赖(R0/T2) | 落 tasks 组 2（`SkillDocument` + 排序） |
| 原语可脱离记忆/工具语料索引技能元数据（承重） | Spike S1–S4 | 落 tasks 组 1（spike 验证） |
| 复用 R0、不新增模块依赖 | Spike S4；D1 | 落 tasks 组 2 |
| 技能匹配不接嵌入（BM25-only） | D3；Non-Goals；R4 | 落 tasks 组 2（`HybridRanker` 空向量） |
| 独立 `skill_search` 工具，默认关不注册（backward-safe 命门） | D2；D5；R2 | 落 tasks 组 3（config + 条件注册 + 回归测试） |
| `listSkills`/`loadSkill` `@Tool` 面任何配置下字节稳定 | D2；交叉依赖(S1) | 落 delta spec（字节稳定 requirement）+ tasks 组 3 |
| `skill_search` 分级 READ_ONLY（对齐 tool_search/memory_search） | D2 | 落 tasks 组 3（`ToolRiskClassifier` 登记 + 用例） |
| 排序只读 metadata 不触正文（渐进加载不回归） | D1；R3 | 落 tasks 组 2（渐进护栏用例） |
| 无匹配/空 query 回退平铺（不报错不空） | D7 | 落 tasks 组 3 |
| 与 S3 usage/老化正交、未来可组合排序（本 spec 不做） | 交叉依赖(S3)；Non-Goals | 记为正交 + 未来项（design 说明） |
| 新 capability 而非 MODIFIED builtin-skills | D4 | 落 delta spec（`skill-matching` 新 capability） |
| 现有 `builtin-skills`/`composite-skill`/`shared-retrieval` 单测全绿（硬约束） | Goals | 落 tasks 组 4（验收全量测试） |
