# skill-curator-and-graded-promotion Specification

## Purpose
TBD - created by archiving change skill-curator-and-graded-promotion. Update Purpose after archive.
## Requirements
### Requirement: 技能使用分析（pig 侧自喂 usage）

系统 SHALL 在 `SkillsTool.loadSkill` **命中**路径把一次加载记为一次使用信号，喂给 AgentScope 2.0 原生 `SkillUsageStore`（`bumpUse`）——因 pig「不当嘴」（不启用原生 `SkillUsageMiddleware`/`SkillLoadTool`），使用信号 MUST 由 pig 侧自喂。记录 MUST 经一个 `SkillUsageRecorder` seam（默认实现 `noop()`），且 MUST **容错**——记录路径的任何异常 MUST 被吞掉且 MUST NOT 影响 `loadSkill` 的返回。因原生 `bumpUse` 只对**已存在**的 usage 记录生效，native-backed recorder 对**未注册**的技能名 MUST 安全 no-op（不抛、不新建记录、不误标手搓的内置/用户技能为 agent-created）。`SkillsTool` 的 `listSkills`/`loadSkill` `@Tool` 名、签名、返回语义（空列表、未知名、读错兜底、无支持文件）MUST 逐字不变。

#### Scenario: loadSkill 命中记录一次使用
- **WHEN** `skills.curator.enabled=true`，一个已有 usage 记录的技能被 `loadSkill(name)` 命中
- **THEN** 该技能的 usage 计数递增一次（`SkillUsageStore.bumpUse`），且 `loadSkill` 返回其正文 + 支持文件（返回语义与今天一致）

#### Scenario: 未注册技能名记录时安全 no-op
- **WHEN** `loadSkill` 命中一个**没有** usage 记录的技能（如手搓内置/用户技能）
- **THEN** recorder 不抛异常、不新建记录、不误标该技能，`loadSkill` 返回不受影响

#### Scenario: 默认关闭时零记录、零回归
- **WHEN** `skills.curator.enabled=false`（默认）
- **THEN** `SkillsTool` 使用 `SkillUsageRecorder.noop()`，`loadSkill`/`listSkills` 行为与引入本能力前逐字节一致，不产生任何 usage 写入

### Requirement: 技能 curator 老化归档采纳（默认只读）

系统 SHALL 把原生 `SkillCurator` **当纯库采纳**（`SkillCurator(AbstractFilesystem, SkillUsageStore, WorkspaceSkillRepository, SkillCuratorConfig)`，经 `LocalFilesystem(workspaceRoot)` + `RuntimeContext::empty` 离线驱动），做技能老化归档，并挂现有 `TaskScheduler` 定时（`schedule(id, TaskSchedule.cron(schedule), Runnable)`）。归档 MUST 落 `skills/.archive/`，与 pig `WorkspaceSkillSource`（点前缀目录跳过）及 `NativeRepositorySkillSource`（点前缀名过滤）兼容——归档后的技能 MUST NOT 再被 pig 读栈列为 active（`listSkills`/`loadSkill` 不再出现）。启用后 MUST 默认**只读**：`auto-archive=false` 时 curator MUST NOT 执行破坏性迁移（只产出 umbrella dry-run 报告 + 派生老化候选供展示）；仅当 `auto-archive=true`（opt-in）时才执行 stale→`.archive` 的真迁移。

#### Scenario: 默认只读——只产建议不归档
- **WHEN** `skills.curator.enabled=true` 且 `auto-archive=false`（默认），curator 定时或手动触发
- **THEN** curator 产出 umbrella dry-run 报告 + 老化候选清单，但 MUST NOT 移动任何技能（技能仍在 `skills/<name>/`，仍被 `listSkills` 列出）

#### Scenario: opt-in 真归档且归档不浮现
- **WHEN** `auto-archive=true` 且一个 agent-created 技能超过 `archive-after-days` 陈旧，curator 执行一次
- **THEN** 该技能被移入 `skills/.archive/<name>/`，随后 `WorkspaceSkillSource.discover()`/`NativeRepositorySkillSource.discover()`/`listSkills` MUST NOT 再列出它（点前缀跳过）

### Requirement: umbrella-merge 取代手搓 Jaccard 相似度告警

系统 SHALL 在 `skills.curator.enabled=true` 时用原生 curator 的 `umbrellaPassMode`（语义 umbrella-merge）取代 pig 手搓的 Jaccard 描述相似度告警（`SkillGate` 现有的词袋 Jaccard 近似）。当 curator 关闭（默认）时，`SkillGate` MUST 保留今日的 Jaccard 告警行为（零行为变更）。系统 MUST NOT 同时运行两套相似度判定。

#### Scenario: curator 开启时由 umbrella 取代 Jaccard
- **WHEN** `skills.curator.enabled=true`，提升/整理一个描述被更宽泛技能覆盖的近似技能
- **THEN** 相似度判定来自原生 `umbrellaPassMode`（语义 umbrella-merge），而非 pig 的词袋 Jaccard

#### Scenario: curator 关闭时保留今日 Jaccard
- **WHEN** `skills.curator.enabled=false`（默认）
- **THEN** `SkillGate` 的描述相似度告警行为与引入本能力前逐字节一致

### Requirement: 分级晋级门（SkillGate 委托原生 SkillPromotionGate）

系统 SHALL 让 `SkillGate.promote` 在既有内容安全扫描 + 去重之后、原子提升之前，把**准入决策**委托给一个注入的原生 `SkillPromotionGate`（经 `SkillPromotionReviewer` seam，默认实现等价今日 `/skill approve` 行为）。决策为 `Approve` 时 MUST 继续用 pig `SkillStagingArea` 原子提升（保 `.pending` 布局 + `SkillSecurity` 守卫，MUST NOT 换用原生 `SkillPromoter` 的双仓移动）；决策为 `Defer` 或 `Reject`（即**非 `Approve`**）时 MUST 拒绝提升并给出脱敏原因。fail-closed 判定 MUST 以「非 `Approve` 即拒」为准，MUST NOT 硬绑 `Reject` 子类型（原生 `RejectAllGate` 返回的是 `Defer` 而非 `Reject`，但同样永不 `Approve`）。

#### Scenario: interactive 轨映射 /skill approve 为 Approve
- **WHEN** operator 在交互 REPL 执行 `/skill approve <name>`（interactive 轨注入 `LocalApprovalGate`），且草稿通过扫描 + 去重
- **THEN** 原生 gate 返回 `Approve`，`SkillGate` 用 `SkillStagingArea` 原子提升该草稿，复用现有 HITL 确认循环、不弹二次提示

#### Scenario: 非 Approve 决策一律拒绝
- **WHEN** 注入的原生 gate 对某草稿返回 `Defer` 或 `Reject`
- **THEN** `SkillGate.promote` 拒绝提升，草稿留在暂存，`workspace/skills/<name>/` 未被创建，拒绝原因脱敏（不回显凭据）

### Requirement: fail-closed —— 渠道/自主 agent 永不自动晋级

系统 SHALL 保证渠道 / 自主数字员工 agent（无 confirmer）**永不自动晋级**技能，镜像原生权限引擎的 non-interactive fail-closed。渠道/自主轨 MUST 概念上映射到 `RejectAllGate`（其 `review` 返回 `Defer`，永不 `Approve`），且 MUST by-construction 无任何提升入口——`SkillGate` MUST NOT 被注入到渠道/自主轨（仅注入交互 REPL）。即使误注入一个 `RejectAllGate` 支持的 reviewer，其「非 `Approve` 即拒」判定 MUST 使草稿永不晋级。本要求 MUST NOT 削弱 `autonomous-skills` 既有的 fail-closed 契约。

#### Scenario: 渠道 agent 提案但从不晋级
- **WHEN** 渠道/自主 agent 调 `proposeSkill` 起草技能（无 operator 人工门介入）
- **THEN** 草稿仅进入 `.pending` 暂存，不存在任何调用序列让它进 `workspace/skills/<name>/` 或被 `WorkspaceSkillSource` 发现——渠道/自主轨无 `SkillGate`、无晋级路径

#### Scenario: RejectAllGate 的 Defer 被判为拒绝
- **WHEN** 一个 `RejectAllGate` 支持的 reviewer 对草稿 `review` 返回 `Defer`
- **THEN** fail-closed 判定为「非 `Approve`」→ 拒绝晋级（不因 `Defer` 非 `Reject` 而误放行）

### Requirement: 安全模型统一（原生 SkillSecurityScanner 汇入 pig 扫描）

系统 SHALL 在采纳原生 `SkillSecurityScanner`（纯静态 `scanSingleFile`/`scan` + `shouldAllow(TrustLevel, Verdict)`）时，把它**汇入** pig 既有的 `SkillContentScanner`（`DefaultSkillContentScanner`）成**单一判定**，MUST NOT 并行跑两套可能互相矛盾的判定。统一判定 MUST 组合 pig 既有四检（`SkillSecurity` 名/路径 + `SkillLimits` 尺寸 + `CredentialSanitizer` 凭据 + `SkillManifestParser` 结构）与原生 `Verdict`，任一判拒即拒；拒绝原因 MUST 脱敏（不回显命中的凭据值或敏感 finding 明文）。当 curator 关闭（默认）时，`DefaultSkillContentScanner` 行为 MUST 逐字不变。

#### Scenario: 原生判定 DANGEROUS 时拒绝
- **WHEN** `skills.curator.enabled=true`，一份草稿被原生 `SkillSecurityScanner` 判为 `DANGEROUS`
- **THEN** 统一扫描判拒，提升被拒，原因说明缺陷类别而不回显任何凭据/敏感明文

#### Scenario: 默认关闭时扫描逐字不变
- **WHEN** `skills.curator.enabled=false`（默认）
- **THEN** `DefaultSkillContentScanner` 的四检行为与引入本能力前逐字节一致（不并入原生扫描）

### Requirement: 可选灰度（CanaryFilter，默认关闭）

系统 MAY 提供对新晋级技能的灰度放量（`CanaryFilter`），默认**关闭**（`skills.curator.canary.enabled=false`）。因 pig「不当嘴」（无原生 `<available_skills>` 视图），灰度若启用 MUST 作用于 pig **读栈**（`SkillRegistry`/`listSkills`/`loadSkill` 可见集），复用原生 `SkillUsageStore` 的 canary 状态，MUST NOT 重开原生 prompt 视图过滤路径。默认关闭时读栈 MUST 与引入本能力前逐字节一致。

#### Scenario: 默认关闭时读栈不变
- **WHEN** `skills.curator.canary.enabled=false`（默认）
- **THEN** `SkillRegistry`/`listSkills` 的技能可见集不被灰度过滤，行为与今天一致

#### Scenario: 开启灰度按比例逐步可见
- **WHEN** `skills.curator.canary.enabled=true` 且 `percent=N`，一个新晋级的 agent-created 技能进入读栈
- **THEN** 该技能按 canary 比例逐步在会话可见（作用于 pig 读栈而非原生 prompt），复用 `SkillUsageStore` canary 状态

### Requirement: 命令 /skill curator run|status

系统 SHALL 提供 `/skill curator run|status` 子命令（`SkillCommand`）。`run` MUST 手动触发一次 curator（按 `auto-archive` 决定 dry-run 建议或真迁移）并打印本次运行摘要（checked/markedStale/archived/reactivated 或 dry-run 老化候选）。`status` MUST **只读**展示 usage 报告、老化候选、上次运行时间与生效配置，MUST NOT 产生任何迁移副作用。curator 未启用时两者 MUST 提示「未启用」而非静默。

#### Scenario: curator status 只读展示
- **WHEN** `skills.curator.enabled=true`，operator 执行 `/skill curator status`
- **THEN** 展示 usage 报告 + 老化候选 + 上次运行 + 配置，且不移动/归档任何技能（无副作用）

#### Scenario: 未启用时提示
- **WHEN** `skills.curator.enabled=false`（默认），operator 执行 `/skill curator run` 或 `status`
- **THEN** 命令提示 curator 未启用（`skills.curator.enabled=false`），不做任何 curator 动作

### Requirement: 能力可配且默认关闭（开启先只读）

系统 SHALL 以 `skills.curator` 配置块治理本能力：`enabled`（默认 **false**）、`usage-recording`（默认 true，仅 enabled 时生效）、`schedule`（cron）、`stale-after-days`/`archive-after-days`/`min-idle-hours`、`auto-archive`（默认 **false**）、`umbrella-pass-mode`（默认 `dry_run_only`）、`backup-retention`、`canary`（子块，默认关）。当 `enabled=false` 时，`AgentBootstrap` MUST NOT 装配任何 curator/usage/晋级门 seam（recorder=noop、无调度、`SkillGate` 用今日逻辑），系统行为 MUST 与引入本能力前逐字节一致。当 `enabled=true` 时 MUST 默认**先只读**：记录 usage + 产出建议，真晋级仍走现有人工门（`/skill approve`），真归档需 opt-in `auto-archive=true`。全部字段可选、null/缺块安全（getter 归默认、非法值 clamp）。

#### Scenario: 默认关闭时零行为变更
- **WHEN** `skills.curator.enabled=false`（默认）
- **THEN** 无 usage 记录、无 curator 调度、`SkillGate`/`DefaultSkillContentScanner`/读栈行为与今天逐字节一致；`/skill approve` 人工门不变

#### Scenario: 开启后先只读
- **WHEN** `skills.curator.enabled=true`（默认 `auto-archive=false`）
- **THEN** 系统记录 usage + 产出 curator 建议（umbrella dry-run + 老化候选），但不自动归档、不自动晋级；真晋级仍经 operator `/skill approve` 人工门

### Requirement: 「引擎不当嘴」红线延续（S1）

系统 SHALL 延续 S1 的红线：把原生 curator/usage/promotion 引擎只当**纯库**用于治理（存取 + 老化 + 准入决策），MUST NOT 重开原生 prompt 注入路径。具体：`PigAgent` 构建 MUST 继续 `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()`；MUST NOT 调用 `enableSkillCurator`/`enableSkillPromotionGate`/`enableSkillManageTool`/`.skillRepository(...)`；MUST NOT 安装原生技能 middleware 或注册原生技能工具（`load_skill_through_path`/`read_file`/`grep`/`propose_skill`/`skill_manage`）。curator/gate/usage 的输出 MUST 仍经 pig 的 `SkillsTool`/`SkillGate`/`/skill` 暴露；系统 prompt 的字节稳定性 MUST NOT 被本能力破坏。

#### Scenario: 采纳治理引擎但不注入 prompt
- **WHEN** `skills.curator.enabled=true`，curator/usage/晋级门 seam 全部装配
- **THEN** 系统 prompt 中不出现 `<available_skills>` 块，不注册任何原生技能工具，`PigAgent` 仍 `disableDynamicSkills()`；技能仅经 `SkillsTool.listSkills`/`loadSkill` 与 `/skill` 暴露

