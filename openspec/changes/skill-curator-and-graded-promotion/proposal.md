## Why

S1（`native-skill-engine-bridge`，已合并）把 AgentScope 2.0 原生技能引擎的**存取 + 解析**当「库」采纳到 pig 的 `SkillSource` seam 背后（`NativeRepositorySkillSource` 包 `FileSystemSkillRepository`，仍 `disableDynamicSkills()` 不当嘴），并用**承重 spike**（`NativeSkillEngineSpikeTest`，3/3 绿）javap + 可运行证实：原生**自学习闭环三件套**——`SkillUsageStore` / `SkillCurator` / `SkillPromotionGate`（`RejectAllGate`/`LocalApprovalGate`）——能**脱离原生 prompt/middleware，当纯库单独驱动**（无 Model、无 `<available_skills>`、无原生技能工具）。S1 有意把 curator/usage/promotion **留给后续分期**（design D9/D10/D11 明确标注了采纳方向 + 「S2/S3 宿主模块须补 `agentscope-harness`」）。

pig 今天的技能「新陈代谢」是**最大 GAP**：autonomous-skills 只有「提案→暂存→人工门→提升」的**写入**路径，之后技能**只增不汰**——没有使用分析（哪些技能真被用）、没有老化归档（陈旧技能永久堆积、污染 `listSkills`）、晋级门是 pig 手搓的 Jaccard 描述相似度告警（`SkillGate.java:139-164`，词袋近似、非语义），也没有分级（灰度/环境）的准入策略。北极星（记忆：pig-agent-positioning）是「harness 外包给 2.0，pig 只留差异化」——继续手搓技能治理与之相悖。

**本能力（Wave-2 的 S3）**在 S1 铺好的引擎采纳基座上，补齐这条「新陈代谢」链：接原生 `SkillUsageStore`（使用信号）、`SkillCurator`（老化归档 + `umbrellaPassMode` 语义 umbrella-merge，取代手搓 Jaccard）、`SkillPromotionGate`（**分级晋级门**：channel/autonomous→`RejectAllGate` 永不 Approve、interactive→`LocalApprovalGate` 映射现有 `/skill approve` 人工门，中间可选 `CanaryFilter` 灰度），统一到 pig 既有的 `SkillsTool`/`SkillGate`/`SkillContentScanner` seam 上，并挂现有 `TaskScheduler` 定时。默认 **关**（`skills.curator.enabled=false`）；开启也**先只读**——只记录 usage + 产出建议（umbrella dry-run + 老化候选），**真晋级仍走现有人工门、真归档需 opt-in**——向后安全、零模型面回归。

> **分期编号对齐**：S1 的 design 曾以「S2/S3」称呼 curator/promotion（更早的分期草案）；Wave-2 内核路线图**重新划期**——S2 = skill matching（检索，走 R0 共享 ranker，后续），S3 = 本 spec（curator + 分级晋级门，走 usage/汰换）。二者正交（一个管「检索出哪个技能」，一个管「淘汰/准入哪个技能」）。本 proposal 一律用 Wave-2 编号。

## What Changes

- **`pig-agent-tools`（补 `agentscope-harness` 依赖 + 新增 `io.pigagent.tool.skills.curator`）：** S1 design D11 已预告「curator/promoter/gate 留 S2/S3，彼时宿主模块须补 `agentscope-harness`」。`pig-agent-tools` 依赖 `pig-agent-core`（其 `agentscope-harness` 为 compile 作用域 → 已传递可用），本 spec **显式声明**该依赖（Maven 卫生），把 S3 适配器与既有技能栈内聚同模块（无环：tools→core→harness）。
  - **使用分析 seam**：`SkillUsageRecorder`（tools，纯 seam，默认 `noop()`；镜像 `CompressionService.Summarizer`/`ProfileDistiller`/`Embedder` 的 mockable 种子）。`SkillsTool.loadSkill` 命中路径调 `recorder.record(name)`（**容错**，永不影响工具返回）。native-backed `NativeSkillUsageRecorder` 包 `SkillUsageStore`（`LocalFilesystem(workspace)`），把一次 `loadSkill` 记为一次 usage（`bumpUse`；因原生 `bumpUse` 只增**已存在**记录——S1 spike 结论——recorder 先确保 record 存在：技能经 `SkillGate` 提升时 `markAgentCreated`，`loadSkill` 未知名则 no-op 安全）。
  - **curator 采纳**：`SkillCuratorService`（tools）包原生 `SkillCurator(LocalFilesystem, SkillUsageStore, WorkspaceSkillRepository, SkillCuratorConfig)`——老化 stale→**`.archive`**（与 pig `WorkspaceSkillSource` 点前缀跳过 `:71-73`、`NativeRepositorySkillSource` 点前缀过滤 一致，归档技能不再浮现为 active），`umbrellaPassMode` 做语义 umbrella-merge，**取代** `SkillGate` 手搓 Jaccard 告警（`:139-164`）。
  - **分级晋级门**：`SkillGate.promote` 在既有 scan+dedup 后，把**准入决策**委托给注入的原生 `SkillPromotionGate.review(candidate, ctx)`——`Approve`→原子提升（仍用 pig `SkillStagingArea.promote`，保 `.pending` 布局 + `SkillSecurity` 守卫），`Defer`/`Reject`→拒绝。interactive 轨注入 `LocalApprovalGate`（approval = operator 跑 `/skill approve` = 显式授权调用，复用现有 HITL 确认循环）；channel/autonomous 轨 **by-construction 无 `SkillGate`**（AgentBootstrap 只把它注入 REPL），概念上 = `RejectAllGate`（永不 Approve）。可选 `CanaryFilter`（灰度）默认关。
  - **安全模型统一**（S1 D10 落地）：原生 `SkillSecurityScanner`（纯静态 `scan`/`scanSingleFile` + `shouldAllow(TrustLevel, Verdict)`）**汇入** pig 既有 `DefaultSkillContentScanner`（既有 Strategy seam），组合成**单一判定**（pig `CredentialSanitizer`/`SkillSecurity`/`SkillLimits` + native `Verdict`），不跑两套；凭据原因脱敏不回显。
- **`pig-agent-cli`：**
  - `AgentBootstrap` 在 `skills.curator.enabled=true` 时装配上述 seam 的 native-backed 实现，并把 curator 挂现有 `TaskScheduler`（`schedule(id, TaskSchedule.cron(cron), Runnable)`，镜像 user-profile consolidation / outreach briefing 的定时模式）；默认关 → 一个 seam 都不装（`SkillsTool` 用 no-op recorder，`SkillGate` 用今天的 pig scan+dedup+Jaccard），逐字节等价今天。
  - `SkillCommand` 加子命 `/skill curator run|status`：`run` 手动触发一次 curator（按 `auto-archive` 决定 dry-run 或 apply），`status` 展示 usage 报告 + 老化候选 + 上次运行 + 配置（**只读**，永不误删）。
- **`pig-agent-config`**：`SkillsConfig` 加 `curator` 子块（`CuratorConfig`：`enabled` 默认 **false**、`usage-recording`、`schedule`、`stale-after-days`/`archive-after-days`/`min-idle-hours`、`auto-archive` 默认 **false**、`umbrella-pass-mode` 默认 `dry_run_only`、`canary` 子块默认关）。`ConfigurationManager` 全局未知字段容错沿用。
- **`PigAgent` 不改**：继续 `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()`（S1 红线延续），不启用任何原生技能 middleware/工具/`enableSkillCurator`/`enableSkillPromotionGate`。原生 curator/gate/usage-store 全经 pig seam 当**纯库**驱动，输出仍走 pig 的 `SkillsTool`/`SkillGate`/`/skill`。系统 prompt 字节稳定性不变。

## Capabilities

### New Capabilities
- `skill-curator-and-graded-promotion`: 在 S1 引擎采纳基座上补齐技能「新陈代谢」——把 AgentScope 2.0 原生 `SkillUsageStore`（使用分析）/ `SkillCurator`（老化归档 + `umbrellaPassMode` 语义 umbrella-merge，取代手搓 Jaccard）/ `SkillPromotionGate`（分级晋级门）**当纯库采纳**，藏在 pig 既有 `SkillsTool`/`SkillGate`/`SkillContentScanner` seam 背后。使用信号由 pig 侧（`loadSkill`）**自喂**（不当嘴的代价）；curator 挂现有 `TaskScheduler` 定时；**分级晋级门** fail-closed 映射——**channel/autonomous → `RejectAllGate`（永不 Approve，含 S1 精化的 `Defer` 语义）**、**interactive → `LocalApprovalGate`（映射现有 `/skill approve` 人工门、复用同一 HITL 确认循环）**、可选 `CanaryFilter` 灰度；`SkillGate.promote` 内部委托原生 gate；原生 `SkillSecurityScanner` 与 pig `CredentialSanitizer`/tool-sandbox **统一成一套判定**。新增 `/skill curator run|status`。config `skills.curator.enabled` 默认 **false** → 零行为变更；开启也**先只读**（记录 usage + 建议，真晋级仍走现有人工门、真归档 opt-in `auto-archive`）。继续 `disableDynamicSkills()` 不当嘴（S1 红线延续）。

### Modified Capabilities
- 无（`autonomous-skills` 的 `@Tool` 面、fail-closed 契约、`.pending` 暂存不变；本能力**叠加**在其提升路径之后，不改其既有 SHALL）。`native-skill-engine-bridge` 的读栈契约不变（本能力复用其引擎采纳基座）。

## Impact

- **依赖**：`pig-agent-tools` 显式声明 `agentscope-harness`（compile；S1 D11 已预告，传递已可用，本 spec 转直接声明）。无新增第三方（harness 已在离线仓）。无环。
- **代码（`pig-agent-tools`）**：新增 `skills/curator/{SkillUsageRecorder, NativeSkillUsageRecorder, SkillCuratorService, NativeSkillPromotionReviewer（封装 SkillPromotionGate）, CuratorRunSummary}`；`SkillsTool` 加一个 fault-tolerant recorder 调用点（`@Tool` 面不变）；`SkillGate.promote` 加一个可选 native-gate 委托 + `SkillContentScanner` 融合 native scanner；`DefaultSkillContentScanner` 汇入 native `SkillSecurityScanner`。
- **代码（`pig-agent-cli`）**：`AgentBootstrap` 在 enabled 时装配 seam + 挂 `TaskScheduler`；`SkillCommand` 加 `curator run|status`。默认关不改行为。
- **代码（`pig-agent-config`）**：`SkillsConfig` 加 `curator` 子块。
- **不改**：`PigAgent`（继续 disable 原生动态技能）、系统 prompt 装配、`ToolAvailabilityGate`、原生 `PermissionEngine`、返回契约 + 分发守卫、`autonomous-skills` 的写工具/暂存/fail-closed 契约、`native-skill-engine-bridge` 读栈契约。
- **测试**：`pig-agent-tools` 新增承重 spike（`SkillCuratorSpikeTest`：usage 自喂 + `.archive` 兼容 + fail-closed 映射，跑真 harness、离线）+ `SkillUsageRecorderTest`/`SkillCuratorServiceTest`/`SkillGateGradedGateTest`（fake gate/store，离线）；`pig-agent-config` 扩 `CuratorConfigTest`；`pig-agent-cli` 扩（seam 装配 on/off + `/skill curator` + **渠道态永不晋级守卫**）。离线，无真模型。
- **文档**：`CLAUDE.md` builtin-skills 段落补一段（技能新陈代谢：usage/curator/分级晋级门当纯库采纳、仍不当嘴、默认关先只读、S1 基座之上）。

## 诚实局限

- **仅结构性验证**：真实**使用信号质量**（哪些技能「真有效」）、**语义 umbrella-merge 合并质量**、**分级晋级的运营价值**属 live-model / 长期运行验证；离线只覆盖确定性逻辑——usage 自喂/no-op 安全、`.archive` 归档不浮现、fail-closed 映射（渠道永不晋级）、curator dry-run 非破坏、native gate 决策委托、scanner 融合、config 默认。真实老化归档效果建议 `/ls:itest` 或长期观察。
- **usage 自喂是「不当嘴」的代价**：原生 `bumpView`/`bumpUse` 本由原生 `SkillUsageMiddleware`/`SkillLoadTool` 在推理环里喂；pig 不当嘴 → 由 `loadSkill` 自喂（承重 spike #1 坐实可行 + `bumpUse` 先注册 record 的语义）。scope = 主要老化 **agent-created**（经 `SkillGate` 提升）技能的自学习闭环，而非手搓的内置/用户技能（后者无 usage record，`bumpUse` no-op 安全）。
- **CanaryFilter 与「不当嘴」的张力**：原生 `CanaryFilter`/`EnvironmentFilter` 是 `SkillVisibilityFilter`（本为原生 `<available_skills>` 视图过滤）；pig 不当嘴 → 若启用灰度须在 pig **读栈**（`discover`/`listSkills` 的技能列表）应用 filter、复用 `SkillUsageStore` canary 状态。故 canary **默认关**、设 seam、诚实标注（灰度作用于 pig 读栈而非原生 prompt）。
- **spike 是承重门**：本 spec 是否进编码取决于 tasks 第 1 组（usage 自喂 + `.archive` 兼容 + fail-closed 映射）；S1 spike B 已给签名级 + 可运行结论（curator/gate/usage 可当纯库驱动），本组坐实 S3 特有的接入点。不过则停下升级人工。
