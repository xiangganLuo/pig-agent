## Context

pig 的技能栈（`pig-agent-tools`，`io.pigagent.tool.skills`）是**手搓的薄子集**：`SkillsTool`（`listSkills`/`loadSkill`，均 `readOnly`）经 `SkillRegistry` 组合 `WorkspaceSkillSource`（`workspace/skills/<name>/SKILL.md`）+ `ClasspathSkillSource`（内置 SPI）。composite-skill 建了 `SkillManifestParser`/`FrontMatterManifestParser`（`---` fenced front-matter → `SkillMetadata`）+ 支持文件 + 渐进加载 + `SkillSecurity` 加固；autonomous-skills 建了 `proposeSkill`→`.pending` 暂存→人工门→原子提升写路径。而 `PigAgent`（`PigAgent.java:642-643,806-807` 交互轨 + `806-807` 渠道轨）主动 `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()`，把 2.0 原生技能引擎全关。

AgentScope 2.0 自带整套技能自学习闭环（离线仓 `D:\env\...\io\agentscope` 已装 `agentscope-core:2.0.0` + `agentscope-harness:2.0.0`）。北极星（记忆：pig-agent-positioning）是「harness 外包 2.0，pig 只留差异化」——继续手搓技能引擎与之相悖。本 spec 是**内核路线图 Wave-1 的 S1（foundation）**：把原生**存取 + 解析**引擎当「库」采纳到 pig 只读读栈，作为 S2（curator/usage）、S3（promotion gate）的基座。

设计来源：`docs/design/personal-assistant-core-design.md` §7 与 REPLACE/KEEP 台账中「`SkillsTool`+`SkillSource`… KEEP + EXTEND（读路径保留，在其上叠加）」。

## Goals / Non-Goals

**Goals:**
- 把原生 `AgentSkillRepository`（core）当**库**藏在 pig 的 `SkillSource` seam 背后：新增 `NativeRepositorySkillSource`，`getAllSkills()` → pig `Skill`，喂现有 `SkillRegistry` 去重。默认 `FileSystemSkillRepository(workspace/skills)`，可选 `ClasspathSkillRepository`。
- **「引擎不当嘴」红线**：继续 `disableDynamicSkills()`/`disableDefaultWorkspaceSkills()`，**绝不重开** `<available_skills>` prompt 注入路径，不注册原生技能工具（`load_skill_through_path`/`read_file`/`grep`）。`SkillsTool` 的 `@Tool` 名/签名/返回语义 + 系统 prompt 字节稳定性**全不变**。
- **向后安全**：`skills.native.enabled` 默认 false → `SkillRegistry` 源列表逐字节等价今天；开启也只是多一个同根源、去重折叠后行为等价。
- **兼容性核对**：pig `workspace/skills/<name>/SKILL.md` 布局与原生 `FileSystemSkillRepository` 一致；`.pending`/`.archive` 点前缀跳过不破。
- **承重 spike**（门整条技能线）：证明原生 `SkillCurator`/`SkillPromotionGate`/`SkillUsageStore` 能**脱离原生 prompt 注入、当纯库单独驱动**；核对 pig `FrontMatterManifestParser` ↔ 原生 `MarkdownSkillParser` 解析等价。
- 面向设计模式：Adapter（`NativeRepositorySkillSource`/`NativeAgentSkill` 把原生对象适配成 pig `Skill`/`SkillSource`）+ 复用既有 Strategy/registry（`SkillSource`/`SkillRegistry`）+ 值对象（`SkillMetadata`/`SkillResource`）。

**Non-Goals:**
- 不采纳 harness 层的 curator/usage/promotion（`SkillCurator`/`SkillUsageStore`/`SkillPromotionGate`/`SkillPromoter`/`SkillSecurityScanner`）——**S2/S3 分期**；S1 只证明它们可当纯库（spike），不接线。
- 不启用原生 self-learning 工具（`enableSkillManageTool`/`ProposeSkillTool`/`SkillManageTool`）——pig 已有自己的 autonomous-skills 写路径。
- 不重开原生动态技能 prompt（`DynamicSkillMiddleware`/`AgentSkillPromptProvider`/`HarnessSkillMiddleware`/`SkillPromptBuilder`/`SkillRuntime`/`SkillCatalog`）——红线。
- 不改 `SkillsTool` 的 `@Tool` 名/签名/未知名与读错兜底语义；不改 `WorkspaceSkillSource`/`ClasspathSkillSource`/composite-skill/autonomous-skills 契约。
- 不引入 git/DB/Nacos 远程技能仓（`agentscope-extensions-skill-*` 不在离线仓）、不引入技能热重载/marketplace 物化。

## 承重 Spike 结论（javap 签名级 + 可运行验证：均已通过 ✅）

> 用 `javap -classpath <jar> -public <fqcn>` 核对，jar = 离线仓 `agentscope-core-2.0.0.jar` + `agentscope-harness-2.0.0.jar`。判据：（A）原生仓可当只读源采纳；（B）自学习闭环三件套脱离 prompt 注入可当纯库驱动；（C）解析等价。
>
> **可运行验证（tasks 1.x，2026-07-20 跑通）**：`NativeSkillEngineSpikeTest`（`pig-agent-core`，3/3 绿）驱动了 B——`SkillUsageStore` 独立 load/register/bump、`SkillCurator.runOnce(Instant.now())` 独立返回 `CuratorRunReport`、`RejectAllGate.review(...)` 独立返回决策——**全程零原生 middleware/prompt provider/model**；`NativeSkillParserSpikeTest`（`pig-agent-tools`，3/3 绿）验证了 C（解析等价）+ A（布局兼容）。两条与签名级结论**一致**，另得两点**精化**（非矛盾）：
> - **R-Spike-1（gate 语义精化 → D9）**：`RejectAllGate.review(...)` 返回的不是 `Reject` 而是 **`Defer`**（reason=`promotion requires explicit HarnessAgent.promoteSkill call by an authorized caller`）——保证等价：**永不 `Approve`**，无授权显式调用则不晋级。S3 fail-closed 采纳按「非交互轨永不 Approve」判定，不硬绑 `Reject` 子类型。
> - **R-Spike-2（native 仓更严格 → D6）**：native `FileSystemSkillRepository` **要求可解析 front-matter（name/description）** 才登记一个技能——无 front-matter 的裸 `SKILL.md`（如 `# Beta\nbody`）被跳过；pig `WorkspaceSkillSource` 宽松（从目录名派生）。对 S1 同根采纳**安全**：native 源在 pig 源之后（最低优先级，D4），pig 宽松源已覆盖裸技能，去重并集不变。native 仓也**未浮现** `.pending` 暂存目录（暂存不变量在 native 侧亦成立）。

### A. 原生仓 → pig 只读源，采纳成立 ✅
- **`io.agentscope.core.skill.repository.AgentSkillRepository`**（core 接口，`extends AutoCloseable`）：`List<AgentSkill> getAllSkills()`、`AgentSkill getSkill(String)`、`List<String> getAllSkillNames()`、`boolean skillExists(String)`、`getSource()`、`isWriteable()`/`setWriteable(boolean)`、`save/delete`。→ `discover()` 直接 `getAllSkills()`。
- **`io.agentscope.core.skill.AgentSkill`**（core 类）：`getName()`、`getDescription()`、`getSkillContent()`、`Map<String,Object> getMetadata()`、`Map<String,String> getResources()`（path→内容）、`Set<String> getResourcePaths()`、`getSkillId()`、`Optional<Path> getOriginDir()`。→ 干净映射到 pig `Skill`（`name()`/`content()`/`metadata()`/`supportingFiles()`）。
- **`FileSystemSkillRepository(Path)`**（+ `(Path,boolean lazy)`、`(Path,boolean,String source)`、`(Path,boolean,String,boolean writeable)`）——**离线、纯 `Path`，无 `AbstractFilesystem`/`RuntimeContext`/网络**。
- **`ClasspathSkillRepository(String) throws IOException`**（+ `(String,String)`）——离线、classpath 资源。
- **结论**：S1 用这两个 **core** 实现即可，`pig-agent-tools` 已依赖 `agentscope-core` → **零新增依赖**。

### B. 自学习闭环三件套脱离 prompt 注入、可当纯库驱动，"只用引擎不用嘴" ✅
- **prompt 注入是一组独立的类，pig 从不安装**：core `DynamicSkillMiddleware`/`AgentSkillPromptProvider`；harness `HarnessSkillMiddleware`/`SkillUsageMiddleware`/`SkillCuratorMiddleware`、`runtime/{SkillPromptBuilder,SkillRuntime,SkillCatalog,SkillLoadTool}`、`tool/{SkillManageTool,ProposeSkillTool}`。这些才渲染 `<available_skills>` + 注册 `load_skill_through_path`/`read_file`/`grep`。pig 保留 `disableDynamicSkills()`+`disableDefaultWorkspaceSkills()` ⇒ 一个都不装。
- **引擎类的构造/驱动不依赖上述任何一个**（构造函数签名里无 `Model`/`Agent`/`Middleware`/`PromptProvider`）：
  - `SkillUsageStore(AbstractFilesystem)` / `(AbstractFilesystem, String)` — `load()`/`save()`/`bumpView`/`bumpUse`/`bumpPatch`/`markAgentCreated`/`setState`/`agentCreatedReport()`。
  - `SkillCurator(AbstractFilesystem, SkillUsageStore, WorkspaceSkillRepository, SkillCuratorConfig)` — `runOnce(Instant)`/`applyAutomaticTransitions(Instant)`/`shouldRunNow(Instant)`/`runUmbrellaDryRunReport(Instant)`/`loadState`/`saveState`。
  - `SkillPromotionGate.review(SkillCandidate, RuntimeContext): Mono<PromotionDecision>` — 实现 `LocalApprovalGate()`（离线，`defaultPrompter()`/`stdinPrompter(in,out)`）、`RejectAllGate()`（平凡）、`NotifyAndWaitGate(List<NotificationSink>, WorkspaceManager, String[, Duration])`。
  - `SkillPromoter(WorkspaceSkillRepository staging, WorkspaceSkillRepository active, WorkspaceManager, SkillUsageStore, SkillPromotionGate, String, String[, SkillAuditLog])` — `promote(String, String, RuntimeContext): Mono<PromotionResult>`。
  - `SkillSecurityScanner.scan(String, String, Map<String,String>): ScanResult` / `scanSingleFile(String, String)` / `shouldAllow(TrustLevel, Verdict)` — **纯静态**，无 FS/model。
- **离线驱动的依赖地板 = `LocalFilesystem`**（`io.agentscope.harness.agent.filesystem.local.LocalFilesystem`，`AbstractFilesystem` 的本地目录实现，**非** sandbox/容器 `SandboxBackedFilesystem`）+ 一个 `Supplier<RuntimeContext>`（pig 已为原生 state 构造 `RuntimeContext`）。`WorkspaceSkillRepository(AbstractFilesystem, String, Supplier<RuntimeContext>[, String[, boolean]])` 即可离线构造。
- **结论**：curator/gate/promoter/usage-store 全可对一个本地目录 + `LocalFilesystem` 构造并驱动，**无 LLM、无 `<available_skills>`**。"只用引擎不用嘴"签名级成立；task 1.x 写可运行 spike（构 `LocalFilesystem` → `SkillCurator.runOnce(now)` 返回 `CuratorRunReport`；`RejectAllGate.review(...)` 返回 Reject；`LocalApprovalGate` 用 `stdinPrompter` 喂批准）坐实。
- **诚实 caveat**：`bumpView`/`bumpUse` 正常由原生 `SkillUsageMiddleware`/`SkillLoadTool` 在推理环喂；pig 不当嘴 ⇒ S2 采纳 curator 时须由 pig 侧（`loadSkill`）自喂 usage，否则老化无输入。S1 不涉。

### C. 解析等价 ✅（核心键）
- 原生 `MarkdownSkillParser.parse(String): ParsedMarkdown{Map<String,Object> getMetadata(), String getContent(), boolean hasFrontmatter()}`（静态、容错）+ `generate(Map, body)`。
- pig `FrontMatterManifestParser.parse(text, fallbackName): SkillManifest{SkillMetadata, body}`（容错、手写、`---` fence、键 `name`/`description`/`version`/`keywords`/`when-to-use`）。
- 两者都：解析头部 `---` fenced YAML front-matter、返回（元数据 + front-matter 剥离后的正文）、缺失/无闭合 fence 时容错退化。对**驱动发现/列举的核心键 `name`/`description` 等价**。
- **已知差异**（task 1.x 断言、design 记录）：pig 把 `keywords`/`when-to-use` 归一成 `List`，原生 `getMetadata()` 回原始 `Map`；pig 在无 `description` 时从首行正文派生，原生留空。**这不影响 S1 正确性**——`NativeRepositorySkillSource` 用 `AgentSkill.getName()/getDescription()`（原生自解析），不经 pig 解析器；等价核对是为确认「两条读路径对同一 `SKILL.md` 给一致的 name/description」，故切换源不改 `listSkills` 所见。

## Decisions

### D1 — `NativeRepositorySkillSource implements SkillSource`（Adapter，藏在 seam 背后）
新增 `NativeRepositorySkillSource`（`pig-agent-tools`），持一个 `AgentSkillRepository`（core），`discover()` = `repo.getAllSkills()` → 逐个 `new NativeAgentSkill(agentSkill)`，收集成 `List<Skill>`。**容错**：整个 `discover()` try/catch，任何 `Throwable` → `warn` + 返回已收集部分（永不抛，坏源退化「无技能」，与 `SkillSource` 契约 + `WorkspaceSkillSource`/`ClasspathSkillSource` 一致）。默认构造包 `FileSystemSkillRepository(skillsDir)`；可注入任意 `AgentSkillRepository`（测试喂 fake / 未来喂 `ClasspathSkillRepository`）。`name()` = `"native:" + repo.getSource()`。理由：Adapter 把原生对象适配成 pig 抽象，**读栈其余部分（`SkillRegistry`/`SkillsTool`）零感知**；seam 复用既有 `SkillSource`，不建第二套读系统。

### D2 —「引擎不当嘴」红线（本 spec 硬约束）
`PigAgent` **继续** `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()`（`PigAgent.java:642-643,806-807` 两轨都不动）；**不调用** `.skillRepository(...)`/`enableSkillManageTool`/`enableSkillPromotionGate`/`enableSkillCurator`；**不注册**任何原生技能工具（`load_skill_through_path`/`read_file`/`grep`/`propose_skill`/`skill_manage`）。原生仓只经 `NativeRepositorySkillSource.discover()` 被**读取**，绝不经原生 middleware 注入 `<available_skills>`。理由：原生 prompt 注入会（a）破坏 pig 字节稳定系统 prompt（prefix-cache 命中率）——pig 自己装 `NativeMemoryContextMiddleware`/`UserProfileContextMiddleware` 已精心控制 prompt；（b）引入 pig 未注册的原生工具名，模型会幻觉调用。**原生当引擎、不当嘴**——只借其存取/解析/（未来）curator 逻辑，输出仍走 pig 的 `SkillsTool.listSkills`/`loadSkill`。

### D3 — `NativeAgentSkill implements Skill`（适配值对象，渐进加载语义一致）
`record`/final 类持一个 `AgentSkill`：
- `name()` = `agentSkill.getName()`（空白 → 由 `SkillRegistry` 过滤，与既有一致）。
- `content()` = `agentSkill.getSkillContent()`（front-matter 是否已剥离取决于原生仓；若未剥离则用 `MarkdownSkillParser.parse(...).getContent()` 剥离，与 `FileSkill` 语义对齐——task 1.x 核对）。
- `metadata()` = `new SkillMetadata(getName(), getDescription(), keywordsFrom(getMetadata()), versionFrom(getMetadata()))`；从 `getMetadata()` Map 提 `keywords`/`version`，缺省归空。**廉价**（不读 body，`AgentSkill` 已在内存）。
- `supportingFiles()` = `getResources()`（path→内容）逐条 → `SkillResource`（复用 composite-skill 的 `SkillResource`/`FileSkillResource` 形状；大小从内容字节算），受 `SkillLimits` 约束由 `SkillsTool`/`SupportingFilesRenderer` 既有逻辑裁剪。
理由：适配到既有值类型，`SkillsTool` 的 `formatListing`/`loadSkill`/`SupportingFilesRenderer` 无需改；渐进加载语义（metadata 廉价、content 惰性）与 `FileSkill` 一致，无模型面差异。

### D4 — 源优先级：原生源置于 pig 源之后（最低），保证「开启 = 行为等价」
`SkillRegistry` 按优先级去重（先见锁名，workspace-first-wins）。装配顺序：`[WorkspaceSkillSource, ClasspathSkillSource, NativeRepositorySkillSource]`。默认原生源 = `FileSystemSkillRepository(workspace/skills)`，与 `WorkspaceSkillSource` **同根**——两者发现同名技能，dedup 折叠为一条（pig 源先见胜出），`listSkills`/`loadSkill` 输出与今天**逐条等价**。理由：兑现 proposal「开了也只是多一个源、行为等价」的向后安全承诺；把原生源放最低，enabled 时永不 shadow 用户工作区/内置技能。（未来 S2/S3 或让原生源指向**不同**根 = git/DB 远程仓时，才产生新增技能——那是后续能力，届时另论优先级。）

### D5 — 暂存/归档点前缀隔离不破（`.pending`/`.archive` 不浮现）
`WorkspaceSkillSource` 现跳过点前缀目录（autonomous-skills 的 `.pending`/`.archive`）。原生 `FileSystemSkillRepository` 扫 `workspace/skills` 时是否也跳点前缀，**未知（task 1.x 核对）**；无论其行为如何，`NativeRepositorySkillSource` **必须防御性过滤点前缀名**（`skill.name().startsWith(".")` → 跳过 + `warn`），镜像 `WorkspaceSkillSource`。理由：autonomous-skills 的暂存不变量（草稿在 `.pending/<name>/`、`WorkspaceSkillSource` 天然不见、点前缀双保险）不能因新接一个原生源而被破坏——暂存草稿绝不能经原生源泄进 `listSkills`。

### D6 — 目录布局兼容核对
pig `workspace/skills/<name>/SKILL.md`（`WorkspaceManager.getSkillsDir()` = `rootPath.resolve("skills")`；`WorkspaceSkillSource` 扫直接子目录且要求其下有 `SKILL.md`）。原生 `FileSystemSkillRepository(<root>)` 按 skills.md 文档扫 `<root>/<name>/SKILL.md`（`AgentSkill.getOriginDir()` 佐证）——**同构**。task 1.x 可运行核对：把 `FileSystemSkillRepository(workspace/skills)` 指向 pig 布局，断言 `getAllSkillNames()` 集合 == `WorkspaceSkillSource.discover()` 名集（排除点前缀）。理由：布局不兼容会让原生源列不出/错列 pig 技能，须在编码前坐实。

### D7 — 配置 `skills.native`（默认关、向后兼容）
`SkillsConfig` 加 `native` 子块（`NativeSkillConfig`）：`enabled`（默认 **false**）、可选 `classpath-resource-dir`（非空时额外叠加一个 `ClasspathSkillRepository(dir)` 源）。默认关 → `AgentBootstrap` 不追加原生源，`SkillRegistry` 源列表逐字节等价今天。`@JsonIgnoreProperties(ignoreUnknown=true)` 容错沿用。理由：采纳一个新引擎须**保守默认关**（对齐 memory.search/deferred-tools/skills.autonomous 的 off-by-default 惯例）；`enabled=false` 是零行为变更的硬保证。

### D8 — 程序性记忆 vs SKILL.md 边界（与记忆线交叉，归属约定）
清晰三分，**归属互斥**：
- **`MEMORY.md`** = 声明性事实（"是什么"，如"用户在 X 公司"）。
- **`USER.md`** = 身份 + 长期偏好（"你是谁/怎么称呼/语言/风格"）。
- **`SKILL.md`** = 程序性「怎么做」——带 `when-to-use` + 结构化步骤的可复用工作流。
**约定**：从多次成功执行蒸馏出的「做法/流程」→ 落 `SKILL.md`（经 autonomous-skills 写路径 / 未来 S3 原生 promotion），**不塞 `MEMORY.md`**；`MEMORY.md` 只记事实，不记步骤。理由：技能引擎（本线）与记忆引擎（pa-memory-native/user-profile）职责不重叠，避免同一「知识」两处存、注入两次、维护漂移。S1 仅记录此约定；执行落在写路径（autonomous-skills 已在、S3 待接）。

### D9 — fail-closed 采纳方向（S3，仅标注）
S3 采纳原生 `SkillPromotionGate` 时，镜像权限引擎 non-interactive fail-closed：**channel/autonomous 轨 → `RejectAllGate`**（无 confirmer，永不自动晋级），**interactive 轨 → `LocalApprovalGate`**（人工门）。与 autonomous-skills 现有「渠道/自主只提案不提升」语义一致。S1 不接线，仅在此标注采纳方向，供 S3 spec 细化。

### D10 — 安全模型统一方向（S3，仅标注）
S3 采纳原生 `SkillSecurityScanner`（纯静态 `scan`/`scanSingleFile` + `shouldAllow(TrustLevel, Verdict)`）时，须与 pig 既有 `CredentialSanitizer` + `SkillSecurity`（路径/名字/尺寸）+ tool-sandbox **统一成一套判定**，不能跑两套（避免一处放行一处拒绝的不一致）。方向：以其中一个为权威、另一个降为补充，或组合成单一 `SkillContentScanner`（autonomous-skills 已有此 Strategy seam，可作汇聚点）。S1 不涉，仅标注。

### D11 — 依赖方向与新增依赖：S1 零新增第三方
`NativeRepositorySkillSource`/`NativeAgentSkill` 仅用 `agentscope-core`（`io.agentscope.core.skill.*`，`pig-agent-tools` 已依赖）+ 既有 `io.pigagent.tool.skills.*` + SLF4J。`pig-agent-config`/`pig-agent-cli` 无新增依赖。**harness 引擎（curator/promoter/gate）留 S2/S3**——彼时宿主模块须补 `agentscope-harness`（`pig-agent-tools` 现无），在 S2/S3 spec 处理。无环、S1 零新增第三方。

## Risks / Trade-offs

- **R1 — 原生仓解析/剥离语义与 pig 有别**：原生 `getSkillContent()` 是否已剥离 front-matter、`getMetadata()` 键集是否与 pig 一致，需 task 1.x 坐实；若原生不剥离，`NativeAgentSkill.content()` 用 `MarkdownSkillParser` 剥离对齐 `FileSkill`。→ 解析等价 spike（C）覆盖，差异记录在案。
- **R2 — 点前缀暂存泄漏**：原生 FS 仓若不跳 `.pending`/`.archive`，暂存草稿可能经原生源浮现。→ D5 防御性过滤（`NativeRepositorySkillSource` 自己跳点前缀），不依赖原生行为；测试断言 `.pending` 不出现在 `listSkills`。
- **R3 — 开启后重复列举**：原生源与 `WorkspaceSkillSource` 同根 → 同名。→ D4 靠 `SkillRegistry` 既有 dedup 折叠（pig 源先见胜出），测试断言 enabled 时 `listNames()` 与 disabled 时相等。
- **R4 — 承重 spike 不过 → 本线不进编码**：若原生 curator/gate/usage 无法脱离 prompt 注入当纯库驱动，则 S2/S3 采纳方向不成立。→ javap 已给签名级结论（通过），task 1.x 可运行验证坐实；不过则停下升级人工、S2/S3 改走自建（autonomous-skills 已有基础）。
- **R5 — 仅 core 仓离线可用**：git/DB/Nacos 远程仓（`agentscope-extensions-skill-*`）不在离线仓，无法测。→ S1 只用 core `FileSystemSkillRepository`/`ClasspathSkillRepository`；远程仓是未来能力，不在本线。
- **R6 — 采纳新引擎的长期耦合**：把原生仓接进读栈后，pig 读栈对原生 `AgentSkillRepository`/`AgentSkill` API 形成依赖。→ Adapter（D1/D3）隔离，`NativeAgentSkill` 是唯一耦合点；原生 API 变只需改适配器。默认关 + 行为等价 → 采纳可随时回退（关配置）。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 原生 `AgentSkillRepository`/`FileSystemSkillRepository`/`ClasspathSkillRepository` 当只读 `SkillSource` 采纳 | D1/D11；spike A；task 2.x/3.x | 已设计 |
| `AgentSkill` → pig `Skill` 容错适配（name/content/metadata/supportingFiles） | D3；task 3.x | 已设计 |
|「引擎不当嘴」红线：继续 `disableDynamicSkills()`/`disableDefaultWorkspaceSkills()`、不重开 `<available_skills>`、不注册原生技能工具、`SkillsTool` @Tool + 系统 prompt 字节稳定不变 | D2；task 4.x/5.x | 已设计 |
| 目录布局兼容 pig `workspace/skills/<name>/SKILL.md` | D6；spike；task 1.x | 已设计（可运行核对 task 1.x） |
| 暂存/归档点前缀（`.pending`/`.archive`）不浮现 | D5；task 3.x | 已设计 |
| 源优先级置于 pig 源之后 → 开启行为等价 | D4；task 4.x | 已设计 |
| config `skills.native.enabled` 默认关、零行为变更 | D7；task 2.x | 已设计 |
| **承重 spike**：curator/gate/usage 脱离 prompt 注入当纯库驱动（"只用引擎不用嘴"） | spike B；task 1.x | javap 通过（签名级）；可运行验证 task 1.x |
| **承重 spike**：pig `FrontMatterManifestParser` ↔ 原生 `MarkdownSkillParser` 解析等价 | spike C；task 1.x | javap 通过（签名级）；等价断言 task 1.x |
| S1 零新增第三方依赖、无环 | D11；task 2.x-4.x | 已设计 |
| 程序性记忆 vs SKILL.md 边界（三分归属约定） | D8；proposal | 已约定（记录，执行落写路径） |
| fail-closed 采纳方向（channel/autonomous→RejectAllGate、interactive→LocalApprovalGate） | D9 | 标注（延后 S3） |
| 安全模型统一（原生 `SkillSecurityScanner` ↔ pig `CredentialSanitizer`/tool-sandbox） | D10 | 标注（延后 S3） |
| curator/usage 老化归档采纳 | proposal 诚实局限；D8/D9 | 延后 S2 |
| promotion gate 分级晋级 + 安全扫描采纳 | D9/D10 | 延后 S3 |
| usage 计数须 pig 侧自喂（不当嘴的代价） | spike B caveat；proposal 诚实局限 | 延后 S2（记录） |
| 文档同步（CLAUDE.md builtin-skills 段落） | proposal Impact；task 6.x | 已设计 |
