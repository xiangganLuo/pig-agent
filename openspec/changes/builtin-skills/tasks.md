## 1. 新模块骨架 pig-agent-skills-builtin（装配先行）

- [x] 1.1 新建 `pig-agent-skills-builtin/pom.xml`（parent = pig-agent；artifactId=`pig-agent-skills-builtin`；依赖 `pig-agent-tools`）。
- [x] 1.2 父 POM `<modules>` 增 `pig-agent-skills-builtin`（列在 `pig-agent-plugin-builtin` 之后）；`<dependencyManagement>` 增其坐标。
- [x] 1.3 `pig-agent-cli/pom.xml` 增依赖 `pig-agent-skills-builtin`（运行期 classpath，供 `ClasspathSkillSource` 发现）。

## 2. SkillSource 策略抽象 + 两源（pig-agent-tools，TDD）

- [x] 2.1 单测 `WorkspaceSkillSourceTest`（RED）：临时目录含 `a/SKILL.md`、`b/SKILL.md`、`c/`（无 SKILL.md）→ `discover()` 返回 a、b 不含 c；缺失目录/`null` → 空；`FileSkill.content()` 读回文件内容。
- [x] 2.2 新增 `skills/Skill`（接口 `name()` + `content() throws IOException`）、`skills/SkillSource`（`@FunctionalInterface discover()` + `default name()`）。
- [x] 2.3 新增 `skills/FileSkill`（惰性读 `Path`）、`skills/WorkspaceSkillSource`（列含 `SKILL.md` 子目录，容错）——GREEN 2.1。
- [x] 2.4 新增 `skills/spi/SkillProvider`（`List<Skill> skills()`）、`skills/ClasspathSkillSource`（`ServiceLoader<SkillProvider>` 聚合，容错，`name()="classpath"`）。
- [x] 2.5 单测 `ClasspathSkillSourceTest`：`src/test/resources/META-INF/services/io.pigagent.tool.skills.spi.SkillProvider` 声明一个 test-scoped `SampleSkillProvider` → `discover()` 含其技能；无 provider 时（隔离）不抛、返回可用列表。

## 3. SkillRegistry 组合器 + SkillsTool 重构（pig-agent-tools，TDD）

- [x] 3.1 单测 `SkillRegistryTest`（RED）：两个 fake `SkillSource`（同名技能）→ `all()`/`find()` 最高优先（前者）胜；`listNames()` 去重且字母序；未知名 `find` → 空；抛异常的源被跳过（容错）。
- [x] 3.2 新增 `skills/SkillRegistry`（优先级降序、`LinkedHashMap` `putIfAbsent` 去重、`listNames()`/`find()`/`all()`）——GREEN 3.1。
- [x] 3.3 单测 `SkillsToolTest`（RED）：以 fake registry 注入 → `listSkills` 格式化 `- name`/空→"No skills found."；`loadSkill` 命中返回 content、未知→"Skill not found: x"、`IOException`→"Error:"；`workspaceOverridesBuiltin`（工作区源在前的 registry，同名返回工作区内容）。
- [x] 3.4 重构 `skills/SkillsTool`：主构造 `SkillsTool(SkillRegistry)` + 兼容 `SkillsTool(Path)`（内部组合 workspace + classpath 两源）；`@Tool` 名/签名逐字不变——GREEN 3.3。
- [x] 3.5 确认 `SkillsToolProvider`（`new SkillsTool(ctx.skillsDir())`）无需改动即经兼容构造获得内置源。

## 4. 内置技能资源 + Catalog + Provider（pig-agent-skills-builtin，TDD）

- [x] 4.1 撰写 7 份 `src/main/resources/skills/{name}/SKILL.md`（`code-review`/`systematic-debugging`/`tdd`/`refactoring`/`git-commit`/`security-review`/`planning`）——各含标题 + when-to-use + 分步方法/清单，英文、~一页、面向 agent。
- [x] 4.2 新增 `ClasspathSkill`（classloader 读 `skills/<name>/SKILL.md`，UTF-8）、`SkillCatalog`（`SKILL_NAMES` 单一事实源 + `all()`）、`BuiltinSkillProvider`（`skills()`=`SkillCatalog.all()`）。
- [x] 4.3 `META-INF/services/io.pigagent.tool.skills.spi.SkillProvider` 声明 `io.pigagent.skills.builtin.BuiltinSkillProvider`。
- [x] 4.4 单测 `SkillCatalogTest`：`SKILL_NAMES` 大小=7、名唯一；一致性守卫——catalog 名集合 == 实际 classpath 资源目录集合；每个技能 `content()` 非空且含标题行。
- [x] 4.5 单测 `BuiltinSkillDiscoveryTest`：`ServiceLoader<SkillProvider>` 发现 `BuiltinSkillProvider`；`new ClasspathSkillSource().discover()` 返回全部 7 技能，各 `content()` 非空。
- [x] 4.6 单测 `SkillRegistryBuiltinTest`（端到端）：真实内置 + 临时工作区，工作区放同名 `code-review/SKILL.md` → `find` 返回工作区内容（覆盖）；仅内置的技能 → 返回内置内容；经 `SkillsTool` 组合验证 `listSkills` 含 7 内置名。

## 5. 构建与装配验收

- [x] 5.1 `mvn -q -pl pig-agent-skills-builtin -am compile` 绿（新模块独立可编译）。
- [x] 5.2 `mvn -q -pl pig-agent-cli -am compile` 绿（cli 依赖新模块，装配打通）。

## 6. 文档 + 全量验收

- [x] 6.1 `CLAUDE.md`：模块表新增 `pig-agent-skills-builtin` 行（模块数 15→16）；`pig-agent-tools` 行的 `SkillsTool` 描述补「多源组合」；架构要点补一段内置技能 + `SkillSource` 组合说明。
- [x] 6.2 `mvn -q test`（单线程 `-DforkCount=1 -Dsurefire.rerunFailingTestsCount=0`）绿，读 surefire XML 计数确认。
