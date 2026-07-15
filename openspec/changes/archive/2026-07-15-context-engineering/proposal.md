## Why

当前的上下文压缩（`CompressionService`）是「摘要较早回合 + 保留最近 N 条」的单层策略：一次性把 `older` 全部丢给摘要模型压成一段文字，最近 `KEEP_RECENT` 条逐字保留。它在这个 tool 密集、长驻的编码 agent 上有四处结构性不足：

1. **无分层预算** —— 只有一个整体 token 阈值，没有把窗口显式切给「必留（system + 关键事实）/ 最近逐字 / 可摘要旧内容」三档，摘要层没有独立预算约束。
2. **摘要不可递归** —— 若摘要本身仍然过大（超长对话、超多 tool 结果），没有「摘要再摘要」的收敛手段，摘要层可能仍然撑爆预算。
3. **无重要度区分** —— 旧内容一律进摘要，用户的**决策 / 纠正 / 报错**等最该逐字保留的高价值回合，和寒暄闲聊被等同对待、一起被压。
4. **无精确文本保护 / 一致性兜底** —— 代码块、命令、ID/哈希、精确引用等**不能被改写**的内容会被摘要模型改写或丢失；压缩后也没有校验「关键事实是否还在」，一旦摘要漏掉关键上下文，就静默丢失、无从察觉。

这正是 deerflow 意义上的「上下文工程（context engineering）」要解决的：把「压缩」从粗放的 summarize-old 升级为**分层预算 + 递归摘要 + 重要度保留 + 逐字保护 + 一致性校验**的结构化流程。

## What Changes

在既有 `CompressionService` 上**加法式**升级（不破坏其对外契约与既有测试，`/compress now|status|off|on` 与 lineage 记录不变）：

- **三层 token 预算（`ContextBudget` 值对象）**：把窗口按可配比例确定性切成 pinned（必留）/ recent（最近逐字）/ summarized（可摘要旧内容）三档；纯计算、可完全单测（不依赖模型）。摘要层预算作为递归触发阈值；pinned/recent 档为对应内容预留额度并上报（据 token 硬性封顶逐字保留量列为后续，避免与一致性校验相互打架）。
- **递归摘要（`RecursiveSummarizer`，Strategy + 可 mock 模型缝）**：当摘要层仍超预算时，对摘要再摘要（summary-of-summaries），受 `max-summary-depth` 限深；模型调用走既有 `Summarizer` 函数式缝（可 mock，离线可测）。
- **重要度选择性保留（`ImportanceScorer`，Strategy）**：纯启发式给回合打分；用户决策 / 纠正 / 报错等高重要度旧回合逐字保留，闲聊等低重要度旧回合优先进摘要；pinned 预算封顶逐字保留量。
- **代码/精确文本逐字保护（`VerbatimGuard`）**：代码块、命令、ID/哈希、精确引用等 MUST NOT 被递归摘要——正则/启发式识别后整条保留逐字。
- **一致性校验 + 安全回退（`ConsistencyChecker`）**：压缩产出候选后，校验 pinned/高重要度消息与受保护逐字片段是否仍在；不通过则**回退到更安全（更不激进）的行为**并记日志，绝不静默丢关键上下文。
- **配置扩展**（`compression` 块，全部可选、缺省安全）：三档比例、递归开关 + 限深、重要度/逐字/一致性开关、`keep-recent`。缺省值贴合既有行为，既有压缩测试保持全绿。

**无 BREAKING**：`CompressionService` 公开方法签名、`/compress` 面向界面、`CompressionLineageRecorder` 缝、"压缩只作用于内存对话、不碰持久化历史/记忆" 语义均不变；小对话在缺省配置下压缩结果与既有一致。

## Capabilities

### Modified Capabilities
- `context-memory-efficiency`：扩展压缩能力——在既有 tool-aware 估算/摘要之上，新增三层预算、递归摘要（限深）、重要度保留、逐字保护与一致性校验 + 安全回退；并把「摘要」要求扩展为在摘要层预算内、受逐字保护约束的分层摘要。

## Impact

- **代码**：`pig-agent-core` —— 新增 `compression/{BudgetRatios, ContextBudget, ImportanceScorer, HeuristicImportanceScorer, VerbatimGuard, RecursiveSummarizer, ConsistencyChecker, EngineeringOptions, ContextEngineer}`；改 `compression/CompressionService`（`compress` 委托 `ContextEngineer` 生成重写方案，公开 API 不变）、`compression/CompressionStatus`（增补三档预算）。`pig-agent-config` —— `CompressionConfig` 增可选字段。`pig-agent-cli` —— `AgentBootstrap` 把新配置注入 `CompressionService`。
- **不改**：压缩「只作用于内存对话、不碰持久化历史/记忆」；`/compress` 面向界面；`CompressionLineageRecorder` 时机与容错；`TokenEstimator`/`MsgContentRenderer`/`CachingLongTermMemory` 契约。
- **测试**：预算三档确定性切分（含归一化）；递归在超预算时触发、限深；重要度把决策/报错排在闲聊之上；逐字守卫识别代码/命令/ID 并排除出摘要；一致性校验发现丢失的 pinned 事实 → 安全回退；缺省配置复现既有行为。摘要模型缝以 mock 注入，全部离线。不回归既有 `CompressionServiceTest` / `CharBudgetTokenEstimatorTest` / `ModelSummarizerToolAwareTest` / `MsgContentRendererTest`。
- **文档**：`CLAUDE.md` 压缩段落更新为「分层预算 + 递归摘要 + 重要度保留 + 逐字保护/一致性校验」。
