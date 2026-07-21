## ADDED Requirements

### Requirement: 技能匹配复用共享检索原语（SkillDocument）

系统 SHALL 提供按任务语义匹配技能的能力：把每个技能建成一个通用检索文档 `SkillDocument implements io.pigagent.core.search.SearchDocument`（`id`=技能名、`text`=技能名 + 描述 + keywords），复用内核共享检索原语 `io.pigagent.core.search`（`Bm25Index` + `HybridRanker` + CJK `Tokenizer`）对查询打分排序，MUST NOT 自造关键词计分。`SkillDocument.text` MUST 仅取自技能的**廉价元数据** `Skill.metadata()`（名 + 描述 + keywords），MUST NOT 读取技能正文（`Skill.content()`）——排序路径与 `listSkills` 的渐进加载契约一致。技能匹配 SHALL 只用 BM25（经 `HybridRanker` 以空向量图降级为 BM25-only），MUST NOT 为技能匹配引入向量嵌入。该匹配 MUST 与 `memory_search`、`tool_search` **同源**——共用同一 ranker 与同一 `Tokenizer`，同一查询在三线的排序口径一致。被排序的技能集 MUST 与 `listSkills` 平铺看到的技能集同源（同一 `SkillRegistry` 的合并去重结果），故返回的匹配集是该集合的子集、覆盖/去重语义一致。

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

### Requirement: skill_search 只读工具按查询返回 top-K（默认关不注册）

系统 SHALL 提供一个**独立**的只读内置工具 `skill_search`（`@Tool name="skill_search"`，`readOnly=true`）：输入查询/关键词，经共享检索原语返回 top-K 最相关技能（每行 `- <name>` 或 `- <name> — <description>`，`top-k` 由配置 `skills.matching.top-k` 界定，`min-score` 过滤）。该工具 MUST 由配置 `skills.matching.enabled` 门控、**默认关闭 → 不注册**：`enabled=false`（默认）时 `skill_search` MUST NOT 进入模型的工具 schema（初始 schema 与引入本能力前逐字节一致）。当查询为空或无 in-vocab 匹配时，`skill_search` MUST 回退为全量平铺全部技能（不报错、不返回空），使技能始终可被发现。`skill_search` MUST 被风险分级为只读（`ToolRiskClassifier` 中 `READ_ONLY`），与 `tool_search`/`memory_search` 对齐（补齐三件套对称）。`skill_search` 排序看到的技能集 MUST 与 `listSkills` 同源（同一 `SkillRegistry`）。

#### Scenario: 默认关闭时不注册 skill_search

- **WHEN** `skills.matching.enabled=false`（默认），审视模型的工具 schema
- **THEN** 不存在名为 `skill_search` 的工具，初始工具 schema 与引入本能力前逐字节一致

#### Scenario: 开启后命中查询返回 top-K 最相关

- **WHEN** `skills.matching.enabled=true`，以命中若干技能的非空查询调用 `skill_search`
- **THEN** 返回经共享检索原语排序的 top-K 最相关技能（不超过配置 `top-k`），更相关者排前，行格式与 `listSkills` 平铺一致

#### Scenario: 空查询或无匹配回退全量平铺

- **WHEN** `skills.matching.enabled=true`，以空查询、或无任何 in-vocab 命中的查询调用 `skill_search`
- **THEN** 回退为全量平铺全部技能（按名排序，不报错、不返回空）

#### Scenario: skill_search 分级为只读

- **WHEN** 对工具名 `skill_search` 做风险分级
- **THEN** 结果为 `READ_ONLY`（与 `tool_search`/`memory_search` 对齐）

### Requirement: listSkills 与 loadSkill 的 @Tool 面完全不变

系统 SHALL 保证既有技能读栈工具 `listSkills`/`loadSkill` 的 `@Tool` 名、参数签名、schema 与返回语义**在任何配置下逐字保持不变**（无模型面回归）——技能匹配以**独立新工具** `skill_search` 暴露，MUST NOT 改动 `listSkills`（仍无参、全量平铺按名排序、空集合 `"No skills found."`、描述为空 `- <name>`）或 `loadSkill`（未知名 `"Skill not found: <name>"`、读失败 `"Error: <msg>"`、无支持文件时逐字返回正文）。这条红线与 S1（native-skill-engine-bridge）保持一致。

#### Scenario: listSkills 在任何配置下逐字不变

- **WHEN** 在 `skills.matching.enabled` 取任意值时调用 `listSkills`
- **THEN** 其 `@Tool` 名、无参签名、schema 与全量平铺输出（含 `"No skills found."` / `- <name>` / `- <name> — <description>`）与引入本能力前逐字一致

#### Scenario: loadSkill 在任何配置下逐字不变

- **WHEN** 在 `skills.matching.enabled` 取任意值时调用 `loadSkill`
- **THEN** 其 `@Tool` 名、签名、返回语义（含未知名/读错/无支持文件的兜底字符串）与引入本能力前逐字一致

### Requirement: 技能匹配可配且默认关闭

系统 SHALL 以配置块 `skills.matching` 门控本能力：`enabled`（默认 **false**）、`top-k`（默认 10）、`min-score`（默认 0.0）。全部字段可选、null/缺块安全（getter 归默认）、非法值 clamp（`top-k < 1` → 默认、`min-score` 负 → 0）。当 `enabled=false` 时，`AgentBootstrap` MUST NOT 注册 `skill_search`、MUST NOT 装配任何技能匹配 seam，系统行为 MUST 与引入本能力前逐字节一致。本能力 MUST NOT 改写 `builtin-skills` / `composite-skill` 的既有 REQUIREMENT（`SkillRegistry` 多源合并去重、工作区覆盖、渐进加载全部保持），MUST NOT 影响 `skill-curator-and-graded-promotion`（S3）的 usage/老化/晋级（正交）。

#### Scenario: 默认关闭时零行为变更

- **WHEN** `skills.matching.enabled=false`（默认）
- **THEN** 不注册 `skill_search`、不装配任何匹配 seam、技能读栈（`listSkills`/`loadSkill`/`SkillRegistry`）行为与引入本能力前逐字节一致

#### Scenario: 缺块与非法值默认安全

- **WHEN** 配置缺 `skills.matching` 块或字段非法（如 `top-k=0`、`min-score=-1`）
- **THEN** getter 归安全默认（`enabled=false`、`top-k=10`、`min-score=0.0`），非法值被 clamp，不抛异常

#### Scenario: 与 S3 curator 正交

- **WHEN** `skills.matching.enabled=true` 且 `skills.curator.enabled=true` 同时开启
- **THEN** `skill_search` 只对 `SkillRegistry` 当前读栈中的技能按查询排序，不改变 S3 的 usage 记录/老化归档/晋级判定（两能力正交，互不干扰）
