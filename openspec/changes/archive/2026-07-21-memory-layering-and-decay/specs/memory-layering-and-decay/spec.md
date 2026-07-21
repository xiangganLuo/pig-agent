## ADDED Requirements

### Requirement: 事实分层归类（durable → Pinned/General、episodic → Recent，确定性、键入 M-D 分层契约）

系统 SHALL 把固化层 `MEMORY.md` 的每条事实单元**确定性归类**到分层格式契约（`MemoryLayerFormat`）的层级：身份/长期偏好等 durable 事实 SHALL 归入 `Pinned`/`General`，一次性/时效性 episodic 事实 SHALL 归入 `Recent`。归类 MUST 为**纯逻辑、确定性**（无模型调用），MUST 经 M-D 的分层格式契约读写。系统 MUST **向后兼容**旧的无分层 `MEMORY.md`——缺少分层标记时 MUST 视为单层并在整理时重新归类，MUST NOT 报错。层级 SHALL 在每次整理时由事实内容**重新推导**，以对原生 consolidation 的扁平化/改写保持健壮。

#### Scenario: 身份/长期事实归入 durable 层
- **WHEN** 一条事实携带身份/长期偏好信号（如姓名、称呼、长期偏好）
- **THEN** 系统将其确定性归入 `Pinned`/`General` 层

#### Scenario: 情节/时效事实归入 Recent 层
- **WHEN** 一条事实携带情节/时效信号（如「昨天/临时/这次」或一次性 ID/端口）
- **THEN** 系统将其确定性归入 `Recent` 层

#### Scenario: 旧无分层 MEMORY.md 向后兼容
- **WHEN** 读取一个不含分层标记的旧 `MEMORY.md`
- **THEN** 系统将其视为单层、在整理时重新归类各事实，不报错

#### Scenario: 层级每趟重新推导（对原生改写健壮）
- **WHEN** 原生 consolidation 把分层扁平化或改写了措辞后再整理
- **THEN** 系统依据事实内容重新推导层级、重建分层，不依赖原生保留 pig 写入的标记

### Requirement: recency/访问信号与衰减降级（陈旧降级/归档、复用强化保级）

系统 SHALL 依据 **recency** 与**复用**信号对事实衰减：陈旧、久未访问、低层级的事实 SHALL 随时间**降级一层或归档**；被**复用**（检索命中）的事实 SHALL 被强化——抗降级并可提升层级。recency 信号 SHALL 从**只增不改、按日期命名**的日志层 `memory/YYYY-MM-DD.md` 确定性推导（一条事实的 recency = 其文本命中的最新日志日期）。复用/访问信号 SHALL 来自检索命中，经一个 pig 自有的访问记录 seam 持久化到一个对语料扫描**不可见**的点前缀 sidecar；当该信号**未接线或缺失**时，系统 MUST 优雅降级为**仅 recency** 衰减（陈旧照降，只是无复用保级），MUST NOT 报错。衰减/保留评分 MUST 为**确定性纯函数**（`(层级, recencyDays, accessCount) → 保留/降级/归档决策`），MUST 离线以 fixture 可测。

#### Scenario: 陈旧未复用事实被降级/归档
- **WHEN** 一条低层级事实久未访问且超过陈旧阈值
- **THEN** 系统将其降级一层或归档（依其当前层级与阈值确定性决定）

#### Scenario: 复用命中强化保级
- **WHEN** 一条事实在检索中被命中达到强化阈值
- **THEN** 系统保住其层级（抗本轮降级），达到提级阈值且非 Pinned 时提升一层

#### Scenario: recency 从日志日期确定性推导
- **WHEN** 计算一条事实的 recency
- **THEN** 系统取其文本命中的最新 `memory/YYYY-MM-DD.md` 日志日期，确定性推导 recencyDays

#### Scenario: 无访问信号时降级为仅 recency
- **WHEN** 访问记录 seam 未接线或 sidecar 缺失/损坏
- **THEN** 系统按仅 recency 确定性衰减，不抛异常、不报错

### Requirement: 有原则的分层保留策略（Pinned 恒久、归档非删除、暴露 dailyFileRetentionDays）

系统对固化层 `MEMORY.md` 的保留 MUST 依据**分层 + recency + 复用（重要性）**，MUST NOT 仅依赖 token 上限。`Pinned` 层事实 MUST **永不自动降级或归档**（身份/核心恒久）。归档 MUST 为**移到审计归档账本**（一个点前缀位置）而非静默删除；被归档的事实 MUST NOT 被原生 consolidation 重新摄入（不复活）。系统 SHALL 暴露原生日志层保留旋钮 `dailyFileRetentionDays`（config `memory.daily-file-retention-days`）；未配置（默认 0）时 MUST NOT 调用它——保持原生默认（90 天），行为逐字节等于本能力引入前。

#### Scenario: 保留按分层 + recency + 复用
- **WHEN** 整理 `MEMORY.md`
- **THEN** 事实的保留/降级/归档依其层级、recency、复用信号综合确定性决定，而非仅按 token 上限截断

#### Scenario: Pinned 事实恒久
- **WHEN** 一条 Pinned 层事实无论多陈旧、是否被访问
- **THEN** 系统绝不自动将其降级或归档

#### Scenario: 归档非删除且不复活
- **WHEN** 一条事实被归档
- **THEN** 系统将其移出 `MEMORY.md` 并写入点前缀审计归档账本；后续原生 consolidation 不重新摄入它

#### Scenario: dailyFileRetentionDays 暴露且默认字节等价
- **WHEN** 配置 `memory.daily-file-retention-days` 为一个正整数
- **THEN** 该值被传入原生记忆配置的 `dailyFileRetentionDays`；未配置（默认 0）时不调用它，日志层保留为原生默认（90 天），行为逐字节等于本能力引入前

### Requirement: pig 侧后置 curator 与原生全量重写协作（不打架、节流、容错、原子重写）

系统的分层/衰减整理 SHALL 由一个 pig 侧**后置 curator** 承担，运行在原生 consolidation（及 M-D 语义去重）**之后**，挂在既有的任务调度器上。curator MUST **节流**（不每回合运行）、MUST **容错**——读/写失败时降级为**不改动原文件**并记 warn，MUST NOT 崩溃；重写 MUST 为**原子**（临时文件 + 原子移动）且 MUST 保留结构性行/分层标记。curator MUST NOT 改变原生 flush/consolidation 的触发或 prompt。默认 SHALL 为**非破坏 dry-run**（仅报告 would-degrade/would-archive 候选、不改动 `MEMORY.md`）；仅当显式开启 `auto-archive` 时 SHALL 执行真实的降级/归档重写。

#### Scenario: 后置运行不改原生触发/prompt
- **WHEN** 分层衰减启用
- **THEN** curator 在原生 consolidation 之后运行，原生 flush/consolidation 的触发与 prompt 不变

#### Scenario: 默认 dry-run 非破坏
- **WHEN** 分层衰减启用但 `auto-archive` 未开启（默认）
- **THEN** curator 只报告将降级/归档的候选，`MEMORY.md` 不被 pig 侧改动

#### Scenario: auto-archive 时真实降级归档并原子重写
- **WHEN** `auto-archive` 显式开启
- **THEN** curator 按分层契约重排存活事实、把归档事实移出，并原子重写 `MEMORY.md`（保留分层标记）

#### Scenario: 读写失败安全降级
- **WHEN** curator 读取或重写 `MEMORY.md` 失败
- **THEN** 原文件保持不变，系统记 warn 并继续，不崩溃

### Requirement: 复用 M-D 评测 harness 验证记忆新陈代谢质量（离线证结构、真质量延后 live）

系统 SHALL 复用 M-D 的记忆质量评测 harness（`MemoryEvalFixture` + `MemoryQualityAssertions`）验证分层/衰减质量——**重要事实留存**、**无近重复**、**关键信息未丢**，并补充衰减维度断言——**陈旧事实被降级/归档**、**Pinned 恒久**、**复用事实保级**。harness 的**结构与断言框架** MUST 离线以 fake/确定性驱动可运行（不依赖真模型）；真实的「记住对的、降级该降的」质量基线 SHALL 由 live-model `*IT` 覆盖并延后交付（默认模型此前 403）。

#### Scenario: 离线以确定性驱动跑通衰减断言框架
- **WHEN** 以确定性驱动（fake recency/access + 真分层衰减）跑一个固定对话 fixture
- **THEN** 断言框架产出「重要留存 / 无近重复 / 关键不丢 / 陈旧被降级 / Pinned 恒久 / 复用保级」判定，全离线、不依赖真模型

#### Scenario: 真质量基线延后 live IT
- **WHEN** 需要度量真模型下的真实老化质量
- **THEN** 由 live-model `*IT` 用同一 fixture 与断言驱动真 flush/consolidation + 老化（延后交付，默认模型此前 403）

### Requirement: 全默认关与向后兼容

系统 SHALL 提供 `memory.layering-decay` 配置块与平铺的 `memory.daily-file-retention-days`，全部字段可选、默认安全。分层归类、衰减、访问记录、`dailyFileRetentionDays` 覆盖 MUST 全部**默认关闭/未配置**——此时 MUST 无 curator、无调度、`MEMORY.md` 不被 pig 侧改动、访问记录为 no-op、不调用原生 `dailyFileRetentionDays`，行为 MUST 逐字节等于本能力引入前。非法/越界配置 MUST 被安全 clamp。本能力 MUST NOT 改变 `pa-memory-native` 的两层记忆布局、flush/consolidation 触发语义、`/memory on|off` 语义，以及 M-D 的 consolidation prompt 定制与语义去重。

#### Scenario: 默认关即今日行为
- **WHEN** 未配置 `memory.layering-decay` 与 `memory.daily-file-retention-days`（默认）
- **THEN** 无 curator、无调度、`MEMORY.md` 不被 pig 侧改动、访问记录 no-op、不调用原生 `dailyFileRetentionDays`，行为逐字节等于本能力引入前

#### Scenario: 缺配置块用默认
- **WHEN** 配置中无 `memory.layering-decay` 块
- **THEN** 各子项取安全默认（分层衰减关、`daily-file-retention-days=0`），行为与引入前一致

#### Scenario: 启用不改两层布局与开关语义
- **WHEN** 启用分层衰减
- **THEN** `pa-memory-native` 的日志层/固化层布局、flush/consolidation 触发、`/memory on|off` 语义、M-D 的 prompt 定制与去重均不变，仅在原生 consolidation 之后叠加确定性分层/衰减整理
