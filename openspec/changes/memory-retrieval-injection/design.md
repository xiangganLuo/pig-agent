## Context

pig 记忆现状（`pa-memory-native` 已在 main）：工作区级两层记忆——日志层 `memory/YYYY-MM-DD.md`（flush 追加）→ 固化层 `MEMORY.md`（consolidation 去重重写）。固化层的**注入**由 pig 自有的 `NativeMemoryContextMiddleware` 承担：其 `onSystemPrompt` **一次性把整份 `MEMORY.md` 原文追加进 system prompt**（`base + "## Long-term Memory (MEMORY.md)" + 全文`），其余中间件阶段均为恒等。这在 `pa-memory-native` 里是**有意的 prefix-cache 友好设计**：`MEMORY.md` 在一次会话内稳定（consolidation 后台节流），故 system prompt 逐回合字节稳定、命中缓存前缀，仅 consolidation 重写后偶发一次 cache-miss。

代价：随记忆增长，**整份 `MEMORY.md` 常驻**，最坏每回合 ~4000 token——其中大部分与当前问题无关。RAG 式记忆的标准解法是**按相关性只注入 top-K**。但直接把「按 query 检索出的 top-K」塞进 system prompt 会**摧毁 prefix-cache**：query 每回合不同 → 前缀每回合不同 → 每回合 cache miss。因此本 spec 的核心不是「检索」（`MemorySearchIndex` 门面已存在），而是**注入切分**：哪部分留在缓存前缀、哪部分走非缓存位。

既有可复用件：
- `MemorySearchIndex.search(String query, int topK) → List<MemoryDocument>`（`io.pigagent.core.memory.search`，`hybrid-memory-search` 引入的稳定公共门面）——BM25 纯 Java 离线可算，embedder=null 时优雅降级 BM25-only，`search` 永不抛（空 query/空语料返回空表）。
- `MemoryCorpusLoader`：读 `MEMORY.md` + `memory/*.md` + `USER.md`，Markdown 分块、容错。
- **ephemeral trailing-`Msg` 注入路径**：`EphemeralMemoryMiddleware` 已在 `pa-memory-native` 退役，但其机制被 `LoopDetectionMiddleware.maybeResetAndInject` 沿用——在 `onReasoning` 里构造**新的 `ReasoningInput(augmentedMessages, tools, options)`**，把一条 user-side `Msg` 追加到消息尾部，**每步重建、绝不写回持久化历史**。这正是 query-aware 事实要走的注入位。

## Goals / Non-Goals

**Goals**
- 长期记忆注入切分为两段：**pinned 核心**常驻 system prompt（进缓存前缀、会话内字节稳定）；**query-aware top-K 相关事实**以 ephemeral 非缓存位（trailing reasoning `Msg`、每步重建不持久化）按需注入。
- **保住 prefix-cache 稳定性**（承重红线）：query-dependent 内容 MUST NOT 进入被缓存的 system-prompt 前缀。
- 检索复用既有 `MemorySearchIndex.search(query, topK)` 稳定门面；**BM25-only 即可**（零嵌入依赖、离线全绿）。
- Config `memory.injection` 块，**默认关**（今日全量 `MEMORY.md` 注入逐字节不变）。
- pinned 选取纯逻辑、确定性、有界；缺失时安全降级。

**Non-Goals**
- 真实嵌入器/语义相关性质量的离线验证（属 live-model，交 `/ls:itest`；本 spec BM25-only 已全离线可证）。
- 重构 `MemorySearchIndex` 内部（属 **R0 shared-retrieval-primitive**；本 spec 只依赖其公共门面）。
- 改 `pa-memory-native` 记忆库/flush/consolidation 写路径、`user-profile` 的 `USER.md` 注入、A5 压缩、权限/沙箱/渠道语义。
- 程序性「怎么做」的检索注入（属技能线 X3 的 `SKILL.md`；本 spec 只处理**声明性事实**）。
- 调优 consolidation 让其产出稳定 pinned 段（属 `pa-memory-native` 的 prompt 调优，本 spec 只做「若存在则 pin、缺失则降级」的确定性读取）。

## Spike（阻塞前置 Task 0 — 全案最关键决策）

> **承重假设**：AgentScope 2.0 中间件的两个注入位分处 prefix-cache 的两侧——`onSystemPrompt` 产出的内容进入被缓存的 system-prompt 前缀，`onReasoning` 追加到消息尾部的 `Msg` 位于前缀之后、随回合变化且不持久化。若此假设不成立，则整个「pinned/query-aware 切分」失效。spike 必须先钉死它，不过不进编码。

**S1 — 两个注入位与 prefix-cache 的关系（结构不变式）**
- `onSystemPrompt(agent, ctx, currentPrompt) → Mono<String>`：其返回值即模型请求的 system prompt。provider 侧（如 Anthropic prompt caching）缓存的是**请求前缀直到缓存断点**，system prompt 是前缀的一部分。故 **pinned 核心（放 `onSystemPrompt`）= 缓存前缀内容**。
- `onReasoning(agent, ctx, ReasoningInput input, next)`：`ReasoningInput.messages()` 是本推理步的消息列表，位于 system prompt **之后**。往其尾部追加一条 `Msg` = 追加到前缀之后的**非前缀位**。故 **query-aware 事实（放 `onReasoning` trailing `Msg`）= 不进缓存前缀**。
- **离线可证的结构不变式**（不依赖 provider 真机）：
  - (a) `onSystemPrompt` 的入参**不含**当前 user query（签名只有 `currentPrompt`）→ pinned 注入**结构上无法**依赖 query → 对不同 query 字节恒等。
  - (b) query-aware 事实的字节**只出现在** `onReasoning` 产出的新 `ReasoningInput` 的 trailing `Msg` 中，**不出现在** `onSystemPrompt` 产出的 prompt 中。
  - (c) trailing `Msg` 注入**不写回持久化历史**：`onReasoning` 构造新 `ReasoningInput` 交给 `next`，入参 `input.messages()` 不被 mutate；该注入每推理步重建（ephemeral）。
- **precedent 核验**：`LoopDetectionMiddleware.maybeResetAndInject` 已在生产用同一机制（新 `ReasoningInput` + trailing user-side `Msg`，注释明确「ephemeral injection, never written back to history」），且 `pa-memory-native` 明确「原生长期记忆注入的是 system prompt（不是 reasoning 列表）」——两条注入位互不干扰。javap/源码核验 `ReasoningInput` 为 `(messages, tools, options)` record、中间件按列表位置左到右组合。

**S2 — pinned 核心的确定性来源**
- 需要一个 **query-无关、确定性、有界** 的 pinned 子集。核验可选来源：`MEMORY.md` 的 heading 标记段（如 `## Pinned`/身份段）可由纯字符串扫描确定性提取；缺失时降级为空（检索覆盖全部）。`USER.md` 身份已由 `UserProfileContextMiddleware` 独立注入 system prompt（天然在缓存前缀），与本能力互补、不重复接管。
- **结论**：pinned 选取用**纯逻辑 `PinnedSelector`**（确定性、可单测、无模型调用），默认从 `MEMORY.md` 的配置化 pinned heading 段提取、`max-chars` 截断、缺失降级为空。

**Spike 净结论**：走**主路径**——切分成立且离线可证：pinned 放 `onSystemPrompt`（缓存前缀、结构上 query-无关）、query-aware 放 `onReasoning` trailing ephemeral `Msg`（非前缀、每步重建不持久化）。唯一 provider 真机相关件（真实 prefix-cache 命中率、真实语义召回质量）延后 `/ls:itest`；本 spec 证明的是**结构不变式**（query-dependent 字节永不进 `onSystemPrompt` 产出），这是保住 prefix-cache 的充分条件，且完全离线可证。

## Decisions

- **D1 —（首条，承重）注入切分保住 prefix-cache：pinned 进 `onSystemPrompt`、query-aware 进 `onReasoning` trailing ephemeral `Msg`。** query-dependent 内容 **MUST NOT** 进入 `onSystemPrompt` 产出的 system prompt（缓存前缀）。理由：system prompt 是 provider 缓存前缀的一部分，每回合 query 不同；若把检索结果塞进 system prompt，前缀逐回合变 → 每回合 cache miss（正是本 spec 要避免的反效果）。切分后：pinned 因 `onSystemPrompt` 结构上拿不到 query 而对不同 query 字节恒等（会话内稳定、仅 pinned 内容变化时变一次）；query-aware 走 `onReasoning` 追加到消息尾部的 ephemeral `Msg`，位于前缀之后、每步重建不持久化，随回合变化而**不触碰前缀**。**备选**：(a) 全放 system prompt「按 query 拼」——否决（摧毁 prefix-cache）；(b) 用 tool_call 让模型自己检索——否决（多一轮往返、且非「注入即用」）；(c) 全量常驻（今日）——正是要优化的对象。切分是唯一同时满足「相关注入」与「前缀稳定」的方案。
- **D2 — query-aware 注入复用 ephemeral trailing-`Msg` 路径（不新造机制）。** `NativeMemoryContextMiddleware.onReasoning` 沿用 `LoopDetectionMiddleware` 已在生产验证的机制：取 `input.messages()` 尾部的当前 user 消息为 query → `index.search(query, topK)` → 若非空，构造 `RetrievedFactsFormatter` 格式化的一条 user-side `Msg`（`name="memory_retrieval"`）追加进**新的** `ReasoningInput(augmented, tools, options)` 交 `next`；空 query/空结果/禁用 → 原样返回 `input`。**绝不 mutate 入参、绝不写回历史**。**备选**：新建一个独立中间件——否决（与 pinned 注入同属「记忆注入」，合在一个中间件更内聚，且切分逻辑需要两个阶段协同）。
- **D3 — pinned 选取 = 纯逻辑 `PinnedSelector` Strategy，默认 heading 段、有界、缺失降级为空。** 确定性（无模型调用、可单测）；默认从 `MEMORY.md` 的配置化 pinned heading（`pinned.source` 指定，默认约定一个 heading 名）提取该段，按 `pinned.max-chars` 截断；heading 缺失 → pinned 为空（此时 system prompt 无记忆段，全部事实走 query-aware 检索）。**备选**：(a) pin 整份 `MEMORY.md` head 前 N 字符——保留为可配 `source` 选项（总能出内容，但可能 pin 到无关内容）；(b) 让 LLM 选 pinned——否决（引入模型调用 + 破坏确定性/字节稳定）。默认策略确定性、可控、可测。
- **D4 — 检索只依赖 `MemorySearchIndex.search(query, topK)` 公共门面，BM25-only 即可。** 本 spec **不碰** `MemorySearchIndex` 内部（BM25/向量/loader 实现属 R0）；embedder 默认 null → BM25-only（零嵌入依赖、离线全绿、`search` 永不抛）。`memory.injection.embedder-model-id` 可选启用真嵌入器（延后 IT 验证），但**默认与本 spec 的离线正确性无关**。语料源复用 `MemoryCorpusLoader`（`MEMORY.md` + `memory/*.md`）。**备选**：本 spec 自建轻量检索——否决（重复造轮子、违背复用门面）。
- **D5 — 去重：query-aware 检索结果排除 pinned 已含内容。** pinned 段（若来自 `MEMORY.md`）可能与检索命中重叠；注入前对 query-aware 结果按来源/文本做 pinned 去重，避免同一事实在 system prompt 与 trailing `Msg` 双重注入（省 token、避免自相矛盾）。去重为纯逻辑、可单测。
- **D6 — Config `memory.injection`，默认关（保守）。** `enabled` **默认 false** → `onSystemPrompt` 仍注入整份 `MEMORY.md`（今日 `NativeMemoryContextMiddleware` 行为，逐字节不变）、`onReasoning` 恒等（无 ephemeral 注入）。理由：切分改变注入行为，默认关确保零回归、翻开关一步启用；离线测试全覆盖启用后逻辑。字段：`enabled`(false)、`top-k`(默认 6)、`pinned.source`(默认 heading 段)/`pinned.heading`(默认约定名)/`pinned.max-chars`(默认 800)、`embedder-model-id`(空→BM25-only)；非法值 clamp、null-tolerant（镜像 `MemorySearchConfig`/`SandboxPolicy`）。
- **D7 — 交叉依赖显式登记（见 Risks R3/R4）。** 与 R0 只经稳定门面耦合、同期 R0 先合；与 X3 以「声明性事实 vs 程序性 how-to」划界。

## Architecture

```
memory.injection.enabled=true
        │  AgentBootstrap
        ▼
NativeMemoryContextMiddleware(pinnedSelector, MemorySearchIndex, settings)   ← io.pigagent.core.memory
  ├─ onSystemPrompt(currentPrompt):                                    [缓存前缀]
  │     pinned = PinnedSelector.select(MEMORY.md, source, maxChars)    ← query-无关、确定性、有界
  │     return base + "## Pinned Memory" + pinned                      ← 会话内字节稳定
  │        (enabled=false → 注入整份 MEMORY.md，今日行为)
  │
  └─ onReasoning(ReasoningInput input, next):                          [非前缀 / ephemeral]
        query   = 尾部当前 USER 消息文本
        hits    = MemorySearchIndex.search(query, topK)               ← 复用稳定门面, BM25-only
        hits    = dedupAgainstPinned(hits)                            ← D5
        msg     = RetrievedFactsFormatter.format(hits)  (user-side Msg, name="memory_retrieval")
        return next(new ReasoningInput(messages + [msg], tools, options))   ← 每步重建、不写回历史
           (空 query / 空 hits / disabled → next(input) 原样)
```

## Risks / Trade-offs

- **R1 — 承重假设「onReasoning trailing `Msg` 不进缓存前缀」若不成立则切分失效。** → Spike Task 0 阻塞前置钉死（结构不变式离线可证 + `LoopDetectionMiddleware` 生产 precedent + javap/源码核验），不过不进编码；真实 prefix-cache 命中率延后 `/ls:itest` 观测（结构不变式已是充分条件）。
- **R2 — pinned heading 缺失 → pinned 为空。** consolidation 未必产出约定 heading。→ D3 降级为空（安全：全部事实走 query-aware 检索，不崩不空注）；调优 consolidation 产出稳定 pinned 段属 `pa-memory-native`（Non-Goal，登记为未来）；`pinned.source` 另留 `head`（前 N 字符）选项做兜底。
- **R3 —（交叉依赖）R0 shared-retrieval-primitive 并行重构 `MemorySearchIndex` 内部。** → 本 spec **只依赖稳定公共门面** `search(query, topK) → List<MemoryDocument>`（签名/契约不变），不 import/改其内部类；**若两者同期落地，R0 先合**（本 spec rebase 到 R0 后的门面）。design 显式登记此依赖层。
- **R4 —（交叉边界）与技能线 X3 的职责划分。** 本 spec 只处理**声明性事实**（「用户是谁/偏好什么/发生过什么」）的检索注入；程序性「怎么做某事」的知识归 `SKILL.md`（`listSkills`/`loadSkill` 路径），**不在本能力内**。避免两条线都去检索注入而重叠。
- **R5 — query-aware 每回合检索开销。** 个人助理级语料量小、BM25 纯内存、`MemorySearchIndex` 懒/增量+节流构建，单次 `search` 开销可忽略。→ 复用门面既有节流；本 spec 不引入额外索引。
- **R6 — 真实语义召回质量未离线验。** BM25-only 是词面匹配，「换个说法」召回弱于向量。→ 本 spec 目标是**注入架构**（切分 + 按需），BM25-only 已优于「全量常驻的 token 浪费」；真嵌入器经 `embedder-model-id` 可选启用、延后 `/ls:itest`；默认 BM25-only 全离线可证。
- **R7 — enabled=false 的字节等价性。** 必须逐字节等于今日 `NativeMemoryContextMiddleware`。→ 保留今日 `onSystemPrompt` 全量注入分支不动，仅在 `enabled=true` 走新切分；单测断言默认关时 prompt 与今日实现字节一致、`onReasoning` 恒等。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 全量 `MEMORY.md` 常驻 ~4000 token/回合 | Why；D1 切分 | 落 tasks 2/3 |
| prefix-cache 红线：query 内容不得进缓存前缀（承重） | Spike S1；D1（首条 Decision） | 落 tasks 0/1（spike 前置 + 断言） |
| pinned 核心留 system prompt（进前缀、字节稳定） | D1/D3；`PinnedSelector` | 落 tasks 2 |
| query-aware top-K 走 ephemeral trailing `Msg`（不持久化） | D1/D2；复用 `LoopDetectionMiddleware` 机制 | 落 tasks 3 |
| 复用 `MemorySearchIndex.search` 门面、BM25-only | D4 | 落 tasks 3 |
| pinned/检索去重 | D5 | 落 tasks 3 |
| config `memory.injection`、默认关 = 今日行为 | D6；R7 | 落 tasks 4；断言字节等价 tasks 1/5 |
| 依赖 R0 只经稳定门面、同期 R0 先合 | D7；R3 | 已登记（依赖层） |
| 与 X3 划界：声明性事实 vs 程序性 how-to | D7；R4 | 已登记（边界） |
| 真嵌入器/语义质量延后 IT，BM25-only 离线证 | R6；D4 | 落 tasks 6（IT，延后） |
| pinned heading 缺失降级为空 + head 兜底 | R2；D3 | 落 tasks 2 |
