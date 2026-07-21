## 1. Spike（阻塞前置 — 全案最关键决策，不过不进编码）

> 目标：钉死三项的「离线可证核心 vs live 延后真质量」边界（占位校验 / 语义去重 / eval harness 结构）。结论落 `design.md §Spike`。**离线可证**的进编码组，**live 延后**的只留 IT 骨架。

- [x] 1.1 核验原生 consolidation prompt 占位契约（javap）：`io.agentscope.harness.agent.memory.MemoryConfig$Builder` 有 `flushPrompt(String)`/`consolidationPrompt(String)`；`MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT` 含两个 `%d`、以 `String.format(consolidationPrompt, maxTokens, maxChars)` 消费；`MemoryFlushManager` **不**对 flushPrompt 做 `String.format`（flush prompt 无占位要求）。
- [x] 1.2 写 spike 断言（离线，占位校验）：`ConsolidationPromptValidator` 纯字符串校验穷举——恰两个 `%d`=通过；0/1/3 个 `%d`、含 `%s`、含裸 `%`（如 `100%`）=不通过；`%%` 转义不计数；空/纯文本 flush prompt 的非空校验。（`ConsolidationPromptValidatorTest` 11 绿）
- [x] 1.3 写 spike 断言（离线，语义去重）：以 `DeterministicEmbedder` + `Vectors.cosine` 与 `Bm25Index` 双通道，构造「近重复对 + 不相似对」，证 `SemanticDeduplicator` 阈值上确定性丢弃近重复、保留代表条、保留不相似条；无嵌入器（null）→ 走 BM25 通道仍确定性去重。（`SemanticDeduplicatorTest` 9 绿）
- [x] 1.4 写 spike 断言（离线，eval harness 结构）：`MemoryEvalFixture` + `MemoryQualityAssertions` 以**确定性驱动**（fake 抽取 + 真 `SemanticDeduplicator`）跑通，证「记住/去重净/未丢」三断言框架可用。（`MemoryQualityHarnessTest` 2 绿）
- [x] 1.5 结论 + 理由（含被否决备选）记入 `design.md §Spike` 与 §Decisions D1–D6；确认「真 consolidation/去重质量基线」延后 live `*IT`（默认模型此前 403）。

## 2. Prompt 定制 + 占位校验回退（`pig-agent-core.memory.quality` + `pig-agent-cli` `AgentBootstrap`）

- [x] 2.1 `ConsolidationPromptValidator`（core 纯逻辑）：`isValidConsolidationPrompt` = 恰两个 `%d`、无其它裸 `%`（`%%` 除外）；`isValidFlushPrompt` = 非空。null-tolerant、确定性、无模型调用。
- [x] 2.2 改造 `AgentBootstrap.buildMemoryConfig`：consolidation-prompt 非空且 `isValid` → `.consolidationPrompt(custom)`，非空但校验失败 → 记 warn + 回退原生默认（不调）；flush-prompt 非空 → `.flushPrompt(custom)`；空（默认）→ 均不调（原生默认，字节等价）。
- [x] 2.3 单测：`ConsolidationPromptValidatorTest`（穷举占位/裸 `%`/`%%`/空）；`MemoryConsolidationQualityWiringTest`——默认空 → prompt 字段为 null（原生默认）；合法 consolidation prompt 被采用；坏占位 → 回退 null（不抛）；合法 flush prompt 被采用。

## 3. 语义去重后置 curator（`pig-agent-core.memory.quality`）

- [x] 3.1 `SemanticDeduplicator`（core 纯逻辑 Strategy）：可选 `Embedder`（null→BM25 通道）；两两相似度（`Vectors.cosine` 或 `Bm25Index` 自归一化）；超阈值 union-find 分组、确定性保留代表条（最长/更靠后）、`survivingIndices`/`dedup`；不 mutate 入参。
- [x] 3.2 事实单元切分（core 纯逻辑）：`FactUnitSplitter` 把 `MEMORY.md` 切成 fact vs 结构性段（heading/blank 保留），**保留分层格式契约标记**（D6）。
- [x] 3.3 `MemoryConsolidationCurator`（core 后置服务，镜像 `ProfileConsolidationService`）：`maybeCurate()` 节流 → 读 `MEMORY.md` → 切分 → 去重 → 有变更则原子重写（temp + `ATOMIC_MOVE`，保留分层标记）；读/写失败降级为不改动原文件 + warn（不崩）。
- [x] 3.4 单测：`SemanticDeduplicatorTest`（近重复/保代表/不相似/空/单条/阈值 clamp/BM25 通道）；`FactUnitSplitterTest`；`MemoryConsolidationCuratorTest`（`@TempDir` 真 IO 去重后原子重写、无变更不重写、节流、缺文件/空文件/单事实安全）。

## 4. 记忆质量评测 harness（`pig-agent-core` 测试支撑，通用/供 M-C 复用）

- [x] 4.1 `MemoryEvalFixture`（测试支撑）：固定对话 + 期望关键事实集 + 去重阈值；`personalFactsWithDuplicate` 代表性 fixture。
- [x] 4.2 `MemoryQualityAssertions`（测试支撑）：`assertKeyFactsRemembered`/`assertNoNearDuplicates`（复用 `SemanticDeduplicator`）/`assertNoKeyFactLost`。
- [x] 4.3 离线 harness 结构测试（`MemoryQualityHarnessTest`）：确定性驱动跑 fixture、断三指标 + 断言框架能正确 flag 违规（`assertThatThrownBy`）。
- [x] 4.4 供 M-C 复用登记：harness 置于 `memory/quality/eval` 测试支撑包，`design.md §交叉依赖` 登记供给关系。

## 5. 配置 + 分层格式契约（`pig-agent-config` + `pig-agent-core`）

- [x] 5.1 `MemoryConfig` 内嵌 `ConsolidationQualityConfig`（`flush-prompt`/`consolidation-prompt` 默认空）+ `DedupConfig`（`enabled` 默认 false、`similarity-threshold` 默认 0.9 clamp `[0,1]`、`embedder-model-id` 空、`min-gap-minutes` 默认 60 clamp≥1）；getter/setter null-tolerant。
- [x] 5.2 `MemoryLayerFormat`（core）：定义 Pinned/General/Recent 分层契约 + `parse`；**向后兼容**——旧无分层 → 单层 GENERAL，不报错。**只定义契约 + 兼容读取，不建衰减**（属 M-C）。
- [x] 5.3 单测：`ConsolidationQualityConfigTest`（默认/YAML/缺块/clamp/null-tolerant）；`MemoryLayerFormatTest`（分层解析、缺标记降级单层、未知标题当内容、大小写不敏感）。

## 6. 接线（`pig-agent-cli` `AgentBootstrap`）

- [x] 6.1 `AgentBootstrap.build`：`dedup.enabled` 时经 `resolveEmbedder`（null→BM25）建 `MemoryConsolidationCurator`，挂 `TaskScheduler`（`min-gap-minutes` cadence，镜像 profile consolidation）；默认关 → 不建 curator、不挂 schedule。
- [x] 6.2 接线单测：`MemoryConsolidationQualityWiringTest`——默认不注入 prompt（原生默认）、合法采用、坏占位回退、flush 采用；`mvn -pl pig-agent-cli -am compile` 绿。

## 7. 集成测试（真模型 `*IT`，外环，延后 `/ls:itest`）

- [ ] 7.1 `MemoryQualityIT`（真模型）：喂 `MemoryEvalFixture` 固定对话，跑真 flush/consolidation（含可选自定义 prompt），用 `MemoryQualityAssertions` 断**真质量基线**——记住/去重净/未丢。默认模型此前 403，诚实延后。
- [ ] 7.2 `SemanticDedupIT`（真模型/可选真嵌入器）：真嵌入器下的语义去重召回（换说法近重复被抓）、误杀率观测；BM25-only 离线已覆盖。

## 8. 验收 + 文档

- [ ] 8.1 `mvn -q test` whole reactor 全绿（合并后由协调方统一跑；本内环只跑受影响模块定向测试）。
- [x] 8.2 `mvn -pl pig-agent-cli -am compile` 绿（BUILD SUCCESS）。
- [x] 8.3 `CLAUDE.md` 记忆段落新增「consolidation 质量层（prompt 定制 + 语义去重 curator + eval harness + 分层格式契约）」说明 + 配置段 `memory.consolidation-quality`。
- [ ] 8.4 归档时（`/ls:archive`）同步主 spec → `openspec/specs/memory-consolidation-quality/`。
