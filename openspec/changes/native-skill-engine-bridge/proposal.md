## Why

pig 的技能栈今天是**手搓的薄子集**：`SkillsTool`（`listSkills`/`loadSkill`）经 `SkillRegistry` 组合 `WorkspaceSkillSource`（`workspace/skills/<name>/SKILL.md`）+ `ClasspathSkillSource`（内置 SPI），composite-skill 加了 front-matter/支持文件/渐进加载/`SkillSecurity` 加固，autonomous-skills 加了 `proposeSkill`→暂存→人工门→提升的写路径。而 `PigAgent`（`PigAgent.java:642-643,806-807`）主动 `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()` 把 2.0 原生技能引擎**全关掉**。

**头号发现（已 javap 核实）**：AgentScope 2.0（`agentscope-core:2.0.0` + `agentscope-harness:2.0.0`，离线仓已装）自带整套技能「自学习闭环」——core 层的 `AgentSkillRepository`/`FileSystemSkillRepository`/`ClasspathSkillRepository`/`AgentSkill`/`MarkdownSkillParser`（存取 + 解析），harness 层的 `SkillCurator`/`SkillUsageStore`/`SkillPromotionGate`（`LocalApprovalGate`/`RejectAllGate`/`NotifyAndWaitGate`）/`SkillSecurityScanner`/`SkillPromoter`（老化归档 + 分级晋级 + 安全扫描）。北极星是「harness 外包给 2.0，pig 只留差异化」——继续手搓技能引擎与之相悖。

**本能力（S1，foundation）**把原生技能引擎**当「库」采纳，藏在 pig 已投资的 `SkillSource` seam 背后**：新增一个 `NativeRepositorySkillSource implements SkillSource`，包原生 `FileSystemSkillRepository`/`ClasspathSkillRepository`（离线可用），把 `AgentSkillRepository.getAllSkills()` 适配成 pig 的 `Skill`，喂给现有 `SkillRegistry` 去重。**原生只做存取 + 解析（引擎），绝不当嘴**——这是本 spec 的硬红线：**绝不重开原生 `<available_skills>` prompt 注入路径**（`DynamicSkillMiddleware`/`AgentSkillPromptProvider`/`HarnessSkillMiddleware`），因为它会破坏 pig 的字节稳定 prompt（prefix-cache）并引入 pig 未注册的原生工具名（`load_skill_through_path`/`read_file`/`grep`）。

本能力默认 **关闭**（`skills.native.enabled=false`）——开启也只是给 `SkillRegistry` 多接一个源、去重后行为等价（默认源 = `FileSystemSkillRepository(workspace/skills)`，与 `WorkspaceSkillSource` 同根同名，dedup 折叠），**向后安全、零模型面回归**。这是 S2（curator/usage 老化）、S3（promotion gate 分级晋级）采纳原生自学习闭环的**基座**。

## What Changes

- **`pig-agent-tools`（新增 `io.pigagent.tool.skills`）：**
  - `NativeRepositorySkillSource implements SkillSource`：包一个原生 `AgentSkillRepository`（core 接口），`discover()` = `repo.getAllSkills()` → 逐个 `AgentSkill` 适配成 pig `Skill`；**容错**（吞异常 + `warn`，永不抛，坏源退化为「无技能」），喂现有 `SkillRegistry`。默认包 `FileSystemSkillRepository(workspace/skills)`，可选叠加 `ClasspathSkillRepository(resource-dir)`。
  - `NativeAgentSkill implements Skill`（适配值对象）：`name()`=`AgentSkill.getName()`，`content()`=`getSkillContent()`，`metadata()` 从 `getName()`/`getDescription()`/`getMetadata()` 构造 `SkillMetadata`，`supportingFiles()` 从 `getResources()`（path→内容）构造 `SkillResource`。渐进加载语义与 `FileSkill` 一致（metadata 廉价、content 惰性）。
  - **点前缀防护**：`NativeRepositorySkillSource` 过滤掉点前缀名（`.pending`/`.archive`），**镜像 `WorkspaceSkillSource` 的保留目录跳过**，保证 autonomous-skills 暂存区永不经原生源浮现到 `listSkills`。
  - `AgentSkillRepository` 是核心接口，S1 用其 `FileSystemSkillRepository`/`ClasspathSkillRepository` 两个 core 实现（离线、纯 `Path`/`String`，**无 `AbstractFilesystem`/`RuntimeContext`/网络依赖**）；`pig-agent-tools` 已依赖 `agentscope-core`（`SkillsTool` 用 `io.agentscope.core.tool.Tool`），故 **S1 零新增依赖**。
- **`pig-agent-config`**：`PigAgentConfig` 的 `SkillsConfig` 加 `native` 子块（`NativeSkillConfig`：`enabled` 默认 **false**、可选 `classpath-resource-dir`）。`@JsonIgnoreProperties` 容错沿用。
- **`pig-agent-cli`**：`AgentBootstrap` 在装配 `SkillsTool` 的 `SkillRegistry` 时，`skills.native.enabled=true` 才追加 `NativeRepositorySkillSource`（置于 pig 源之后 = 最低优先级，enabled 时同名被 pig 源覆盖 → 行为等价）。默认关 → `SkillRegistry` 源列表逐字节等价今天。
- **`PigAgent` 不改**：继续 `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()`；不启用 `enableSkillManageTool`/`enableSkillPromotionGate`/`enableSkillCurator`/`.skillRepository(...)`；不注册任何原生技能工具。系统 prompt 装配、`SkillsTool` 的 `@Tool` 名/签名/返回语义**逐字不变**。

## Capabilities

### New Capabilities
- `native-skill-engine-bridge`: 把 AgentScope 2.0 原生技能引擎（`AgentSkillRepository`/`FileSystemSkillRepository`/`ClasspathSkillRepository` + `AgentSkill`/`MarkdownSkillParser`）**当纯库采纳，藏在 pig 的 `SkillSource` seam 背后**——新增 `NativeRepositorySkillSource` 把 `getAllSkills()` 适配成 pig `Skill` 喂 `SkillRegistry` 去重。**原生只做存取 + 解析（引擎），不做 prompt 注入（嘴）**：pig 继续 `disableDynamicSkills()`/`disableDefaultWorkspaceSkills()`，**绝不重开** `<available_skills>` 注入路径，`SkillsTool` 的 `listSkills`/`loadSkill` `@Tool` 名/签名/返回语义与系统 prompt 字节稳定性**全不变**（零模型面回归）。目录布局兼容 pig `workspace/skills/<name>/SKILL.md`，暂存/归档点前缀（`.pending`/`.archive`）不浮现。config `skills.native.enabled` 默认 **false** → 零行为变更；开启也只是多一个同根源、去重后等价。是 S2（curator/usage 老化）/S3（promotion gate 分级晋级）采纳原生自学习闭环的基座。

## Impact

- **代码（`pig-agent-tools`）**：新增 `skills/{NativeRepositorySkillSource, NativeAgentSkill}`；`SkillsTool`/`SkillSource`/`SkillRegistry`/`WorkspaceSkillSource`/`ClasspathSkillSource`/composite-skill/autonomous-skills 只读读栈**契约不变**。
- **代码（`pig-agent-config`）**：`SkillsConfig` 加 `native` 子块。
- **代码（`pig-agent-cli`）**：`AgentBootstrap` 在 `skills.native.enabled=true` 时追加一个源；默认关不改行为。
- **不改**：`PigAgent`（继续 disable 原生动态技能）、系统 prompt 装配、`ToolAvailabilityGate`、原生 `PermissionEngine`、返回契约 + 分发守卫、`ToolContractGuard`。
- **测试**：`pig-agent-tools` 新增 `NativeRepositorySkillSourceTest`/`NativeAgentSkillTest`（含**承重 spike** 的 `NativeSkillEngineSpikeTest`：脱离 prompt 注入的纯库驱动 + `FrontMatterManifestParser`↔`MarkdownSkillParser` 解析等价）；`pig-agent-config` 扩 `PigAgentConfigTest`（`native` 默认 + 解析）；`pig-agent-cli` 扩（源装配 on/off）。离线，无真模型。
- **文档**：`CLAUDE.md` builtin-skills 段落补一句「原生技能引擎经 `NativeRepositorySkillSource` 当纯库采纳，仍 `disableDynamicSkills()` 不当嘴」。

## 诚实局限

- **仅 S1 基座**：本 spec 只把原生**仓库/解析（存取引擎）**接进只读读栈；**curator（老化归档 + usage 统计）留 S2、promotion gate（分级晋级 + 安全扫描）留 S3**。S2/S3 的 harness 引擎类（`SkillCurator`/`SkillPromoter`/`SkillPromotionGate`）需在宿主模块补 `agentscope-harness` 依赖（`pig-agent-tools` 现无 harness）——S1 只用 core 仓，故零新增依赖。
- **usage 计数**：原生 `SkillUsageStore` 的 `bumpView`/`bumpUse` 正常由原生 `SkillUsageMiddleware`/`SkillLoadTool` 在推理环里驱动；pig「不当嘴」意味着 S2 采纳 curator 时须由 pig 侧（如 `loadSkill`）自喂 usage，否则老化逻辑无输入。S1 不涉，仅在 design 标注。
- **spike 是承重门**：本线是否进编码取决于 tasks 第 1 组 spike——须证明原生 `SkillCurator`/`SkillPromotionGate`/`SkillUsageStore` 能脱离 prompt 注入当纯库单独驱动，且 pig↔原生解析等价。javap 已在 design.md 给出签名级结论（通过）；可运行验证是 task 1.x。
