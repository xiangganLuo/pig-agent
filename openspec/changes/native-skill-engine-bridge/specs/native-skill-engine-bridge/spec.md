# native-skill-engine-bridge Specification (delta)

## ADDED Requirements

### Requirement: 原生技能仓作为只读 SkillSource 采纳

系统 SHALL 把 AgentScope 2.0 原生技能仓（`io.agentscope.core.skill.repository.AgentSkillRepository`，S1 用其 core 实现 `FileSystemSkillRepository`/`ClasspathSkillRepository`）**当库采纳到 pig 的 `SkillSource` seam 背后**：新增 `NativeRepositorySkillSource implements SkillSource`，其 `discover()` MUST 调 `repo.getAllSkills()` 并把结果适配成 pig `Skill`，交给既有 `SkillRegistry` 去重。`discover()` MUST 容错——任何异常 MUST 被吞并 `warn`、返回已收集部分（永不抛），坏源退化为「无技能」，与既有 `SkillSource` 契约一致。原生仓 MUST NOT 引入新的第三方依赖（`pig-agent-tools` 已依赖 `agentscope-core`）。

#### Scenario: 原生仓的技能经适配后被发现
- **WHEN** 一个 `AgentSkillRepository` 含若干技能，`NativeRepositorySkillSource` 包裹它并 `discover()`
- **THEN** 每个原生 `AgentSkill` 被适配成一个 pig `Skill`（`name`/`content`/`metadata`/`supportingFiles`）并返回，交由 `SkillRegistry` 参与去重

#### Scenario: 坏源容错退化
- **WHEN** 底层 `AgentSkillRepository.getAllSkills()` 抛异常
- **THEN** `NativeRepositorySkillSource.discover()` 返回空（或已收集部分）且不抛异常，`listSkills`/`loadSkill` 不受影响

### Requirement: 「引擎不当嘴」——不重开原生 prompt 注入路径

系统 SHALL 只把原生技能引擎用于**存取 + 解析**，MUST NOT 启用原生的 prompt 注入路径。具体：`PigAgent` 构建 MUST 继续 `disableDynamicSkills()` 且 `disableDefaultWorkspaceSkills()`；MUST NOT 调用 `.skillRepository(...)`/`enableSkillManageTool`/`enableSkillPromotionGate`/`enableSkillCurator`；MUST NOT 安装原生技能 middleware（`DynamicSkillMiddleware`/`AgentSkillPromptProvider`/`HarnessSkillMiddleware`）或注册原生技能工具（`load_skill_through_path`/`read_file`/`grep`/`propose_skill`/`skill_manage`）。`SkillsTool` 的 `listSkills`/`loadSkill` `@Tool` 名、签名、返回语义（空列表、未知名、读错兜底）MUST 逐字不变，系统 prompt 的字节稳定性 MUST 不被本能力破坏。

#### Scenario: 原生仓被读取但不注入 prompt
- **WHEN** `skills.native.enabled=true`，原生源被接入读栈
- **THEN** 技能仅经 `SkillsTool.listSkills`/`loadSkill` 暴露给模型；系统 prompt 中不出现 `<available_skills>` 块，也不注册 `load_skill_through_path`/`read_file`/`grep` 等原生工具

#### Scenario: SkillsTool 模型面零回归
- **WHEN** 对比接入原生源前后 `SkillsTool` 的 `@Tool` 表面
- **THEN** `listSkills`/`loadSkill` 的名字、参数、返回语义完全一致（既有 `SkillsTool` 测试保持通过）

### Requirement: AgentSkill 到 pig Skill 的容错适配

系统 SHALL 提供 `NativeAgentSkill implements Skill` 把原生 `AgentSkill` 适配成 pig `Skill`：`name()` 取 `getName()`；`content()` 取 `getSkillContent()`（若原生未剥离 front-matter，则剥离以对齐 `FileSkill` 语义）；`metadata()` 由 `getName()`/`getDescription()`/`getMetadata()` 构造 `SkillMetadata`（keywords/version 缺省归空，且 MUST 廉价——不读 body）；`supportingFiles()` 由 `getResources()` 构造 `SkillResource`（受既有 `SkillLimits` 约束）。适配 MUST 保持 composite-skill 的渐进加载语义（metadata 廉价、content 惰性）。

#### Scenario: 元数据廉价、内容惰性
- **WHEN** `SkillsTool.listSkills` 遍历技能
- **THEN** 每个 `NativeAgentSkill.metadata()` 提供 name + description 而不触发额外的 body 读取；`loadSkill` 时 `content()` 才返回完整正文 + 支持文件

### Requirement: 目录布局兼容与暂存隔离不破

系统 SHALL 保证原生 `FileSystemSkillRepository` 扫描的 `<root>/<name>/SKILL.md` 布局与 pig `workspace/skills/<name>/SKILL.md`（`WorkspaceManager.getSkillsDir()`）兼容。`NativeRepositorySkillSource` MUST **防御性过滤点前缀名**（`name` 以 `.` 起头，如 autonomous-skills 的 `.pending`/`.archive`），无论原生仓自身是否跳过——保证暂存草稿绝不经原生源浮现到 `listSkills`/`loadSkill`。

#### Scenario: 暂存目录不经原生源浮现
- **WHEN** `workspace/skills/` 下存在 `.pending/<draft>/SKILL.md`（autonomous-skills 暂存草稿），且原生源已启用
- **THEN** 该草稿 MUST NOT 出现在 `listSkills` 中（`NativeRepositorySkillSource` 跳过点前缀名）

#### Scenario: 布局同构
- **WHEN** `FileSystemSkillRepository(workspace/skills)` 指向 pig 布局
- **THEN** 其 `getAllSkillNames()`（排除点前缀）与 `WorkspaceSkillSource.discover()` 的名集一致

### Requirement: 能力可配且默认关闭

系统 SHALL 以 `skills.native` 配置块治理本能力：`enabled`（默认 **false**）、可选 `classpath-resource-dir`。当 `enabled=false` 时，`AgentBootstrap` MUST NOT 追加 `NativeRepositorySkillSource`，`SkillRegistry` 的源列表 MUST 与引入本能力前逐字节一致（零行为变更）。当 `enabled=true` 时，原生源 MUST 置于既有 pig 源（`WorkspaceSkillSource`/`ClasspathSkillSource`）之后（最低优先级），使默认同根场景下同名技能被 pig 源覆盖、`listSkills`/`loadSkill` 输出与关闭时逐条等价。

#### Scenario: 关闭时零行为变更
- **WHEN** `skills.native.enabled=false`（默认）
- **THEN** `SkillRegistry` 的源列表与今天一致；`listSkills`/`loadSkill` 行为逐字节不变

#### Scenario: 开启后行为等价（同根去重折叠）
- **WHEN** `skills.native.enabled=true` 且原生源默认 = `FileSystemSkillRepository(workspace/skills)`（与 `WorkspaceSkillSource` 同根）
- **THEN** `SkillRegistry` 按名去重折叠（pig 源先见胜出），`listSkills` 的技能名集与关闭时相等（不重复列举、不新增、不 shadow 用户技能）
