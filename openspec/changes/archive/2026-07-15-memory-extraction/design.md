## Context

记忆 `record` 的调用链（已读源码确认）：`EphemeralMemoryContextHook.recordConversation`（`PostCallEvent`，`messages = memory.getMessages()` = **全量短期会话**）→ `CachingLongTermMemory.record`（透传 + 失效缓存）→ `CompositeLongTermMemory.record`（禁用/无会话层 → no-op，否则**只写会话层**）→ 会话层 `FileSystemLongTermMemory.record`（把消息按 20 字符阈值截断成 200 字条目 append 到 `sessions/{id}/temp-memory.md`）。会话层记忆对象由 `SessionManager.activate` 每次切换会话时 `new FileSystemLongTermMemory(tempMemoryPath(id))` 建好并 `memory.setSessionMemory(...)`。检索半部走 `FileSystemLongTermMemory.retrieve`（读同一文件的尾部）。

压缩（`CompressionService.ModelSummarizer`）给出了「在当前模型上起一个一次性 `PigAgent` 做辅助 LLM 调用」的复用范式，且 `Summarizer` 是可注入 seam（离线单测用假实现）。`CompressionLineageRecorder` 给出了「best-effort、失败吞掉并记日志、绝不打断主流程」的谱系。本设计沿用这两条范式。

## Goals / Non-Goals

**Goals:**
- 把会话层 `record` 从「死记原始回合」升级为「LLM 抽取结构化事实」：分类 + 置信度 + 纠正覆盖 + 去噪。
- 抽取 + 写入**离开回合关键路径**（异步去抖），关闭时无泄漏，失败优雅降级。
- 用设计模式表达（Strategy/Decorator/纯函数/工厂接缝），不面向功能硬写；机制与决策解耦、可离线单测。
- 默认 `enabled=false` → 逐字节向后兼容。

**Non-Goals:**
- 不动**全局层**（`workspace/context/memory.md`）写路径——全局层仍是人工策展。
- 不改 `PreReasoning` 的 ephemeral 注入半部（prefix-cache-context 契约不变）。
- 不做跨会话事实合并 / 语义相似度去重（只按 `subject` 字符串去重）。
- 不做运行期抽取质量评估、不引入向量检索。
- 会话切换时**不**同步 flush 未决抽取（见 R2，会阻塞切换）；只保证**关闭**时 flush。

## Decisions

- **D1 — `ExtractingLongTermMemory`（Decorator，`implements LongTermMemory`）作为抽取的唯一入口。** 包住会话层 `FileSystemLongTermMemory` 委托：
  - `retrieve(query)` → 直接委托（读的是同一个 `temp-memory.md`，抽取写入也落这里 → 模型看到的是**结构化事实**而非原文，检索更优）。
  - `record(messages)`：`enabled` 谓词（`BooleanSupplier`，运行期可读）为 false → **委托 `delegate.record`（原始 append，逐字节等价旧行为）**；为 true → 把 `() -> pipeline.process(snapshot)` **提交给去抖 scheduler 后立即返回 `Mono.empty()`**（不阻塞回合）。
  - 只包 Decorator、不改 `CompositeLongTermMemory`/`CachingLongTermMemory`：两层合并、来源标注、只写会话层、缓存失效全不动。

- **D2 — `MemoryExtractor`（Strategy 接口 = 可 mock 接缝）。** `@FunctionalInterface List<ExtractedFact> extract(List<Msg> turn)`。生产实现 `LlmMemoryExtractor`（`Supplier<Model>` → 起一次性 `PigAgent` 发 `EXTRACTION_PROMPT`+渲染后的回合 → 取回复文本 → `FactJsonParser.parse`）。**prompt + JSON 解析 + 分类**都在实现侧。单测：pipeline 用假 `MemoryExtractor`（返回固定事实/抛异常）；解析逻辑用纯 `FactJsonParser` 直接单测；`LlmMemoryExtractor` happy path 用返回固定 JSON 的假 `Model`（离线，仿 `ModelSummarizerToolAwareTest`），降级路径用会抛异常的 `Supplier<Model>`。

- **D3 — 值对象 + 分类枚举（不可变）。**
  - `FactCategory`（enum：`USER_PREFERENCE("user-preference")` / `PROJECT_FACT("project-fact")` / `REFERENCE("reference")`，含 `label()` + 容错 `fromLabel(s, default)`——未知标签回退，不抛）。枚举登记表模式。
  - `ExtractedFact`（record：`subject` / `category` / `statement` / `confidence` / `correction`）；紧致构造器**钳制** `confidence` 到 [0,1]、null 安全；`withConfidence`/`asCorrection` 拷贝方法。`subject` 是去重/覆盖的键。

- **D4 — 纯函数三件套（可独立单测，DRY，无副作用）。**
  - `MemoryNoiseFilter`：`isDurable(Msg)`（TOOL 角色 / 含 `ToolUseBlock`/`ToolResultBlock` 的消息 → 非耐久）、`isEphemeral(text)`（正则命中「我跑了/正在运行/executing…」等**瞬时状态**句式，及 mvn/build 状态噪声）、`filter(List<Msg>)`（剥离工具消息 + 丢弃瞬时/空白，产出干净的 user/assistant 文本消息列表）。**噪声判定与抽取解耦**。
  - `ConfidenceGate`：`gate(facts, threshold)` → 保留 `confidence ≥ threshold` **或** `correction==true` 的事实；`correction` 事实被规范化为**高置信度**（`asCorrection()`→conf=1.0），从而绕过阈值门。
  - `FactMerger`：`merge(existing, incoming)` → **按 subject 去重覆盖**：incoming 覆盖同 subject 的 existing；incoming 内部同 subject 时 `correction` 优先、否则高 confidence 优先。**去重/替换，非盲目 append**。

- **D5 — `FactStore` 接口 + `MarkdownFactStore` 文件实现（与 Decorator 同路径）。** `load()`（读 `temp-memory.md` 解析回 `List<ExtractedFact>`，容错：解析不了的行跳过）/`save(facts)`（把事实写成**既人类可读又可解析**的 Markdown，含隐藏的 `subject`/`conf`/`category` 元数据）。`MarkdownFactStore` 与 Decorator 委托的 `FileSystemLongTermMemory` **指向同一个 `temp-memory.md`**（工厂用同一 `Path` 构造两者）→ 写走 store、检索走委托、天然一致。

- **D6 — `MemoryExtractionPipeline`（编排 + 优雅降级单点）。** `process(List<Msg> turn)`：`recentWindow(turn)`（取全量快照尾部近窗，默认最近 12 条 → 覆盖去抖合并进来的一小簇回合）→ `noiseFilter.filter` → 空则 return → `extractor.extract` → `gate.gate(,threshold)` → 空则 return（无耐久事实）→ `store.load()` → `merger.merge(existing, gated)` → `store.save(merged)`。**整体 try/catch → log.warn + return**（沿用压缩谱系「吞掉并记日志」）：抽取器抛异常、解析失败、IO 失败都不会打断回合。

- **D7 — `AsyncMemoryExtractionScheduler`（去抖 executor，`AutoCloseable`，单例共享）。** 单条 daemon `ScheduledExecutorService` + **单槽未决 Runnable** + `ScheduledFuture`：`submit(job)` = 替换未决 job + cancel 旧 future + 按 `debounceMs` 重排（**合并**：快速连续 N 次 submit → 只跑最近一次；因每个 job 闭包持最近**全量**快照，其近窗已含这一簇回合 → 不丢事实）；`flush()` = 立即跑未决（同步、阻塞）；`close()` = flush + shutdown + awaitTermination（**关闭无泄漏**）。全程 `synchronized` 保线程安全。**共享**：抽取 LLM 调用发生在该 scheduler 的后台线程 → 离开回合关键路径。

- **D8 — 默认 `enabled=false` 的取舍（记录理由）。** 抽取每（去抖）簇多打一次 LLM 调用（成本 + 后台延迟），且改变落盘内容格式。保守默认 **false** → 无配置时 `AgentBootstrap` 用 `SessionMemoryFactory.DEFAULT`（纯 `FileSystemLongTermMemory`）→ 与引入本能力前逐字节一致（对齐 `deferred-tools`/`exec-sandbox` 的默认关闭范式）。开启需显式 `memory.extraction.enabled=true`。`ExtractingLongTermMemory` 仍持 `BooleanSupplier enabled`（运行期读配置）→ 运行期关掉即刻降级到原始 record（graceful）。

- **D9 — `SessionMemoryFactory` 接缝（`pig-agent-session`）+ `SessionManager` 接线。** 会话层记忆的构造从 `SessionManager` 硬编码 `new FileSystemLongTermMemory(...)` 抽成 `@FunctionalInterface SessionMemoryFactory { LongTermMemory create(Path tempMemoryFile); DEFAULT = FileSystemLongTermMemory::new; }`。`SessionManager` 增一个**重载构造器**（多一个 `SessionMemoryFactory` 参数），旧 7 参构造器委托默认工厂 → 既有调用者/测试不破。`AgentBootstrap` 启用抽取时注入包装工厂（同 `Path` 建 `FileSystemLongTermMemory` + `MarkdownFactStore` + 共享无状态 pure 组件 + 共享 scheduler → `ExtractingLongTermMemory`）；关闭时注入 `DEFAULT`。

- **D10 — 接线顺序 + 生命周期（`AgentBootstrap`）。** 读 `memory.extraction`；`enabled` 时建**共享单例**：`LlmMemoryExtractor(() -> agentHolder.get().getModel())`、`MemoryNoiseFilter`、`ConfidenceGate`、`FactMerger`、`AsyncMemoryExtractionScheduler(debounceMs)`，组装成 `SessionMemoryFactory`（per-session 只 new 出 path-bound 的 `MarkdownFactStore` + `MemoryExtractionPipeline` + `ExtractingLongTermMemory`）传给 `SessionManager`。scheduler 存进 `Services` 的可关闭字段，`shutdownCommon()` 里 `close()`（flush 未决 + 停线程）。未启用：传 `DEFAULT`，不建 scheduler（无线程、无开销）。

## Risks / Trade-offs

- **R1 — 抽取质量依赖真模型，单测只锁确定性逻辑。** mock 模型只能证明「接线/门控/覆盖/去噪/去抖/降级」正确，**证明不了**抽取出的事实是否准确、分类是否合理。→ 真实质量留 `*IT`（本次不跑）；文档诚实标注。
- **R2 — 会话切换 + 去抖窗内立刻在新会话 record，旧会话最后一簇抽取可能被覆盖丢失。** 单槽 scheduler 的未决 job 会被新会话的 submit 替换。→ 记忆本就是 best-effort；同步 flush 会用 LLM 调用**阻塞会话切换**（差 UX），故不做；只保证关闭 flush。文档标注为可接受的 best-effort 边界。
- **R3 — 近窗 = 最近 12 条。** 一簇内超过近窗的更早回合的事实可能漏抽。→ 12 条 ≈ 6 轮，覆盖正常去抖簇；漏抽的事实下一簇仍可能被重抽（按 subject 覆盖天然幂等）。
- **R4 — 抽取写入结构化 Markdown，与旧原始 append 格式不同。** 若用户曾 `enabled=false` 跑出原始条目、后改 `true`：`MarkdownFactStore.load` 只解析得回带元数据的事实行，旧原始条目解析不回 → 不参与 merge，但**仍在文件里**、检索仍读到（尾部窗口）。→ 无数据丢失；混合格式仅影响 merge 覆盖旧原始条目的能力（可接受）。文档标注。
- **R5 — 不回归。** 默认路径用 `DEFAULT` 工厂 → `SessionManager`/`CompositeLongTermMemory`/注入半部/`/memory` 开关全绿；新增 8 参构造器不动 7 参既有调用点。

## 落实追踪表（评审/需求发现项 → 落点 + 状态）

| 发现项 / 需求 | 落点 | 状态 |
|---|---|---|
| 从「死记原始回合」升级为「抽取结构化事实」 | D1 Decorator + D6 pipeline；spec Req 2 | 已实现 |
| `MemoryExtractor` Strategy + 可 mock 接缝 | D2 接口 + `LlmMemoryExtractor` + `FactJsonParser`；spec Req 2/8 | 已实现 |
| 分类（user-preference/project-fact/reference）+ 置信度 [0,1] | D3 `FactCategory`+`ExtractedFact`；spec Req 2 | 已实现 |
| 置信度门：≥阈值留、<丢；阈值可配（默认 0.7） | D4 `ConfidenceGate`；config；spec Req 3 | 已实现 |
| 纠正 → 高置信度 + 按 subject 覆盖旧事实（非盲 append） | D4 `FactMerger`+`ExtractedFact.asCorrection`；spec Req 4 | 已实现 |
| 噪声过滤：剥离工具调用/结果 + 会话瞬时事件；纯谓词独立单测 | D4 `MemoryNoiseFilter`；spec Req 5 | 已实现 |
| 异步去抖写入：离开关键路径 + 合并快速回合 + 关闭 flush 无泄漏 | D7 `AsyncMemoryExtractionScheduler` + D1 record；spec Req 6 | 已实现 |
| 优雅降级：抽取失败 = log+skip，绝不打断回合 | D6 pipeline try/catch + D2 `LlmMemoryExtractor` 吞异常；spec Req 7 | 已实现 |
| 只动会话层；不动全局层写路径；注入半部不变 | D1（只包会话层委托）；不改 `CompositeLongTermMemory`/注入；spec Req 8 | 已实现 |
| 配置 `memory.extraction`（enabled 默认 false + threshold + debounce-ms） | config `MemoryConfig.MemoryExtractionConfig`；D8；spec Req 1 | 已实现 |
| 禁用 → 原始 record 逐字节等价 | D1 谓词 false 委托 + D8/D9 `DEFAULT` 工厂；spec Req 1 | 已实现 |
| 「用设计模式，别面向功能硬写」 | Decorator（`ExtractingLongTermMemory`）+ Strategy（`MemoryExtractor`）+ 纯函数（noise/gate/merger）+ 工厂接缝（`SessionMemoryFactory`）+ 枚举登记（`FactCategory`） | 已实现 |
| 抽取质量真模型验证 | R1；非目标 | 延后（*IT，本次不跑） |
| 跨会话事实合并 / 语义去重 | 非目标；只按 subject 去重 | 延后 |
| 会话切换同步 flush | R2；非目标（阻塞切换） | 延后（仅关闭 flush） |
