# Tasks — composite-skill

> 内环 TDD：每个可测单元先写测试（RED）→ 实现（GREEN）→ `mvn test` → 勾选。分组后 `mvn -q -pl pig-agent-cli -am compile`。

## 1. 前置

- [x] 1.1 从 `origin/main` 拉分支 `feat/20260715-composite-skill`（已建）。
- [x] 1.2 `mvn -q -pl pig-agent-cli -am compile` 绿基线（已确认）。

## 2. 元数据与解析（`pig-agent-tools`，Strategy）

- [x] 2.1 `SkillMetadata`（record：`name`/`description`/`keywords`/`version`，不可变，`ofName(name)` 兜底工厂）。
- [x] 2.2 `SkillManifest`（record：`metadata` + `body`）与 `SkillManifestParser`（接口 + `defaults()`）。
- [x] 2.3 `FrontMatterManifestParser`：`SkillManifestParserTest`（RED）——present（全字段）/absent（派生 name+desc）/partial（部分字段）/malformed（无闭合 `---` → 整体正文）/keywords 逗号与块列表/body 剥离 front-matter。实现至 GREEN。

## 3. `Skill` 契约扩展 + `FileSkill` 渐进读

- [x] 3.1 `Skill` 接口加 `default SkillMetadata metadata()`（`ofName`）+ `default List<SkillResource> supportingFiles()`（空）——确认既有实现（`FixedSkill`/匿名）仍编译。
- [x] 3.2 `SkillResource` 接口（`path`/`size`/`isText`/`read`）+ `FileSkillResource` 实现。
- [x] 3.3 `FileSkill` 升级：`metadata()` 只读头部前缀解析；`content()` 读全文剥离 front-matter。`FileSkillTest`（RED→GREEN）——惰性元数据（不读全文即得 name/desc）、`content()` 剥离 front-matter、无 front-matter 逐字等价、读失败 `metadata()` 兜底不抛。

## 4. 安全加固（`SkillSecurity` + `SkillLimits` + `WorkspaceSkillSource`）

- [x] 4.1 `SkillLimits`（record 值对象 + `defaults()`）。`SkillSecurity`（纯静态 `isWithin(root,candidate)` real-path 归一）。`SkillSecurityTest`（RED→GREEN）——包含/不含、`../` 逃逸、不可解析退 normalize。
- [x] 4.2 `WorkspaceSkillSource(Path, SkillLimits)` 加固扫描：real-path 直接子目录校验、`SKILL.md` 尺寸上限、畸形/超限/逃逸 `log.warn` 跳过。构造 `FileSkill(name, dirReal, limits)`。
- [x] 4.3 `FileSkill.supportingFiles()`：技能目录 real-path 内限深枚举 `SKILL.md` 外常规文件，`SkillLimits` 条数/尺寸约束、逃逸剔除。
- [x] 4.4 `WorkspaceSkillSourceHardeningTest`（RED→GREEN）——支持文件浮现、`../`/符号链接逃逸跳过（符号链接用 `assumeTrue` 跳过不可建链接的平台）、超大 `SKILL.md` 跳过；既有 `WorkspaceSkillSourceTest`（含 SKILL.md 才算/缺失/空/null）保持绿。

## 5. `SkillsTool` 契约精化（`@Tool` 面不变）

- [x] 5.1 `listSkills()` 仅调 `metadata()`（绝不 `content()`），格式 `- <name>`/`- <name> — <desc>`。
- [x] 5.2 `loadSkill()` 追加支持文件有界浮现（无支持文件逐字返回正文）。兼容构造 `SkillsTool(Path)` 用 `SkillLimits.defaults()`。
- [x] 5.3 `SkillsToolCompositeTest`（RED→GREEN）——渐进列举「读正文即抛的间谍技能」下 `listSkills` 不触发正文读取；带描述格式；支持文件浮现（含内联/超限只列名/二进制）；既有 `SkillsToolTest`（格式化/未知名/读错/覆盖）逐字通过。

## 6. 内置技能补 front-matter（`pig-agent-skills-builtin`）

- [x] 6.1 `ClasspathSkill.metadata()`（读资源头部前缀解析）+ `content()`（剥离 front-matter）。
- [x] 6.2 为 7 份 `resources/skills/<name>/SKILL.md` 补 front-matter（`description`/`keywords`/`version`），确保剥离后正文仍以 `# 标题` 起头。
- [x] 6.3 `CompositeBuiltinSkillTest`（RED→GREEN）——每内置技能 `metadata().description()` 非空、`content()` 剥离后 `startsWith("# ")`；既有 `SkillCatalogTest`/`SkillRegistryBuiltinTest`/`BuiltinSkillDiscoveryTest` 通过（`SkillRegistryBuiltinTest` 列举断言随描述格式微调）。

## 7. 收尾

- [x] 7.1 `mvn -q test`（单线程）全绿，读 surefire XML 计数确认（tools 293/skills-builtin 12，3 个符号链接测试在 Windows 上 `assumeTrue` 跳过）。
- [x] 7.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 7.3 更新 `CLAUDE.md` builtin-skills 段落（复合 Skill：元数据 + 支持文件 + 渐进加载 + 加固）。
- [x] 7.4 提交 `feat: 复合 Skill（SKILL.md 元数据 + 支持文件 + 渐进加载 + 遍历/符号链接/尺寸加固）`。
- [ ] 7.5 归档：同步主 spec → `openspec/specs/`，change 移 `openspec/changes/archive/2026-07-15-composite-skill/`，提交归档。
