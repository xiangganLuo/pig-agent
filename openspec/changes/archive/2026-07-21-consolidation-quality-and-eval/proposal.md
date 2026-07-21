## Why

pig 的原生双层记忆（`pa-memory-native` 已在 main）：per-turn **flush** 把对话事实抽取进日志层 `memory/YYYY-MM-DD.md`，后台节流的 **consolidation** 由 LLM 全量重写去重进工作区级固化层 `MEMORY.md`。抽取/去重的**质量**（准不准、丢没丢关键、去重干不干净）目前**只有 live 模型能验**——`AgentBootstrap.buildMemoryConfig` 用的是原生 `MemoryConfig` 默认 prompt，pig 侧既无更强的抽取/去重指令、也无任何离线可测的质量护栏与评测手段。`CrossSessionMemoryTest` 只证了「跨会话结构」，没证「记得准/不丢/去重净」。

本 spec 是内核路线图 **Wave-3 的 M-D「consolidation 质量 + 语义去重 + eval harness」**，在**不改默认行为**的前提下，为记忆质量补三样东西：(1) 可选、更强的 flush/consolidation **prompt 定制**；(2) 复用 R0 检索原语的 pig 侧**语义去重后置 curator**（非纯字符串）；(3) 一套**记忆质量评测 harness**（固定对话 → 断言关键事实被记住/去重干净/未丢），离线证结构、真质量基线延后 live。三者全默认关/opt-in、additive，翻开开关才生效。

## What Changes

- **定制 flush/consolidation prompt（opt-in、additive）**：在 `AgentBootstrap.buildMemoryConfig` 里，当配置了自定义 prompt 时调用原生 `MemoryConfig.builder().flushPrompt(String)` / `.consolidationPrompt(String)`（javap 核验：`io.agentscope.harness.agent.memory.MemoryConfig$Builder` 有此二方法）——给出更准的抽取/去重指令。**默认（未配置）沿用原生默认 prompt，行为逐字节不变**。**承重约束**：原生 `MemoryConsolidator` 以 `String.format(consolidationPrompt, maxTokens, maxChars)` 消费 consolidation prompt（默认常量 `DEFAULT_CONSOLIDATION_PROMPT` 内含「`within %d tokens (approximately %d characters)`」两个 `%d`），故**自定义 consolidation prompt MUST 恰含两个 `%d`、且不含其它裸 `%` 转换**，否则后台 consolidation 会在 `String.format` 处抛异常；pig 侧加一层**占位校验**，校验失败 → 记 warn + 回退默认 prompt（绝不崩、绝不让后台任务抛）。flush prompt 是纯 SYSTEM prompt（原生**不**对它做 `String.format`），无占位要求，空白回退默认。
- **语义去重后置 curator（复用 R0 `io.pigagent.core.search`，默认关）**：启用时，在原生 consolidation 之后跑一个 pig 侧**后置** curator——读固化层 `MEMORY.md`、切分为事实单元、计算两两**语义相似度**、把超阈值的近重复丢弃（保留代表条）、原子重写 `MEMORY.md`。相似度复用既有检索原语：`io.pigagent.core.search`（`Bm25Index`/`HybridRanker`/`Tokenizer`）+ `io.pigagent.core.memory.search`（`Embedder`/`DeterministicEmbedder`/`Vectors.cosine`）。**配了嵌入模型**（`resolveEmbedder`，与 M-B 同源）→ 用真嵌入器 cosine；**无嵌入器 → 降级 BM25 相似度**（零依赖）。去重逻辑纯确定性、离线以 `DeterministicEmbedder`/BM25 可测；服务侧节流 + 容错（镜像 `ProfileConsolidationService`/`SkillCuratorService`）。
- **记忆质量评测 harness（离线结构 + live 质量延后）**：一套**固定对话 fixture + 断言框架**驱动记忆流水线，断言三类指标——**关键事实被记住 / 去重干净 / 关键信息未丢**。**离线**交付 harness 的结构 + 断言脚手架（fake/确定性驱动，全离线）；**真质量基线**（LLM 真实抽取/去重度量）延后 live-model `*IT`（默认模型此前 403，诚实延后）。harness 设计为可供后续 **M-C（分层衰减）**复用。
- **MEMORY.md 分层格式契约（供 M-C 复用）**：M-D 先**定义/对齐**一个稳定的 `MEMORY.md` 分层/分段格式契约（供未来 M-C 的分层衰减键入）——定制 consolidation prompt 产出符合该契约的结构、语义去重 curator 保留其分层标记；契约**向后兼容**旧无分层的 `MEMORY.md`（缺标记视为单层，不报错）。此契约只在启用结构化 consolidation 时生效，默认不产出、零回归。
- **新增 config `memory.consolidation-quality` 块**（全可选、默认安全）：`flush-prompt` / `consolidation-prompt`（空 → 原生默认）、`dedup` 子块（`enabled` 默认 **false**、`similarity-threshold`、`embedder-model-id` 空→BM25、`min-gap-minutes` 节流）。默认关即今日行为逐字节不变。

**非破坏**：全默认关/additive；启用后不改 `pa-memory-native` 的两层记忆布局、flush/consolidation 触发语义、`/memory on|off` 语义——仅**加**更强 prompt、**加**一层后置去重、**加**评测手段。

## Capabilities

### New Capabilities
- `memory-consolidation-quality`: 记忆固化质量层——**可选**的 flush/consolidation prompt 定制（含 consolidation prompt 两个 `%d` 占位的 pig 侧校验 + 失败安全回退）、pig 侧**语义去重后置 curator**（复用 `io.pigagent.core.search` BM25 + `Embedder`/cosine，`DeterministicEmbedder` 离线可测、无嵌入器降级 BM25、节流容错）、**记忆质量评测 harness**（固定对话 fixture + 「记住/去重净/未丢」断言框架，离线证结构、真质量延后 live `*IT`），以及一个供 M-C 复用的 `MEMORY.md` 分层格式契约。config 门控、全默认关/additive（默认逐字节等于 `pa-memory-native` 今日行为）。

### Modified Capabilities
<!-- 无。本能力是 pa-memory-native 之上的独立叠加：默认关/未配置时 prompt=原生默认、无 pig 侧去重、无评测介入，行为逐字节等于今日，故不改写 pa-memory-native 主 spec 的既有 Requirement（复用其两层记忆为语料/写路径、不动其 flush/consolidation 触发与 /memory 语义）。此叠加策略与已归档的 hybrid-memory-search / memory-retrieval-injection 一致。 -->

## Impact

- **代码**：`pig-agent-core`（新增 `memory/quality/`：纯逻辑 `ConsolidationPromptValidator`（consolidation prompt 恰含两个 `%d`、无裸 `%` 校验）、语义去重纯策略 `SemanticDeduplicator`（复用 `core.search` BM25 + `memory.search` `Embedder`/`Vectors.cosine`）、后置服务 `MemoryConsolidationCurator`（节流/容错/原子重写，镜像 `ProfileConsolidationService`）、`MEMORY.md` 分层格式契约值对象——具体命名以 `design.md` 为准）；`pig-agent-config`（`MemoryConfig` 内嵌 `ConsolidationQualityConfig` 块）；`pig-agent-cli`（`AgentBootstrap.buildMemoryConfig` 接自定义 prompt + 校验回退；启用去重时按 `resolveEmbedder` 建 curator 并挂 `TaskScheduler`，镜像 profile consolidation 接线；默认关 → 沿用今日构造）。
- **不改**：`pa-memory-native` 两层记忆布局 / flush/consolidation 触发 / `/memory on|off`（复用不动）；A5 压缩（`CompressionService`，正交）；`hybrid-memory-search`/`memory-retrieval-injection` 的检索注入（本 spec 只写记忆库、不碰注入切分）；权限/沙箱/渠道 fail-closed。
- **交叉依赖**（详见 `design.md §交叉依赖`）：
  - **M-C（分层衰减，后续）**：本 spec 先**定义/对齐** `MEMORY.md` 分层格式契约，M-C 归档后**复用**该契约做分层衰减；本 eval harness 也**供 M-C 复用**（design 显式登记此供给层与顺序依赖）。
  - **M-B（真嵌入，Wave-2）**：语义去重的嵌入器与 M-B **同源**（复用 `AgentBootstrap.resolveEmbedder` → E0 store 指针）；无嵌入器 → BM25 相似度去重（零依赖降级），故本 spec 不硬依赖 M-B 完成。
  - **技能线边界**：**声明性事实**归 `MEMORY.md`（本 spec 的对象），**程序性 how-to** 归 `SKILL.md`（技能线，不在此）。
- **测试**：离线单测——**Group 1 承重 spike**：证 (a) 语义去重逻辑（BM25/cosine 阈值去重、`DeterministicEmbedder`）确定性可测；(b) consolidation prompt 两个 `%d` 校验 + 失败安全回退（含裸 `%`/缺/多占位）；(c) eval harness 结构（固定对话 fixture + 断言框架，fake/确定性驱动全离线）。其后：prompt 接线（默认=原生默认字节等价、自定义被采用）；curator 节流/容错/原子重写/无嵌入器降级 BM25/默认关不改动 `MEMORY.md`；分层格式契约向后兼容；config 默认/YAML/clamp。**真 consolidation 质量基线（LLM 抽取/去重「记住/没丢关键/去重干净」度量）→ live `*IT`（`/ls:itest`，默认模型此前 403，诚实延后）**。
- **文档**：`CLAUDE.md` 记忆段落新增「consolidation 质量层（prompt 定制 + 语义去重 curator + 评测 harness）」说明 + 配置段 `memory.consolidation-quality` 同步。
