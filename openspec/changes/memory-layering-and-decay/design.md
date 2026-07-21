## Context

pig 记忆现状（`pa-memory-native` + M-D 均已在 main）：工作区级两层记忆——日志层 `memory/YYYY-MM-DD.md`（per-turn flush **只增不改、按日期命名**、无去重）→ 固化层 `MEMORY.md`（后台节流 consolidation 由 LLM **全量重写**去重）。M-D 在其上叠加了质量层：可选 prompt 定制 + 语义去重后置 curator + 分层格式契约 + 评测 harness。**唯一的「代谢」边界是 consolidation 的 token 上限**（`consolidation-max-tokens` 默认 4000）——没有分层保留、没有 recency/复用信号、没有「陈旧降级、常用保住」。

M-D 已铺好、本 spec 直接键入/复用的地基（代码事实）：
- **`MemoryLayerFormat`（`io.pigagent.core.memory.quality`）**：三层 `Layer{PINNED("Pinned"), GENERAL("General"), VOLATILE("Recent")}`（most-durable first），`parse(md) → Map<Layer,String>`（`##`/`###` 标题键入、标题前内容与整份无标记文件归 `GENERAL`）、`layerForHeading(h)`。**向后兼容**：旧无分层 `MEMORY.md` = 单层 GENERAL，从不报错。类注释明确「a future layered-decay capability (M-C) has a stable interface to key on … does NOT implement decay (that is M-C)」——**本 spec 就是 M-C**。
- **`FactUnitSplitter`**：把 `MEMORY.md` 逐行切成 `Segment(text, fact)`——heading/blank 是**结构性**段（保留分层标记 verbatim），content bullet/段落是 **fact** 段。`factTexts(segments)` 取 fact 文本。重建 = 顺序拼接存活段文本。分层标记因此在重写中天然保留。
- **`MemoryConsolidationCurator`（M-D 去重 curator，本 spec 镜像其形态）**：读 `MEMORY.md` → `FactUnitSplitter.split` → `SemanticDeduplicator.survivingIndices` → `rebuild`（保结构性段）→ **原子重写**（temp + `ATOMIC_MOVE`，回退 plain replace）；`maybeCurate()` 按 `minGap` 节流（`Clock` 可注入）、`curateNow()` 无视节流；**容错**——任何读/切/写失败记 warn 并**不改动原文件**。挂 `AgentBootstrap.build`：`taskScheduler.schedule("memory:dedup", TaskSchedule.cron("*/"+(gapMinutes*60)), curator::maybeCurate)`，`dedup.enabled` 才建。
- **评测 harness（`memory/quality/eval/`，测试支撑，M-D 明确「供 M-C 复用」）**：`MemoryEvalFixture(name, conversation, expectedKeyFacts, dedupThreshold)`（`personalFactsWithDuplicate()` 中文 fixture）+ `MemoryQualityAssertions.{assertKeyFactsRemembered, assertNoNearDuplicates, assertNoKeyFactLost}`（token-subset 匹配 via 共享 `Tokenizer`；近重复复用 `SemanticDeduplicator`）。全离线确定性。
- **检索原语（R0 `shared-retrieval` + `hybrid-memory-search`/`embedding-model-layer`）**：`io.pigagent.core.search` 的 `Bm25Index`/`HybridRanker`/`SearchDocument`/`Tokenizer`（CJK）；`io.pigagent.core.memory.search` 的 `Embedder`/`DeterministicEmbedder`/`Vectors.cosine`/`MemoryCorpusLoader`（读 `MEMORY.md`/`memory/*.md`/`USER.md`、容错）；`AgentBootstrap.resolveEmbedder(id, modelManager)`：config id → store 默认嵌入指针 → null（→BM25），与 M-B 同源。
- **原生 `MemoryConfig.Builder`（pa-memory-native javap B1，权威）**：有 `dailyFileRetentionDays(int)`（默认 `DEFAULT_DAILY_FILE_RETENTION_DAYS=90`）、`sessionRetentionDays(int)`（默认 180）。**pig 现 `buildMemoryConfig` 从不调 `dailyFileRetentionDays`** → 一律吃原生默认 90。
- **`buildMemoryConfig(PigAgentConfig.MemoryConfig, ModelManager)`**：映射 `flushTrigger`/`consolidationMinGap`/`consolidationMaxTokens`/`model` + M-D 的 `flushPrompt`/`consolidationPrompt`（校验回退）。**未映射 `dailyFileRetentionDays`**——本 spec 补。
- **后置服务/aging precedent**：`ProfileConsolidationService`（节流、容错、原子移动、挂 `TaskScheduler`）；`SkillCuratorService`（S3，定期 aging，`auto-archive=false` 默认 dry-run、stale→`skills/.archive/` 点前缀、`SkillUsageRecorder.noop()` seam）。

## Goals / Non-Goals

**Goals**
- **事实分层归类**：确定性把 `MEMORY.md` 事实归入 `MemoryLayerFormat` 三层（durable→Pinned/General、episodic→Recent），经 M-D 契约读写，向后兼容旧无分层文件。
- **recency + 复用信号驱动衰减**：陈旧/久未访问/低层事实降级或归档；复用（检索命中）强化保级/提级。衰减/保留评分为**确定性纯函数**，离线以 fixture 可测。
- **有原则保留策略**：按分层 + recency + 复用保留/降级/归档；`Pinned` 恒久、归档非删除（点前缀审计账本、不复活）；暴露 `dailyFileRetentionDays`。
- **与原生全量重写协作而非打架**：M-D 式确定性后置 curator（consolidation 之后跑、层级每趟重推导、节流/容错/原子重写、不改原生触发/prompt）。
- **复用 M-D 评测 harness**：`MemoryEvalFixture`/`MemoryQualityAssertions` 断「重要留存/陈旧降级/关键不丢」；真质量延后 live `*IT`。
- config `memory.layering-decay` + `memory.daily-file-retention-days`，**全默认关/additive**，默认路径逐字节等于今日。

**Non-Goals**
- 真「记住对的、降级该降的」质量基线离线验证（属 live-model，交 `/ls:itest`；默认模型此前 403）。
- 重写/替换原生 flush/consolidation 引擎，或接管其触发/写入（本 spec 是其**后置**叠加）。
- LLM 语义改写/合并事实（那是原生 consolidation 的活；M-C 是**确定性**分层/降级/归档，保离线可证）。
- 改 M-D 的 consolidation prompt/语义去重要求（M-C 在其**之后**跑，顺序依赖，不回改）。
- 改 `pa-memory-native` 两层布局 / flush·consolidation 触发 / `/memory` 语义；改 `memory-retrieval-injection` 注入切分；A5 压缩；权限/沙箱/渠道语义。
- 程序性 how-to 的老化（归技能线 S3 的 `SKILL.md`，见交叉依赖——同构但正交）。
- 多用户/多租户记忆隔离（pig 单用户）。

## Spike（阻塞前置 Task 组 1 — 全案最关键决策，不过不进编码）

> 目标：钉死三条边界的「离线可证核心 vs live 延后真质量」，结论落本节 + Decisions。**离线可证**的进编码组；**live 延后**的只留 IT 骨架。承重问题：**衰减/分层能否确定性离线证？访问信号从哪来、怎么持久化？后置 curator 会不会与原生全量重写打架？**

**S1 — 分层归类 + 衰减/保留评分是确定性纯逻辑（离线可证，承重）**
- **分层归类**：`FactLayerClassifier.classify(factText) → MemoryLayerFormat.Layer` 是**纯函数**——从事实文本的确定性信号判层：身份/长期偏好信号（如「我叫/我的名字/请叫我/我一直/我总是/我偏好/my name is/always/prefer」+ 无时效词）→ `PINNED`（身份核心）或 `GENERAL`（一般 durable）；情节/时效信号（如「昨天/刚才/临时/这次/今天的/just now/temporary/for now」或含一次性 token 端口/路径/ID）→ `VOLATILE`。信号表是**编译进的确定性规则**（可 `tool-overrides` 式扩展），可穷举单测。**结论**：分层归类离线可证（真「像不像身份」的语义判断延后 live）。
- **衰减/保留评分**：`RetentionScorer.decide(layer, recencyDays, accessCount, opts) → Decision{KEEP, PROMOTE, DEGRADE, ARCHIVE}` 是**纯函数状态机**——`PINNED` 永不降级/归档（`KEEP`）；`GENERAL` 久未访问且很陈旧（`recencyDays > staleAfterDays` 且 `accessCount < reinforceThreshold`）→ `DEGRADE` 到 `VOLATILE`（先给一次「再被看见」的机会）；`VOLATILE` 陈旧且未复用（`recencyDays > archiveAfterDays` 且 `accessCount < reinforceThreshold`）→ `ARCHIVE`；`accessCount ≥ reinforceThreshold` → 抗降级（`KEEP`），`accessCount ≥ promoteThreshold` 且非 `PINNED` → `PROMOTE` 一层。阈值 clamp、确定性 tie-break。**结论**：评分是纯逻辑状态机，fixture 穷举可测（真「该不该降」延后 live）。

**S2 — 访问信号来源 + 持久化（承重决策，本 spike 的核心澄清）**
- **recency（主信号，无需 sidecar）**：日志层 `memory/YYYY-MM-DD.md` **只增不改、按日期命名**（原生 flush 追加、非重写）——一条事实的 recency = 它**文本命中的最新日志日期**（token-set/BM25 匹配 via 共享 `Tokenizer`，复用 `MemoryCorpusLoader` 读日志层）。这是 robust 的确定性 recency 源：日志文件名即日期，无需持久化任何计数。首次 curate 见不到任何信号 → 视为「now 首见」（宽限期，首趟不归档）。
- **访问/复用（reinforcement，需持久化，seam + sidecar）**：「被复用」是**事件流**（检索命中），不能从文件推导 → 唯一需要持久化的信号。来源 = 检索命中：检索经 `MemorySearchIndex.search(query, topK)`（`hybrid-memory-search`/M-B）与 `memory-retrieval-injection` 的 query-aware 路径返回事实时即一次「use」。pig 自有 `MemoryAccessRecorder` seam（**默认 `noop()`**，镜像 S3 `SkillUsageRecorder.noop()`）记一次命中；持久化到**点前缀 sidecar** `memory/.decay/access-state.json`（点前缀=对 `MemoryCorpusLoader`/原生 `listMemoryFilePaths`/`WorkspaceSkillSource` 式扫描器**不可见**，不被摄入/不进语料），key = **归一化事实指纹**（`Tokenizer` token-set 签名，轻改写仍匹配；重改写重置历史=诚实局限），value = `{lastAccessEpochDay, accessCount}`。容错——损坏→空、绝不崩。
- **优雅降级**：`MemoryAccessRecorder` 未接线（seam=no-op）或 sidecar 缺失/损坏 → 衰减退化为**仅 recency**（陈旧照降，只是没有「复用保级」增强）。故 reinforcement 是**additive 增强**，recency 衰减是**离线可证核心**。**结论**：recency 离线可证（日志日期确定性）、reinforcement 经 seam+sidecar 可离线注入信号测（`Clock`/`epochDay` 注入），真信号量/指纹稳定性延后 live。

**S3 — 后置 curator 与原生全量重写不打架（承重设计）**
- **时序**：curator 在**原生 consolidation（及 M-D 去重）之后**跑、挂 `TaskScheduler`、节流（`min-gap-minutes`）——不与每回合 flush、也不与后台 consolidation 抢；读**快照**（读时 `MEMORY.md` 内容快照）、写**整文件原子替换**（temp+`ATOMIC_MOVE`），失败不改动原文件。镜像 M-D `MemoryConsolidationCurator` 已验证的形态。
- **对原生改写健壮**：层级**每趟由 `FactLayerClassifier` 从事实内容重新推导**（不依赖原生保留 pig 写的标记）——即使下一轮原生 consolidation 把分层标题扁平化/改写措辞，M-C 下一趟 curate 依据事实内容**重建**分层。不与原生 prompt 冲突（本 spec **不改** consolidation prompt；M-D 的可选 prompt 若产出分层标题，M-C 直接沿用 `MemoryLayerFormat.parse`，否则从内容推导）。
- **归档不复活**：`ARCHIVE` 是把事实**移出** `MEMORY.md`、写入点前缀归档账本 `memory/.decay/archive/YYYY-MM.md`（`DecayArchiveWriter` append，审计可查）。归档目标是**点前缀子目录**，不匹配原生 `listMemoryFilePaths` 的 `memory/YYYY-MM-DD.md` 顶层日期文件名模式，故原生 consolidation **不重新摄入**归档事实（不复活）。offline：我方 curator + 语料完全可控可断言；真「原生不摄入点前缀子目录」延后 live 核验（pa-memory-native javap 证 `MEMORY_DIR="memory"` + 日期文件名，子目录/点前缀不匹配）。
- **与 M-D 去重的顺序**：两者都挂 `TaskScheduler`、都节流、都原子重写。约定 dedup 在前（去近重复）、layering-decay 在后（分层+衰减）——各读当前 `MEMORY.md` 快照、原子写；节流错开降低撞车。**结论**：不打架的三要素（后置 + 层级每趟重推导 + 点前缀归档不摄入）离线结构可证，真并发/原生摄入延后 live。

**Spike 净结论**：走**主路径**。三条边界清晰且各有 precedent——分层归类/衰减评分=确定性纯逻辑（`FactLayerClassifier`/`RetentionScorer` fixture 穷举，precedent = M-D `SemanticDeduplicator` 确定性去重）；访问信号=recency 从日志日期确定性推导 + reinforcement 经 `MemoryAccessRecorder` seam+点前缀 sidecar（缺失降级仅 recency，precedent = S3 `SkillUsageRecorder.noop()`）；不打架=M-D 式后置 curator + 层级每趟重推导 + 点前缀归档（precedent = `MemoryConsolidationCurator` + S3 `.archive`）。全 opt-in/默认关、默认字节等价。真质量基线延后 live `MemoryDecayIT`（默认模型 403），复用 M-D `MemoryEvalFixture`/`MemoryQualityAssertions`。进编码。

## Decisions

- **D1 — 事实分层归类 = 纯逻辑 `FactLayerClassifier`，经 M-D `MemoryLayerFormat` 读写，向后兼容。** 从事实文本的确定性信号判 `Layer`（身份/长期偏好→Pinned/General、情节/时效→Recent）；curate 时对每条 fact 段重新归类、按 `MemoryLayerFormat` 分层标题重排；旧无分层 `MEMORY.md` 依 `parse` 视为单层 GENERAL 再归类，不报错。**理由**：分层是衰减的前提，且 M-D 已定契约（D6 明确留 M-C 键入），纯逻辑分类可离线穷举、不引模型调用/不破字节稳定。**备选**：(a) 让 LLM 分层——否决（引模型、不确定、离线不可证、与原生 consolidation 争 prompt）；(b) 只信原生 consolidation 产出的分层标题——否决（原生可能扁平化/不产出，脆弱）。分类每趟重推导（S3）兼得健壮。
- **D2 —（承重）访问信号：recency 从日志日期确定性推导 + reinforcement 经 seam+点前缀 sidecar，缺失降级仅 recency。** recency = 事实文本命中的最新 `memory/YYYY-MM-DD.md` 日期（日志只增不改、文件名即日期，robust）；reinforcement = 检索命中经 `MemoryAccessRecorder`（默认 no-op）记入 `memory/.decay/access-state.json`（点前缀不可见、指纹 key、容错）。未接线/缺失 → 仅 recency 衰减。**理由**：直接回答「访问信号从哪来、怎么持久化」——recency 无需持久化（从文件推）、reinforcement 是唯一需持久化的事件流信号，用最小 seam+sidecar 且默认 no-op 保证「不接线也能确定性衰减」。**备选**：(a) 把访问计数写进 `MEMORY.md`——否决（被原生全量重写清掉、事实被改写后 key 失配）；(b) 强依赖检索命中做唯一信号——否决（无检索发生时永不衰减）。
- **D3 — 衰减/保留 = 纯函数状态机 `RetentionScorer`，Pinned 恒久、降级两步、复用强化、归档非删除。** `(layer, recencyDays, accessCount) → {KEEP, PROMOTE, DEGRADE, ARCHIVE}`：Pinned→KEEP；General 陈旧未复用→DEGRADE 到 Volatile；Volatile 更陈旧未复用→ARCHIVE；`accessCount≥reinforce`→抗降级 KEEP、`≥promote` 非 Pinned→PROMOTE 一层。**理由**：确定性、可穷举、体现「分层+recency+重要性（层级+复用即重要性代理）」；两步降级（General→Volatile→Archive）给「再被看见」的机会、减少误杀。**备选**：(a) 单一 token 封顶（现状）——正是要改的对象；(b) 独立 LLM importance 打分——否决（引模型、离线不可证；层级+复用已是重要性代理，YAGNI）。
- **D4 — pig 侧后置 curator `MemoryLayeringDecayCurator`，镜像 M-D `MemoryConsolidationCurator`，与原生不打架。** consolidation（及 M-D 去重）**之后**跑、挂 `TaskScheduler`、`min-gap-minutes` 节流、读快照、原子重写（保结构性段/分层标记 via `FactUnitSplitter`）、失败不改原文件 + warn。层级**每趟重推导**（对原生扁平化健壮）。**理由**：直接复用 pig 成熟后置服务范式（`MemoryConsolidationCurator`/`ProfileConsolidationService`），零新范式、天然不与原生写入争 prompt/触发。**备选**：改原生 consolidation prompt 让它自己分层衰减——否决（真质量难离线验、改默认破字节稳定、越 M-D 边界）。
- **D5 — 归档 = 移到点前缀审计账本、不复活、不删除。** `ARCHIVE` 把事实移出 `MEMORY.md`、append 到 `memory/.decay/archive/YYYY-MM.md`（`DecayArchiveWriter`）。点前缀子目录不匹配原生 `memory/YYYY-MM-DD.md` 顶层日期模式 → 原生 consolidation 不摄入（不复活）；镜像 S3 `.archive` 语义。**理由**：新陈代谢要「降解」不要「销毁」——归档可审计、可回捞，且不复活是「衰减真的生效」的必要条件。**备选**：直接删事实——否决（不可审计、误杀不可逆）。
- **D6 — 复用嵌入器与 M-B 同源，无嵌入器降级 BM25（不硬依赖 M-B）。** 指纹/recency 匹配与（可选）语义近似经 `resolveEmbedder`（config `embedder-model-id` → store 默认 → null）；null → 共享 `core.search` BM25/`Tokenizer` token-set（零依赖、离线全绿）。**理由**：与 `hybrid-memory-search`/`memory-retrieval-injection`/M-D/M-B 同一 E0 seam，不重复造；BM25 降级保证不被 M-B 阻塞。**备选**：硬依赖真嵌入器——否决（阻塞、离线不可证）。
- **D7 — 暴露 `dailyFileRetentionDays`（config `memory.daily-file-retention-days`），默认 0=不调=原生默认 90。** `buildMemoryConfig` 在 `daily-file-retention-days > 0` 时调 `MemoryConfig.builder().dailyFileRetentionDays(n)`，否则不调（原生默认 90，字节等价）。**理由**：任务显式要求；这是「原始日志层保留」旋钮，与固化层分层衰减互补（前者管 `memory/*.md` 老日志清理、后者管 `MEMORY.md` 事实降级）。`session-retention-days`（原生默认 180）为对称的**可选未来同类**，本 spec 不作硬需求（YAGNI）。**备选**：把它塞进 `layering-decay` 子块——否决（它是原生 `MemoryConfig` 映射项，语义上与 `consolidation-max-tokens` 同层，平铺更自然）。
- **D8 — 默认非破坏 dry-run，`auto-archive=true` 才真移动（镜像 S3）。** `layering-decay.enabled` 时，`auto-archive=false`（默认）→ curator 只**报告** would-degrade/would-archive 候选（记 info、不改 `MEMORY.md`）；`auto-archive=true`（opt-in）→ 真降级/归档重写。**理由**：衰减误杀不可逆（虽归档可回捞），dry-run 让用户先观察再放行；与 S3 `SkillCuratorService` 默认 dry-run 同构。**备选**：启用即真移动——否决（首次启用即可能误降核心事实，无观察窗口）。
- **D9 — config `memory.layering-decay` + `memory.daily-file-retention-days`，全默认关/additive。** 内嵌 `MemoryConfig`：`layering-decay.{enabled false, stale-after-days, archive-after-days, min-gap-minutes, reinforce-access-threshold, promote-access-threshold, auto-archive false, embedder-model-id}` + 平铺 `daily-file-retention-days`(0)。非法值 clamp、null-tolerant（镜像 `DedupConfig`/`InjectionConfig`）。默认全关 = 无 curator、无 schedule、`dailyFileRetentionDays` 不调、`MEMORY.md` 不改动、`MemoryAccessRecorder` no-op。**理由**：默认关确保零回归、一步启用。
- **D10 — 交叉依赖显式登记（见 §交叉依赖）。** 与 M-D（分层契约 + eval，顺序依赖：M-D 先）、M-B（嵌入器同源、BM25 降级不硬依赖）、技能线 S3（记忆 aging vs 技能 aging，同构正交）。

## Architecture

```
memory.layering-decay.*  /  memory.daily-file-retention-days   (config, 默认全关)
        │  AgentBootstrap.buildMemoryConfig / build
        ▼
① 暴露 dailyFileRetentionDays（原生日志层保留）                    ← D7
   daily-file-retention-days > 0 → MemoryConfig.builder().dailyFileRetentionDays(n)
   0(默认) → 不调 → 原生默认 90（字节等价）

② 访问信号（reinforcement，默认 no-op）                            ← D2
   检索命中(MemorySearchIndex.search / injection) → MemoryAccessRecorder.record(hits)
        └→ memory/.decay/access-state.json  {指纹: {lastAccessEpochDay, accessCount}}
           (点前缀不可见/不摄入；未接线 → no-op → 仅 recency)

③ 记忆分层与衰减后置 curator（layering-decay.enabled，默认关）      ← 挂 TaskScheduler（节流）
   MemoryLayeringDecayCurator.maybeCurate():                       (镜像 MemoryConsolidationCurator)
     segments = FactUnitSplitter.split(MEMORY.md 快照)             ← 保结构性段/分层标记
     for each fact:
        layer      = FactLayerClassifier.classify(text)            ← D1 确定性归类（每趟重推导）
        recency    = 最新命中的 memory/YYYY-MM-DD.md 日期           ← D2 从日志日期推（robust）
        accessCnt  = access-state[指纹].accessCount (default 0)     ← D2 sidecar（缺失→0）
        decision   = RetentionScorer.decide(layer, recencyDays, accessCnt)  ← D3 状态机
     auto-archive=false(默认) → 只报告 would-degrade/would-archive（不改）   ← D8 dry-run
     auto-archive=true → 按 MemoryLayerFormat 重排存活事实分层
                       + ARCHIVE 事实移出 → DecayArchiveWriter(memory/.decay/archive/) ← D5 不复活
                       → 原子重写 MEMORY.md（失败不改原文件 + warn）           ← D4 容错

④ 评测（复用 M-D harness）
   离线: MemoryEvalFixture + MemoryQualityAssertions{重要留存/无近重复/关键不丢}  ← 确定性驱动
        + 新增衰减维度断言（陈旧被降级、Pinned 恒久、复用保级）
   live: MemoryDecayIT 真模型跑真 flush/consolidation + 老化 → 真质量基线（延后 /ls:itest）
```

## 交叉依赖（与 M-D / M-B / 技能线 S3）

- **M-D（consolidation 质量 + 分层契约 + eval，已在 main，顺序依赖）**：M-C **建在** M-D 的 `MemoryLayerFormat` 分层契约（D1 键入其 `Layer`/`parse`）+ `FactUnitSplitter`（D4 切分保标记）+ eval harness（复用 `MemoryEvalFixture`/`MemoryQualityAssertions`）之上。**顺序：M-D 先（已归入 main），M-C 后**——M-C **不回改** M-D 的 consolidation prompt 定制/语义去重要求；M-C 的 layering-decay curator 在 M-D dedup curator **之后**跑（§S3）。design 显式登记「M-D 先、M-C 后」（**非并行**）与「复用而非改写」。
- **M-B（真嵌入，Wave-2）**：reinforcement 的指纹/语义近似经 `AgentBootstrap.resolveEmbedder`（与 `hybrid-memory-search`/`memory-retrieval-injection`/M-D **同一 E0 seam**）；**无嵌入器 → BM25/token-set 降级**（零依赖），故本 spec **不硬依赖 M-B**——可并行/先行，M-B 落地后指纹匹配/语义近似自动升级，无需回改衰减逻辑。
- **技能线 S3（skill-curator-and-graded-promotion，已在 main，同构正交）**：M-C 汰**声明性记忆事实**（`MEMORY.md`），S3 汰**程序性技能**（`SKILL.md`）——**同一「新陈代谢/aging」范式**（recency+usage → keep/degrade/archive、归档非删除、点前缀 `.archive`、dry-run 默认、挂 `TaskScheduler` 的 curator、`*UsageRecorder.noop()` seam），但**不同对象、无共享状态**。M-C `MemoryAccessRecorder` 镜像 S3 `SkillUsageRecorder`，`MemoryLayeringDecayCurator` 镜像 S3 `SkillCuratorService` + M-D `MemoryConsolidationCurator`。两条线不重叠（声明性 vs 程序性边界，与 M-D/注入切分同界）。design 登记此同构（设计模式复用）与正交（不共享状态）。

## Risks / Trade-offs

- **R1 —（承重）后置 curator 与原生全量重写打架 / 归档复活。** → §S3 + D4/D5：后置跑 + 层级每趟重推导（对扁平化健壮）+ 点前缀归档不匹配原生日期文件名模式（不摄入）+ 节流 + 原子重写 + 失败不改原文件。真并发/原生对点前缀子目录的摄入行为延后 live 核验（javap 证顶层日期文件名模式）。
- **R2 — 分层归类误判（把身份当情节降级，或把噪音当 durable 常驻）。** → D1 信号表保守（身份信号明确才升 Pinned，其余默认 General 不轻易降）+ 两步降级（先 General→Volatile 给机会）+ dry-run 默认（D8 先观察）+ eval `assertNoKeyFactLost`/新增「Pinned 恒久」断言防误降。真误判率延后 live IT。
- **R3 — 指纹匹配对重改写不稳（原生 consolidation 改写措辞 → 访问历史/recency 失配）。** → recency 主用日志层（只增不改、原文更稳）；reinforcement 指纹用 token-set（轻改写仍匹配），重改写重置历史是**诚实局限**——但重置只是「少了复用加成」，不误删（衰减仍走 recency + 宽限期）；真稳定性延后 live 观测。默认 no-op 时此风险不存在。
- **R4 — 访问信号缺失 → 衰减退化为仅 recency（无复用保级）。** → D2 设计即接受：reinforcement 是 additive 增强、recency 是可证核心；`MemoryAccessRecorder` 默认 no-op，未接线也确定性衰减（陈旧照降）。接线后升级为「复用保级」，无需回改。
- **R5 — 真「记住对的、降级该降的」质量未离线验。** → 诚实延后 live `MemoryDecayIT`（默认模型 403）；离线证的是**分层归类/衰减评分的确定性逻辑 + curator 结构 + 断言框架 + 默认关字节等价**（护栏而非质量本身），复用 M-D harness。
- **R6 —（交叉依赖）M-C 依赖 M-D 的分层契约/eval，M-D 须先。** → M-D 已在 main；顺序依赖显式登记，M-C 不回改 M-D consolidation prompt/去重（§交叉依赖）。
- **R7 —（交叉依赖）reinforcement 嵌入器与 M-B 同源但 M-B 未必先到。** → D6 无嵌入器降级 BM25/token-set，不硬依赖 M-B；M-B 落地自动升级。
- **R8 — `dailyFileRetentionDays` 误配置删掉需要的日志。** → 默认 0=不调（原生默认 90）；非法值 clamp（`≤0`→不调、极端值不放大）；这是原生已有旋钮，本 spec 只暴露、不改其语义。
- **R9 — enabled=false / 默认的字节等价性。** → 默认路径**不调** `dailyFileRetentionDays`、**不建** curator、**不挂** schedule、`MemoryAccessRecorder` no-op；单测断 `buildMemoryConfig` 默认产出与今日一致、现有 memory 测试仍绿。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 记忆只长不代谢、无分层/recency/复用信号 | Why；D1/D2/D3 | 落 tasks 组 1/2/3 |
| 事实分层归类（durable→Pinned/General、episodic→Recent），键入 M-D `MemoryLayerFormat` | Spike S1；D1 | 落 tasks 组 1（分类器）+ 组 3（curate 重排） |
| 分层归类确定性纯逻辑、向后兼容旧无分层 | Spike S1；D1 | 落 tasks 组 1/2 |
| recency 信号从日志日期确定性推导（无需 sidecar） | Spike S2；D2 | 落 tasks 组 2 |
| 访问/复用信号来源（检索命中）+ 持久化（seam+点前缀 sidecar） | Spike S2；D2 | 落 tasks 组 2（recorder/sidecar） |
| 复用强化保级/提级、缺信号降级仅 recency | Spike S2；D2/D3；R4 | 落 tasks 组 1/2 |
| 衰减/保留评分纯函数状态机（Pinned 恒久/两步降级/归档） | Spike S1；D3 | 落 tasks 组 1 |
| pig 侧后置 curator 与原生全量重写不打架（后置+每趟重推导+点前缀归档） | Spike S3；D4/D5；R1 | 落 tasks 组 3 |
| 层级每趟重推导，对原生扁平化健壮 | Spike S3；D4 | 落 tasks 组 3 |
| 归档非删除、点前缀审计账本、不复活 | Spike S3；D5；R1 | 落 tasks 组 3 |
| 暴露 `dailyFileRetentionDays`（现吃原生默认 90） | D7；R8 | 落 tasks 组 4（config）+ 组 5（接线） |
| 默认非破坏 dry-run，auto-archive 才真移动 | D8 | 落 tasks 组 3 |
| curator 节流/容错/原子重写、镜像 `MemoryConsolidationCurator` | D4；R1 | 落 tasks 组 3 |
| 嵌入器与 M-B 同源、BM25/token-set 降级、不硬依赖 M-B | D6；R7；交叉依赖 | 落 tasks 组 2/3 + 登记 |
| 复用 M-D 评测 harness 断重要留存/陈旧降级/关键不丢 | Spike S3；D3；交叉依赖 | 落 tasks 组 6 |
| 真质量基线延后 live `*IT`（默认模型 403） | Spike S1/S3；R5 | 落 tasks 组 7（IT，延后） |
| `MEMORY.md` 分层契约来自 M-D、顺序依赖 M-D 先 | 交叉依赖；R6 | 已登记（顺序依赖，非并行） |
| 技能线 S3：记忆 aging vs 技能 aging，同构正交 | 交叉依赖 | 已登记（边界 + 设计模式复用） |
| config `memory.layering-decay`/`daily-file-retention-days`、全默认关=今日 | D9；R9 | 落 tasks 组 4；断字节等价 tasks 组 5 |
| 误降防护（保守信号 + 两步降级 + dry-run + Pinned 恒久断言） | R2 | 落 tasks 组 1/6 |
