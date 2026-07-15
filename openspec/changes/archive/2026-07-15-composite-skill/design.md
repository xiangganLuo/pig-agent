## Context

`SkillsTool`（`pig-agent-tools` 核心工具）经 `SkillRegistry` 组合「内置源（`ClasspathSkillSource`）+ 工作区源（`WorkspaceSkillSource`）」，一个技能 = 一份 `SKILL.md`。`builtin-skills` 已建立「SPI 发现 + 注册表单一事实源 + `SkillSource` 策略」的范式。本 spec 把技能从**单文件**升级为**复合能力包（目录 bundle）**，镜像 DeerFlow 的 composite Skill：元数据在列举时廉价浮现、正文按需加载、目录可携带支持文件、工作区技能加固。

不是重写工具语义，是**加一层元数据/正文的渐进契约 + 支持文件浮现 + 工作区安全边界**：`SkillsTool` 的 `@Tool` 名/签名逐字保留（无模型面回归），无 front-matter 的技能 `content()` 逐字等价，模块缺席仍退化为纯工作区。

## Goals / Non-Goals

**Goals:**
- SKILL.md 支持可选 YAML front-matter 元数据（`name`/`description`/`keywords`/`when-to-use`/`version`），**容错**——缺失/部分/畸形都不崩，7 个内置技能逐字兼容。
- **渐进加载**：`listSkills` 仅读元数据（省 token）、`loadSkill` 按需读正文 + 支持文件浮现。
- 技能目录可携带支持文件，`loadSkill` 有界浮现（列名 + 尺寸，小文本内联）。
- 工作区技能加固：拒绝**路径遍历 / 符号链接逃逸**出技能目录，`SKILL.md` + 支持文件**尺寸上限**；畸形/超限/逃逸技能容错跳过。
- 面向设计模式：`SkillMetadata` 值类型 + `SkillManifestParser` Strategy + `SkillLimits` 值对象 + `SkillSecurity` 纯静态守卫（镜像 `SsrfGuard`）。

**Non-Goals:**
- 不改 `SkillsTool` 的 `@Tool` 名/签名/未知名与读错兜底语义，不改其风险分级（READ_ONLY）。
- 不改 `SkillProvider` SPI / `ClasspathSkillSource` / `SkillRegistry` 的发现与去重契约（工作区覆盖内置保留）。
- 不引入技能热重载、远程技能仓库、版本协商/依赖解析（沿用 `plugin-system` 的 Non-Goals）。
- 不引入 YAML 第三方库（front-matter 由手写容错 mini-parser 承载，零新增依赖）。
- 不新增技能 WRITE/scaffold 工具（本次仅读路径加固；若未来加写，按本 spec D8 的原子 + `0600` 约束落地）。
- 不改 `ToolContext`（`SkillLimits.defaults()` 安全默认，无需运行期配置）。

## Decisions

### D1 — `SkillMetadata` 值类型（不可变 record）
`record SkillMetadata(String name, String description, List<String> keywords, String version)`。构造时 `keywords` 拷贝为不可变列表、`null` 归一为空串/空列表。工厂 `ofName(String name)` 返回「仅名字、其余空」的兜底元数据。理由：元数据是列举阶段的**廉价快照**，值类型天然不可变、易测、可缓存；`ofName` 让所有未实现解析的 `Skill`（测试替身、旧实现）经默认方法拿到合法元数据（backward compatible）。

### D2 — `SkillManifestParser` 策略（接口 + `FrontMatterManifestParser` 实现）
`interface SkillManifestParser { SkillManifest parse(String text, String fallbackName); static SkillManifestParser defaults(); }`，`record SkillManifest(SkillMetadata metadata, String body)`。默认实现 `FrontMatterManifestParser`：
- 文本首个非空行为 `---` → 逐行读到闭合 `---`，中间按 `key: value` **容错**解析（无冒号行忽略、值去引号、`keywords`/`when-to-use` 逗号或 `- item` 块列表都吃）；正文 = 闭合行之后（**front-matter 已剥离**）。
- 无 front-matter → 整体为正文，元数据派生（`description` = 首个非空行去 `#`/空白后截断 ~160 字）。
- **畸形**（有起始 `---` 但无闭合）→ 视作无 front-matter（整体正文 + 派生），永不抛。
理由：Strategy 让「front-matter 方言」可替换/扩展而不动调用方；纯函数（输入字符串、输出值）离线可测；手写 mini-parser 避免引第三方 YAML 且天然容错。

### D3 — 渐进加载：`Skill.metadata()`（廉价）vs `Skill.content()`（按需）
`Skill` 接口新增 `default SkillMetadata metadata()`（默认 `ofName(name())`）与 `default List<SkillResource> supportingFiles()`（默认空）——**默认方法 = 非破坏**，旧实现零改动编译。
- `FileSkill.metadata()`：只读文件**头部有界前缀**（首 N 行 / 上限字节，front-matter 与首标题必在顶部）→ `parse(prefix, name).metadata()`；读失败 → `ofName(name)`（永不抛）。
- `FileSkill.content()`：读全文 → `parse(full, name).body()`（剥离 front-matter）。
- `SkillsTool.listSkills()` **只调 `metadata()`**（绝不 `content()`），`loadSkill` 才调 `content()`。
理由：`listSkills` 只需名字/描述；若列举就把每份 `SKILL.md` 全读进内存则浪费（工作区可能几十个技能，正文各上千字）。「元数据 vs 正文」两级读法是本次核心 token 效率收益，且用一个**读正文即失败的间谍技能**在单测中断言 `listSkills` 不触发正文读取。

### D4 — 支持文件浮现：有界契约（`SkillResource` + `SkillLimits`）
`interface SkillResource { String path(); long size(); boolean isText(); String read() throws IOException; }`（`path` 为相对技能目录的路径）。`FileSkill.supportingFiles()` 在**技能目录 real-path 内**递归（限深）枚举 `SKILL.md` 以外的常规文件，产出 `FileSkillResource`。`loadSkill` 的输出契约：
- 无支持文件 → **逐字返回 `content()`**（backward compatible）。
- 有支持文件 → `content()` + `\n\n` + 一段「Supporting files (N):」：每文件 `- <相对路径> (<人类可读尺寸>)`；**小文本文件**（`isText()` 且 `size <= maxSupportingFileBytes`）在其下 fenced 内联；二进制/超限文件仅列名注 `binary`/`not inlined`。
`SkillLimits`（record 值对象，`defaults()`）：`maxSkillBytes`（默认 256 KiB）、`maxSupportingFileBytes`（内联上限，默认 32 KiB）、`maxSupportingFiles`（条数上限，默认 64）、`maxInlineBytes`（内联总字节上限，默认 128 KiB）。理由：支持文件是「复合」的核心，但**必须有界**——否则一个塞满大文件的技能目录会撑爆上下文/token。值对象把上限集中、默认安全（无需配置，镜像 `SandboxPolicy` 默认安全哲学）。

### D5 — 工作区安全加固：`SkillSecurity`（纯静态，镜像 `SsrfGuard`）
`SkillSecurity`（`final`，私有构造，纯静态）承载 real-path 归一化的**目录包含判定**：`isWithin(Path root, Path candidate)` = 二者 `toRealPath()`（不可解析时退 `toAbsolutePath().normalize()`）后 `candidate.startsWith(root)`。`WorkspaceSkillSource.discover()` 加固流程：
1. `root = skillsDir` 的 real-path；遍历**直接子目录**。
2. 每个候选技能目录：`isWithin(root, dir)` 且其 real-path 的父为 root（**直接子**）——否则符号链接逃逸/越级，`log.warn` 跳过。
3. `SKILL.md`：存在、是常规文件、real-path 仍在技能目录内（防 `SKILL.md` 符号链接逃逸）、`size <= maxSkillBytes`——否则跳过。
4. 通过者产出 `new FileSkill(name, dir(real), skillMd, limits)`。
支持文件枚举（D4）同样以技能目录 real-path 为根做 `isWithin` 过滤，逃逸文件剔除。理由：`WorkspaceSkillSource` 解析**用户投放**的目录，与 `FileSystemTools` 凭证黑名单 / `SsrfGuard` 私网判定同属「外部输入边界加固」——复用 real-path 归一化哲学（`toRealPath` 抓 `../` 与符号链接），把逻辑收在一个纯静态守卫里而非散落 `discover()`。

### D6 — 内置技能补 front-matter，`content()` 剥离保正文契约
为 7 份内置 `SKILL.md` 补 front-matter（`description`/`keywords`/`version`），令 `listSkills` 展示语义化描述。`ClasspathSkill.metadata()` 读资源头部前缀解析、`content()` 读全资源剥离 front-matter。**关键**：`content()` 剥离 front-matter 后正文仍以 `# 标题` 起头——故 `SkillCatalogTest`（`content().stripLeading().startsWith("# ")`）、`SkillRegistryBuiltinTest`（`loadSkill("tdd").startsWith("# Test-Driven Development")`）逐字通过，`content()` 契约不回归。理由：借内置技能端到端演示元数据浮现；剥离而非保留 front-matter，使 `loadSkill` 回吐的是**纯指令正文**（元数据已在 `listSkills` 浮现），语义更干净。

### D7 — `SkillsTool` 契约精化，`@Tool` 面逐字不变
- `listSkills()`：`registry.all()` → 按名排序 → 每技能取 `metadata()`：`description` 空 → `- <name>`（逐字兼容旧输出）；非空 → `- <name> — <description>`。空集合 → `"No skills found."`。
- `loadSkill(skill_name)`：`registry.find` 空 → `"Skill not found: <name>"`（逐字）；命中 → `content()` + 支持文件浮现（D4）；`IOException` → `"Error: <msg>"`（逐字）。
- 兼容构造 `SkillsTool(Path skillsDir)` 内部 `new SkillRegistry(List.of(new WorkspaceSkillSource(skillsDir, SkillLimits.defaults()), new ClasspathSkillSource()))`——`SkillsToolProvider`/`AgentBootstrap` 零改动。
理由：模型面契约（工具名/参数/未知名/读错文案）逐字保留是硬约束；仅**丰富**列举描述与正文附带的支持文件浮现，均为叠加、无回归。

### D8 — 若未来加技能写入：原子 + `0600`（本次不实现，仅立约）
本次仅**读路径**加固，不新增写工具。若后续加「技能 scaffold/写入」，MUST 走 temp-file + `Files.move`（原子）并 `restrictToOwner`（POSIX `0600`，非 POSIX 忽略），镜像 `JsonModelStore.persist`。此处立约以免将来遗漏，不产出代码（YAGNI）。

### D9 — 依赖方向与新增依赖：零新增第三方
`pig-agent-tools` skills 抽象仅用 JDK（`Files`/`InputStream`/`BufferedReader`）+ 既有 SLF4J；`pig-agent-skills-builtin` 仅依赖 `pig-agent-tools`。无环、零新增第三方库（front-matter 手写解析）。

## Risks / Trade-offs

- **R1 — `content()` 语义微调（剥离 front-matter）**：有 front-matter 的技能 `content()` 不再含 front-matter 块。→ front-matter 是元数据（已在 `listSkills` 浮现），非指令；无 front-matter 技能逐字等价；`@Tool` 签名不变。视作**改进非回归**（D6 已记录知情）。
- **R2 — Windows 符号链接测试局限**：Windows 建符号链接需权限（开发者模式/管理员），CI/本机可能失败。→ 符号链接逃逸单测用 `Assumptions.assumeThat` 在无法建链接时跳过；**遍历（`../`）与尺寸上限**的加固不依赖符号链接、始终受测。`SkillSecurity.isWithin` 纯逻辑单测覆盖包含/逃逸判定，不依赖真实符号链接。
- **R3 — 渐进列举的头部前缀读**：`metadata()` 只读头部前缀，若 front-matter 异常巨大（超前缀）→ 当作无 front-matter 派生。→ front-matter 天然小（几行），前缀上限（首 ~200 行 / 64 KiB）远超正常；异常大 front-matter 是畸形，容错派生即可。
- **R4 — 支持文件内联撑爆上下文**：技能目录塞大量/大文件。→ `SkillLimits` 条数 + 单文件内联上限 + 内联总字节三重有界；超限只列名不内联；`SKILL.md` 本身超限则整技能跳过（D4/D5）。
- **R5 — 工作区覆盖内置 + 元数据**：工作区同名技能 shadow 内置，其元数据也随之覆盖。→ 与既有覆盖语义一致（`SkillRegistry` 首见即锁定），可预期。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| SKILL.md front-matter 元数据（容错解析） | D1/D2；composite-skill R1；task 2.x | 已实现 |
| `SkillManifestParser` Strategy（接口 + 默认实现，纯函数） | D2；composite-skill R1；task 2.x | 已实现 |
| 渐进加载（listSkills 仅元数据 / loadSkill 按需正文） | D3；builtin-skills（MODIFIED）；composite-skill R2；task 3.x/5.x | 已实现 |
| 支持文件有界浮现（`SkillResource` + `SkillLimits`） | D4；composite-skill R3；task 4.x/5.x | 已实现 |
| 工作区加固：遍历/符号链接逃逸拒绝（`SkillSecurity`） | D5；composite-skill R4；task 4.x | 已实现 |
| 工作区加固：SKILL.md/支持文件尺寸上限 + 容错跳过 | D4/D5；composite-skill R4；task 4.x | 已实现 |
| 内置技能补 front-matter + `content()` 剥离保正文契约 | D6；builtin-skills（MODIFIED）；task 6.x | 已实现 |
| `SkillsTool` `@Tool` 名/签名逐字不变 | D7；composite-skill R2/R3；task 5.x | 已实现 |
| 兼容构造 `SkillsTool(Path)` + `SkillsToolProvider` 零改动 | D7；task 5.x | 已实现 |
| 未来技能写入的原子 + `0600` 约束（立约不实现） | D8 | 延后（本次仅立约，无代码） |
| 零新增第三方依赖、无环 | D9；task 2.x | 已实现 |
| Windows 符号链接测试以 `assumeThat` 跳过、遍历/尺寸始终受测 | R2；task 4.x | 已实现 |
| 文档同步（CLAUDE.md builtin-skills 段落） | proposal Impact；task 7.x | 已实现 |
