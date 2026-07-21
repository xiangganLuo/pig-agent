## ADDED Requirements

### Requirement: 技能匹配复用共享检索原语（SkillDocument）

系统 SHALL 提供按任务语义匹配技能的能力：把每个技能建成一个通用检索文档 `SkillDocument implements io.pigagent.core.search.SearchDocument`（`id`=技能名、`text`=技能名 + 描述 + keywords），复用内核共享检索原语 `io.pigagent.core.search`（`Bm25Index` + `HybridRanker` + CJK `Tokenizer`）对查询打分排序，MUST NOT 自造关键词计分。`SkillDocument.text` MUST 仅取自技能的**廉价元数据** `Skill.metadata()`（名 + 描述 + keywords），MUST NOT 读取技能正文（`Skill.content()`）——排序路径与 `listSkills` 的渐进加载契约一致。技能匹配 SHALL 只用 BM25（经 `HybridRanker` 以空向量图降级为 BM25-only），MUST NOT 为技能匹配引入向量嵌入。该匹配 MUST 与 `memory_search`、`tool_search` **同源**——共用同一 ranker 与同一 `Tokenizer`，同一查询在三线的排序口径一致。匹配的技能集 MUST 与 `listSkills` 平铺看到的技能集同源（同一 `SkillRegistry` 的合并去重结果），故 top-K 是该集合的子集、覆盖/去重语义一致。

#### Scenario: 更相关的技能排在前

- **WHEN** 技能匹配开启，以某查询对多个技能（名/描述/keywords 部分匹配）打分排序
- **THEN** 经共享检索原语（BM25，经 `HybridRanker` 空向量 BM25-only）打分后，与查询更相关的技能排在返回结果的前面

#### Scenario: 中文查询命中含中文名/描述的技能

- **WHEN** 存在描述或名字为中文的技能，以对应中文查询做技能匹配
- **THEN** 该技能被命中并排前（CJK unigram+bigram 分词生效，与记忆/工具检索一致）

#### Scenario: 排序只读元数据不触正文（渐进加载不回归）

- **WHEN** 一个技能的 `content()` 会抛异常/被断言不应被调用，触发技能匹配排序
- **THEN** 排序只经该技能的 `metadata()`（名 + 描述 + keywords）构造 `SkillDocument.text`，MUST NOT 触发其 `content()`

#### Scenario: 只依赖内核共享检索包

- **WHEN** 审视 `SkillDocument` 及技能匹配排序逻辑的依赖
- **THEN** 仅依赖 `io.pigagent.core.search`（`SearchDocument`/`Bm25Index`/`HybridRanker`/`Tokenizer`），不 import 任何 `io.pigagent.core.memory.search` 记忆域类型

### Requirement: listSkills 可选按查询返回 top-K（默认关零回归）

`listSkills` SHALL 可选地按查询返回 top-K 最相关的技能，且该能力 MUST 由配置 `skills.matching.enabled` 门控、**默认关闭**。当技能匹配**关闭**（默认）时，`listSkills` MUST 逐字节保持今日行为：无 `query` 入参、把技能按名排序**全量平铺**（每行 `- <name>` 或 `- <name> — <description>`），技能集为空时返回 `"No skills found."`。当技能匹配**开启**时，`listSkills` MAY 接受一个**可选** `query` 入参：`query` 为空或缺省时 MUST 仍全量平铺（与今日一致）；`query` 非空时 MUST 经共享检索原语返回 top-K 最相关技能（每行格式与平铺一致，`top-k` 由配置界定）。当 `query` 非空但无 in-vocab 匹配时，`listSkills` MUST 回退为全量平铺（不报错、不返回空），使技能始终可被发现。

#### Scenario: 默认关闭时 listSkills 全量平铺（逐字节不变）

- **WHEN** `skills.matching.enabled=false`（默认）调用 `listSkills`
- **THEN** 无 `query` 入参，返回按名排序的全部技能（`- <name>` / `- <name> — <description>`），空技能集返回 `"No skills found."`——与引入本能力前逐字节一致

#### Scenario: 开启后非空查询返回 top-K 最相关

- **WHEN** `skills.matching.enabled=true`，以命中若干技能的非空 `query` 调用 `listSkills`
- **THEN** 返回经共享检索原语排序的 top-K 最相关技能（不超过配置 `top-k`），更相关者排前，行格式与平铺一致

#### Scenario: 开启后空查询回退全量平铺

- **WHEN** `skills.matching.enabled=true`，以空或缺省 `query` 调用 `listSkills`
- **THEN** 返回全量平铺（按名排序全部技能），与默认关闭时的平铺输出一致

#### Scenario: 非空查询无匹配回退全量平铺

- **WHEN** `skills.matching.enabled=true`，以无任何 in-vocab 命中的非空 `query` 调用 `listSkills`
- **THEN** 回退为全量平铺全部技能（不报错、不返回空）

### Requirement: SkillsTool @Tool 面字节稳定（与 S1 一致）

系统 SHALL 保证 `SkillsTool` 的 `@Tool` 对外面无模型面回归：`loadSkill` 的 `@Tool` 名、参数签名与返回语义（未知名 `"Skill not found: <name>"`、读失败 `"Error: <msg>"`、无支持文件时逐字返回正文）MUST **在任何配置下逐字保持不变**。`listSkills` 的 `@Tool` **名** MUST 恒为 `listSkills`；技能匹配**关闭**（默认）时其参数签名（无 `query` 入参）、schema 与输出 MUST 与引入本能力前逐字节一致。新增的可选 `query` 入参 MUST 仅在技能匹配**显式开启**时出现（用户主动行为），MUST NOT 改变默认（关闭）路径的签名语义。

#### Scenario: loadSkill 在任何配置下逐字不变

- **WHEN** 在 `skills.matching.enabled` 取任意值时调用 `loadSkill`
- **THEN** 其 `@Tool` 名、签名、返回语义（含未知名/读错/无支持文件的兜底字符串）与引入本能力前逐字一致

#### Scenario: 默认关闭时 listSkills 无 query 入参

- **WHEN** `skills.matching.enabled=false`（默认），审视 `listSkills` 的 `@Tool` schema
- **THEN** 其名为 `listSkills` 且不含 `query` 入参，与引入本能力前逐字节一致

### Requirement: 技能匹配可配且默认关闭

系统 SHALL 以配置块 `skills.matching` 门控本能力：`enabled`（默认 **false**）、`top-k`（默认 10）、`min-score`（默认 0.0）。全部字段可选、null/缺块安全（getter 归默认）、非法值 clamp（`top-k < 1` → 默认、`min-score` 负 → 0）。当 `enabled=false` 时，`AgentBootstrap` MUST 注册无 `query` 入参的 `listSkills` 变体、MUST NOT 装配任何技能匹配 seam，系统行为 MUST 与引入本能力前逐字节一致。本能力 MUST NOT 改写 `builtin-skills` / `composite-skill` 的既有 REQUIREMENT（`SkillRegistry` 多源合并去重、工作区覆盖、渐进加载在默认关时逐字保持），MUST NOT 影响 `skill-curator-and-graded-promotion`（S3）的 usage/老化/晋级（正交）。

#### Scenario: 默认关闭时零行为变更

- **WHEN** `skills.matching.enabled=false`（默认）
- **THEN** 不装配任何匹配 seam、`listSkills` 无 `query` 入参、技能读栈（`listSkills`/`loadSkill`/`SkillRegistry`）行为与引入本能力前逐字节一致

#### Scenario: 缺块与非法值默认安全

- **WHEN** 配置缺 `skills.matching` 块或字段非法（如 `top-k=0`、`min-score=-1`）
- **THEN** getter 归安全默认（`enabled=false`、`top-k=10`、`min-score=0.0`），非法值被 clamp，不抛异常

#### Scenario: 与 S3 curator 正交

- **WHEN** `skills.matching.enabled=true` 且 `skills.curator.enabled=true` 同时开启
- **THEN** 技能匹配只对 `SkillRegistry` 当前读栈中的技能按查询排序，不改变 S3 的 usage 记录/老化归档/晋级判定（两能力正交，互不干扰）
