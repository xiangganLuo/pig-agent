## Context

`SkillsTool`（`pig-agent-tools` 核心工具）是 agent 拉取「能力包」的入口，但今天只认工作区一处目录，agent 开箱无技能可用。`plugin-system` + `pig-agent-plugin-builtin` 已给出「随发行版内置一组能力 + SPI 发现 + 注册表单一事实源 + 抽象骨架」的成熟范式。本 spec **镜像该范式**为技能领域引入内置技能，并把 `SkillsTool` 从单目录扫描器重构为多源组合器。

不是重写工具语义，是**加一层组合抽象 + 一个内置资源模块**：`SkillsTool` 的 `@Tool` 名/签名/返回语义逐字保留（无模型面回归），模块缺席即退化为纯工作区（与今天一致）。

## Goals / Non-Goals

**Goals:**
- 随发行版内置一组 curated 系统技能（首发 7 个），开箱可 `listSkills`/`loadSkill`。
- 引入 `SkillSource` 策略抽象 + `SkillProvider` SPI + `SkillRegistry` 组合器，镜像插件范式（`PluginSource`/`Plugin`/`PluginCatalog`）。
- 内置源经 classpath + `ServiceLoader` 发现；工作区源扫既有目录；两源经注册表合并，**工作区覆盖同名内置**。
- `SkillsTool` `@Tool` 面逐字不变；模块缺席退化为纯工作区（零行为变更）。

**Non-Goals:**
- 不改 `plugin-system`/`tool-autoregister`/`tool-permissions`/`tool-availability`/`tool-json-contract` 的行为契约。
- 不改 `SkillsTool` 的对外 `@Tool` 名/签名/语义，不改其风险分级（`SkillsTool` 方法为 READ_ONLY）。
- 不引入技能热重载、版本协商、远程技能仓库（沿用 `plugin-system` 的 Non-Goals 精神）。
- 不改 `ToolContext`（内置源无需运行期依赖，纯 classpath 发现）。

## Decisions

### D1 — `SkillSource` 策略抽象（镜像 `PluginSource`）
在 `pig-agent-tools` 的 `io.pigagent.tool.skills` 引入 `@FunctionalInterface SkillSource { List<Skill> discover(); default String name(); }`。语义与 `PluginSource` 逐字对齐：**容错**（实现永不抛、永不返 `null`，失败降级为空列表），单抽象方法便于测试用 lambda 注入。理由：技能发现与插件发现是同构问题（多源、容错、可组合），复用同一策略形态使读者一眼认得，且天然支持「内置 + 工作区」以外未来更多源（如远程）而无需改组合器。

### D2 — `Skill` 值类型：name + 惰性 content
`Skill` 为接口：`String name()` + `String content() throws IOException`。`content()` **惰性**——`discover()` 只返回轻量句柄（名 + 读取器），仅 `loadSkill` 命中时才真正读资源/文件。理由：`listSkills` 只需名字，若 `discover` 就把所有 `SKILL.md` 读进内存则浪费（工作区可能几十个技能）；惰性使列举廉价、加载精确。`content()` 显式声明 `throws IOException` 让 `SkillsTool` 沿用今天的 "Error: <msg>" 兜底格式（读失败不抛给模型）。

### D3 — `SkillProvider` SPI（镜像 `Plugin`）+ `ClasspathSkillSource`（镜像 `ServiceLoaderPluginSource`）
- `io.pigagent.tool.skills.spi.SkillProvider { List<Skill> skills(); }`：内置模块经 `META-INF/services/io.pigagent.tool.skills.spi.SkillProvider` 声明其实现，`ServiceLoader` 发现——**加一个内置技能 = 往 catalog 加一名 + 放一份资源**，无中央装配改动。
- `ClasspathSkillSource implements SkillSource`：用 `ServiceLoader<SkillProvider>` 迭代（容错，坏条目 `log.warn` 跳过，镜像 `ServiceLoaderPluginSource` 的 while-hasNext-try 结构）聚合所有 provider 的 `skills()`。`name()` 返回 `"classpath"`。
- 内置模块提供**单个** `BuiltinSkillProvider`（其 `skills()` = `SkillCatalog.all()`），而非「每技能一个 provider」：技能只是一份 MD 资源，无需各自的 SPI 条目；单 provider + catalog 内枚举名字最简洁。

### D4 — `WorkspaceSkillSource`（镜像 `DirectoryPluginSource`）
`WorkspaceSkillSource implements SkillSource`，构造入参 `Path skillsDir`。`discover()`：目录为 `null`/不存在/非目录 → `List.of()`（容错）；否则列出**含 `SKILL.md` 的**子目录，各产出一个 `FileSkill(name, dir/SKILL.md)`。`name()` 返回 `"workspace:<dir>"`。
- **判据「含 `SKILL.md` 才算技能」**：技能的定义就是「一份 `SKILL.md`」，列出无 `SKILL.md` 的空目录只会让随后的 `loadSkill` 失败、误导模型。这是对旧 `listSkills`（列所有子目录）的**有意精化**——正常场景（每个技能目录都有 `SKILL.md`）逐字等价，仅不再展示不可加载的残缺目录。记录于此以示知情。

### D5 — `SkillRegistry` 组合器：优先级去重 = 工作区覆盖内置
`SkillRegistry(List<SkillSource> sources)`，**sources 按优先级降序**（最高优先在前）。`all()` 按顺序遍历各源 `discover()`，用 `LinkedHashMap<name, Skill>` + `putIfAbsent` 语义**首见即锁定**（= 最高优先胜），去重。`listNames()` = `all()` 的名字集合、字母序排序（确定性、易测）。`find(name)` = `all()` 中首个同名。
- **工作区覆盖内置**由「工作区源排在内置源之前」实现：`new SkillRegistry(List.of(workspaceSource, classpathSource))`。用户在 `workspace/skills/code-review/SKILL.md` 放一份，即 shadow 掉内置 `code-review`。
- 遍历各源 `discover()` 再包一层 try/catch 降级为空（纵深防御，尽管源本身已容错）。

### D6 — `SkillsTool` 重构为组合器，`@Tool` 面逐字不变
- 主构造 `SkillsTool(SkillRegistry registry)`；保留兼容构造 `SkillsTool(Path skillsDir)`，其内部组合 `new SkillRegistry(List.of(new WorkspaceSkillSource(skillsDir), new ClasspathSkillSource()))`。
- `@Tool listSkills()`：`registry.listNames()`；空 → `"No skills found."`；否则每名一行 `"- <name>"`（与旧格式一致）。
- `@Tool loadSkill(skill_name)`：`registry.find(name)` 为空 → `"Skill not found: <name>"`（与旧一致）；命中 → `content()`，`IOException` → `"Error: <msg>"`（与旧一致）。
- **为何 `SkillsToolProvider` 不动**：它今天就是 `new SkillsTool(context.skillsDir())`；兼容构造承接组合逻辑，故 auto-register（默认）与手动兜底两条路径都自动获得内置源。`ToolContext` 无需新字段。

### D7 — 内置模块 `pig-agent-skills-builtin`（镜像 `pig-agent-plugin-builtin`）
- 包 `io.pigagent.skills.builtin`；artifactId `pig-agent-skills-builtin`；依赖仅 `pig-agent-tools`（拿 `Skill`/`SkillProvider` 接口）。
- `SkillCatalog`（单一事实源）：`SKILL_NAMES = List.of("code-review","systematic-debugging","tdd","refactoring","git-commit","security-review","planning")`；`all()` 把每名映射为 `ClasspathSkill(name, "skills/<name>/SKILL.md", loader)`。
- `ClasspathSkill implements Skill`：`content()` 经 classloader 读 `skills/<name>/SKILL.md`（UTF-8），资源缺失抛 `IOException`。
- `BuiltinSkillProvider implements SkillProvider`：`skills()` 返回 `SkillCatalog.all()`；`META-INF/services/io.pigagent.tool.skills.spi.SkillProvider` 声明它。
- **一致性守卫**：单测断言 `SkillCatalog.SKILL_NAMES` == 实际 classpath 资源目录集合 == provider `skills()` 名集合（镜像 `PluginCatalogTest` 的漂移守卫）。

### D8 — 技能内容：agent 面的方法指南（英文，~一页）
7 份 `SKILL.md` 均为**面向 agent 的可执行方法指南**（非规则/非本仓治理文档），各含：标题、when-to-use、一套具体分步方法/清单。取材自扎实工程实践（可借鉴本仓 `.claude/rules/common/*` 的精神，改写为 agent 第一人称的操作指南）。7 个：`code-review`、`systematic-debugging`、`tdd`、`refactoring`、`git-commit`、`security-review`、`planning`。英文以贴合模型上下文常态。

### D9 — 依赖方向与新增依赖：零新增第三方
- `pig-agent-tools` 的 skills 抽象只用 JDK（`ServiceLoader`/`Files`/`InputStream`）+ 既有 SLF4J，无新第三方。
- `pig-agent-skills-builtin` 仅依赖 `pig-agent-tools`（无环：tools 不反向依赖 builtin）。
- `pig-agent-cli` 增依赖 `pig-agent-skills-builtin`（运行期 classpath）。父 POM 增 module + dependencyManagement。无环、零新增第三方库。

## Risks / Trade-offs

- **R1 — `listSkills` 语义微调（只列含 `SKILL.md` 的目录）**：对残缺空目录不再展示。→ 技能定义即「一份 `SKILL.md`」，展示不可加载目录反而误导；正常场景逐字等价（D4 已记录知情）。
- **R2 — 工作区覆盖内置可能「意外」shadow**：用户建同名目录会盖掉内置技能。→ 这是**设计意图**（用户可定制/覆盖内置），且仅影响同名；列举去重后仍只显示一份，行为可预期（D5）。
- **R3 — `ClasspathSkillSource` 在无内置模块的 classpath 上**：`ServiceLoader` 找不到 provider。→ 返回空 → `SkillRegistry` 退化为纯工作区 = 今天行为（模块缺席零变更，backward compatible）。
- **R4 — 兼容构造 `SkillsTool(Path)` 隐式挂 classpath 源**：读者可能不察觉它现在也合并内置。→ 以 javadoc 明示；且这正是「模块在则内置可用、不在则纯工作区」的自洽实现，且 `SkillsToolProvider` 无需改动。
- **R5 — 惰性 `content()` 与源存活期**：`Skill` 句柄持有 `Path`/资源路径，读取时目录/资源已变。→ `IOException` 被 `SkillsTool` 兜底为 "Error:"；与今天读文件失败的处理一致。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| `SkillSource` 策略抽象（容错、单抽象方法） | D1；builtin-skills R1；task 2.x | 已实现 |
| `Skill` 惰性 content 值类型 | D2；task 2.x | 已实现 |
| `SkillProvider` SPI + `ClasspathSkillSource`（ServiceLoader 发现） | D3；builtin-skills R1/R2；task 2.x/4.x | 已实现 |
| `WorkspaceSkillSource`（含 `SKILL.md` 判据、容错） | D4；builtin-skills R3；task 2.x | 已实现 |
| `SkillRegistry` 优先级去重 = 工作区覆盖内置 | D5；builtin-skills R2/R4；task 3.x | 已实现 |
| `SkillsTool` 组合器，`@Tool` 面逐字不变 | D6；builtin-skills R2/R5；task 3.x | 已实现 |
| 内置模块 `pig-agent-skills-builtin` + catalog 一致性 | D7；builtin-skills R1；task 4.x | 已实现 |
| 7 个 curated 技能内容（agent 面方法指南） | D8；builtin-skills R1；task 4.x | 已实现 |
| 零新增第三方依赖、无环、父 POM/cli 装配 | D9；builtin-skills R5；task 5.x | 已实现 |
| 模块缺席退化为纯工作区（零行为变更） | D5/D6；builtin-skills R4；task 3.x（单测） | 已实现 |
| 文档同步（CLAUDE.md 模块表 + 说明） | proposal Impact；task 6.x | 已实现 |
