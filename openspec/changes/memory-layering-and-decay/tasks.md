## 1. Spike（阻塞前置 — 全案最关键决策，不过不进编码）

> 目标：钉死三条边界的「离线可证核心 vs live 延后真质量」——分层归类 + 衰减评分（确定性纯逻辑）/ 访问信号来源 + 持久化 / 后置 curator 不打架原生。结论落 `design.md §Spike` 与 §Decisions D1–D9。**离线可证**的进编码组，**live 延后**的只留 IT 骨架。

- [ ] 1.1 核验 M-D 契约可键入（读码）：`MemoryLayerFormat.{Layer(PINNED/GENERAL/VOLATILE), parse, layerForHeading}` 向后兼容（无标记=单层 GENERAL）；`FactUnitSplitter.{split, factTexts}` 保结构性段/分层标记；`MemoryConsolidationCurator` 的节流/容错/原子重写形态可镜像；eval `MemoryEvalFixture`/`MemoryQualityAssertions` 可复用。
- [ ] 1.2 核验原生 `dailyFileRetentionDays`（pa-memory-native javap B1）：`MemoryConfig.Builder.dailyFileRetentionDays(int)` 存在、默认 90；`buildMemoryConfig` 现**从不**调用它 → 补映射的落点确认。
- [ ] 1.3 写 spike 断言（离线，分层归类 + 衰减评分）：`FactLayerClassifier.classify` 对身份/长期偏好 fixture → PINNED/GENERAL、对情节/时效 fixture → VOLATILE（穷举信号）；`RetentionScorer.decide` 状态机——Pinned→KEEP、General 陈旧未复用→DEGRADE、Volatile 陈旧未复用→ARCHIVE、`accessCount≥reinforce`→抗降级 KEEP、`≥promote` 非 Pinned→PROMOTE；阈值 clamp、确定性。
- [ ] 1.4 写 spike 断言（离线，访问信号）：recency 从 `memory/YYYY-MM-DD.md` 日志日期确定性推导（token-set 命中最新日期，`Clock`/`epochDay` 注入）；`MemoryAccessRecorder` seam 记命中入点前缀 `memory/.decay/access-state.json`（指纹 key，容错，损坏→空）；未接线（no-op）/缺失 → 衰减退化仅 recency（陈旧照降）。
- [ ] 1.5 写 spike 断言（离线，不打架原生）：后置 curator 读快照 + 原子重写 + 失败不改原文件；层级**每趟重推导**（喂扁平化 `MEMORY.md` 仍重建分层）；`ARCHIVE` 移出事实 → 点前缀 `memory/.decay/archive/` 账本（不匹配原生 `memory/YYYY-MM-DD.md` 顶层日期模式→不复活，offline 我方语料可控可断言）。
- [ ] 1.6 结论 + 理由（含被否决备选）记入 `design.md §Spike` 与 §Decisions D1–D9；确认「真『记住对的、降级该降的』质量基线」延后 live `MemoryDecayIT`（默认模型此前 403），复用 M-D `MemoryEvalFixture`/`MemoryQualityAssertions`。

## 2. 访问信号：recency 推导 + reinforcement seam + sidecar（`pig-agent-core.memory.decay`）

- [ ] 2.1 `RecencyResolver`（core 纯逻辑）：读日志层 `memory/*.md`（复用 `MemoryCorpusLoader` 式分块 + 共享 `Tokenizer` token-set 命中），给一条事实文本算「最新命中日志日期 → recencyDays」；无命中 → 视为「now 首见」（宽限，首趟不归档）；`Clock`/today 注入、容错。
- [ ] 2.2 `MemoryAccessStore`（core，点前缀 sidecar）：读/写 `memory/.decay/access-state.json`（指纹 → `{lastAccessEpochDay, accessCount}`）；指纹 = `Tokenizer` token-set 归一化签名；原子写、损坏→空、绝不崩。点前缀 → 对 `MemoryCorpusLoader`/原生扫描不可见。
- [ ] 2.3 `MemoryAccessRecorder`（core seam，默认 `noop()`，镜像 S3 `SkillUsageRecorder`）：`record(hits)` 把检索命中的事实指纹 `bumpAccess`（`lastAccessEpochDay`/`accessCount`）；default no-op → 零行为、仅 recency 衰减。
- [ ] 2.4 单测：`RecencyResolverTest`（`@TempDir` 造多日日志、断最新命中日期/无命中宽限/容错）；`MemoryAccessStoreTest`（读写/指纹稳定/损坏降级/原子）；`MemoryAccessRecorderTest`（no-op 零副作用、接线后 bump 正确）。

## 3. 分层归类 + 衰减评分 + 后置 curator（`pig-agent-core.memory.decay`）

- [ ] 3.1 `FactLayerClassifier`（core 纯逻辑 Strategy）：`classify(factText) → MemoryLayerFormat.Layer`——身份/长期偏好信号→PINNED/GENERAL、情节/时效/一次性 token 信号→VOLATILE；信号表编译进 + 保守默认 GENERAL；null-tolerant、确定性、无模型调用。
- [ ] 3.2 `RetentionScorer`（core 纯逻辑）：`decide(layer, recencyDays, accessCount, opts) → Decision{KEEP, PROMOTE, DEGRADE, ARCHIVE}`——Pinned→KEEP；General 陈旧未复用→DEGRADE(→Volatile)；Volatile 陈旧未复用→ARCHIVE；`≥reinforce`→抗降级 KEEP、`≥promote` 非 Pinned→PROMOTE；阈值 clamp、确定性 tie-break、不 mutate 入参。
- [ ] 3.3 `DecayArchiveWriter`（core）：`ARCHIVE` 事实 append 到点前缀审计账本 `memory/.decay/archive/YYYY-MM.md`（不匹配原生日期文件名模式→不复活）；原子/容错。
- [ ] 3.4 `MemoryLayeringDecayCurator`（core 后置服务，镜像 `MemoryConsolidationCurator`）：`maybeCurate()` 节流 → 读 `MEMORY.md` 快照 → `FactUnitSplitter.split` → 逐 fact `classify` + `RecencyResolver` + `MemoryAccessStore` → `RetentionScorer.decide`；`auto-archive=false`（默认）→ 只报告 would-degrade/would-archive（记 info、不改）；`auto-archive=true` → 按 `MemoryLayerFormat` 重排存活事实分层 + ARCHIVE 移出 → 原子重写（保结构性段）；读/写失败降级为不改动原文件 + warn。
- [ ] 3.5 单测：`FactLayerClassifierTest`（身份/情节/时效/未知默认/大小写）；`RetentionScorerTest`（各层各信号分支/阈值 clamp/复用保级提级/不 mutate）；`DecayArchiveWriterTest`（append/点前缀路径/容错）；`MemoryLayeringDecayCuratorTest`（`@TempDir` 真 IO：dry-run 不改、auto-archive 降级+归档+原子重写、层级每趟重推导喂扁平化仍重建、节流、缺文件/空文件安全、失败不改原文件、Pinned 恒久）。

## 4. 配置 + `dailyFileRetentionDays` 暴露（`pig-agent-config`）

- [ ] 4.1 `MemoryConfig` 内嵌 `LayeringDecayConfig`（`enabled` 默认 false、`stale-after-days`、`archive-after-days`、`min-gap-minutes` clamp≥1、`reinforce-access-threshold`、`promote-access-threshold`、`auto-archive` 默认 false、`embedder-model-id` 空）+ 平铺 `daily-file-retention-days`（默认 0=不调=原生默认 90；`≤0`→不调）；getter/setter null-tolerant、非法值 clamp（镜像 `DedupConfig`/`InjectionConfig`）。
- [ ] 4.2 单测：`LayeringDecayConfigTest`（默认/YAML/缺块/clamp/null-tolerant）；`daily-file-retention-days` 默认 0、越界 clamp。

## 5. 接线（`pig-agent-cli` `AgentBootstrap`）

- [ ] 5.1 `buildMemoryConfig`：`daily-file-retention-days > 0` → `MemoryConfig.builder().dailyFileRetentionDays(n)`；0（默认）→ 不调（原生默认 90，字节等价）。
- [ ] 5.2 `build`：`layering-decay.enabled` 时经 `resolveEmbedder`（null→BM25/token-set）建 `MemoryLayeringDecayCurator`（`RecencyResolver`+`MemoryAccessStore`+`FactLayerClassifier`+`RetentionScorer`+`DecayArchiveWriter`），挂 `TaskScheduler`（`min-gap-minutes` cadence，**在 M-D `memory:dedup` 之后**跑，镜像其调度）；接线 `MemoryAccessRecorder` 到检索命中路径（`MemorySearchIndex.search`/injection，默认 no-op）；默认关 → 不建 curator、不挂 schedule、recorder no-op。
- [ ] 5.3 接线单测：`MemoryLayeringDecayWiringTest`——默认关 `buildMemoryConfig`/`build` 产出与今日一致（不调 `dailyFileRetentionDays`、无 curator/schedule）；`daily-file-retention-days>0` 到达原生 `MemoryConfig`；`layering-decay.enabled` 建 curator + 挂 schedule；`mvn -pl pig-agent-cli -am compile` 绿。

## 6. 复用 M-D 评测 harness + 衰减维度断言（`pig-agent-core` 测试支撑）

- [ ] 6.1 复用 `MemoryEvalFixture`/`MemoryQualityAssertions`（M-D，`memory/quality/eval`）：以确定性驱动跑分层/衰减，断「重要事实留存 / 无近重复 / 关键不丢」。
- [ ] 6.2 新增衰减维度断言（`MemoryDecayAssertions` 或扩 `MemoryQualityAssertions`）：`assertStaleFactsDegraded`（陈旧未复用被降级/归档）、`assertPinnedNeverArchived`（Pinned 恒久）、`assertReusedFactRetained`（复用保级）；离线确定性驱动 + `assertThatThrownBy` 证能正确 flag 违规。
- [ ] 6.3 离线 harness 结构测试（`MemoryLayeringDecayHarnessTest`）：喂 `MemoryEvalFixture` + fake recency/access → curate → 断三质量指标 + 三衰减维度，全离线不依赖真模型。

## 7. 集成测试（真模型 `*IT`，外环，延后 `/ls:itest`）

- [ ] 7.1 `MemoryDecayIT`（真模型）：喂 `MemoryEvalFixture` 固定对话跑真 flush/consolidation + 分层衰减 curate（含 recency/access 老化），用 `MemoryQualityAssertions` + 衰减断言断**真质量基线**——重要留存、陈旧降级、复用保级、关键不丢。默认模型此前 403，诚实延后。
- [ ] 7.2 `MemoryAccessSignalIT`（真模型/可选真嵌入器）：真检索命中喂 `MemoryAccessRecorder` → 复用保级的真实召回；重改写下指纹匹配稳定性观测；BM25/token-set 降级离线已覆盖。

## 8. 验收 + 文档

- [ ] 8.1 `mvn -q test` whole reactor 全绿（合并后由协调方统一跑；本内环只跑受影响模块定向测试）。
- [ ] 8.2 `mvn -pl pig-agent-cli -am compile` 绿（BUILD SUCCESS）。
- [ ] 8.3 `CLAUDE.md` 记忆段新增「记忆分层与衰减（M-C）」说明（分层归类 + recency/复用衰减 + 有原则保留 + 暴露 `dailyFileRetentionDays` + 后置 curator 不打架原生 + 默认关）+ 配置段 `memory.layering-decay` / `memory.daily-file-retention-days`。
- [ ] 8.4 归档时（`/ls:archive`）同步主 spec → `openspec/specs/memory-layering-and-decay/`。
