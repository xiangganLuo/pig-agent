## Why

pig 的技能读栈（`SkillsTool.listSkills`/`loadSkill` + `SkillRegistry`）至今**只会平铺**：`listSkills` 把所有技能按名排序全量列出，`SkillMetadata` 里的 `description`/`keywords` **完全不参与检索/排序**。技能一多（内置 7 个 + 工作区 + 未来 curator 沉淀），模型要"按当前任务找对技能"就得读一长串再自己挑——既费 token 又易错。

与此同时，内核路线图 R0（`shared-retrieval`，已合并）已把检索原语泛型化上提到 `io.pigagent.core.search`（`SearchDocument{id,text}` + `Bm25Index.index(List<? extends SearchDocument>)` + `HybridRanker` + CJK `Tokenizer`），**就是为了让记忆检索、工具检索（Tool-OS T2 已用 `ToolDocument` 落地）、技能匹配（Skills S2）三线同源复用一套 ranker**，杜绝三套评分漂移。本 spec 是 R0「三线同源」的**第三个消费者**：按任务语义匹配技能，复用与 `memory_search`/`tool_search` 完全同源的 ranker/分词。

## What Changes

- **新增 `SkillDocument implements io.pigagent.core.search.SearchDocument`**（`pig-agent-tools`，镜像 T2 的 `ToolDocument`）：`id`=技能名、`text`=名+描述+keywords，三者均取自廉价的 `Skill.metadata()`（**不读正文**，保持渐进加载）。经 `Bm25Index` +（可选）`HybridRanker`（空向量图 → BM25-only 降级）+ CJK `Tokenizer` 对 query 排序，**不自造评分**；只 `import io.pigagent.core.search`，不牵扯任何 `io.pigagent.core.memory.search` 记忆域类型。
- **`listSkills` 可选按 query 返回 top-K 最相关**（新增能力，config 开关，**默认关**）：`skills.matching.enabled=false`（默认）→ `listSkills` **逐字节不变**（无 query 入参、平铺全部按名排序、既有边界字符串全保持）；`enabled=true` → `listSkills` 接受一个**可选** `query` 入参——空/缺省仍平铺全部（与今日一致），非空则经共享 ranker 返回 top-K。
- **`SkillsTool` 的 `@Tool` 面零回归**：`loadSkill` 的名/签名/返回语义**永远逐字不变**；`listSkills` 的 `@Tool` **名不变**，默认关时其 schema/输出**逐字节不变**——新增能力仅在显式开启时经**可选入参**暴露，不改默认签名语义（与 S1 的 `@Tool` 字节稳定红线一致）。
- **新增 config 块 `skills.matching`**（`enabled` 默认 false、`top-k`、`min-score`，全可选/默认安全/null 缺块安全/非法值 clamp）。

无 **BREAKING**：默认关 → 技能读栈（`listSkills`/`loadSkill`/`SkillRegistry`）与引入本能力前**逐字节一致**；`SkillsTool` 现有 `@Tool` 对外契约（工具名、`loadSkill` 签名、平铺列举语义）全部保持。硬约束：现有 `builtin-skills`/`composite-skill`/`shared-retrieval` 全部单测保持绿。

## Capabilities

### New Capabilities
- `skill-matching`: 按任务语义匹配技能——把每个技能建成 `SkillDocument implements SearchDocument`（`id`=名、`text`=名+描述+keywords，取自廉价 metadata），复用内核共享检索原语 `io.pigagent.core.search`（BM25 + CJK 分词，`HybridRanker` 空向量→BM25-only）对 query 排序，`listSkills` 可选返回 top-K；**默认关 → 技能读栈逐字节不变**。是 R0「三线同源」的第三个消费者（memory_search / tool_search / skill matching 一套 ranker）。

### Modified Capabilities
<!-- 无。本 spec 不改写 builtin-skills 既有 REQUIREMENT：新增能力默认关 → listSkills/loadSkill/SkillRegistry 逐字节不变，byte-stability 红线作为新能力自身的一条 REQUIREMENT 承载（镜像 S1 native-skill-engine-bridge / S3 skill-curator 的做法——各自新 capability，不 MODIFIED builtin-skills）。检索原语来自已合并的 shared-retrieval（R0），此处仅消费其契约、不改其 REQUIREMENT。 -->

## Impact

- **代码（新能力，默认关时对读栈无感）**：
  - `pig-agent-tools` `io.pigagent.tool.skills`：新增内部 `SkillDocument implements io.pigagent.core.search.SearchDocument`（`of(Skill)`：`id`=名、`text`=名+描述+keywords，源自 `metadata()`）；`SkillsTool`/一个匹配器把仍在读栈的技能建索引 + 排序（`Bm25Index.index` → `bm25.score(query)` → `HybridRanker.rank(bm25, 空向量, 1.0, 0, minScore, topK)`）。`pig-agent-tools` 已依赖 `pig-agent-core`，**无需新增模块依赖**。
  - `pig-agent-config` `PigAgentConfig.SkillsConfig`：新增 `matching` 子块（`MatchingSkillsConfig`：`enabled` 默认 false、`top-k`、`min-score`）。
  - `pig-agent-cli` `AgentBootstrap`/`SkillsToolProvider`：据 `skills.matching.enabled` 注册合适的 `listSkills` 变体（关→今日无参变体；开→带可选 query 变体），使默认关时初始 schema 与引入前逐字节一致（具体注册机制见 design.md D2，`/ls:code` 定形）。
- **不改**：`loadSkill` 的 `@Tool` 契约与返回语义；`SkillRegistry` 的多源合并/去重/工作区覆盖语义；`SkillMetadata`/`Skill`/`SkillSource` 契约；`shared-retrieval`（R0）的任何 REQUIREMENT（仅消费其契约）；`skill-curator-and-graded-promotion`（S3）的 usage/老化/晋级（正交，见 design.md「交叉依赖」）；权限/可用性/返回契约守卫。
- **依赖**：消费已合并的 `shared-retrieval`（R0）——本 spec 是 R0「Skills S2 同源复用」承诺的兑现（design.md「交叉依赖」）。
- **测试（离线，硬约束=全绿）**：spike（`SkillDocument` 索引 + 排序，含中文技能命中）；`listSkills` 默认关逐字节不变（无 query、平铺全部）+ 开启后非空 query 返回 top-K + 空 query 回退平铺 + 无匹配回退平铺（不报错）；`SkillDocument` 只读 metadata 不触正文（渐进加载护栏）；现有 `builtin-skills`/`composite-skill`/`shared-retrieval` 单测保持绿。
- **文档**：`CLAUDE.md`「Built-in skills」段落增补——技能匹配已同源复用 `io.pigagent.core.search`（三线同源第三个消费者），`listSkills` 可选 top-K（默认关字节稳定）。
