## Why

今天一个「技能」几乎等于一份裸 `SKILL.md`：`listSkills` 把每个技能的内容读法当作惰性句柄，但**列举时只有名字可展示**（无描述、无触发词），`loadSkill` 直接把整份 `SKILL.md` 回吐。这有两个短板：

1. **列举不省 token 也不含语义**：agent 面对一串裸名字（`- code-review`）无从判断「何时该拉哪个技能」，只能逐个 `loadSkill` 试探——既费 token 又低效。DeerFlow 的 composite Skill 范式给出了答案：**元数据（front-matter）在列举时廉价浮现，正文按需加载**（渐进加载 / progressive disclosure）。
2. **技能只能是单文件**：一个真实能力包往往需要「说明 + 模板/示例/脚本等支持文件」，当前 `SKILL.md` 单文件模型承载不了。
3. **工作区技能未加固**：`WorkspaceSkillSource` 直接把用户丢进 `workspace/skills/` 的目录当技能扫描，未防**路径遍历 / 符号链接逃逸**，也无**尺寸上限**（可被超大/zip-bomb 式内容拖垮列举）。

本次把技能从「单份 `SKILL.md`」升级为**复合能力包（目录 bundle）**，面向设计模式落地：`SkillMetadata` 值类型 + `SkillManifestParser` 策略解析 front-matter；`Skill` 契约扩出**廉价 `metadata()`**（渐进列举）与**有界 `supportingFiles()`**（支持文件浮现）；`WorkspaceSkillSource` 复用 `FileSystemTools`/`SsrfGuard` 的 real-path 归一化哲学做遍历/符号链接/尺寸加固。**`SkillsTool` 的 `@Tool` 名/签名逐字不变（无模型面回归）**，注册表去重 + 工作区覆盖内置行为保留，7 个内置技能仍开箱可列可加载。

## What Changes

- **`pig-agent-tools`（`io.pigagent.tool.skills`）新增：**
  - `SkillMetadata`（record 值类型）：`name` / `description` / `keywords` / `version`，含 `ofName(name)` 兜底工厂——**不可变**。
  - `SkillManifestParser`（**Strategy 接口**）+ `FrontMatterManifestParser`（默认实现）：解析可选的 `---` fenced YAML front-matter（`name`/`description`/`keywords`/`when-to-use`/`version`），**容错**——缺 front-matter → 名字取目录、描述取首个标题/首行；畸形（无闭合 `---`）→ 整体当正文、元数据派生。返回 `SkillManifest(metadata, body)`（正文已剥离 front-matter）。
  - `SkillResource`（支持文件描述符：相对路径 + 尺寸 + 是否文本 + 惰性 `read()`）。
  - `SkillLimits`（record 值对象）：`SKILL.md` 尺寸上限、单支持文件内联上限、支持文件条数上限、内联总字节上限；`defaults()` 安全默认（无需配置）。
  - `SkillSecurity`（纯静态工具，镜像 `SsrfGuard`）：real-path 归一化的目录包含判定（遍历/符号链接逃逸拒绝）。
- **`Skill` 接口扩契约（默认方法，非破坏）**：新增 `default SkillMetadata metadata()`（渐进列举，默认由 `name()` 派生）与 `default List<SkillResource> supportingFiles()`（默认空）。既有实现（`FixedSkill`、匿名测试技能）零改动仍编译。
- **`FileSkill` 升级**：`metadata()` **只读文件头部有界前缀**解析 front-matter（渐进、省 IO）；`content()` 读全文并剥离 front-matter（无 front-matter 时逐字等价）；`supportingFiles()` 在**技能目录 real-path 内**枚举 `SKILL.md` 以外的常规文件，受 `SkillLimits` 条数/尺寸约束、符号链接逃逸剔除。
- **`WorkspaceSkillSource` 加固**：扫描时对每个技能目录做 real-path 包含校验（拒绝符号链接逃逸出 `skills/` 根）、`SKILL.md` 尺寸上限校验；**畸形/超限/逃逸的技能被 `log.warn` 跳过（容错，永不崩溃 `listSkills`）**。
- **`SkillsTool` 契约精化（`@Tool` 名/签名不变）**：
  - `listSkills()`：仅调 `metadata()`（**绝不读正文**），格式 `- <name>` 或 `- <name> — <description>`（描述空时退回裸名，逐字兼容旧输出）。
  - `loadSkill(name)`：读 `content()`（全文，front-matter 已剥离）；**有支持文件时**追加一段有界「Supporting files」浮现（列名 + 尺寸，小文本内联）；**无支持文件时逐字返回正文**（逐字兼容旧行为）。
- **`pig-agent-skills-builtin`**：`ClasspathSkill` 的 `metadata()`（读资源头部前缀解析）+ `content()`（剥离 front-matter）；为 7 个内置 `SKILL.md` **补 front-matter**（description/keywords/version），令 `listSkills` 展示语义化元数据——正文（剥离后）仍以 `# 标题` 开头，`content()` 契约不回归。

无 **BREAKING**：`@Tool` 名/签名/未知名与读错兜底逐字不变；无 front-matter 的技能 `content()` 逐字等价；模块缺席仍退化为纯工作区。

## Capabilities

### New Capabilities
- `composite-skill`: 技能从单份 `SKILL.md` 升级为**复合能力包**——SKILL.md 支持可选 YAML front-matter 元数据（容错解析，`SkillMetadata` + `SkillManifestParser` 策略）；技能目录可携带支持文件并经 `loadSkill` 有界浮现；`listSkills` 渐进只读元数据、`loadSkill` 按需读正文（省 token）；`WorkspaceSkillSource` 对工作区技能做路径遍历/符号链接逃逸/尺寸加固，畸形/超限/逃逸技能容错跳过。

### Modified Capabilities
- `builtin-skills`: 「SkillSource 策略抽象组合内置源与工作区源」需求精化为**渐进加载契约**——`listSkills` MUST 仅读元数据、`loadSkill` MUST 按需读正文（+ 支持文件浮现）；内置技能 MAY 携带 front-matter 以在列举时展示语义化描述（正文剥离 front-matter 后仍逐字以标题起头）。

## Impact

- **代码（`pig-agent-tools`）**：新增 `skills/SkillMetadata`、`skills/SkillManifestParser`、`skills/FrontMatterManifestParser`、`skills/SkillManifest`、`skills/SkillResource`、`skills/FileSkillResource`、`skills/SkillLimits`、`skills/SkillSecurity`；升级 `skills/Skill`（默认方法）、`skills/FileSkill`、`skills/WorkspaceSkillSource`、`skills/SkillsTool`。`SkillsToolProvider`/`ToolContext`/`AgentBootstrap` 不变（`SkillsTool(Path)` 兼容构造内部用 `SkillLimits.defaults()`）。
- **代码（`pig-agent-skills-builtin`）**：升级 `ClasspathSkill`（`metadata()`/`content()`）；7 份 `resources/skills/<name>/SKILL.md` 补 front-matter。
- **协作/不改**：`ToolRegistrar` 自动注册、`ToolAvailabilityGate`、权限 veto、返回契约 + 分发守卫、`SkillCatalog` 漂移守卫（名集合不变）全部不变。
- **测试**：`pig-agent-tools` 新增 `SkillManifestParserTest`（present/absent/partial/malformed）、`SkillSecurityTest`（real-path 包含/逃逸）、`WorkspaceSkillSourceTest` 扩展（遍历/符号链接/尺寸跳过 + 支持文件浮现）、`SkillsToolTest` 扩展（渐进列举不读正文 + 支持文件浮现）、`FileSkillTest`（惰性元数据 + 剥离 front-matter）。`pig-agent-skills-builtin` 扩展（front-matter 元数据 + 内容剥离后仍以标题起头）。
- **文档**：`CLAUDE.md` builtin-skills 段落补充复合 Skill（元数据 + 支持文件 + 渐进加载 + 加固）。
