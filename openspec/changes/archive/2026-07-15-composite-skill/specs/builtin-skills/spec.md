# builtin-skills Specification (delta)

## MODIFIED Requirements

### Requirement: SkillSource 策略抽象组合内置源与工作区源

系统 SHALL 以一个 `SkillSource` 策略抽象（`List<Skill> discover()`，容错、永不抛、永不返 `null`）承载技能来源，并提供两个实现：**内置源**（`ClasspathSkillSource`，经 `ServiceLoader<SkillProvider>` 聚合 classpath 内置技能）与**工作区源**（`WorkspaceSkillSource`，扫 `workspace/skills/<name>/SKILL.md`）。`SkillsTool` MUST 经一个 `SkillRegistry` 组合器把多源合并：`listSkills` MUST 展示内置 + 工作区技能并按名去重；`loadSkill` MUST 按名解析。

技能读取 MUST 遵循**渐进加载契约**：`listSkills` MUST 仅读取每个技能的**元数据**（`Skill.metadata()` — 名字 + 可选描述，来自 SKILL.md 头部/front-matter 的廉价读），MUST NOT 读取任一技能的完整正文；`loadSkill` MUST 在命中时才读完整正文（`Skill.content()`）并浮现支持文件。`Skill` 句柄 MUST 惰性（`discover` 只返回轻量句柄，命中时才读）。内置技能 MAY 携带 YAML front-matter 以便 `listSkills` 展示语义化描述；当 `content()` 剥离 front-matter 后，其正文 MUST 仍逐字以 Markdown 标题（`# `）起头（`content()` 契约不回归）。

`SkillsTool` 的 `@Tool` 方法名（`listSkills`/`loadSkill`）、参数签名与返回语义 MUST 逐字保持不变（无模型面回归）：`listSkills` 空集合仍返回 `"No skills found."`、描述为空的技能仍格式化为 `- <name>`；`loadSkill` 未知名仍返回 `"Skill not found: <name>"`、读失败仍返回 `"Error: <msg>"`、无支持文件时逐字返回正文。

#### Scenario: listSkills 合并内置与工作区并去重
- **WHEN** 内置源提供若干技能、工作区源提供若干技能（可能含同名），调用 `listSkills`
- **THEN** 返回两源技能名的并集（按名去重、每名一行 `- <name>` 或 `- <name> — <description>`），空集合返回 `"No skills found."`

#### Scenario: listSkills 渐进——仅读元数据不读正文
- **WHEN** 一个技能的 `content()` 会抛异常/被断言不应被调用，调用 `listSkills`
- **THEN** `listSkills` 只调用该技能的 `metadata()` 完成列举，MUST NOT 触发其 `content()`（列举廉价、省 token）

#### Scenario: loadSkill 返回内置技能内容
- **WHEN** 一个技能名仅由内置源提供，调用 `loadSkill(name)`
- **THEN** 返回该内置技能 `SKILL.md` 的正文内容（front-matter 已剥离，仍以 `# 标题` 起头）

#### Scenario: 未知技能名返回 not-found
- **WHEN** 调用 `loadSkill(name)` 且 `name` 不由任何源提供
- **THEN** 返回 `"Skill not found: <name>"`（不抛异常）
