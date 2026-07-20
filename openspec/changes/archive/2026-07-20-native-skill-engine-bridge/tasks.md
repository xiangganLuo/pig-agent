# Tasks — native-skill-engine-bridge

> 内环 TDD：每个可测单元先写测试（RED）→ 实现（GREEN）→ `mvn test` → 勾选。分组后 `mvn -q -pl pig-agent-cli -am compile`。
> **第 1 组是承重 spike，门整条技能线（S1/S2/S3）**：不过则停下升级人工、本线不进编码（design.md「承重 Spike 结论」已给 javap 签名级判据，本组做可运行坐实）。

## 1. 承重 spike（门整条技能线）—— `pig-agent-tools`

- [x] 1.1 **纯库驱动 spike**（"只用引擎不用嘴"）：`NativeSkillEngineSpikeTest`（放 `pig-agent-core` 测试——该模块已依赖 `agentscope-harness`，S1 主代码仍零新增依赖）——用 `LocalFilesystem(tempDir)` + `Supplier<RuntimeContext>` 构造 `SkillUsageStore`/`WorkspaceSkillRepository`/`SkillCurator(...)`，断言 `SkillCurator.runOnce(Instant.now())` 返回 `CuratorRunReport`、`SkillUsageStore` 独立 load/register/bump、`RejectAllGate.review(candidate, ctx)` 独立返回决策——**全程无 Model/Agent、无 `enableXxx`、无 `<available_skills>` 注入**。3/3 绿。结论写回 design.md「spike B」+ R-Spike-1（gate 返回 `Defer` 非 `Reject`，仍永不 Approve）。
- [x] 1.2 **解析等价 spike**：`NativeSkillParserSpikeTest`（`pig-agent-tools`，core-only）——代表性 `SKILL.md`（含/无 front-matter）分别过 pig `SkillManifestParser.defaults()` 与原生 `MarkdownSkillParser.parse(text)`，断言核心键 `name`/`description` 一致、正文剥离一致；已知差异（native 无 front-matter 时 description 空 vs pig 从首行派生）已断言并记录 design.md「spike C」。2/2 绿。
- [x] 1.3 **布局兼容核对**：`NativeSkillParserSpikeTest.nativeFileSystemRepo_...`——temp `workspace/skills/<name>/SKILL.md`（含 `.pending/<x>/SKILL.md` 暂存），`FileSystemSkillRepository(skills).getAllSkillNames()` vs `WorkspaceSkillSource.discover()`。布局同构（native 读 front-matter 技能）；**R-Spike-2**：native 更严格（要求 front-matter，跳裸技能），native 亦不浮现 `.pending`——记录 design.md「D6」。1/1 绿。
- [x] 1.4 spike 汇总：三项结论 + 两点精化（R-Spike-1/2，非矛盾）写回 design.md「承重 Spike 结论」。**GREEN → 进入第 2 组编码**。

## 2. 配置 + 装配开关（`pig-agent-config` / `pig-agent-cli`）

- [x] 2.1 `PigAgentConfig.SkillsConfig` 加 `native` 子块（`NativeSkillConfig`：`enabled` 默认 false、可选 `classpath-resource-dir`）+ 访问器（null/默认安全）。`NativeSkillConfigTest`（4/4）断言默认值（`native.enabled=false`）+ 解析 + null setter 容错。（未知字段容错由 `ConfigurationManager` 的全局 `DeserializationProblemHandler` 提供，非 per-class 注解——本 worktree 基线无 top-level `@JsonIgnoreProperties`。）
- [x] 2.2 `AgentBootstrap.skillsToolWithNative(...)`：`skills.native.enabled=true` 时组 `SkillRegistry([Workspace, Classpath, NativeRepositorySkillSource(FileSystemSkillRepository(skills))])`（`classpath-resource-dir` 非空再叠 `ClasspathSkillRepository`，容错），作为 `ToolRegistrar.registerAll` 的 **manual override** 覆盖自动 `SkillsToolProvider`（同 `@Tool` 名）；fallback 分支同用。默认关 → `List.of()` 覆盖为空 → 源列表逐字节不变。`mvn -pl pig-agent-cli -am compile` 绿。

## 3. 适配器（`pig-agent-tools`，`io.pigagent.tool.skills`）

- [x] 3.1 `NativeAgentSkill implements Skill`（适配 `AgentSkill`）+ `NativeSkillResource`（in-memory）。`NativeAgentSkillTest`（8/8）：`name()`/`content()`（front-matter 剥离对齐 `FileSkill`）/`metadata()`（从 `getMetadata()` 提 keywords[List 或逗号串]/version、缺省归空、廉价）/`supportingFiles()`（`getResources()` → `SkillResource`）/`when-to-use` 回退。
- [x] 3.2 `NativeRepositorySkillSource implements SkillSource`（持 `AgentSkillRepository`，`discover()`=`getAllSkills()`→`NativeAgentSkill`）。`NativeRepositorySkillSourceTest`（4/4）：真 `FileSystemSkillRepository` 适配；**容错**（repo 抛→空，不抛）；**点前缀过滤**（`.pending` 不浮现）；`name()`=`"native:"+getSource()`。

## 4. 集成到读栈（`pig-agent-tools` / `pig-agent-cli`）

- [x] 4.1 `NativeSkillSourceDedupTest`（2/2）：同根 `[Workspace, Classpath, Native(同根)]` 的 `listNames()` 与仅 `[Workspace, Classpath]` **相等**（去重折叠，行为等价，D4）；不同根 native 源为**增量**（新名出现），同名冲突由高优先级 pig 工作区源胜出。
- [x] 4.2 红线回归守卫 `NativeSkillRedLineGuardTest`（2/2，`pig-agent-core`）：pig 构建的 `PigAgent` 的 `getToolkit().getToolNames()` **不含** `load_skill_through_path`/`read_file`/`grep`/`propose_skill`/`skill_manage`（`disableDynamicSkills` 保持），且 delegate middleware 无原生 `*Skill*`（不当嘴）。S1 不改 `PigAgent`，红线 by-construction；既有 `SkillsToolTest` 保持绿（@Tool 面不变）。

## 5. 收尾

- [x] 5.1 新增测试全绿：`NativeSkillEngineSpikeTest`(3) + `NativeSkillParserSpikeTest`(3) + `NativeAgentSkillTest`(8) + `NativeRepositorySkillSourceTest`(4) + `NativeSkillSourceDedupTest`(2) + `NativeSkillRedLineGuardTest`(2) + `NativeSkillConfigTest`(4)。
- [x] 5.2 `mvn -pl pig-agent-cli -am compile` 绿。
- [x] 5.3 更新 `CLAUDE.md` builtin-skills 段落（原生技能引擎经 `NativeRepositorySkillSource` 当纯库采纳、仍 `disableDynamicSkills()` 不当嘴、默认 `skills.native.enabled=false` 行为等价、S2/S3 基座）。
- [x] 5.4 提交（分组提交：spike / 适配器 / 配置+装配+集成+红线）。
- [ ] 5.5 归档：同步主 spec → `openspec/specs/native-skill-engine-bridge/`，change 移 `openspec/changes/archive/<date>-native-skill-engine-bridge/`（**人工确认门，不在本轮**）。
