## 1. 三层 token 预算（值对象，纯计算）

- [x] 1.1 新增 `compression/BudgetRatios`（record）：三档比例 + 构造时归一化（非负、按和归一；和≤0 退回缺省 0.2/0.3/0.5）+ `defaults()`。
- [x] 1.2 新增 `compression/ContextBudget`（record）：`allocate(int total, BudgetRatios)` —— `pinned=floor(total*p)`、`recent=floor(total*r)`、`summarized=total-pinned-recent`（三档和恒等 total）。
- [x] 1.3 单测：按比例确定性切分、比例未归一化先归一化、三档和==total、total=0 边界。

## 2. 重要度评分（Strategy）

- [x] 2.1 新增 `compression/ImportanceScorer`（接口，`int score(Msg)` + 默认 `isHighImportance`）。
- [x] 2.2 新增 `compression/HeuristicImportanceScorer`：决策/纠正（EN+CJK 关键词）、报错关键词加权，极短确认判低。
- [x] 2.3 单测：决策/纠正/报错分数 > 闲聊；极短确认判低；`isHighImportance` 阈值。

## 3. 逐字保护（VerbatimGuard）

- [x] 3.1 新增 `compression/VerbatimGuard`：`isProtected(Msg)` / `containsProtected(String)` / `protectedSpans(String)`——识别围栏代码块、行内代码、命令行、长 hex/SHA/UUID、精确引用。
- [x] 3.2 单测：代码块/命令/哈希/UUID 命中；普通闲聊不误判；`protectedSpans` 取回片段。

## 4. 递归摘要（Strategy + 可 mock 模型缝）

- [x] 4.1 新增 `compression/RecursiveSummarizer`：包裹既有 `Summarizer` 缝 + `TokenEstimator`；`summarize(toSummarize, budget) → Result(text, depth)`；超预算且未达 maxDepth 则摘要再摘要；空白摘要短路；maxDepth≤0 退化单层。
- [x] 4.2 单测（mock summarizer）：超预算触发再摘要、达 maxDepth 停止、预算内不递归、空白摘要短路。

## 5. 一致性校验（ConsistencyChecker）

- [x] 5.1 新增 `compression/ConsistencyChecker`：`check(required, candidate) → Result(ok, missing)`——required（高重要度/受保护消息 + 受保护片段）是否为 candidate 渲染文本子串。
- [x] 5.2 单测：required 全在 → ok；某 required 缺失 → 报缺失项。

## 6. 编排器（ContextEngineer）+ 安全回退

- [x] 6.1 新增 `compression/EngineeringOptions`（record）：递归开关/maxDepth、重要度/逐字/一致性开关、keepRecent、ratios；`defaults()` + `SAFE`（逐字+重要度 ON、递归 OFF）+ 坏值钳制。
- [x] 6.2 新增 `compression/ContextEngineer`：`rewrite(messages, budgetTokens) → List<Msg>|null`——分档（重要度/逐字）→ 递归摘要（摘要层预算）→ 组候选 → 一致性校验 → 失败走 SAFE 档重建再校验 → 仍失败返回 null（保留原文）。
- [x] 6.3 单测（mock summarizer）：缺省配置小对话复现既有 `[summary]+recent`；高重要度旧回合逐字保留；受保护内容不进摘要；一致性失败 → SAFE 回退保住关键事实；SAFE 也失败 → 返回 null。

## 7. CompressionService 加法式接线

- [x] 7.1 `CompressionService`：新增包内构造（注入 `ContextEngineer`）；生产构造从配置建 `EngineeringOptions` + 装配默认 `ContextEngineer`；`compress` 改为委托 `engineer.rewrite(...)`（null→false 保留原文；否则 clear+写入+记 lineage）。保留全部既有公开方法与构造签名。
- [x] 7.2 `CompressionStatus`：增补三档预算（pinned/recent/summarized）字段，`status` 填充。
- [x] 7.3 既有 `CompressionServiceTest` / `ModelSummarizerToolAwareTest` 全绿（不改；如需改则在 design 说明理由）。

## 8. 配置扩展

- [x] 8.1 `PigAgentConfig.CompressionConfig`：新增可选字段 `keep-recent`、`recursive-summary`、`max-summary-depth`、`importance-retention`、`verbatim-protection`、`consistency-check`、`pinned-ratio`/`recent-ratio`/`summarized-ratio`（缺省安全）+ getters/setters。
- [x] 8.2 `AgentBootstrap`：把新配置读出、建 `EngineeringOptions` 传入 `CompressionService`。

## 9. 验收

- [x] 9.1 `mvn -q test` 单线程绿（surefire 计数），既有压缩/记忆测试不回归。
- [x] 9.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 9.3 `CLAUDE.md` 压缩段落更新为「分层预算 + 递归摘要 + 重要度保留 + 逐字保护/一致性校验」。
