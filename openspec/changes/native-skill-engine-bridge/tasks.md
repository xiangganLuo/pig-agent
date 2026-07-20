# Tasks — native-skill-engine-bridge

> 内环 TDD：每个可测单元先写测试（RED）→ 实现（GREEN）→ `mvn test` → 勾选。分组后 `mvn -q -pl pig-agent-cli -am compile`。
> **第 1 组是承重 spike，门整条技能线（S1/S2/S3）**：不过则停下升级人工、本线不进编码（design.md「承重 Spike 结论」已给 javap 签名级判据，本组做可运行坐实）。

## 1. 承重 spike（门整条技能线）—— `pig-agent-tools`

- [ ] 1.1 **纯库驱动 spike**（"只用引擎不用嘴"）：写 `NativeSkillEngineSpikeTest`（临时/可保留为回归）——用 `LocalFilesystem`（`io.agentscope.harness.agent.filesystem.local.LocalFilesystem`，指向 temp 目录）+ 一个 `Supplier<RuntimeContext>` 构造 `SkillUsageStore` / `WorkspaceSkillRepository` / `SkillCurator(...)`，断言 `SkillCurator.runOnce(Instant.now())` 返回 `CuratorRunReport`、`RejectAllGate.review(candidate, ctx)` 返回 Reject、`LocalApprovalGate(stdinPrompter(...))` 可喂批准——**全程无 Model/Agent、无 `enableXxx`、无 `<available_skills>` 注入**。结论写回 design.md「spike B」（通过/不过）。
  > ⚠️ 本子任务需在宿主测试模块见到 `agentscope-harness`；若 `pig-agent-tools` test scope 无 harness，spike 放能见 harness 的模块（如 `pig-agent-core` 测试）或临时加 test-scope 依赖，**不影响 S1 主代码零新增依赖**（S1 主代码只用 core 仓）。
- [ ] 1.2 **解析等价 spike**：写 `SkillParserEquivalenceTest`——对若干代表性 `SKILL.md`（含 front-matter / 无 front-matter / 无闭合 fence）分别过 pig `FrontMatterManifestParser.parse(text, name)` 与原生 `MarkdownSkillParser.parse(text)`，断言核心键 `name`/`description` 一致、正文剥离一致；记录已知差异（keywords List vs Map、description 派生）到 design.md「spike C」。
- [ ] 1.3 **布局兼容核对**：写 `NativeRepositoryLayoutTest`——在 temp `workspace/skills/<name>/SKILL.md`（含一个 `.pending/<x>/SKILL.md` 暂存）建 pig 布局，`FileSystemSkillRepository(skillsDir)` 的 `getAllSkillNames()` 与 `WorkspaceSkillSource.discover()` 名集对比（排除点前缀），确认布局同构；记录原生 FS 仓对点前缀目录的行为（是否自动跳）到 design.md「D5/D6」。
- [ ] 1.4 spike 汇总：三项结论写回 design.md；**若任一不过 → 停下升级人工，本线不进编码**。通过则继续第 2 组。

## 2. 配置 + 装配开关（`pig-agent-config` / `pig-agent-cli`）

- [ ] 2.1 `PigAgentConfig.SkillsConfig` 加 `native` 子块（`NativeSkillConfig`：`enabled` 默认 false、可选 `classpath-resource-dir`）+ 访问器（null/默认安全）。`PigAgentConfigTest` 断言默认值（`native.enabled=false`）+ 解析 + 未知字段容错。
- [ ] 2.2 `AgentBootstrap` 装配 `SkillsTool` 的 `SkillRegistry` 处：`skills.native.enabled=true` 才在**已有源之后**追加 `NativeRepositorySkillSource`（默认 `FileSystemSkillRepository(workspace/skills)`；`classpath-resource-dir` 非空再叠 `ClasspathSkillRepository`）。默认关 → 源列表不变。CLI 侧测试：on/off 两态源列表。

## 3. 适配器（`pig-agent-tools`，`io.pigagent.tool.skills`）

- [ ] 3.1 `NativeAgentSkill implements Skill`（适配 `AgentSkill`）。`NativeAgentSkillTest`（RED→GREEN）：`name()`/`content()`（含 front-matter 剥离对齐 `FileSkill`，据 1.2/1.3 结论）/`metadata()`（从 `getMetadata()` 提 keywords/version、缺省归空、廉价不读 body）/`supportingFiles()`（`getResources()` → `SkillResource`，受 `SkillLimits`）。
- [ ] 3.2 `NativeRepositorySkillSource implements SkillSource`（持 `AgentSkillRepository`，`discover()`=`getAllSkills()`→`NativeAgentSkill`）。`NativeRepositorySkillSourceTest`（RED→GREEN）：fake `AgentSkillRepository` 喂技能 → `discover()` 适配正确；**容错**（repo 抛异常 → 返回空 + warn，不抛）；**点前缀过滤**（名以 `.` 起头的技能被跳过，`.pending`/`.archive` 不浮现）；`name()`=`"native:"+getSource()`。

## 4. 集成到读栈（`pig-agent-tools` / `pig-agent-cli`）

- [ ] 4.1 `SkillRegistry` 去重折叠验证：`SkillRegistry` 同时含 `WorkspaceSkillSource` + `NativeRepositorySkillSource(FileSystemSkillRepository(同根))` → `listNames()` 与仅 `WorkspaceSkillSource` 时**相等**（同名折叠，pig 源先见胜出）。`SkillsTool` 的 `listSkills`/`loadSkill` 输出 enabled 前后逐条等价（行为等价断言）。
- [ ] 4.2 红线回归守卫：断言 `PigAgent` 构建路径仍 `disableDynamicSkills()`+`disableDefaultWorkspaceSkills()`、未调用 `.skillRepository(...)`/`enableSkillManageTool`/`enableSkillPromotionGate`/`enableSkillCurator`、未注册 `load_skill_through_path`/`read_file`/`grep`（可经现有构建断言 / toolkit 名集断言）；`SkillsTool` 的 `@Tool` 名/签名/返回语义不变（既有 `SkillsToolTest` 保持绿）。

## 5. 收尾

- [ ] 5.1 `mvn -q test`（单线程）全绿，读 surefire XML 计数确认；列新增测试。
- [ ] 5.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [ ] 5.3 更新 `CLAUDE.md` builtin-skills 段落（原生技能引擎经 `NativeRepositorySkillSource` 当纯库采纳、仍 `disableDynamicSkills()` 不当嘴、默认 `skills.native.enabled=false` 行为等价、S2/S3 基座）。
- [ ] 5.4 提交 `feat: 原生技能引擎当库采纳（NativeRepositorySkillSource，藏 SkillSource seam 背后，引擎不当嘴）`。
- [ ] 5.5 归档：同步主 spec → `openspec/specs/native-skill-engine-bridge/`，change 移 `openspec/changes/archive/<date>-native-skill-engine-bridge/`，提交归档。
