# composite-skill Specification (delta)

## ADDED Requirements

### Requirement: SKILL.md YAML front-matter 元数据（容错解析）

一份 `SKILL.md` MAY 以 `---` fenced YAML front-matter 起头，声明可选元数据键 `name`、`description`、`keywords`、`when-to-use`、`version`。系统 SHALL 以一个 `SkillManifestParser`（Strategy）解析之，产出不可变 `SkillMetadata`（`name`/`description`/`keywords`/`version`）与剥离 front-matter 后的正文 `body`。解析 MUST **容错**且永不抛：

- **无 front-matter** → 名字取 fallback（技能目录名），描述派生自正文首个非空行（去 `#`/空白、截断），正文即全文。
- **部分字段** → 缺失键归一为空串/空列表，其余照取。
- **畸形**（有起始 `---` 但无闭合 `---`）→ 视作无 front-matter（整体为正文 + 派生元数据），不抛异常。
- `keywords` / `when-to-use` MUST 同时支持逗号内联（`a, b, c`）与 `- item` 块列表两种写法。

`SkillMetadata` MUST 不可变（record，`keywords` 防御性拷贝），并提供 `ofName(name)` 兜底工厂供未实现解析的 `Skill` 经默认方法取得合法元数据。这些新增 MUST NOT 引入第三方 YAML 依赖（手写 mini-parser）。

#### Scenario: 完整 front-matter 被解析
- **WHEN** `SKILL.md` 顶部含闭合的 `---` front-matter，声明 `name`/`description`/`keywords`/`version`
- **THEN** `SkillMetadata` 的对应字段被填充，`keywords` 为解析出的列表，正文（`body`）为闭合 `---` 之后的内容（front-matter 已剥离）

#### Scenario: 无 front-matter 时容错派生
- **WHEN** `SKILL.md` 不含 front-matter（直接以 `# 标题` 起头）
- **THEN** `SkillMetadata.name` 取 fallback（目录名），`description` 派生自首个非空行，正文为全文；不抛异常

#### Scenario: 畸形 front-matter 容错为正文
- **WHEN** `SKILL.md` 以 `---` 起头但无闭合 `---`（畸形）
- **THEN** 整体被当作正文、元数据派生，`SkillManifestParser` 不抛异常

### Requirement: 支持文件目录浮现（有界契约）

一个技能 MAY 是一个目录 bundle：除 `SKILL.md` 外携带若干支持文件（模板/示例/数据等）。`Skill` SHALL 经 `List<SkillResource> supportingFiles()` 暴露之（默认空——单文件技能无支持文件）；每个 `SkillResource` MUST 提供相对路径、字节尺寸、是否文本、惰性 `read()`。`loadSkill` 的输出契约 MUST 有界：

- 技能**无支持文件**时，`loadSkill` MUST 逐字返回正文（backward compatible）。
- 技能**有支持文件**时，`loadSkill` MUST 在正文后追加一段「Supporting files」浮现：逐个列出相对路径 + 人类可读尺寸；**小文本文件**（文本且不超过单文件内联上限）MAY 内联其内容，二进制或超限文件仅列名并标注（不内联）。

浮现 MUST 受一个 `SkillLimits` 值对象约束（支持文件条数上限、单文件内联字节上限、内联总字节上限），默认安全、无需配置，以防超大/众多支持文件撑爆上下文/token。

#### Scenario: 无支持文件逐字返回正文
- **WHEN** 一个技能目录只有 `SKILL.md`，调用 `loadSkill(name)`
- **THEN** 返回值逐字等于该技能正文（不追加任何「Supporting files」段落）

#### Scenario: 有支持文件时浮现且有界
- **WHEN** 一个技能目录含 `SKILL.md` + 若干支持文件（含一个小文本文件与一个超过内联上限的文件），调用 `loadSkill(name)`
- **THEN** 返回值在正文后列出各支持文件的相对路径与尺寸；小文本文件被内联；超过内联上限的文件仅列名不内联

### Requirement: 工作区技能安全加固（遍历/符号链接/尺寸）

`WorkspaceSkillSource` 解析用户投放于 `workspace/skills/` 的技能，MUST 对**外部输入**加固，复用 real-path 归一化哲学（`toRealPath`，不可解析时退 `toAbsolutePath().normalize()`）：

- **路径遍历 / 符号链接逃逸**：一个技能目录、其 `SKILL.md`、其支持文件的 real-path MUST 落在其所属技能目录（进而 `skills/` 根）之内；任何经 `../` 或符号链接逃逸出边界的目录/文件 MUST 被拒绝（技能跳过或该文件剔除）。
- **尺寸上限**：`SKILL.md` 超过 `SkillLimits` 的尺寸上限的技能 MUST 被跳过；支持文件的内联 MUST 受单文件/总量上限约束（超限只列名）。
- **容错**：任一畸形/超限/逃逸的技能 MUST 被 `log.warn` 跳过而非抛出——MUST NOT 崩溃 `listSkills`/`loadSkill`；其余健康技能不受影响。

包含判定 SHALL 收敛于一个纯静态守卫（`SkillSecurity`，镜像 `SsrfGuard` 的纯函数/离线可测形态），而非散落于扫描逻辑。既有容错行为（目录缺失/为空/`null`/无 `SKILL.md` 子目录 → 空）MUST 保持不变。

#### Scenario: 符号链接逃逸的技能目录被跳过
- **WHEN** `workspace/skills/` 下某「技能目录」是指向 `skills/` 根之外的符号链接（在支持符号链接的平台上）
- **THEN** 该技能被 `log.warn` 跳过、不出现在 `listSkills`；其余健康技能仍被列出（`discover` 不抛异常）

#### Scenario: 超大 SKILL.md 被跳过
- **WHEN** 某技能目录的 `SKILL.md` 超过 `SkillLimits` 的尺寸上限
- **THEN** 该技能被跳过、不出现在 `listSkills`；`discover` 不抛异常

#### Scenario: 支持文件逃逸边界被剔除
- **WHEN** 某技能目录内一个「支持文件」经符号链接指向技能目录之外
- **THEN** 该文件不出现在该技能的 `supportingFiles()`/`loadSkill` 浮现中；正文与合规支持文件不受影响
