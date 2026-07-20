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

- [x] 5.1 `SkillPromotionReviewer` seam（`approve(name,desc,skillMd)→boolean`；默认 `alwaysApprove()` = 今日 `/skill approve`）+ `NativeSkillPromotionReviewer`（包原生 `SkillPromotionGate`，构 `SkillCandidate`（native `SkillSecurityScanner` + `newAgentDraft`），`review` → **仅 `Approve` 返 true，`Defer`/`Reject`/异常返 false**，fail-closed）。`SkillGate` 加 6 参构造多接 reviewer + `umbrellaMergeEnabled`（默认构造委托 `alwaysApprove(),false`）；`promote` 在 scan+dedup 后、原子提升前调 `reviewer.approve(...)`——true→`SkillStagingArea.promote`，false→新状态 `REJECTED_GATE` + 脱敏原因。`SkillGateGradedGateTest`（3/3）：alwaysApprove→今日 PROMOTED；reject reviewer→`REJECTED_GATE`、草稿留 `.pending`、未安装；reviewer 仅在 scan+dedup 后被调。**绿** ✅
- [x] 5.2 安全模型统一（D8）：`DefaultSkillContentScanner` 加 `nativeScanEnabled` 构造，curator 启用时并入 native `SkillSecurityScanner.scanSingleFile` + `shouldAllow(AGENT_CREATED, verdict)`（DANGEROUS→拒），reason 仅报 verdict 类别（不回显 secret/命令）；默认关逐字不变。`DefaultSkillContentScannerNativeTest`（3/3）：native 关→危险体仍过（pig 四检不看命令语义）、native 开→`curl|bash` 体拒（reason 含「native security scan」）、native 开→干净体过。**绿** ✅
- [x] 5.3 `umbrellaPassMode` 取代 Jaccard：`SkillGate` 的 `umbrellaMergeEnabled=true`（curator 开）时 `similarityWarnings`（Jaccard）被抑制（下沉给 curator umbrella）；默认 false 保留今日 Jaccard 告警。既有 `SkillGateTest`（9/9）保持绿（默认路径 Jaccard 不变）。**绿** ✅
- [x] 5.4 `AgentBootstrap` 按轨注入：**interactive** → `curatorOn` 时 `NativeSkillPromotionReviewer(interactiveApprovalGate())`（`LocalApprovalGate` + approve-prompter 反映 operator `/skill approve`，不弹二次 stdin）+ `DefaultSkillContentScanner(nativeScanEnabled=true)` + `SkillGate(umbrellaMergeEnabled=true)`；**channel/autonomous** → 仍**不注入 `SkillGate`**（by-construction）。默认（curator 关）→ `alwaysApprove()` + native scan off + Jaccard 保留 → 今日 `/skill approve` 逐字不变。`mvn -pl pig-agent-cli -am compile` **绿** ✅

## 6. 命令 + fail-closed 守卫（`pig-agent-cli`）

- [x] 6.1 `SkillCommand` 加子命 `curator run|status`（curator 分支先于 gate==null 守卫，独立处理）：`run`→`SkillCuratorService.runOnce()`、`status`→`status()`，打印 `CuratorRunSummary.describe()`；curator 未启用（`skillCuratorService==null`）→提示「not enabled」。`SkillCuratorService` 经 `ReplContext`（新末位 record 组件，nullable）← `AgentRepl`（新增 full 构造 + 旧签名 backward-compat 构造，18 处测试调用点零改）← `PigAgentCli`。`SkillCommandTest`（6/6）：未启用→提示、启用→只读 status 行。**绿** ✅
- [x] 6.2 **渠道态永不晋级守卫**（安全相关，锁死）：`ChannelPromotionFailClosedTest`（2/2，`pig-agent-tools`）——`NativeSkillPromotionReviewer(new RejectAllGate()).approve(...)` == false（Defer 非 Approve）；`SkillGate` 注入该 reviewer → `promote` 返 `REJECTED_GATE`、草稿留 `.pending`、`workspace/skills/<name>/` 未创建。锁 gate-mapping 半（by-construction 半 = AgentBootstrap 只把 `SkillGate` 给 REPL，channel/autonomous 无 SkillGate，wiring 不变量）。守卫 `autonomous-skills` fail-closed 契约在 S3 不被削弱。**绿** ✅

## 7. 收尾

- [x] 7.1 新增测试全绿：`SkillCuratorSpikeTest`(3) + `SkillUsageRecorderTest`(4) + `SkillsToolUsageTest`(3) + `SkillCuratorServiceTest`(3) + `SkillGateGradedGateTest`(3) + `DefaultSkillContentScannerNativeTest`(3) + `CuratorConfigTest`(4) + `SkillCommandTest`(6, 含 curator) + `ChannelPromotionFailClosedTest`(2)；既有 `SkillsToolTest`(6)/`SkillGateTest`(9)/`AgentCommandSubTest`(6) 保持绿（模型面/读栈零回归）。**`mvn -pl pig-agent-tools -am test` 全绿：424 tests, 0 failures**。
- [x] 7.2 `mvn -pl pig-agent-cli -am compile` **绿**。（`mvn verify` 全量覆盖率门交合并后统一跑，不在本轮——coordinator 指示「别等全量套件」。）
- [x] 7.3 更新 `CLAUDE.md`：builtin-skills 段落追加 S3 段（usage 自喂/curator 老化归档/分级晋级门当纯库采纳、仍 `disableDynamicSkills()` 不当嘴、`skills.curator` 默认关先只读、R-Spike-S3-1 可写仓、fail-closed 映射、D8 安全统一、D12 边界）；config 段加 `skills.curator`。
- [x] 7.4 分组提交（spike / 依赖+配置 / usage seam / curator+调度 / 分级门+安全统一 / 命令+守卫 / 收尾）——conventional commits，7 提交。
- [ ] 7.5 归档：同步主 spec → `openspec/specs/skill-curator-and-graded-promotion/`，change 移 `openspec/changes/archive/<date>-skill-curator-and-graded-promotion/`（**人工确认门，不在本轮**）。
