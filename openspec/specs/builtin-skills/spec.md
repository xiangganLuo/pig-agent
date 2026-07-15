# builtin-skills Specification

## Purpose
随发行版内置一组 curated 系统技能（每个 = 一份 `SKILL.md` 能力包），让编码 agent 开箱即有按需加载的能力包，而非依赖用户自建工作区技能。技能经 `SkillProvider` SPI + `ServiceLoader` 开箱发现（镜像 `plugin-system` 的插件发现范式）；`SkillsTool` 经 `SkillSource` 策略抽象组合「内置源 + 工作区源」，`listSkills` 合并去重、`loadSkill` 工作区覆盖同名内置，模块缺席时退化为纯工作区（零行为变更）。
## Requirements
### Requirement: 随发行版内置一组 curated 系统技能

系统 SHALL 随发行版提供一组 curated 内置系统技能，每个技能 = 一份 `SKILL.md` 能力包，作为 classpath 资源 `skills/<name>/SKILL.md` 打包于内置技能模块 `pig-agent-skills-builtin`。首发技能集 MUST 至少覆盖：`code-review`、`systematic-debugging`、`tdd`、`refactoring`、`git-commit`、`security-review`、`planning`（7 个）。每个内置技能 MUST 经 `SkillProvider` SPI（`META-INF/services/io.pigagent.tool.skills.spi.SkillProvider`）+ `ServiceLoader` 开箱发现，并 MUST 经一个注册表/工厂（`SkillCatalog`）作为单一事实源枚举；注册表枚举的技能名集合与实际 classpath 资源目录集合 MUST 一致（漂移即视为缺陷）。每个内置 `SKILL.md` 的内容 MUST 非空且为面向 agent 的可执行方法指南（标题 + when-to-use + 分步方法/清单）。

#### Scenario: 内置技能经 ServiceLoader 开箱发现
- **WHEN** `pig-agent-skills-builtin` 在 classpath 上，经 `ClasspathSkillSource`（`ServiceLoader<SkillProvider>`）发现
- **THEN** 7 个内置技能（code-review/systematic-debugging/tdd/refactoring/git-commit/security-review/planning）全部被发现，且每个技能的 `content()` 非空

#### Scenario: 注册表与资源一致（漂移守卫）
- **WHEN** 比对 `SkillCatalog` 枚举的技能名集合与内置模块 classpath 上实际存在的 `skills/<name>/SKILL.md` 资源集合
- **THEN** 两个集合一致（新增内置技能必须同时加进 catalog 与资源目录，否则视为漂移）

### Requirement: SkillSource 策略抽象组合内置源与工作区源

系统 SHALL 以一个 `SkillSource` 策略抽象（`List<Skill> discover()`，容错、永不抛、永不返 `null`）承载技能来源，并提供两个实现：**内置源**（`ClasspathSkillSource`，经 `ServiceLoader<SkillProvider>` 聚合 classpath 内置技能）与**工作区源**（`WorkspaceSkillSource`，扫 `workspace/skills/<name>/SKILL.md`）。`SkillsTool` MUST 经一个 `SkillRegistry` 组合器把多源合并：`listSkills` MUST 展示内置 + 工作区技能并按名去重；`loadSkill` MUST 按名解析。`Skill` 的内容读取 MUST 惰性（`discover` 只返回轻量句柄，命中时才读）。`SkillsTool` 的 `@Tool` 方法名（`listSkills`/`loadSkill`）、参数签名与返回语义 MUST 逐字保持不变（无模型面回归）。

#### Scenario: listSkills 合并内置与工作区并去重
- **WHEN** 内置源提供若干技能、工作区源提供若干技能（可能含同名），调用 `listSkills`
- **THEN** 返回两源技能名的并集（按名去重、每名一行 `- <name>`），空集合返回 `"No skills found."`

#### Scenario: loadSkill 返回内置技能内容
- **WHEN** 一个技能名仅由内置源提供，调用 `loadSkill(name)`
- **THEN** 返回该内置技能 `SKILL.md` 的内容

#### Scenario: 未知技能名返回 not-found
- **WHEN** 调用 `loadSkill(name)` 且 `name` 不由任何源提供
- **THEN** 返回 `"Skill not found: <name>"`（不抛异常）

### Requirement: 工作区覆盖同名内置技能

当工作区源与内置源提供**同名**技能时，`SkillRegistry` MUST 令工作区技能覆盖（shadow）内置技能：去重时最高优先源（工作区）胜。据此 `listSkills` 中同名技能 MUST 只出现一次，`loadSkill` MUST 返回工作区版本的内容。用户 SHALL 可通过在 `workspace/skills/<name>/SKILL.md` 放置同名文件来定制/覆盖任一内置技能。

#### Scenario: 同名工作区技能覆盖内置
- **WHEN** 内置源与工作区源都提供名为 `code-review` 的技能，调用 `loadSkill("code-review")`
- **THEN** 返回工作区版本的内容（内置版本被 shadow）；`listSkills` 中 `code-review` 只出现一次

### Requirement: 模块缺席或工作区缺失时容错且零行为变更

当内置技能模块 `pig-agent-skills-builtin` 不在 classpath 上时，`ClasspathSkillSource` MUST 发现为空，系统行为 MUST 与未引入该模块时完全一致（纯工作区技能）。当工作区技能目录缺失、为空或不可读时，`WorkspaceSkillSource` MUST 容错返回空而非抛出。任一源 `discover()` 抛出时 MUST 被 `SkillRegistry` 隔离跳过，不影响其余源。

#### Scenario: 内置模块缺席退化为纯工作区
- **WHEN** 运行时 classpath 不含 `pig-agent-skills-builtin`
- **THEN** `ClasspathSkillSource` 返回空，`listSkills`/`loadSkill` 仅反映工作区技能（与今天行为逐字一致）

#### Scenario: 工作区目录缺失被容错
- **WHEN** `workspace/skills/` 不存在或为空目录
- **THEN** `WorkspaceSkillSource.discover()` 返回空列表而不抛异常；`listSkills` 仅反映内置技能（若模块在）

