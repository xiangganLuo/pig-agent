## Context

`CompressionService.compress(sessionId, memory, messages)` 当前流程：`splitAt = size - KEEP_RECENT` → `older = [0,splitAt)`、`kept = [splitAt,size)` → `summarize(older)` 得一段文字 → `memory.clear()` → 写入 `[summary] + kept`。摘要走 `Summarizer` 函数式接口（`String summarize(List<Msg>)`），默认实现 `ModelSummarizer` 用当前模型起一个一次性 agent 摘要（这是**天然的可 mock 模型缝**——单测里注入 `older -> "SUMMARY"` 即可离线）。触发判据走 `TokenEstimator`（Strategy，默认 `CharBudgetTokenEstimator` ≈ 字符/4）。压缩成功后触发 `CompressionLineageRecorder`（容错、与核心解耦）。

本次在**不改这套编排与缝**的前提下，把「如何由整段对话得出重写后的消息列表」这块决策逻辑抽出为一个纯粹（除摘要缝外无副作用）的 `ContextEngineer`，让分层预算 / 递归 / 重要度 / 逐字 / 校验都可脱离 `Memory` 单测；`CompressionService` 仍是唯一改 `Memory` 与记 lineage 的地方。

## Goals / Non-Goals

**Goals:**
- 用值对象 + Strategy 表达「上下文工程」：`ContextBudget`（分层预算）/`ImportanceScorer`（重要度）/`RecursiveSummarizer`（递归摘要）/`VerbatimGuard`（逐字保护）/`ConsistencyChecker`（一致性）各司其职，纯逻辑可单测。
- 摘要模型调用集中在**一个可 mock 的缝**（复用既有 `Summarizer`），单测全离线。
- 缺省配置**复现既有 summarize-old/keep-recent 行为**，既有压缩测试全绿。
- 一致性校验失败时**回退到更安全行为**，绝不静默丢关键上下文。

**Non-Goals:**
- 不引入精确 tokenizer（沿用 `~字符/4`，仅新增分层预算的切分数学）。
- 不改压缩「只作用于内存对话、不碰持久化历史/记忆」的边界，不改 `/compress` 面向界面、lineage 语义。
- `ContextBudget.recentTokens` 只用于**上报/展示 + 作为最近窗口的目标提示**，不据其裁剪最近窗口条数（避免回归既有以条数为准的 `KEEP_RECENT` 行为）；据 token 收紧最近窗口列为后续。

## Decisions

- **D1 — 值对象 `BudgetRatios` + `ContextBudget`（三层预算，纯计算）。** `BudgetRatios(pinned, recent, summarized)` 构造时**归一化**（各项非负、按和归一；和 ≤ 0 退回缺省 0.2/0.3/0.5）。`ContextBudget.allocate(total, ratios)`：`pinned = floor(total*pinned)`、`recent = floor(total*recent)`、`summarized = total - pinned - recent`（余数归 summarized，保证三档之和**恒等于** total、确定性）。理由：分配是纯数学、要可完全单测且确定；用不可变记录 + 静态工厂表达，压缩编排只消费结果。**摘要层 `summarizedTokens` 作为递归触发阈值（强约束）；`pinnedTokens`/`recentTokens` 为对应内容预留额度并在 `/compress status` 上报**——不据 `pinnedTokens` 硬性封顶逐字保留量（见 D5-note）。
- **D2 — Strategy `ImportanceScorer` + `HeuristicImportanceScorer`。** 接口 `int score(Msg)` + 默认 `boolean isHighImportance(Msg)`（`score ≥ HIGH_IMPORTANCE`）。启发式信号：USER 角色的**决策/约束/纠正**关键词（decide/decision/must/never/instead/actually/correction/要求/必须/务必/不要/改成/其实/纠正…）加权；任意角色含**报错**关键词（error/exception/failed/failure/panic/报错/失败/异常）加权；极短确认（ok/thanks/嗯/好的…）判低。理由：需求点名「决策/纠正/错误」高于闲聊；抽成 Strategy 让评分算法可换、可 mock、可单测；保守取阈值，使普通消息不被误判为高（保证既有测试的 plain 消息不被逐字拉出）。
- **D3 — `VerbatimGuard`（逐字保护，纯启发式/正则）。** `isProtected(Msg)` = 其渲染文本命中任一受保护模式；`protectedSpans(String)` 供一致性校验取回受保护片段。**受保护清单**：① 围栏代码块 ```` ``` … ``` ````；② 行内代码 `` `…` ``（长度达阈值，避免误伤单字）；③ 命令行（以 `$ ` / 常见命令前缀 git|mvn|npm|docker|curl|kubectl|sudo|bash… 起头的行）；④ 长十六进制 / SHA 哈希（≥ 7 位 hex 串、40/64 位哈希）、UUID；⑤ 精确引用（成对 `"…"` 中的较长文本）。命中即**整条消息**进逐字保留，不喂摘要器。理由：这些是 MUST NOT 被改写的内容，摘要模型一改写就破坏正确性；整条保留最简单且无误差（片段级拼接易错）。宁可漏放（保守），不可误伤正常闲聊（阈值 + 词首锚定）。
- **D4 — Strategy `RecursiveSummarizer` 包裹 `Summarizer` 缝 + `TokenEstimator`。** `summarize(List<Msg> toSummarize, int budgetTokens) → Result(text, depth)`：先 `summary = summarizer.summarize(toSummarize)`；`summary` 空白 → 返回空（上游据此中止，保留原文，沿用既有契约）；随后 `while estimate(wrap(summary)) > budget && depth < maxDepth：summary = summarizer.summarize([wrap(summary)]); depth++`。理由：递归收敛靠既有估算器判预算、靠既有摘要缝做「摘要再摘要」，**模型调用只此一处缝**、可 mock；限深防不收敛/成本失控。`maxDepth ≤ 0` 或递归关闭 → 退化为单层摘要（既有行为）。
- **D5 — `ContextEngineer` 编排（含主路径 → 安全回退 → 保底）。** 纯逻辑（除摘要缝）。`rewrite(messages, budgetTokens) → List<Msg>|null`：
  1. `messages.size() ≤ keepRecent` → 返回 null（无可压，`CompressionService` 保持原文）。
  2. `splitAt = size - keepRecent`；`older=[0,splitAt)`、`recent=[splitAt,size)`。
  3. **分档**（按当前 `EngineeringOptions`）：`verbatimKeep` = `older` 中 `scorer.isHighImportance || guard.isProtected` 者（保序）；`toSummarize` = 其余。（**D5-note**：不据 `pinnedTokens` 硬性封顶 `verbatimKeep`——因为一致性校验的 `required` 也基于 high-importance/protected，若 cap 丢掉一条 high-importance 会必然触发校验失败并最终回退到「不压缩」，反而失去压缩收益；故 pinned 硬封顶列为后续，当前 pinned 只做预留+上报。）
  4. `summary = recursiveSummarizer.summarize(toSummarize, summarizedTokens)`；`toSummarize` 非空且 `summary` 空白 → 返回 null（保留原文）。
  5. 候选 `candidate = [summaryMsg?] + verbatimKeep + recent`（`summaryMsg` 名 `summary`、ASSISTANT 角色、前缀 `[Earlier conversation summary]\n`，与既有一致）。
  6. **一致性校验**（若开）：`required` = `verbatimKeep`（含其受保护片段）+ `recent` 的受保护片段；`checker.check(required, candidate)` 全在 → 返回 candidate；否则进 D6。
  7. 校验关闭 → 直接返回 candidate。
- **D6 — 一致性失败 → 安全回退契约（更不激进）。** 主路径用**配置档** `EngineeringOptions`（可能关了逐字/重要度、开了深递归——更激进）。回退时改用 `EngineeringOptions.SAFE`：**强制逐字保护 ON + 重要度保留 ON + 递归 OFF（单层）**——把所有可识别的关键消息逐字留下、且不做最有损的「摘要再摘要」，严格更安全。用 SAFE 档重建候选并**再校验**：通过 → 返回该更安全候选并 `log.warn` 记录「已回退」；仍不通过 → 返回 null（**保持原上下文不压缩**，绝不静默丢弃关键上下文），`log.warn` 记录丢失项（脱敏、不打印片段内容）。理由：需求要求「回退到更安全的先前行为、绝不静默丢关键上下文」；SAFE 档正是「更不激进」的先前式行为（不递归、最大化逐字），保底档是「宁可不压也不丢」。这与既有「任何异常即中止、保留原文」的安全默契一脉相承。
- **D7 — 缺省配置复现既有行为。** 缺省 `EngineeringOptions`：递归 ON（maxDepth 3）、重要度 ON、逐字 ON、一致性 ON、`keepRecent = 6`、比例 0.2/0.3/0.5。对**小对话 + 纯文本**（既有测试的场景）：`verbatimKeep` 为空（plain 消息既不高重要度也不受保护）、mock/真实摘要都很短不触发递归、无受保护片段 → `candidate = [summary] + recent`，与既有 `[summary] + kept` 逐条一致（size 7）。故既有测试无需改动即全绿；另加显式「缺省复现」测试锁定。
- **D8 — `CompressionService` 加法式接线。** 保留全部公开方法与既有构造函数；新增一个包内构造重载注入 `ContextEngineer`（供测试）；生产构造从新配置字段建 `EngineeringOptions` + 默认组件装配 `ContextEngineer`。`compress` 主体改为：`plan = engineer.rewrite(messages, budgetTokens)`；`plan == null` → 返回 false（保留原文）；否则 `clear` + 逐条写入 `plan` + 记 lineage + 返回 true。`maybeCompress`/`compressNow`/`status`/enable 逻辑不变。

## Risks / Trade-offs

- **R1 — 启发式重要度/逐字有误差。** 评分与识别都是启发式，可能漏放高价值内容或漏判受保护片段。→ 一致性校验（D5.6）+ 安全回退（D6）作为兜底：漏判导致关键内容进了摘要、校验能发现 → 回退保住；保守阈值使误伤（把闲聊判成受保护/高重要度）只是「少压一点」，不影响正确性。
- **R2 — 递归摘要仍有损。** summary-of-summaries 会进一步丢细节。→ 只对 `toSummarize`（已排除逐字/高重要度）递归；关键内容不进递归；限深防过压。
- **R3 — 校验为子串近似。** 一致性校验按「受保护片段/关键消息渲染文本是否为候选渲染文本的子串」判定，非语义级。→ 目标是抓「整条被摘要吞掉」这类硬丢失（子串足够）；语义级校验非目标。
- **R4 — 缺省行为等价性依赖测试场景。** 「缺省复现既有行为」对**小对话/纯文本**成立；大对话本就是本能力要改进的目标（会保留更多高价值内容、可能递归）。→ 既有测试都是小对话/纯文本，缺省全绿；新行为由新测试覆盖。此为有意的行为增强，非回归。
- **R5 — 未经真模型验证。** 递归摘要的**摘要质量**、真实决策/报错语料上的重要度判准，只在 mock 下验证结构正确性（触发/限深/分档/回退），未跑真模型 `*IT`。→ 结构与安全兜底离线可证；摘要语义质量待真模型 itest（本次不跑，避免配额）。

## 落实追踪表（需求发现项 → 落点 + 状态）

| 发现项 / 需求 | 落点 | 状态 |
|---|---|---|
| 三层 token 预算（pinned/recent/summarized，确定性、可单测） | D1 `BudgetRatios`+`ContextBudget`；spec ADDED「三层 token 预算分配」 | 已实现 |
| 递归摘要（summary-of-summaries，限深，可 mock 模型缝） | D4 `RecursiveSummarizer` 复用 `Summarizer` 缝；spec ADDED「递归摘要（限深）」 | 已实现 |
| 重要度选择性保留（决策/纠正/错误 > 闲聊） | D2 `ImportanceScorer`+`HeuristicImportanceScorer`；spec ADDED「重要度选择性保留」 | 已实现 |
| 代码/命令/ID/精确文本逐字保护 | D3 `VerbatimGuard`；spec ADDED「代码/精确文本逐字保护」 | 已实现 |
| 一致性校验 + 校验失败安全回退（绝不静默丢） | D5/D6 `ConsistencyChecker`+`ContextEngineer` 回退；spec ADDED「一致性校验与安全回退」 | 已实现 |
| 配置项 + 缺省复现既有行为、既有测试全绿 | D7/D8 `EngineeringOptions`+配置字段；spec ADDED「默认安全配置」 + MODIFIED「摘要保留 tool 调用与结果」 | 已实现 |
| 「用设计模式，别面向功能编程」 | 值对象（ContextBudget/BudgetRatios）+ Strategy（ImportanceScorer/RecursiveSummarizer/TokenEstimator）+ 编排器（ContextEngineer）+ 复用既有 Summarizer 缝 | 已实现 |
| 据 token 收紧最近窗口条数 | D-非目标；`recentTokens` 仅上报/展示 | 延后 |
| 据 `pinnedTokens` 硬性封顶逐字保留量 | D1/D5-note；与一致性校验冲突，`pinnedTokens` 仅预留+上报 | 延后 |
| 递归摘要质量、真语料重要度判准 | R5；结构离线可证，语义待真模型 itest | 延后（不跑 itest） |
