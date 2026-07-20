# Tasks — native-skill-engine-bridge

> 内环 TDD：每个可测单元先写测试（RED）→ 实现（GREEN）→ `mvn test` → 勾选。分组后 `mvn -q -pl pig-agent-cli -am compile`。
> **第 1 组是承重 spike，门整条技能线（S1/S2/S3）**：不过则停下升级人工、本线不进编码（design.md「承重 Spike 结论」已给 javap 签名级判据，本组做可运行坐实）。

## 1. 承重 spike（门整条技能线）—— `pig-agent-tools`

- [x] 1.1 **纯库驱动 spike**（"只用引擎不用嘴"）：`NativeSkillEngineSpikeTest`（放 `pig-agent-core` 测试——该模块已依赖 `agentscope-harness`，S1 主代码仍零新增依赖）——用 `LocalFilesystem(tempDir)` + `Supplier<RuntimeContext>` 构造 `SkillUsageStore`/`WorkspaceSkillRepository`/`SkillCurator(...)`，断言 `SkillCurator.runOnce(Instant.now())` 返回 `CuratorRunReport`、`SkillUsageStore` 独立 load/register/bump、`RejectAllGate.review(candidate, ctx)` 独立返回决策——**全程无 Model/Agent、无 `enableXxx`、无 `<available_skills>` 注入**。3/3 绿。结论写回 design.md「spike B」+ R-Spike-1（gate 返回 `Defer` 非 `Reject`，仍永不 Approve）。
- [x] 1.2 **解析等价 spike**：`NativeSkillParserSpikeTest`（`pig-agent-tools`，core-only）——代表性 `SKILL.md`（含/无 front-matter）分别过 pig `SkillManifestParser.defaults()` 与原生 `MarkdownSkillParser.parse(text)`，断言核心键 `name`/`description` 一致、正文剥离一致；已知差异（native 无 front-matter 时 description 空 vs pig 从首行派生）已断言并记录 design.md「spike C」。2/2 绿。
- [x] 1.3 **布局兼容核对**：`NativeSkillParserSpikeTest.nativeFileSystemRepo_...`——temp `workspace/skills/<name>/SKILL.md`（含 `.pending/<x>/SKILL.md` 暂存），`FileSystemSkillRepository(skills).getAllSkillNames()` vs `WorkspaceSkillSource.discover()`。布局同构（native 读 front-matter 技能）；**R-Spike-2**：native 更严格（要求 front-matter，跳裸技能），native 亦不浮现 `.pending`——记录 design.md「D6」。1/1 绿。
- [x] 1.4 spike 汇总：三项结论 + 两点精化（R-Spike-1/2，非矛盾）写回 design.md「承重 Spike 结论」。**GREEN → 进入第 2 组编码**。

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
