## 1. Spike（阻塞前置 — 全案最关键决策，不过不进编码）

> 目标：钉死「注入切分保住 prefix-cache」的承重假设——pinned 进 `onSystemPrompt`（缓存前缀）、query-aware 进 `onReasoning` trailing ephemeral `Msg`（非前缀、不持久化）。结论落 `design.md §Spike`。

- [ ] 1.1 核验 AgentScope 2.0 中间件两个注入位与 prefix-cache 的关系：`onSystemPrompt` 产出即 system prompt（缓存前缀的一部分）、`onReasoning` 的 `ReasoningInput.messages()` 位于 system prompt 之后（javap/源码核验 `ReasoningInput(messages, tools, options)` record + 中间件按列表位置左到右组合）。
- [ ] 1.2 核验 `onSystemPrompt` 签名**不含**当前 query（仅 `currentPrompt`）→ pinned 注入结构上无法依赖 query → 对不同 query 字节恒等（结构不变式，无需 provider 真机）。
- [ ] 1.3 核验 ephemeral trailing-`Msg` precedent：`LoopDetectionMiddleware.maybeResetAndInject` 构造新 `ReasoningInput(augmented, tools, options)`、追加 user-side `Msg`、不 mutate 入参、不写回历史；确认可原样复用于 query-aware 注入。
- [ ] 1.4 写 spike 断言（离线，允许临时/一次性测试）：(a) 同一 pinned 下两个不同 query 触发 `onSystemPrompt` → prompt 字节相同；(b) query-aware 事实只出现在 `onReasoning` 产出的 trailing `Msg`、不出现在 system prompt；(c) 入参 `messages()` 未被 mutate、注入每步重建。**spike 不过则停下升级人工**。
- [ ] 1.5 结论 + 理由（含被否决备选：全放 system prompt / tool_call 自检索 / 全量常驻）记入 `design.md §Spike` 与 §Decisions D1。

## 2. pinned 选取 + system prompt 注入（`pig-agent-core.memory` / `memory.injection`）

- [ ] 2.1 `MemoryInjectionSettings`（core 不可变值对象：`enabled`/`topK`/`pinnedSource`/`pinnedHeading`/`pinnedMaxChars`/`embedderModelId`；非法值 clamp、null-tolerant；镜像 `MemorySearchConfig`）。
- [ ] 2.2 `PinnedSelector`（纯逻辑 Strategy）：默认从 `MEMORY.md` 的配置化 pinned heading 段确定性提取、按 `maxChars` 截断；缺失 → 空 pinned；另留 `head`（前 N 字符）兜底来源。
- [ ] 2.3 改造 `NativeMemoryContextMiddleware.onSystemPrompt`：`enabled=true` → 只注入 `PinnedSelector` 产出的 pinned 块（query-无关、有界）；`enabled=false` → 保留今日「注入整份 `MEMORY.md`」分支不动（字节等价）。
- [ ] 2.4 单测：`PinnedSelectorTest`（heading 段提取、`maxChars` 截断、heading 缺失降级为空、head 兜底、确定性）；`NativeMemoryContextMiddlewareTest` 追加——`enabled=true` 只注 pinned、对不同 query 字节恒等、会话内稳定；`enabled=false` 逐字节等于今日全量注入。

## 3. query-aware ephemeral 注入（`pig-agent-core.memory`）

- [ ] 3.1 `RetrievedFactsFormatter`（纯逻辑）：`List<MemoryDocument>` → 一条 trailing user-side `Msg`（`name="memory_retrieval"`）的文本格式化；空表 → 无注入。
- [ ] 3.2 pinned/检索去重（纯逻辑）：query-aware 结果排除 pinned 已含内容（按来源/文本），避免双重注入（D5）。
- [ ] 3.3 新增 `NativeMemoryContextMiddleware.onReasoning`：取尾部当前 USER 消息为 query → `MemorySearchIndex.search(query, topK)`（**只调稳定公共门面**，BM25-only 即可）→ 去重 → `RetrievedFactsFormatter` → 追加进**新的** `ReasoningInput(messages + [msg], tools, options)` 交 `next`；空 query/空结果/`enabled=false` → `next(input)` 原样（恒等）；绝不 mutate 入参、不写回历史（复用 `LoopDetectionMiddleware` 机制）。
- [ ] 3.4 单测：不同 query → 不同 trailing `Msg`、内容不进 system prompt；空 query/空结果 → 无注入且入参未改；注入不写回历史（入参 `messages()` 未被 mutate、每步重建）；去重（同一事实不双注）；BM25-only（embedder=null）经门面出结果不抛。

## 4. 配置（`pig-agent-config`）

- [ ] 4.1 `MemoryConfig` 内嵌 `InjectionConfig`（`enabled` 默认 **false**、`top-k` 默认 6、`pinned.source`/`pinned.heading`/`pinned.max-chars` 默认 800、`embedder-model-id` 空）；getter/setter null-tolerant、`@JsonIgnoreProperties(ignoreUnknown=true)` 契合 config-resilience。
- [ ] 4.2 单测：`MemoryConfigTest`/`InjectionConfigTest`——默认值、YAML 解析、缺块用默认、非法值 clamp。

## 5. 接线（`pig-agent-cli` `AgentBootstrap`）

- [ ] 5.1 `AgentBootstrap`：`memory.injection.enabled` 时为 `NativeMemoryContextMiddleware` 注入一个 `MemorySearchIndex`（语料 = workspace `MEMORY.md` + `memory/`；`embedder-model-id` → `resolveStoredModel` → 真嵌入器，或空 → BM25-only）+ pinned/top-k 设置；默认关 → 沿用今日的全量注入构造（不建索引、`onReasoning` 恒等）。
- [ ] 5.2 确认 interactive/channel/peer 三条注入轨一致（本能力对渠道非交互轨同样默认关；启用语义一致）。
- [ ] 5.3 单测/接线校验：`enabled=false` 时 `AgentBootstrap` 产出与今日一致（不引入索引、注入行为字节等价）；`enabled=true` 时中间件持有索引且切分生效。

## 6. 集成测试（真模型 `*IT`，外环，延后 `/ls:itest`）

- [ ] 6.1 `MemoryRetrievalInjectionIT`（真模型/可选真嵌入器）：跨回合观察——pinned 常驻、query-aware 按当前问题召回相关事实并注入；断言 system-prompt 前缀逐回合稳定（prefix-cache 结构不变式的真机侧观测）、召回相关性（延后 `/ls:itest`）。

## 7. 验收 + 文档

- [ ] 7.1 `mvn -q test` whole reactor 全绿（报告数目 + 新增测试数）。
- [ ] 7.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [ ] 7.3 `CLAUDE.md` 记忆段落新增「检索即注入（pinned 常驻 + query-aware ephemeral）」说明 + 配置段 `memory.injection` 同步。
- [ ] 7.4 `openspec validate memory-retrieval-injection --strict` 通过；归档时（`/ls:archive`）同步主 spec → `openspec/specs/memory-retrieval-injection/`。
