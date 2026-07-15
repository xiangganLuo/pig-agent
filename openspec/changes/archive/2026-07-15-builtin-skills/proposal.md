## Why

`SkillsTool` 当前只认工作区一处技能源：`listSkills`/`loadSkill` 硬编码扫 `workspace/skills/{name}/SKILL.md`。这意味着 agent **开箱没有任何技能**——用户必须自己往工作区写 `SKILL.md` 才能用 `loadSkill` 拉取一份「能力包」。对一个编码 agent 而言，code-review / 系统化调试 / TDD / 重构 / git-commit / 安全评审 / 计划这些**通用工程方法**本应随发行版内置、按需加载，而不是每个用户各自重造。

与此同时，`plugin-system` + `pig-agent-plugin-builtin` 已经证明了一条成熟范式：**SPI 策略（`Plugin`）+ `ServiceLoader` 发现（`ServiceLoaderPluginSource`）+ 注册表单一事实源（`PluginCatalog`）+ 抽象骨架（`AbstractToolPlugin`）**，把「随发行版内置一组能力、开箱可用、加一个 = 加类 + 一条 service 行」这件事做成了标准结构。**内置技能的正确归宿正是镜像这一范式**：新开一个 `pig-agent-skills-builtin` 模块承载 curated 技能资源，并在 `pig-agent-tools` 引入一个 `SkillSource` 策略抽象把「内置源」与「工作区源」组合起来。

本次是**面向设计模式（而非过程式代码）的能力增强**：`SkillsTool` 从「单一目录扫描器」升级为「多源组合器」，内置源与工作区源经统一策略抽象合并，工作区可覆盖（shadow）同名内置技能，且**模块缺席即退化为纯工作区**（与今天逐字一致）。

## What Changes

- **`pig-agent-tools` 引入 `SkillSource` 策略抽象**（`io.pigagent.tool.skills`）：单抽象方法 `List<Skill> discover()`（容错、永不抛、永不返 `null`），镜像 `PluginSource`。配套值类型 `Skill`（`name()` + 惰性 `content()`）。两个实现：
  - `ClasspathSkillSource`（**内置源**）：经 `ServiceLoader<SkillProvider>` 发现 classpath 上的内置技能提供方并聚合其 `skills()`，镜像 `ServiceLoaderPluginSource`。
  - `WorkspaceSkillSource`（**工作区源**）：扫 `workspace/skills/{name}/SKILL.md`（缺失/空目录容错为空），镜像 `DirectoryPluginSource`。
- **`SkillProvider` SPI**（`io.pigagent.tool.skills.spi`）：镜像 `Plugin`，返回 `List<Skill>`，由内置模块经 `META-INF/services` 声明。
- **`SkillRegistry` 组合器**（`io.pigagent.tool.skills`）：按优先级顺序（工作区在前 = 最高优先）合并多源，按名去重（最高优先胜 = **工作区覆盖内置**），供 `listNames()`/`find(name)`。
- **`SkillsTool` 重构为组合器**：`@Tool` 方法 `listSkills`/`loadSkill` 及签名**逐字不变**（无模型面回归）；内部委托 `SkillRegistry`。`listSkills` 展示内置 + 工作区（按名去重）；`loadSkill` 按名解析、工作区覆盖内置；未知名 → not-found；空/缺失工作区目录容错。保留 `SkillsTool(Path)` 兼容构造（内部组合 工作区 + classpath 内置两源）。
- **新模块 `pig-agent-skills-builtin`**：curated 技能作为 classpath 资源 `src/main/resources/skills/{name}/SKILL.md`；`SkillCatalog`（单一事实源，镜像 `PluginCatalog`）+ `BuiltinSkillProvider`（`SkillProvider` 实现）+ `META-INF/services/io.pigagent.tool.skills.spi.SkillProvider` 声明。首发 7 个技能：`code-review`、`systematic-debugging`、`tdd`、`refactoring`、`git-commit`、`security-review`、`planning`。
- **装配**：`pig-agent-cli` 新增依赖 `pig-agent-skills-builtin`（令其资源上 classpath，供 `ClasspathSkillSource` 发现）；父 POM `<modules>` + `<dependencyManagement>` 增列该模块。`AgentBootstrap`/`SkillsToolProvider` 经兼容构造自动获得内置源，无需改 `ToolContext`。

无 **BREAKING**：`SkillsTool` 的 `@Tool` 名/签名/返回语义不变；模块缺席时 `ClasspathSkillSource` 发现为空 → 纯工作区行为（与今天一致）。

## Capabilities

### New Capabilities
- `builtin-skills`: 系统随发行版提供一组 curated 内置系统技能（每个 = 一份 `SKILL.md` 能力包），经 `SkillProvider` SPI + `ServiceLoader` 开箱发现；`SkillsTool` 经 `SkillSource` 策略抽象组合「内置源 + 工作区源」，`listSkills` 合并去重、`loadSkill` 工作区覆盖同名内置；模块缺席退化为纯工作区（零行为变更）。

<!-- 不改：plugin-system / tool-autoregister / tool-permissions / tool-availability / tool-json-contract 的行为契约。SkillsTool 仍是核心工具、仍经既有 ToolProvider 自动注册、风险分级不变（READ_ONLY）。 -->

## Impact

- **代码（`pig-agent-tools`）**：新增 `skills/Skill`、`skills/SkillSource`、`skills/SkillRegistry`、`skills/ClasspathSkillSource`、`skills/WorkspaceSkillSource`、`skills/FileSkill`、`skills/spi/SkillProvider`；`skills/SkillsTool` 重构为组合器（新增 `SkillsTool(SkillRegistry)` + 兼容 `SkillsTool(Path)`）。`SkillsToolProvider` 不变。
- **代码（新模块 `pig-agent-skills-builtin`）**：`SkillCatalog`、`BuiltinSkillProvider`、`ClasspathSkill`；7 份 `resources/skills/{name}/SKILL.md`；`META-INF/services/io.pigagent.tool.skills.spi.SkillProvider`。
- **装配（父 POM + cli）**：父 POM `<modules>` 增 `pig-agent-skills-builtin`、`<dependencyManagement>` 增其坐标；`pig-agent-cli/pom.xml` 增依赖。
- **协作/不改**：`ToolContext`（仍只需 `skillsDir()`）、`ToolRegistrar` 自动注册、`SkillsToolProvider`、`ToolAvailabilityGate`、权限 veto、返回契约 + 分发守卫，全部不变，叠加于组合后的 `SkillsTool` 之上。
- **测试**：`pig-agent-tools` 新增 `SkillRegistryTest`（去重/覆盖/未知/容错）、`WorkspaceSkillSourceTest`（列目录/缺失/空/无 `SKILL.md`）、`SkillsToolTest`（格式化/未知/覆盖）、`ClasspathSkillSourceTest`（test-scoped service 行发现）；`pig-agent-skills-builtin` 新增 `SkillCatalogTest`（一致性 + 7 技能非空）、`BuiltinSkillDiscoveryTest`（`ServiceLoader` 发现 7 技能各非空）、`SkillRegistryBuiltinTest`（真实内置 + 临时工作区覆盖端到端）。
- **文档**：`CLAUDE.md` 模块表新增 `pig-agent-skills-builtin` 行 + 内置技能与 `SkillSource` 组合说明。
