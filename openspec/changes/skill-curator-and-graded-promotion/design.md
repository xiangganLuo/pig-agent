## Context

S1（`native-skill-engine-bridge`，已归档 `2026-07-20`）把原生技能引擎的**存取 + 解析**当库采纳到 pig `SkillSource` seam 背后：`NativeRepositorySkillSource`（包 `FileSystemSkillRepository`）、`NativeAgentSkill`，仍 `disableDynamicSkills()`/`disableDefaultWorkspaceSkills()` 不当嘴，`skills.native.enabled` 默认关。S1 的承重 spike（`NativeSkillEngineSpikeTest`，`pig-agent-core`，3/3 绿）已可运行证实自学习闭环三件套可脱离原生 prompt/middleware 当纯库驱动，并留两点精化：**R-Spike-1**（`RejectAllGate.review` 返回 `Defer` 非 `Reject`，但**永不 Approve**）、**R-Spike-2**（native `FileSystemSkillRepository` 更严格、要求 front-matter，且不浮现 `.pending`）。

pig 现有技能写路径（`autonomous-skills`，`io.pigagent.tool.skills.authoring`）：`proposeSkill`→`.pending` 暂存→人工门 `/skill approve`→`SkillGate.promote`（scan + dedup + `SkillStagingArea` 原子提升）。其**晋级门是手搓的**：`SkillGate.similarityWarnings`/`jaccard`（`:139-164`）用词袋 Jaccard 近似描述相似度给非阻断告警；之后技能**只增不汰**——无使用分析、无老化归档、无分级准入。

本 spec 是 **Wave-2 的 S3**：在 S1 引擎采纳基座上补齐「新陈代谢」——usage / curator / 分级晋级门，全走 pig 既有 seam（`SkillsTool`/`SkillGate`/`SkillContentScanner`/`SkillRegistry`），面向设计模式（Adapter + Strategy/seam + registry），默认关、开启先只读。设计来源：`docs/design/personal-assistant-core-design.md` §7 技能自学习闭环；S1 design 的 D8/D9/D10（程序性记忆边界 / fail-closed 采纳方向 / 安全模型统一），本 spec 逐条落地。

## Goals / Non-Goals

**Goals:**
- **使用分析**：pig 侧（`loadSkill` 命中）自喂 usage 到原生 `SkillUsageStore`（"不当嘴"→自喂），经 mockable `SkillUsageRecorder` seam，容错、默认 no-op。
- **curator 采纳**：原生 `SkillCurator` 当纯库驱动——老化 stale→`.archive`（与 pig 点前缀跳过兼容）、`umbrellaPassMode` 语义 umbrella-merge **取代** `SkillGate` 手搓 Jaccard；挂现有 `TaskScheduler` 定时；默认**只读**（dry-run + 建议），`auto-archive` opt-in 才真归档。
- **分级晋级门**：`SkillGate.promote` 委托原生 `SkillPromotionGate.review(...)` 决策——**channel/autonomous → `RejectAllGate`（永不 Approve）**、**interactive → `LocalApprovalGate`（映射 `/skill approve`、复用 HITL）**，可选 `CanaryFilter` 灰度（默认关）。
- **安全模型统一**：原生 `SkillSecurityScanner` 汇入 pig `DefaultSkillContentScanner`（单一判定，不跑两套；凭据脱敏）。
- **命令**：`/skill curator run|status`（只读 status 永不误删）。
- **可配默认关 + 开启先只读**：`skills.curator.enabled` 默认 false → 逐字节等价今天；开启 = 记录 usage + 建议，真晋级仍走现有人工门、真归档 opt-in。
- 面向设计模式：Adapter（`NativeSkillUsageRecorder`/`NativeSkillPromotionReviewer`/`SkillCuratorService` 把原生 harness 对象适配成 pig seam）+ Strategy/seam（`SkillUsageRecorder`/`SkillContentScanner`/`SkillPromotionReviewer`，mockable）+ 复用既有 registry（`SkillRegistry`）+ 值对象（`CuratorRunSummary`）。

**Non-Goals:**
- 不重开原生动态技能 prompt（`DynamicSkillMiddleware`/`AgentSkillPromptProvider`/`HarnessSkillMiddleware`/`SkillLoadTool`）——**S1 红线延续**；不注册原生技能工具，不 `enableSkillCurator`/`enableSkillPromotionGate`。
- 不换用原生 `SkillPromoter`（其自带 staging/active `WorkspaceSkillRepository` + 自己的原子移动，会打乱 pig `.pending` 布局 + `SkillSecurity` 守卫）——只委托原生 **gate**（决策），保 pig `SkillStagingArea` 提升（D7）。
- 不改 `autonomous-skills` 的 `@Tool` 名/签名/fail-closed 契约、`.pending` 暂存不变量；不改 `native-skill-engine-bridge` 读栈契约。
- 不引入 git/DB/Nacos 远程技能仓、不引入技能热重载/marketplace 物化。
- 不做 S2（skill matching / 检索 ranker）——正交、后续。
- CanaryFilter 的**深度灰度**（多环境编排、逐步 ramp-up 自动化）不做；本 spec 只留默认关的读栈 seam + 单一 canary %。

## 承重 Spike 结论（javap 签名级 + 待 task 1.x 可运行坐实）

> jar = 离线仓 `agentscope-harness-2.0.0.jar` + `agentscope-core-2.0.0.jar`（`D:\env\apache-maven-3.9.10\repository\io\agentscope`）。判据：（1）usage 可由 pig 侧自喂；（2）curator 归档目录 = `.archive`，与 pig 点前缀跳过兼容且归档不再浮现为 active；（3）fail-closed 映射 1:1（非交互轨永不 Approve、交互轨复用 HITL）。S1 spike B 已证三件套可脱离 prompt 当纯库驱动（本 spec 复用该结论），本组坐实 **S3 特有的接入点**。

### Spike-1 — usage 可由 pig 侧（`loadSkill`）自喂 ✅（javap；task 1.x 可运行坐实）
- javap `SkillUsageStore(AbstractFilesystem)`：`load(): Map<String,SkillUsageRecord>`、`save(map)`、`get(name): Optional<SkillUsageRecord>`、`bumpView/bumpUse/bumpPatch(name)`、`markAgentDraft(name, agent)`、`markAgentCreated(name, agent, List<env>)`、`setState/setPinned/forget`、`agentCreatedReport()`。构造仅需 `AbstractFilesystem`——离线地板 = `LocalFilesystem(Path)`（S1 spike 已用）。
- **关键语义（S1 spike 已发现，本 spec 依赖）**：`bumpUse` 只增**已存在**记录；未注册的技能须先 `markAgentDraft`/`markAgentCreated`（`SkillUsageRecord.newAgentDraft(name)`）。→ pig 接入方案：（a）技能经 `SkillGate.promote` 提升成功后 `markAgentCreated(name, "agent", envs)`，让**提升出来的**技能有 usage record；（b）`loadSkill(name)` 命中 → `recorder.record(name)` → `NativeSkillUsageRecorder` 若 `get(name)` 存在则 `bumpUse`，不存在则**安全 no-op**（不误标手搓内置/用户技能为 agent-created）。故老化 scope = **agent-created（自学习闭环）技能**，与自学习闭环设计一致（native 有 `agentCreatedReport()`/`AbstractAgentCreatedFilter`/`isAgentCreated()` 佐证）。
- **诚实点**：native usage 本由 `SkillUsageMiddleware`/`SkillLoadTool` 在推理环喂；pig 不当嘴 → 自喂。task 1.x 坐实：构 `LocalFilesystem(temp)` + `SkillUsageStore`，`markAgentCreated`→`bumpUse`→`get().useCount()` 递增；对未注册名 `bumpUse` no-op 安全（不抛、不新建）。

### Spike-2 — curator 归档目录 = `.archive`，与 pig 点前缀跳过兼容、归档不再浮现 ✅（javap + 文档；task 1.x 可运行坐实）
- javap `SkillCurator(AbstractFilesystem, SkillUsageStore, WorkspaceSkillRepository, SkillCuratorConfig)`：`runOnce(Instant): CuratorRunReport`、`applyAutomaticTransitions(Instant): TransitionCounts`、`runUmbrellaDryRunReport(Instant): String`、`shouldRunNow(Instant): boolean`、`loadState/saveState`、`isPaused/setPaused`。`CuratorRunReport{TransitionCounts transitions(), String dryRunReportPath(), Instant ranAt(), long durationMs()}`；`TransitionCounts{int checked, markedStale, archived, reactivated}`。
- 官方 skill 文档（`skill.md` self-learning §）：`archiveAfterDays` → 「移入 `skills/.archive/`」。pig `WorkspaceSkillSource.tryAdd` 跳过点前缀目录（`:71-73`，`.pending`/`.archive`），`NativeRepositorySkillSource.discover` 跳过点前缀名（S1 D5）→ **归档技能天然不浮现为 active**。S1 spike R-Spike-2 已证 native 仓不浮现 `.pending`；task 1.x 补证 `.archive` 同理。
- **读只/apply 分级（D4 的地板）**：`runUmbrellaDryRunReport` 非破坏（只出报告路径）；`runOnce`/`applyAutomaticTransitions` **会真迁移**（含 stale→archive）。→ 「只读模式」= `auto-archive=false` 时**不**调 `runOnce`/`applyAutomaticTransitions`，只 `runUmbrellaDryRunReport` + 读 `SkillUsageStore` 记录派生老化候选（`latestActivityAt()` vs `staleAfterDays`），`/skill curator status` 展示「would archive」；`auto-archive=true`（opt-in）才 `runOnce` 真归档。task 1.x 坐实 dry-run 非破坏、`runOnce` 归档进 `.archive` 且不再被 pig 读栈列出。

### Spike-3 — fail-closed 映射 1:1 ✅（javap + S1 R-Spike-1；task 1.x 守卫坐实）
- javap `SkillPromotionGate.review(SkillCandidate, RuntimeContext): Mono<PromotionDecision>`；`PromotionDecision` 密封三态：`Approve(reviewerId, List<env>, Instant)`、`Defer(Duration retryAfter, String reason)`、`Reject(String reason, String reviewerId)`。实现：`RejectAllGate()`/`(Duration)`；`LocalApprovalGate()`/`(Duration)`/`(Duration, Prompter, List<env>)` + `defaultPrompter()`/`stdinPrompter(in,out)`；`NotifyAndWaitGate(...)`。
- **映射（S1 D9 落地）**：
  - **channel/autonomous → `RejectAllGate`**：其 `review` 返回 **`Defer`**（reason=「promotion requires explicit HarnessAgent.promoteSkill call by an authorized caller」，S1 R-Spike-1）——**永不 Approve**。fail-closed 判定 MUST 按「**非 `Approve`**」而非硬绑 `Reject` 子类型（含 `Defer`）。且 pig 侧 by-construction 加固：channel/autonomous agent **根本不持 `SkillGate`**（AgentBootstrap 只注入 REPL），无任何提升入口。
  - **interactive → `LocalApprovalGate`**：其 `review` 走 `Prompter`（`Function<SkillCandidate, CompletableFuture<PromotionDecision>>`）。pig 的 `/skill approve <name>` **本身就是** operator 的显式授权决定 → 注入一个「反映 `/skill approve` 动作」的 prompter（该命令语境下直接产出 `Approve`），复用现有 HITL/operator 确认循环，**不**弹第二次 stdin 提示。
- task 1.x 守卫（安全相关）：`RejectAllGate.review(candidate, ctx).block()` **非 `Approve`**（含 `Defer`）；`LocalApprovalGate` 用 approve-prompter → `Approve`、用 reject-prompter → 非 Approve；**渠道态永不晋级守卫**（cli 测）：channel/autonomous 装配下无 `SkillGate`、`proposeSkill` 后草稿永不进 `workspace/skills/<name>/`。

## Decisions

### D1 — 模块归属：`pig-agent-tools` 补 `agentscope-harness`，S3 适配器落 `io.pigagent.tool.skills.curator`
S1 design D11 已预告「curator/promoter/gate 留 S2/S3，彼时宿主模块须补 `agentscope-harness`（`pig-agent-tools` 现无直接声明）」。依赖链 tools→core→harness（core 的 harness 为 compile 作用域，已传递可用），本 spec 把它转为 `pig-agent-tools/pom.xml` 的**直接声明**（Maven 卫生：直接用即直接声明）。S3 适配器（`SkillUsageRecorder`+`NativeSkillUsageRecorder`、`SkillCuratorService`、`NativeSkillPromotionReviewer`、`CuratorRunSummary`）落 `io.pigagent.tool.skills.curator`，与既有 `SkillsTool`/`SkillGate`/`NativeRepositorySkillSource` 内聚同模块（无环）。理由：技能治理逻辑集中在技能模块，避免跨模块 seam；`SkillsTool.loadSkill`/`SkillGate.promote` 可直接调 seam，无需绕到 cli。

### D2 — 使用分析：`SkillUsageRecorder` seam + `loadSkill` 自喂（容错、默认 no-op）
新增 `SkillUsageRecorder`（tools，接口，`record(String skillName)`；静态 `noop()`）。`SkillsTool` 构造多接一个 `SkillUsageRecorder`（默认 `noop()` → 今日行为）；`loadSkill` 在**命中**（`registry.find` 非空）后调 `recorder.record(name)`，**try/catch 吞异常**（usage 记录永不影响工具返回，与既有 `safeMetadata`/`safeSupportingFiles` 容错一致）。`NativeSkillUsageRecorder`（tools）包 `SkillUsageStore`（`LocalFilesystem(workspaceRoot)`）：`get(name)` 存在 → `bumpUse`（+ `save`），不存在 → no-op（不误标）。理由：Strategy/seam（mockable，镜像 `Summarizer`/`ProfileDistiller`/`Embedder`）；默认 no-op = 向后安全；容错 = 一个坏的 usage store 绝不拖垮 `loadSkill`。

### D3 — curator 采纳：`SkillCuratorService` 包原生 `SkillCurator`，挂 `TaskScheduler`
`SkillCuratorService`（tools）持 `LocalFilesystem(workspaceRoot)` + `SkillUsageStore` + `WorkspaceSkillRepository(fs, "skills", ctxSupplier)` + `SkillCuratorConfig`（由 pig config 映射：`intervalHours`/`minIdleHours`/`staleAfterDays`/`archiveAfterDays`/`umbrellaPassMode`/`backupRetention`）。暴露 `runOnce(): CuratorRunSummary`（按 `auto-archive` 决定调 `runUmbrellaDryRunReport`+派生候选 vs `runOnce(Instant)` 真迁移）+ `status(): CuratorRunSummary`（只读）。`AgentBootstrap` 在 enabled 时 `taskScheduler.schedule("skill:curator", TaskSchedule.cron(schedule), service::runScheduled)`（镜像 `user-profile:consolidation` / `outreach:briefing`）。`ctxSupplier` = `RuntimeContext::empty`（S1 spike 已证足够）。理由：Adapter 把原生 curator 藏在 pig service 背后；复用既有 `TaskScheduler`，不引第二套调度。

### D4 — 默认关 + 开启先只读（读只/apply 分级）
`skills.curator.enabled` 默认 **false** → 不装任何 seam（recorder=noop、无 curator schedule、`SkillGate` 用今日逻辑），逐字节等价今天。开启后默认 `auto-archive=false` + `umbrella-pass-mode=dry_run_only`：只记录 usage + 出 umbrella dry-run 报告 + 派生老化候选（**非破坏**），`/skill curator status` 展示「would archive」建议；`auto-archive=true`（opt-in）才 `runOnce` 真 stale→`.archive`。理由：采纳新引擎必**保守默认关**（对齐 memory.search/skills.autonomous/deferred-tools 的 off-by-default 惯例）；「先只读」= 让运营先观察 curator 建议再放开真归档，符合任务「开了先只读记录 + 建议，晋级仍走现有人工门」。

### D5 — `umbrellaPassMode` 语义 umbrella-merge 取代手搓 Jaccard
`SkillGate.similarityWarnings`/`jaccard`（`:139-164`，词袋 Jaccard 描述相似度告警）在 `skills.curator.enabled=true` 时**下沉**给 curator 的 `umbrellaPassMode`（`DISABLED`/`DRY_RUN_ONLY`/`LIVE`，语义 umbrella-merge——把描述被更宽泛技能覆盖的近似技能建议合并）。curator 关（默认）时 `SkillGate` 保留今日 Jaccard 告警（零行为变更）。理由：Jaccard 是 pig 手搓近似（词袋、非语义、易误报），原生 umbrella-merge 是更强的语义汰换；「取代」而非「并存」，避免两套相似度判定漂移；默认关时不动今日行为。

### D6 — 分级晋级门：`SkillGate.promote` 委托原生 `SkillPromotionGate`（决策 seam）
新增 `SkillPromotionReviewer` seam（tools，`review(SkillCandidate|pig 等价, RuntimeContext): Decision`；默认 `alwaysApprove()` = 今日 `/skill approve` 行为）+ `NativeSkillPromotionReviewer` 包一个原生 `SkillPromotionGate`。`SkillGate.promote` 在既有 scan + dedup 后、原子提升前，调 `reviewer.review(...)`——`Approve`→继续 `SkillStagingArea.promote`（保 pig 布局），`Defer`/`Reject`→`PromotionResult` 拒绝（新状态或复用 `REJECTED_SCAN`/`ERROR` 语义 + 原因）。AgentBootstrap 按轨注入：interactive → `NativeSkillPromotionReviewer(LocalApprovalGate(approve-prompter))`；channel/autonomous **无 `SkillGate`**（by-construction）。fail-closed 判定 = 「非 `Approve` 即拒」（含 `Defer`，S1 R-Spike-1）。理由：把准入决策**统一到原生 gate 抽象**（S1 D9 落地），交互轨复用现有 HITL、不弹二次提示；不换 native `SkillPromoter`（D7）保 pig `.pending` 布局。默认（curator 关）→ reviewer 未注入 → 今日 `/skill approve` 行为不变。

### D7 — 不换原生 `SkillPromoter`，保 pig `SkillStagingArea` 提升
原生 `SkillPromoter(WorkspaceSkillRepository staging, WorkspaceSkillRepository active, WorkspaceManager, SkillUsageStore, SkillPromotionGate, env, reviewerId[, SkillAuditLog])` 自带 staging/active 双仓 + 自己的原子移动 + 内部调 gate。采纳它会**打乱 pig `.pending` 布局**（pig 用 `skills/.pending/<name>/`，`SkillStagingArea` 原子 move + POSIX 0600 + `SkillSecurity` 守卫）并绕过 pig 既有 scan/dedup。故本 spec 只委托原生 **gate**（决策），提升仍走 `SkillStagingArea.promote`。理由：最小侵入、保既有暂存不变量与安全守卫；native `SkillPromoter` 记录为「考虑过但延后」的更重方案（若未来采纳原生双仓再论）。

### D8 — 安全模型统一：原生 `SkillSecurityScanner` 汇入 pig `DefaultSkillContentScanner`
S1 D10 落地。`DefaultSkillContentScanner`（既有 Strategy seam）在 `skills.curator.enabled=true` 时**并入** native `SkillSecurityScanner.scanSingleFile(name, content): ScanResult{Verdict SAFE/CAUTION/DANGEROUS, findings, reportText}` + `shouldAllow(TrustLevel, Verdict)`：pig 既有四检（`SkillSecurity` 名/路径 + `SkillLimits` 尺寸 + `CredentialSanitizer` 凭据 + `SkillManifestParser` 结构）**AND** native `Verdict`（`AGENT_CREATED` 信任级下 `DANGEROUS` 拒、`CAUTION` 视策略）——**任一拒即拒**，组合成单一判定，不跑两套。凭据/findings 原因脱敏（不回显命中值）。理由：避免「一处放行一处拒绝」的不一致；以 `SkillContentScanner` seam 作汇聚点（autonomous-skills 已有此抽象）。默认关时 `DefaultSkillContentScanner` 逐字不变。

### D9 — 可选 `CanaryFilter`（灰度，默认关，作用于 pig 读栈而非原生 prompt）
原生 `CanaryFilter(percent[, rampUpDays], SkillUsageStore)` / `EnvironmentFilter(env, SkillUsageStore)` 是 `SkillVisibilityFilter.filter(List<AgentSkill>, RuntimeContext): List<AgentSkill>`——本为原生 `<available_skills>` 视图过滤。pig 不当嘴（无原生 prompt 视图）→ 若启用灰度，须在 pig **读栈**（`NativeRepositorySkillSource`/`SkillRegistry` 输出、即 `listSkills`/`loadSkill` 可见集）应用 filter，复用 `SkillUsageStore` canary 状态（把 pig `Skill` 名映射回 native `AgentSkill` 或按名过滤）。`skills.curator.canary.enabled` 默认 **false** → 读栈不变。理由：canary 让新提升的 agent-created 技能**逐步放量**（先对部分会话可见）；但与「不当嘴」有张力（原生本作用于 prompt 视图）→ 默认关、设 seam、诚实标注作用点是 pig 读栈。深度多环境灰度 = Non-Goal。

### D10 — 命令 `/skill curator run|status`（只读 status 永不误删）
`SkillCommand` 加子命：`curator run` → `SkillCuratorService.runOnce()`（按 `auto-archive` dry-run 或 apply），打印 `CuratorRunSummary`（checked/markedStale/archived/reactivated 或「would archive」候选）；`curator status` → 只读展示 usage 报告（`agentCreatedReport()`）+ 老化候选 + 上次运行 + 生效配置。curator 未启用时提示「未启用（skills.curator.enabled=false）」。理由：operator 面运营入口（对齐 `/skill review|approve|reject` 风格）；`status` 只读、`run` 显式触发——不自动误删。

### D11 — config `skills.curator` 块（默认全关/安全）
`SkillsConfig` 加 `curator`（`CuratorConfig`）：`enabled`（默认 **false**）、`usage-recording`（默认 true，仅 enabled 时生效）、`schedule`（cron，默认周级如 `0 3 * * 0`）、`stale-after-days`（30）、`archive-after-days`（90）、`min-idle-hours`（2）、`auto-archive`（默认 **false**）、`umbrella-pass-mode`（默认 `dry_run_only`）、`backup-retention`（3）、`canary`（子块：`enabled` 默认 false、`percent` 10、`ramp-up-days` 0）。全部可选、null/缺块安全（getter 归默认）。`ConfigurationManager` 全局未知字段容错沿用（`DeserializationProblemHandler`，非 per-class 注解——与 S1 `NativeSkillConfig` 一致）。理由：保守默认；映射到 `SkillCuratorConfig.builder()` 的字段一一对应，pig 侧 clamp 非法值。

### D12 — 程序性记忆 vs SKILL.md 边界（S1 D8 承接）
S1 D8 约定三分归属：`MEMORY.md`=声明性事实、`USER.md`=身份/偏好、`SKILL.md`=程序性「怎么做」。本 spec 的 **usage 是「哪些 SKILL.md 真有效」的信号**（procedural memory 的**有效性/新陈代谢**），归技能线；与记忆线（pa-memory-native 的 `MEMORY.md` 事实、user-profile 的 `USER.md`）**正交**——usage 不写入 `MEMORY.md`，`MEMORY.md` 不记技能使用计数。理由：技能引擎（本线）与记忆引擎职责不重叠，避免同一知识两处存、维护漂移；与记忆线 M-C/M-D（程序性记忆）在「做法归 SKILL.md、事实归 MEMORY」这条边界上一致。

## Risks / Trade-offs

- **R1 — `bumpUse` 需先注册 record**：native `bumpUse` 只增已存在记录（S1 spike 结论）。→ D2：提升时 `markAgentCreated`，`loadSkill` 未知名 no-op 安全；spike-1 可运行坐实（含未注册名 no-op 不抛）。老化 scope 明确为 agent-created 技能（诚实局限）。
- **R2 — 归档技能泄漏/重现**：curator 归档 → `.archive`，若 pig 读栈误列则技能「删而复现」。→ D3 + S1 点前缀跳过（`WorkspaceSkillSource:71-73` / `NativeRepositorySkillSource` D5）；spike-2 断言 `runOnce` 后归档技能不出现在 `listSkills`。
- **R3 — fail-closed 被破坏（渠道晋级）**：若渠道/自主轨误持 `SkillGate` 或 reviewer 误判 `Defer` 为通过 → 权限逃逸。→ D6 判定「非 Approve 即拒」（含 `Defer`）+ by-construction 无 `SkillGate`；spike-3 守卫 + cli「渠道态永不晋级」测试（安全相关，锁死）。
- **R4 — curator 依赖地板（`LocalFilesystem`/`RuntimeContext`）**：需构造 harness `AbstractFilesystem` + `Supplier<RuntimeContext>`。→ S1 spike B 已证 `LocalFilesystem(temp)` + `RuntimeContext::empty` 足够；本 spec 复用（workspace 根 = pig `WorkspaceManager.getRootPath()`）。
- **R5 — CanaryFilter 与「不当嘴」张力**：原生 filter 本作用于 prompt 视图，pig 无。→ D9：默认关、作用于 pig 读栈、诚实标注；深度灰度 Non-Goal。
- **R6 — 真实质量需 live/长期验证**：usage 信号「真有效」、umbrella 语义合并质量、分级运营价值离线测不了。→ 诚实局限；离线只锁确定性逻辑（自喂/no-op、`.archive` 不浮现、fail-closed、dry-run 非破坏、gate 委托、scanner 融合、config 默认）；真实效果 `/ls:itest` / 长期观察。
- **R7 — 承重 spike 不过 → 不进编码**：若 usage 无法自喂、或归档目录不兼容、或 fail-closed 映射不成立 → 停下升级人工。→ javap 已给签名级结论（通过），task 1.x 坐实。
- **R8 — 长期耦合 harness API**：接 `SkillUsageStore`/`SkillCurator`/`SkillPromotionGate`/`SkillSecurityScanner` 后 pig 依赖这些 harness API。→ Adapter（D1/D2/D3/D6/D8）隔离，`Native*` 适配器是唯一耦合点；默认关可随时回退（关配置）。
- **R9 — `SkillGate` 双职责膨胀**：`SkillGate` 现已 scan+dedup+promote，再加 native-gate 委托 + scanner 融合有膨胀风险。→ 决策/扫描均经 seam（`SkillPromotionReviewer`/`SkillContentScanner`）外置，`SkillGate` 只多两个注入点（保 <800 行、方法 <50 行的仓库规约）。

## 交叉依赖（本 spec 显式承接）

- **建在 S1（`native-skill-engine-bridge`，已合并）之上**：复用 `NativeRepositorySkillSource` 的引擎采纳基座（`LocalFilesystem`/`RuntimeContext` 地板、点前缀过滤、Adapter 模式）；继续 `disableDynamicSkills()`/`disableDefaultWorkspaceSkills()` **不当嘴红线**（S1 D2）。S1 的 D9/D10（fail-closed 采纳方向 / 安全模型统一）本 spec 逐条落地为 D6/D8。
- **fail-closed 一致性**：镜像原生权限引擎 non-interactive fail-closed（`tool-permissions`：channel/autonomous → `DONT_ASK` + ASK→DENY）——晋级门 channel/autonomous → `RejectAllGate`（永不 Approve），interactive → `LocalApprovalGate` **复用同一 HITL 确认循环**（`RequireUserConfirmEvent`/`/skill approve` 的 operator 决定）。
- **安全模型统一**：原生 `SkillSecurityScanner` ↔ pig `CredentialSanitizer` + `SkillSecurity` + tool-sandbox 采纳时**统一成一套判定**（D8），不出两套（一处放行一处拒绝）。
- **与 S2（skill matching，后续）正交**：S2 走 R0 共享 ranker 做**检索**（列出/匹配哪个技能），S3 走 usage/curator 做**汰换/准入**（淘汰陈旧、灰度放量）——一个「选出」一个「淘汰」，互不冲突；共用 `SkillRegistry`/`SkillSource` 读栈但作用面不同。
- **程序性记忆 vs SKILL.md 边界（记忆线 M-C/M-D）**：做法归 SKILL.md；S3 的 usage 是「哪些技能有效」的信号（D12），归技能线、不写 `MEMORY.md`。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 使用分析：`loadSkill` 自喂 usage（`SkillUsageRecorder` seam，容错、默认 no-op） | D2；spike-1；task 1.x/3.x | 已设计 |
| `bumpUse` 只增已存在记录 → 提升时 `markAgentCreated`、未知名 no-op 安全 | D2；spike-1；R1 | javap 通过（签名级）；可运行 task 1.x |
| curator 采纳（`SkillCurator` 纯库驱动、挂 `TaskScheduler`） | D3；spike-2；task 3.x/4.x | 已设计（S1 spike B 已证可当纯库） |
| 老化归档 stale→`.archive`，与 pig 点前缀跳过兼容、归档不浮现 | D3；spike-2；R2 | javap+文档通过；可运行 task 1.x |
| 默认关 + 开启先只读（`auto-archive`/`umbrella-pass-mode` dry-run） | D4；D11；task 2.x/4.x | 已设计 |
| `umbrellaPassMode` 语义 umbrella-merge 取代手搓 Jaccard（`SkillGate:139-164`） | D5；task 4.x | 已设计 |
| 分级晋级门：`SkillGate.promote` 委托原生 `SkillPromotionGate` | D6；spike-3；task 5.x | 已设计 |
| fail-closed 映射：channel/autonomous→`RejectAllGate`（永不 Approve，含 `Defer`）、interactive→`LocalApprovalGate`（复用 HITL） | D6；spike-3；R3；task 1.x/5.x/6.x | javap+S1 R-Spike-1 通过；守卫 task 1.x/6.x |
| 不换原生 `SkillPromoter`，保 pig `SkillStagingArea` 提升 | D7；Non-Goals | 已设计 |
| 安全模型统一：原生 `SkillSecurityScanner` 汇入 pig `DefaultSkillContentScanner` | D8；task 5.x | 已设计（S1 D10 落地） |
| 可选 `CanaryFilter` 灰度（默认关，作用 pig 读栈） | D9；R5；task 4.x | 已设计（默认关、诚实标注） |
| 命令 `/skill curator run\|status`（只读 status 永不误删） | D10；task 6.x | 已设计 |
| config `skills.curator` 块默认全关/安全 | D11；task 2.x | 已设计 |
| 程序性记忆 vs SKILL.md 边界（usage=有效性信号，不写 MEMORY） | D12；proposal；交叉依赖 | 已约定 |
| 模块归属：`pig-agent-tools` 补 `agentscope-harness`（S1 D11 兑现） | D1；task 2.x | 已设计 |
| **承重 spike**：usage 自喂 + `.archive` 兼容 + fail-closed 映射 | spike-1/2/3；task 1.x | javap 通过（签名级）；可运行坐实 task 1.x |
| 文档同步（CLAUDE.md builtin-skills 段落） | proposal Impact；task 7.x | 已设计 |
| 深度多环境灰度 / 采纳原生双仓 `SkillPromoter` | Non-Goals；D7/D9 | 延后（记录） |
| S2 skill matching（检索 ranker） | 交叉依赖 | 延后（正交） |
