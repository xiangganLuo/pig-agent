## Why

pig 现在把**整份 `MEMORY.md` 无差别塞进 system prompt**（`NativeMemoryContextMiddleware.onSystemPrompt` 一次性注入全文），随记忆增长最坏每回合 ~4000 token 常驻上下文——既烧 token，又让「大部分与当前问题无关的旧事实」挤占推理窗口。行业蓝本（RAG 式记忆）的做法是**按需相关注入**：只把稳定的身份/强偏好常驻，其余事实按当前问题**相关性检索 top-K** 再临时注入。

本 spec 是内核路线图 **Wave-1 的 M-A「检索即注入」**，把长期记忆注入从「全量常驻」改为「pinned 常驻 + query-aware 按需」。**承重红线是 prefix-cache**：query 相关的内容一旦进入被缓存的 system-prompt 前缀，会导致每回合 cache miss——本 spec 的首要决策就是钉死注入切分，保住 prefix-cache 稳定性。检索复用既有 `MemorySearchIndex` 门面（**BM25 即可、零嵌入依赖、离线可测**）。

## What Changes

- **改造 `NativeMemoryContextMiddleware`（`pig-agent-core` `io.pigagent.core.memory`）为两段式注入**：
  - **`onSystemPrompt` → 只注入 pinned 核心**（身份/强偏好等稳定内容）：一个**有界、确定性、query-无关**的记忆子集常驻 system prompt——**留在被缓存的 prefix 里**，会话内字节稳定，仅在 pinned 内容本身变化时才变一次（可接受的偶发 cache-miss，与今日 consolidation 触发一致）。
  - **`onReasoning` → query-aware top-K 相关事实按 ephemeral 非缓存位注入**：以当前 user 消息为查询，经 `MemorySearchIndex.search(query, topK)` 检索 top-K 相关事实，作为**trailing user-side `Msg`** 追加到一个**新的 `ReasoningInput`**（每步重建、绝不写回历史）——**复用现有 ephemeral 注入路径**（`LoopDetectionMiddleware.maybeResetAndInject` 沿用的、原 `EphemeralMemoryMiddleware` 的 trailing-`Msg` 机制）。该位置在 system prompt 之后，**不属于缓存前缀**，故 query-dependent 内容随回合变化而不触发前缀 cache miss。
- **检索复用既有门面**：调用 `MemorySearchIndex.search(query, topK)`（稳定公共门面）；**BM25-only 即可**（embedder = null 优雅降级），零嵌入依赖、离线全绿。语料源 = `pa-memory-native` 的 `MEMORY.md` + `memory/*.md`（复用 `MemoryCorpusLoader`）。
- **新增 config `memory.injection` 块**（全可选、默认安全）：`enabled`（**默认 false** = 今日「全量 `MEMORY.md` 进 system prompt、无 ephemeral 注入」逐字节不变）、`top-k`、`pinned` 子块（`source` + `max-chars`）、可选 `embedder-model-id`（空 → BM25-only）。
- **默认关即今日行为**：`memory.injection.enabled=false` → `onSystemPrompt` 仍注入整份 `MEMORY.md`、`onReasoning` 为恒等——**逐字节等于本 spec 引入前**。

**非破坏**：默认关；启用后 `pa-memory-native` 的记忆库/写路径（flush/consolidation）不受影响，仅**注入位置与切分**改变。

## Capabilities

### New Capabilities
- `memory-retrieval-injection`: 长期记忆的 **RAG 式按需注入**——pinned 核心常驻 system prompt（进缓存前缀、字节稳定），query-aware top-K 相关事实以 ephemeral 非缓存位（trailing reasoning message、每步重建不持久化）按需注入；query-dependent 内容**绝不进入被缓存的 system-prompt 前缀**（保住 prefix-cache 稳定性）。检索经既有 `MemorySearchIndex` 稳定门面（BM25-only 即可、离线可测）；config 门控、默认关（今日全量注入不变）。

### Modified Capabilities
<!-- 无。本能力是 pa-memory-native 之上的独立叠加：默认关时其注入行为逐字节等于今日，故不改写 pa-memory-native 主 spec 的既有 Requirement（复用其记忆库为检索语料源、不动其 flush/consolidation 写路径）。此策略与已归档的 hybrid-memory-search 一致。 -->

## Impact

- **代码**：`pig-agent-core`（改造 `memory/NativeMemoryContextMiddleware`：`onSystemPrompt` 只注 pinned、新增 `onReasoning` 做 query-aware ephemeral 注入；新增纯逻辑值对象/策略 `memory/injection/{PinnedSelector, RetrievedFactsFormatter, MemoryInjectionSettings}` 之类——具体命名以 design 为准）；`pig-agent-config`（`MemoryConfig` 内嵌 `InjectionConfig` 块）；`pig-agent-cli`（`AgentBootstrap`：`memory.injection.enabled` 时为注入中间件构建/注入一个 `MemorySearchIndex`（BM25-only 或按 `embedder-model-id`），并传入 pinned/top-k 设置；默认关 → 沿用今日的全量注入构造）。
- **不改**：`pa-memory-native` 记忆库/flush/consolidation 写路径（复用不改）；`user-profile`（`USER.md` 仍由 `UserProfileContextMiddleware` 独立注入 system prompt——身份天然已在缓存前缀，与本能力互补，不动）；A5 压缩；权限/沙箱/渠道 fail-closed。
- **交叉依赖**（详见 `design.md`）：与 **R0（shared-retrieval-primitive）** 协调——R0 并行重构 `MemorySearchIndex` 内部，本 spec **只依赖其稳定公共门面** `search(query)→results`，同期落地则 R0 先合；与**技能线（X3）**边界——本 spec 只处理**声明性事实**的检索注入，程序性「怎么做」归 `SKILL.md`，不在此。
- **测试**：离线单测——**Group 1 承重 spike**：证明注入切分保住 prefix-cache（`onSystemPrompt` 对不同 query 字节不变、会话内稳定；query-aware 内容只出现在 trailing ephemeral `Msg`、不进 system prompt、不写回历史）。其后：pinned 选取（有界/确定性/heading 缺失降级为空）；query-aware 检索走门面（top-K、空 query/空结果不注入）；ephemeral `Msg` 每步重建不持久化；`enabled=false` 逐字节等于今日全量注入；config 默认/YAML/clamp。**真实相关性质量（真嵌入器）→ `*IT`（`/ls:itest`），本 spec BM25-only 全离线可证**。
- **文档**：`CLAUDE.md` 记忆段落新增「检索即注入（pinned + query-aware ephemeral）」说明 + 配置段 `memory.injection` 同步。
