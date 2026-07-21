## 1. Spike（阻塞前置 — 全案最关键决策，不过不进编码）

> 目标：钉死三项的「离线可证核心 vs live 延后真质量」边界——分层归类 + 衰减评分（确定性纯逻辑）/ 访问信号来源 + 持久化 / 后置 curator 不打架原生。结论落 `design.md §Spike` 与 §Decisions D1–D9。**离线可证**的进编码组，**live 延后**的只留 IT 骨架。

- [x] 1.1 核验 M-D 契约可键入（读码）：`MemoryLayerFormat.{Layer(PINNED/GENERAL/VOLATILE), parse, layerForHeading}` 向后兼容（无标记=单层 GENERAL）；`FactUnitSplitter.{split, factTexts}` 保结构性段/分层标记；`MemoryConsolidationCurator` 的节流/容错/原子重写形态可镜像；eval `MemoryEvalFixture`/`MemoryQualityAssertions` 可复用。（均确认）
- [x] 1.2 核验原生 `dailyFileRetentionDays`（pa-memory-native javap B1）：`MemoryConfig.Builder.dailyFileRetentionDays(int)` 存在、默认 90；`buildMemoryConfig` 现**从不**调用它 → 补映射的落点确认（`MemoryLayeringDecayWiringTest` 坐实默认 90 / 配置 120）。
- [x] 1.3 spike 断言（离线，分层归类 + 衰减评分）：`FactLayerClassifier.classify` 身份→PINNED / 情节·时效→VOLATILE / durable→GENERAL（穷举信号，`FactLayerClassifierTest` 8 绿）；`RetentionScorer.decide` 状态机——Pinned→KEEP、General 陈旧→DEGRADE、Volatile 陈旧→ARCHIVE、复用保级/提级、阈值 clamp（`RetentionScorerTest` 11 绿）。
- [x] 1.4 spike 断言（离线，访问信号）：`RecencyResolver` 从 `memory/YYYY-MM-DD.md` 日期确定性推导（无信号=grace，`RecencyResolverTest` 6 绿）；`MemoryAccessStore` 点前缀 sidecar 容错（`MemoryAccessStoreTest` 5 绿）；`MemoryAccessRecorder` 默认 no-op / 接线后 bump（`MemoryAccessRecorderTest` 3 绿）；未接线 → 仅 recency（curator 测覆盖）。
- [x] 1.5 spike 断言（离线，不打架原生）：`MemoryLayeringDecayCurator` 读快照 + 原子重写 + 失败不改原文件；层级**每趟重推导**（喂扁平化 `MEMORY.md` 仍重建分层）；`ARCHIVE` 移出 → 点前缀 `memory/.decay/archive/`（不匹配原生日期模式→不复活）——`MemoryLayeringDecayCuratorTest` 8 绿。
- [x] 1.6 结论 + 理由（含否决备选）已记入 `design.md §Spike` 与 §Decisions D1–D9；真质量基线延后 live `MemoryDecayIT`（默认模型 403），复用 M-D `MemoryEvalFixture`/`MemoryQualityAssertions`。

## 2. 访问信号：recency 推导 + reinforcement seam + sidecar（`pig-agent-core.memory.decay`）

- [x] 2.1 `RecencyResolver`（core 纯逻辑）：读日志层 `memory/*.md`（共享 `Tokenizer` token-subset 命中），算「最新命中日志日期 → recencyDays」；无命中 → `NO_SIGNAL`（scorer 视为 fresh，宽限不归档）；`today` 注入、容错。
- [x] 2.2 `MemoryAccessStore`（core，点前缀 sidecar）：读/写 `memory/.decay/access-state.json`（Jackson，`fingerprint → {accessCount, lastAccessEpochDay}`）；原子写、损坏→空、绝不崩；点前缀 → 对语料扫描不可见。`FactFingerprint` = `Tokenizer` token-set 归一化签名。
- [x] 2.3 `MemoryAccessRecorder`（core seam，默认 `noop()`，镜像 S3 `SkillUsageRecorder`）：`record(hits)` 把命中事实指纹 `bump`；default no-op → 零行为、仅 recency 衰减。
- [x] 2.4 单测：`RecencyResolverTest`（多日日志/最新命中/无命中宽限/非 date 文件跳过/容错）；`MemoryAccessStoreTest`（读写/跨实例持久/损坏降级/点前缀）；`MemoryAccessRecorderTest`（no-op 零副作用 / 接线 bump / null 容错）。

## 3. 分层归类 + 衰减评分 + 后置 curator（`pig-agent-core.memory.decay`）

- [x] 3.1 `FactLayerClassifier`（core 纯逻辑 Strategy）：`classify(factText) → Layer`——身份/how-to-address→PINNED（保守窄）、情节/时效→VOLATILE、durable 默认→GENERAL；身份优先于情节；null-tolerant、确定性、无模型调用；信号集可注入。
- [x] 3.2 `RetentionScorer`（core 纯逻辑）+ `RetentionDecision`：`decide(layer, recencyDays, accessCount) → {KEEP, PROMOTE, DEGRADE, ARCHIVE}`——Pinned→KEEP；General 陈旧未复用→DEGRADE；Volatile 陈旧未复用→ARCHIVE；`≥reinforce`→KEEP、`≥promote` 非 Pinned→PROMOTE；阈值 clamp、不 mutate。
- [x] 3.3 `DecayArchiveWriter`（core）：`ARCHIVE` 事实 append 到点前缀 `memory/.decay/archive/YYYY-MM.md`（不匹配原生日期模式→不复活）；返回 append 是否成功（失败则 curator 不丢事实）；原子/容错。
- [x] 3.4 `MemoryLayeringDecayCurator`（core 后置服务，镜像 `MemoryConsolidationCurator`）：`maybeCurate()` 节流 → 读 `MEMORY.md` 快照 → `FactUnitSplitter.split` → 逐 fact `classify` + `RecencyResolver` + `MemoryAccessStore` → `RetentionScorer.decide`；`auto-archive=false`（默认）→ 只报告 would-degrade/would-archive（不改）；`auto-archive=true` → 按 `MemoryLayerFormat` 重排存活分层 + ARCHIVE 移出 → 原子重写；失败不改原文件 + warn。
- [x] 3.5 单测：`FactLayerClassifierTest`（身份/情节/时效/默认/大小写）；`RetentionScorerTest`（各层各信号/clamp/复用保级提级/不 mutate）；`DecayArchiveWriterTest`（append/点前缀/空表 no-op）；`MemoryLayeringDecayCuratorTest`（`@TempDir` 真 IO：dry-run 不改、auto-archive 降级+归档+原子重写、层级重推导扁平化重建、节流、缺/空文件安全、Pinned 恒久、复用保级、幂等）。

## 4. 配置 + `dailyFileRetentionDays` 暴露（`pig-agent-config`）

- [x] 4.1 `MemoryConfig` 内嵌 `LayeringDecayConfig`（`enabled` 默认 false、`auto-archive` false、`stale-after-days` 30、`archive-after-days` 90 clamp≥stale、`reinforce-access-threshold` 2 clamp≥1、`promote-access-threshold` 5 clamp≥reinforce、`min-gap-minutes` 60 clamp≥1）+ 平铺 `daily-file-retention-days`（默认 0=不调=原生默认 90）；getter/setter null-tolerant、非法值 clamp。**精化（偏离原计划）**：M-C 复用信号用 token-set 指纹（零嵌入依赖，D6 默认 BM25/token-set），故**不暴露 `embedder-model-id`**（YAGNI，避免死旋钮）。
- [x] 4.2 单测：`LayeringDecayConfigTest`（默认关 + retention 0、YAML、缺块、clamp、null-tolerant）。

## 5. 接线（`pig-agent-cli` `AgentBootstrap`）

- [x] 5.1 `buildMemoryConfig`：`daily-file-retention-days > 0` → `MemoryConfig.builder().dailyFileRetentionDays(n)`；0（默认）→ 不调（原生默认 90，字节等价）。
- [x] 5.2 `build`：`layering-decay.enabled` 时建 `MemoryLayeringDecayCurator`（`FactLayerClassifier`+`RetentionScorer`+`DecayArchiveWriter`），挂 `TaskScheduler("memory:layering-decay", cron */gap*60)`，**在 M-D `memory:dedup` 之后**；`MemoryAccessRecorder`（store-backed 当 enabled）接线到 `HybridMemorySearchTool`（memory_search 命中 → 复用信号），默认 no-op；默认关 → 不建 curator/schedule、recorder no-op。**精化**：curator 无嵌入器（token-set 指纹），故不经 `resolveEmbedder`。
- [x] 5.3 接线单测：`MemoryLayeringDecayWiringTest`——默认 `dailyFileRetentionDays` 原生默认 90、配置 120 到达原生；`mvn -pl pig-agent-cli -am compile` 绿。

## 6. 复用 M-D 评测 harness + 衰减维度断言（`pig-agent-core` 测试支撑）

- [x] 6.1 复用 `MemoryEvalFixture`/`MemoryQualityAssertions`（M-D）：确定性驱动跑分层/衰减，断「重要留存 / 无近重复 / 关键不丢」（`MemoryLayeringDecayHarnessTest`）。
- [x] 6.2 `MemoryDecayAssertions`（新增衰减维度）：`assertFactInLayer`、`assertStaleFactArchived`、`assertReusedFactRetained`、`assertPinnedNeverArchived`；离线确定性 + `assertThatThrownBy` 证能正确 flag 违规。
- [x] 6.3 离线 harness 结构测试（`MemoryLayeringDecayHarnessTest`）：喂 `MemoryEvalFixture` + 受控 recency/access → curate → 断三质量指标 + 三衰减维度，全离线不依赖真模型（2 绿）。

## 7. 集成测试（真模型 `*IT`，外环，延后 `/ls:itest`）

- [ ] 7.1 `MemoryDecayIT`（真模型）：喂 `MemoryEvalFixture` 固定对话跑真 flush/consolidation + 分层衰减 curate（含 recency/access 老化），用 `MemoryQualityAssertions` + `MemoryDecayAssertions` 断**真质量基线**——重要留存、陈旧降级、复用保级、关键不丢。默认模型此前 403，诚实延后。
- [ ] 7.2 `MemoryAccessSignalIT`（真模型）：真检索命中喂 `MemoryAccessRecorder` → 复用保级的真实召回；重改写下指纹匹配稳定性观测；token-set 降级离线已覆盖。

## 8. 验收 + 文档

- [ ] 8.1 `mvn -q test` whole reactor 全绿（合并后由协调方统一跑；本内环只跑受影响模块定向测试——`pig-agent-core` memory.decay 44 + harness 2、`pig-agent-config` 5、`pig-agent-cli` wiring 2、`pig-agent-tools` HybridMemorySearchTool 4 均绿）。
- [x] 8.2 `mvn -pl pig-agent-cli -am compile` 绿（BUILD SUCCESS）。
- [x] 8.3 `CLAUDE.md` 记忆段新增「记忆分层与衰减（M-C）」说明（分层归类 + recency/复用衰减 + 有原则保留 + 暴露 `dailyFileRetentionDays` + 后置 curator 不打架原生 + 默认关）+ 配置段 `memory.layering-decay` / `memory.daily-file-retention-days` + 模块表 `memory/decay`。
- [ ] 8.4 归档时（`/ls:archive`）同步主 spec → `openspec/specs/memory-layering-and-decay/`。
