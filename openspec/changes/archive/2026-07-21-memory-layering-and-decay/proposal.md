## Why

pig 的长期记忆（`pa-memory-native`）只会**长**、不会**代谢**：flush 每回合往日志层追加事实，consolidation 由 LLM 全量重写去重到工作区级 `MEMORY.md`，唯一的边界是一个 token 上限（`consolidation-max-tokens`，默认 4000）。于是「我叫罗湘赣」这种身份事实，和「昨天那个临时端口号」这种一次性情节，被**平等对待**——都堆在同一层，都靠 token 封顶时被 LLM 决定去留。没有分层、没有 recency/复用信号、没有「陈旧的降级、常用的保住」的新陈代谢。对一个 24h 常驻助理，这意味着核心身份可能被一堆过期琐事挤掉，而真正无用的近期噪音却和长期偏好一样常驻注入。

M-D（已在 main）已经把地基铺好：`MemoryLayerFormat`（Pinned/General/Recent 三层、`##` 标题键入、**向后兼容**——无标记旧文件=单层 GENERAL）定义了**分层格式契约**，`FactUnitSplitter`/`SemanticDeduplicator`/`MemoryConsolidationCurator` 给出了「原生全量重写**之后**跑 pig 侧确定性后置 curator」的范式，`memory/quality/eval/`（`MemoryEvalFixture`+`MemoryQualityAssertions`）给出了可复用的离线评测 harness。**M-D 只定契约、不建衰减，衰减明确留给 M-C**。本 spec 就是在 M-D 契约上建「记忆新陈代谢」。

## What Changes

- **事实分层归类**：把 `MEMORY.md` 的每条事实单元确定性归入 `MemoryLayerFormat` 的三层——身份/长期偏好等 durable 事实 → `Pinned`/`General`，一次性/时效性 episodic 事实 → `Recent`。分层由**纯逻辑分类器**从事实内容信号推导，经 M-D 分层契约读写；向后兼容旧无分层 `MEMORY.md`（视为单层，curate 时重新归类）。
- **recency/访问信号 + 衰减降级**：陈旧、久未访问、低层级的事实随时间**降级一层或归档**；**复用强化**——被检索命中的事实保级/提级（抗降级）。recency 主信号来自**只增不改、按日期命名的日志层** `memory/YYYY-MM-DD.md`（robust，无需 sidecar）；访问/复用信号来自检索命中，经 pig 自有 `MemoryAccessRecorder` seam（默认 no-op）记入一个**点前缀 sidecar** 计数（缺失则优雅降级为「仅 recency」）。衰减/保留评分是**确定性纯函数** `(层级, recencyDays, accessCount) → {KEEP, PROMOTE, DEGRADE, ARCHIVE}`。
- **有原则的保留策略**：`MEMORY.md` 不再只靠 token 封顶——按**分层 + recency + 复用（重要性）**保留/降级/归档；`Pinned` 永不自动归档（身份/核心恒久）；归档是**移到审计归档账本**（点前缀 `.archive`，镜像技能 curator）而非静默删除，且归档事实**不被原生 consolidation 重新摄入**（不复活）。
- **暴露 `dailyFileRetentionDays`**：`buildMemoryConfig` 现在**从不**调用原生 `MemoryConfig.builder().dailyFileRetentionDays(...)`（吃原生默认 90 天）——本 spec 把它经 config `memory.daily-file-retention-days` 暴露（默认 0=不调=原生默认 90，保持字节等价），作为「原始日志层保留」与「固化层分层衰减」互补的一环。
- **pig 侧后置 curator 与原生全量重写协作（不打架）**：分层/衰减走一个 M-D 式确定性后置 curator（`MemoryLayeringDecayCurator`），**在原生 consolidation（及 M-D 去重）之后**跑、挂 `TaskScheduler`、节流 + 容错 + 原子重写；**层级每趟重新推导**（对原生扁平化/改写健壮），**不改**原生 flush/consolidation 触发/prompt。默认**非破坏 dry-run**（仅报告 would-degrade/would-archive），`auto-archive=true` 才真移动（镜像 S3 技能 curator）。
- **复用 M-D 评测 harness**：以 `MemoryEvalFixture`/`MemoryQualityAssertions` 断言「重要事实留存、陈旧被降级、关键不丢」；离线以确定性驱动证结构，真「记住对的、降级该降的」质量基线延后 live `*IT`（默认模型 403）。
- **config `memory.layering-decay` 块 + `memory.daily-file-retention-days`**，**全默认关/additive**：未配置时无 curator、无 schedule、`MEMORY.md` 不被 pig 侧改动、`dailyFileRetentionDays` 不调（原生默认 90），行为逐字节等于本能力引入前。

**非 BREAKING**：全部 opt-in、默认关；默认路径逐字节等于今日。启用后仅在原生 consolidation 之后叠加一层确定性分层/衰减后置整理，不改两层布局、不改 flush/consolidation 触发、不改 `/memory on|off` 语义、不改 M-D 去重/注入切分。

## Capabilities

### New Capabilities
- `memory-layering-and-decay`: 在 M-D `MemoryLayerFormat` 分层契约上建记忆新陈代谢——确定性事实分层归类（durable→Pinned/General、episodic→Recent）、recency + 复用信号驱动的衰减降级/归档（陈旧降级、复用强化保级）、有原则的分层保留策略（Pinned 恒久、归档非删除、暴露 `dailyFileRetentionDays`），全部经原生全量重写**之后**的 pig 侧确定性后置 curator 实现（与原生不打架），复用 M-D 评测 harness。全默认关。

### Modified Capabilities
<!-- 无。M-D 的 memory-consolidation-quality 尚未归档（其主 spec 未同步进 openspec/specs/），且 M-C 是其上的**新增**新陈代谢行为而非改写其 SHALL；分层契约/eval harness 以**顺序依赖 + 复用**方式建立（见 design §交叉依赖），不 MODIFIED M-D 的 consolidation prompt/去重要求。 -->

## Impact

- **代码**：`pig-agent-core`（新增 `memory/decay/`：`FactLayerClassifier` 分层归类、`RetentionScorer` 衰减/保留评分、`MemoryLayeringDecayCurator` 后置 curator、`MemoryAccessRecorder` 访问信号 seam + 点前缀 sidecar 存储、`DecayArchiveWriter` 归档账本；复用 `memory/quality/` 的 `MemoryLayerFormat`/`FactUnitSplitter` 与 `core.search`/`Embedder` 检索原语）；`pig-agent-config`（`MemoryConfig` 内嵌 `LayeringDecayConfig` + 平铺 `daily-file-retention-days`）；`pig-agent-cli`（`AgentBootstrap.buildMemoryConfig` 暴露 `dailyFileRetentionDays`；`build` 在 `layering-decay.enabled` 时经 `resolveEmbedder` 建 curator、挂 `TaskScheduler`，接线 `MemoryAccessRecorder` 到检索路径）。
- **不改**：`pa-memory-native` 两层布局 / flush·consolidation 触发 / `/memory` 语义；M-D 的 consolidation prompt 定制 + 语义去重（M-C 在其**之后**跑）；`memory-retrieval-injection` 注入切分；A5 压缩；权限/沙箱/渠道语义。
- **测试**：新增 `FactLayerClassifierTest`/`RetentionScorerTest`/`MemoryLayeringDecayCuratorTest`/`MemoryAccessRecorderTest`（离线确定性 + `@TempDir` 真 IO）；**复用** `MemoryEvalFixture`/`MemoryQualityAssertions` 断「重要留存/陈旧降级/关键不丢」；`buildMemoryConfig` 断 `dailyFileRetentionDays` 到达原生 + 默认关字节等价；真质量基线延后 `MemoryDecayIT`（默认模型 403）。
- **文档**：`CLAUDE.md` 记忆段新增「记忆分层与衰减（M-C）」说明 + 配置段 `memory.layering-decay` / `memory.daily-file-retention-days`。
