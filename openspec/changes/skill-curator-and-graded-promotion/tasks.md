# Tasks — skill-curator-and-graded-promotion

> 内环 TDD：每个可测单元先写测试（RED）→ 实现（GREEN）→ `mvn test` → 勾选。分组后 `mvn -q -pl pig-agent-cli -am compile`。
> **第 1 组是承重 spike（安全相关），门整条 S3**：不过则停下升级人工、本 spec 不进编码（design.md「承重 Spike 结论」已给 javap 签名级判据；S1 spike B 已证三件套可当纯库驱动，本组坐实 S3 特有的接入点：usage 自喂 / `.archive` 兼容 / fail-closed 映射）。

## 1. 承重 spike（门整条 S3，安全相关）—— `pig-agent-core`（该模块已依赖 harness，spike 先于 tools 补依赖，真正"门在前"）

- [x] 1.1 **usage 自喂 spike**（"不当嘴 → 自喂"）：`SkillCuratorSpikeTest.usageSelfFed_bumpUseIncrementsExisting_unknownIsNoOp`——`LocalFilesystem(@TempDir)` + `SkillUsageStore`，断言：（a）`markAgentCreated(name,"agent",envs)` → `bumpUse(name)`×2 → `get(name).useCount()` ≥2；（b）对**未注册**名直接 `bumpUse` **no-op 安全**（不抛、`get` 仍空、无幻影 record）。**绿** ✅ 全程无 Model/Agent/原生 middleware。
- [x] 1.2 **`.archive` 归档兼容 spike**：`SkillCuratorSpikeTest.curatorArchive_landsInDotArchive_notSurfacedByNativeRepo`——temp `workspace/skills/<name>/SKILL.md`（含 front-matter）+ `markAgentCreated`，构**可写** `WorkspaceSkillRepository(fs,"skills",ctx,"workspace",true)`（R-Spike-S3-1：只读仓 delete 被静默忽略）+ `SkillCurator`；断言 `runUmbrellaDryRunReport(now)` **非破坏**（技能仍 active），`runOnce(now+200d)` 把陈旧 agent-created 技能移入 `skills/.archive/`，且随后 `FileSystemSkillRepository(skills).getAllSkillNames()`（pig S1 默认原生源）**不列出**它。**绿** ✅ 坐实「归档 = `.archive`、与 pig 点前缀/直接子目录扫描不冲突、归档不复现为 active」。
- [x] 1.3 **fail-closed 映射 spike**（安全相关）：`SkillCuratorSpikeTest.failClosedMapping_rejectAllNeverApproves_localApprovalCanApprove`——`RejectAllGate().review(candidate, RuntimeContext.empty()).block()` **非 `PromotionDecision.Approve`**（返回 `Defer`，S1 R-Spike-1）；`LocalApprovalGate(Duration, approve-prompter, envs).review(...)` → `Approve`、reject-prompter → 非 `Approve`。**绿** ✅ 坐实「非交互轨永不 Approve、交互轨可授权 Approve」1:1 映射。
- [x] 1.4 spike 汇总：三项结论 + 精化 **R-Spike-S3-1（archive 需可写仓）** 写回 design.md「承重 Spike 结论」。**GREEN → 进入第 2 组编码。**

## 2. 依赖 + 配置（`pig-agent-tools` / `pig-agent-config`）

- [ ] 2.1 `pig-agent-tools/pom.xml` 显式声明 `agentscope-harness`（compile，兑现 S1 design D11）；`mvn -q -pl pig-agent-tools -am compile` 绿，确认无环（tools→core→harness）。
- [ ] 2.2 `PigAgentConfig.SkillsConfig` 加 `curator` 子块（`CuratorConfig`：`enabled` 默认 false、`usage-recording` 默认 true、`schedule` 默认 `0 3 * * 0`、`stale-after-days` 30、`archive-after-days` 90、`min-idle-hours` 2、`auto-archive` 默认 false、`umbrella-pass-mode` 默认 `dry_run_only`、`backup-retention` 3、`canary` 子块 `enabled` false/`percent` 10/`ramp-up-days` 0）+ 访问器（null/缺块安全、非法值 clamp）。`CuratorConfigTest`：默认值（`enabled=false`/`auto-archive=false`/`umbrella-pass-mode=dry_run_only`）+ 解析 + null setter 容错 + canary 默认关。

## 3. 使用分析 seam（`pig-agent-tools`，`io.pigagent.tool.skills.curator` + `SkillsTool`）

- [x] 3.1 `SkillUsageRecorder`（接口，`record(String)` + 默认 `markCreated(String)`；静态 `noop()`）+ `NativeSkillUsageRecorder`（包 `SkillUsageStore(LocalFilesystem(workspaceRoot))`：`record`→`bumpUse`（native provenance-gated：已注册 agent-created 才增，未注册 no-op）、`markCreated`→`markAgentCreated`；全 try/catch 吞异常）。`SkillUsageRecorderTest`（4/4）：no-op 不抛；native markCreated→record 递增、未注册名 no-op（无幻影 record）、mock store 抛异常时吞掉。**绿** ✅
- [x] 3.2 `SkillsTool` 加 3 参构造多接 `SkillUsageRecorder`（默认 `noop()`，既有构造委托 noop）；`loadSkill` 命中后调 `recordUsage(name)`（try/catch 容错，永不影响返回）。`SkillsToolTest`（6/6）保持绿（`@Tool` 面逐字不变）；`SkillsToolUsageTest`（3/3）断言命中记录一次、未命中不记录、recorder 抛异常不影响返回。**绿** ✅

## 4. curator 采纳 + 调度（`pig-agent-tools` / `pig-agent-cli`）

- [x] 4.1 `CuratorRunSummary`（record：dryRun/checked/markedStale/archived/reactivated/trackedCount/staleCandidates/dryRunReportPath/ranAt + `describe()`）。`SkillCuratorService`（持 `SkillUsageStore`+`SkillCurator`+`autoArchive`；`forWorkspace(root, config, autoArchive)` 建**可写** `WorkspaceSkillRepository`）：`runOnce()`（`auto-archive=false`→`runUmbrellaDryRunReport`+读 usage 派生老化候选，非破坏；`true`→`SkillCurator.runOnce(now)` 真迁移）、`status()`（只读）、`runScheduled()`（fire-and-forget + 日志）。`SkillCuratorServiceTest`（3/3，real store + temp fs）：dry-run 非破坏、apply 归档进 `.archive`、status 只读。**绿** ✅
- [x] 4.2 `CanaryFilter` 灰度——**采纳文档 off-ramp（tasks 4.2 备选）**：config `skills.curator.canary` 子块（默认关）即 seam；深度读栈过滤（把 native `CanaryFilter`/`SkillUsageStore` canary 状态套到 pig `SkillRegistry` 输出）**延后深度实现**，不阻塞主线（design D9 已诚实标注：与「不当嘴」张力、作用于 pig 读栈）。默认关 → 读栈逐字节不变。
- [x] 4.3 `AgentBootstrap`：`skills.curator.enabled` 时构 `SkillCuratorService.forWorkspace(workspace.getRootPath(), buildCuratorConfig(cfg), autoArchive)` 并 `taskScheduler.schedule("skill:curator", TaskSchedule.cron(schedule), ::runScheduled)`；默认关不装、不调度。`skillsToolWithNative(dir, nativeCfg, recorder)` 注入 recorder（`usageRecordingOn`→`NativeSkillUsageRecorder(workspaceRoot)`，else `noop()`）；override 条件 = native 源 OR usage-recording（默认路径也能注入）。`buildCuratorConfig` 映射 pig config→native `SkillCuratorConfig`（umbrella 字符串→枚举，未知→`DRY_RUN_ONLY`）。`SkillCuratorService` 挂 `Services`（nullable）。`mvn -pl pig-agent-cli -am compile` **绿** ✅

## 5. 分级晋级门 + 安全模型统一（`pig-agent-tools` / `pig-agent-cli`）

- [ ] 5.1 `SkillPromotionReviewer` seam（`review(...)→Decision`；默认 `alwaysApprove()` = 今日 `/skill approve`）+ `NativeSkillPromotionReviewer`（包原生 `SkillPromotionGate`，把 pig 暂存草稿构造成 `SkillCandidate`，`review` 返回 → pig `Decision`；**非 `Approve` 即拒**，含 `Defer`）。`SkillGate` 构造多接 reviewer（默认 `alwaysApprove()`）；`promote` 在 scan+dedup 后、原子提升前调 `reviewer.review(...)`——Approve→`SkillStagingArea.promote`，非 Approve→`PromotionResult` 拒绝 + 脱敏原因。`SkillGateGradedGateTest`（fake reviewer）：alwaysApprove→今日行为；reject/defer reviewer→拒绝、草稿留暂存、原子提升未发生。
- [ ] 5.2 安全模型统一：`DefaultSkillContentScanner` 在 curator 启用时并入 native `SkillSecurityScanner.scanSingleFile`（pig 四检 AND native `Verdict`，任一拒即拒，findings 脱敏）；默认关时逐字不变。`DefaultSkillContentScannerTest` 扩：native `DANGEROUS`→拒、`SAFE`→沿用 pig 判定、凭据原因不回显。
- [ ] 5.3 `umbrellaPassMode` 取代 Jaccard：curator 启用时 `SkillGate.similarityWarnings`/`jaccard`（`:139-164`）下沉给 curator umbrella；curator 关（默认）时保留今日 Jaccard 告警。`SkillGateTest` 扩：默认关→今日 Jaccard 告警；curator 开→告警来自 umbrella（或移除 Jaccard、由 `/skill curator status` 出建议）。
- [ ] 5.4 `AgentBootstrap` 按轨注入 reviewer：**interactive** → `NativeSkillPromotionReviewer(LocalApprovalGate(approve-prompter))`（复用现有 `/skill approve` HITL）；**channel/autonomous** → **不注入 `SkillGate`**（by-construction，AgentBootstrap 只把 `SkillGate` 给 `ReplContext`）。默认（curator 关）→ reviewer 未注入 → 今日 `/skill approve` 行为不变。

## 6. 命令 + fail-closed 守卫（`pig-agent-cli`）

- [ ] 6.1 `SkillCommand` 加子命 `curator run|status`：`run`→`SkillCuratorService.runOnce()` 打印 `CuratorRunSummary`；`status`→只读 usage 报告+候选+上次运行+配置；curator 未启用→提示。`SkillCommandTest`：`curator status` 只读（无迁移副作用）、未启用提示、`curator run` 调 service。
- [ ] 6.2 **渠道态永不晋级守卫**（安全相关，锁死）：`ChannelPromotionFailClosedTest`（cli/core）——channel/autonomous 装配下**无 `SkillGate`**、agent 只有 `proposeSkill`/`skillManage`（stage-only），断言不存在任何调用序列让草稿进 `workspace/skills/<name>/` 或被 `WorkspaceSkillSource` 发现；即使误注入 `RejectAllGate` reviewer，`review` 返回 `Defer` → 判定「非 Approve」→ 拒。守卫 `autonomous-skills` 的 fail-closed 契约在 S3 下不被削弱。

## 7. 收尾

- [ ] 7.1 新增测试全绿：`SkillCuratorSpikeTest`(3+) + `SkillUsageRecorderTest` + `SkillCuratorServiceTest`(+Canary) + `SkillGateGradedGateTest` + `DefaultSkillContentScannerTest`(扩) + `SkillGateTest`(扩) + `CuratorConfigTest` + `SkillCommandTest`(扩) + `ChannelPromotionFailClosedTest`；既有 `SkillsToolTest`/`SkillGate`/`NativeRepositorySkillSourceTest` 保持绿（模型面/读栈零回归）。
- [ ] 7.2 `mvn -pl pig-agent-cli -am compile` 绿；`mvn verify` 覆盖率地板不回退。
- [ ] 7.3 更新 `CLAUDE.md` builtin-skills 段落（技能新陈代谢：usage/curator/分级晋级门当纯库采纳、仍 `disableDynamicSkills()` 不当嘴、`skills.curator` 默认关先只读、S1 基座之上、fail-closed 映射）。
- [ ] 7.4 提交（分组提交：spike / 依赖+配置 / usage seam / curator+调度 / 分级门+安全统一 / 命令+守卫）。
- [ ] 7.5 归档：同步主 spec → `openspec/specs/skill-curator-and-graded-promotion/`，change 移 `openspec/changes/archive/<date>-skill-curator-and-graded-promotion/`（**人工确认门，不在本轮**）。
