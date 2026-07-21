## 1. Spike（阻塞前置 — 全案最关键决策，不过不进编码）

> 目标：钉死三项的「离线可证核心 vs live 延后真质量」边界（占位校验 / 语义去重 / eval harness 结构）。结论落 `design.md §Spike`。**离线可证**的进编码组，**live 延后**的只留 IT 骨架。

- [ ] 1.1 核验原生 consolidation prompt 占位契约（javap，已初证）：`io.agentscope.harness.agent.memory.MemoryConfig$Builder` 有 `flushPrompt(String)`/`consolidationPrompt(String)`；`MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT` 含两个 `%d`、以 `String.format(consolidationPrompt, maxTokens, maxChars)` 消费；`MemoryFlushManager` **不**对 flushPrompt 做 `String.format`（flush prompt 无占位要求）。
- [ ] 1.2 写 spike 断言（离线，占位校验）：`ConsolidationPromptValidator` 纯字符串校验穷举——恰两个 `%d`=通过；0/1/3 个 `%d`、含 `%s`、含裸 `%`（如 `100%`）=不通过；`%%` 转义不计数；空/纯文本 flush prompt 的非空校验。
- [ ] 1.3 写 spike 断言（离线，语义去重）：以 `DeterministicEmbedder` + `Vectors.cosine` 与 `Bm25Index` 双通道，构造「近重复对（高相似）+ 不相似对」，证 `SemanticDeduplicator` 在阈值上确定性丢弃近重复、保留代表条、保留不相似条；无嵌入器（null）→ 走 BM25 通道仍确定性去重。
- [ ] 1.4 写 spike 断言（离线，eval harness 结构）：`MemoryEvalFixture`（固定对话 + 期望关键事实 + 期望无近重复）+ `MemoryQualityAssertions` 以**确定性驱动**（fake 抽取把标注关键事实落日志层 + 真 `SemanticDeduplicator`）跑通，证「记住/去重净/未丢」三断言框架可用（证框架、非真模型质量）。
- [ ] 1.5 结论 + 理由（含被否决备选：换更强默认 prompt / 信任坏占位让它炸 / pig 侧 LLM 二次去重 / 硬依赖真嵌入器 / 只写 IT）记入 `design.md §Spike` 与 §Decisions D1–D6；确认「真 consolidation/去重质量基线」延后 live `*IT`（默认模型此前 403，诚实延后）。

## 2. Prompt 定制 + 占位校验回退（`pig-agent-core.memory.quality` + `pig-agent-cli` `AgentBootstrap`）

- [ ] 2.1 `ConsolidationPromptValidator`（core 纯逻辑）：`isValid(prompt)` = 恰两个 `%d`、无其它裸 `%` 转换（`%%` 转义除外）；`flushPromptOk(prompt)` = 非空。null-tolerant、确定性、无模型调用。
- [ ] 2.2 改造 `AgentBootstrap.buildMemoryConfig`：读 `memory.consolidation-quality.flush-prompt`/`consolidation-prompt`；consolidation-prompt 非空且 `isValid` → `.consolidationPrompt(custom)`，非空但校验失败 → 记 warn + **回退原生默认**（不调）；flush-prompt 非空 → `.flushPrompt(custom)`；空（默认）→ 均不调（原生默认，字节等价）。
- [ ] 2.3 单测：`ConsolidationPromptValidatorTest`（穷举占位/裸 `%`/`%%`/空）；`AgentBootstrapMemoryConfigTest`（或既有 `buildMemoryConfig` 测试追加）——默认空 → 产出与今日一致（不含自定义 prompt 字段，字节等价）；合法 consolidation prompt 被采用；坏占位 consolidation prompt → 回退默认 + warn（不抛）；合法 flush prompt 被采用。

## 3. 语义去重后置 curator（`pig-agent-core.memory.quality`）

- [ ] 3.1 `SemanticDeduplicator`（core 纯逻辑 Strategy）：入参事实单元列表 + 相似度阈值 + 可选 `Embedder`（null→BM25 通道）；两两相似度（`Embedder`!=null → `Vectors.cosine`，否则用 `io.pigagent.core.search.Bm25Index` 归一化相似度）；超阈值近重复分组、确定性保留代表条（写死 tie-break：更长/更靠后）、丢其余；返回去重后列表（不 mutate 入参，immutable 输出）。
- [ ] 3.2 事实单元切分（core 纯逻辑）：把 `MEMORY.md` 切成事实单元（复用 `MemoryCorpusLoader` 式 Markdown 分块或轻量 bullet/段落切分），**保留分层格式契约标记**（D6：不打散约定的分层标题段）。
- [ ] 3.3 `MemoryConsolidationCurator`（core 后置服务，镜像 `ProfileConsolidationService`）：`maybeCurate()` = 节流（`Duration` min-gap，自节流）→ 读 `MEMORY.md`（快照）→ 切分 → `SemanticDeduplicator` → 若有变更则**原子重写**（临时文件 + 原子移动，保留分层标记）；读/写失败 → 降级为**不改动原文件** + 记 warn（绝不崩、绝不抛）。嵌入器经构造注入（可为 null）。
- [ ] 3.4 单测：`SemanticDeduplicatorTest`（近重复去重/保代表/不相似保留/空/单条/阈值边界；`DeterministicEmbedder` 与 null→BM25 两通道确定性）；`MemoryConsolidationCuratorTest`（节流不重复跑；`@TempDir` 真文件 IO 去重后原子重写；读/写失败原文件保留不崩；无变更不重写；保留分层标记）。

## 4. 记忆质量评测 harness（`pig-agent-core` 测试支撑，通用/供 M-C 复用）

- [ ] 4.1 `MemoryEvalFixture`（测试支撑值对象）：固定对话（`List<Msg>` 或简化转录）+ 期望被记住的关键事实集 + 期望无近重复集；提供 1–2 个代表性 fixture（含个人事实 + 潜在近重复）。
- [ ] 4.2 `MemoryQualityAssertions`（测试支撑）：`assertKeyFactsRemembered(memoryMd, fixture)` / `assertNoNearDuplicates(memoryMd, threshold)`（复用 `SemanticDeduplicator` 判残留近重复）/ `assertNoKeyFactLost(before, after, fixture)`（去重前后关键事实未被误杀）。
- [ ] 4.3 离线 harness 结构测试：以**确定性驱动**（fake 抽取落关键事实到日志层/固化层 + 真 `SemanticDeduplicator`）跑 fixture，断三指标——证 harness 与断言框架可用（非真模型质量）。**通用化**：fixture/断言不绑死去重，M-C 可复用同一套跑分层衰减。
- [ ] 4.4 供 M-C 复用登记：harness 类置于可被后续 change 复用的测试支撑包，`design.md §交叉依赖` 登记供给关系。

## 5. 配置 + 分层格式契约（`pig-agent-config` + `pig-agent-core`）

- [ ] 5.1 `MemoryConfig` 内嵌 `ConsolidationQualityConfig` 块：`flush-prompt`/`consolidation-prompt`（默认空）、`dedup` 子块（`enabled` 默认 **false**、`similarity-threshold` 默认保守偏高如 0.9、`embedder-model-id` 空→BM25、`min-gap-minutes` 节流默认如 60）；getter/setter null-tolerant、非法值 clamp（顶层 `@JsonIgnoreProperties(ignoreUnknown=true)` 已由 config-resilience 提供）。命名避开既有 flat `memory.consolidation-min-gap-minutes`/`consolidation-max-tokens`（不动）。
- [ ] 5.2 `MEMORY.md` 分层格式契约值对象（core，如 `MemoryLayerFormat`）：定义约定的分层/分段标记（常驻核心 / 一般 / 易失近期）+ 解析/校验帮助；**向后兼容**——旧无分层 `MEMORY.md` 缺标记 → 视为单层，不报错。**只定义契约 + 兼容读取，不建衰减**（衰减属 M-C）。
- [ ] 5.3 单测：`ConsolidationQualityConfigTest`（默认值、YAML 解析、缺块用默认、null-tolerant、clamp）；`MemoryLayerFormatTest`（分层解析、缺标记降级单层、向后兼容）。

## 6. 接线（`pig-agent-cli` `AgentBootstrap`）

- [ ] 6.1 `AgentBootstrap.build`：`memory.consolidation-quality.dedup.enabled` 时——经 `resolveEmbedder(dedup.embedder-model-id, modelManager)` 取嵌入器（null→BM25）建 `MemoryConsolidationCurator`，挂 `TaskScheduler`（`min-gap-minutes` cadence，镜像 profile consolidation / outreach briefing 接线）；默认关 → 不建 curator、不挂 schedule。
- [ ] 6.2 接线单测：`MemoryConsolidationCuratorWiringTest`——`dedup.enabled=false` → 不建 curator/不挂 schedule；`enabled=true` → 建可用 curator（空 `MEMORY.md` 不抛、无嵌入器 BM25 可跑）。既有 `AgentFactoryTest`/`buildMemoryConfig`/`CrossSessionMemoryTest` 仍绿（默认路径字节等价）。

## 7. 集成测试（真模型 `*IT`，外环，延后 `/ls:itest`）

- [ ] 7.1 `MemoryQualityIT`（真模型）：喂 `MemoryEvalFixture` 的固定对话，跑真 flush/consolidation（含可选自定义 prompt），用 `MemoryQualityAssertions` 断**真质量基线**——关键事实被记住、去重干净、关键信息未丢。默认模型此前 403，诚实延后。
- [ ] 7.2 `SemanticDedupIT`（真模型/可选真嵌入器）：真嵌入器下的语义去重召回（「换个说法」的近重复被抓）、误杀率观测；BM25-only 离线已覆盖，真语义质量延后。

## 8. 验收 + 文档

- [ ] 8.1 `mvn -q test` whole reactor 全绿（离线单测）。
- [ ] 8.2 `mvn -pl pig-agent-cli -am compile` 绿。
- [ ] 8.3 `CLAUDE.md` 记忆段落新增「consolidation 质量层（prompt 定制 + 语义去重 curator + 评测 harness + 分层格式契约）」说明 + 配置段 `memory.consolidation-quality` 同步。
- [ ] 8.4 归档时（`/ls:archive`）同步主 spec → `openspec/specs/memory-consolidation-quality/`（本 change 的 `openspec validate --strict` 已在 spec 阶段通过）。
