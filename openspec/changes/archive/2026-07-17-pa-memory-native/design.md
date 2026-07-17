## Context

pig 记忆现状（代码事实）：`CompositeLongTermMemory`（`io.agentscope.core.memory.LongTermMemory` 实现）合并**全局**（`workspace/context/memory.md`）+ **会话**（`sessions/{id}/temp-memory.md`）两层；`retrieve` 带来源标注读两层，但 `record()` **只写会话层**（L56-63，`sessionMemory.record(...)`）。`SessionManager.activate → setSessionMemory(fresh)` 在 `/session new` 换掉会话层。→ durable 事实随会话消失（本 bug）。`FileSystemLongTermMemory.MIN_TEXT_LENGTH=20` 附带丢短事实。注入经 `EphemeralMemoryMiddleware`（`MiddlewareBase`）在 `onReasoning` 把 `retrieve` 结果作为 user 侧末尾 msg 注入（ephemeral、system prompt 字节恒定），`onAgent` 后 `record`；`CachingLongTermMemory` 回合内缓存检索。A4 抽取（`memory/extraction/*`，默认关、仅会话层）与 A5 压缩（`compression/*`，仅内存对话）为独立子系统。`PigAgent.Builder.build()` 对 vehicle `disableMemoryHooks()/disableMemoryTools()/disableCompaction()`（L500-509、L626-634），即**关掉了 2.0 原生两层记忆**，改用自建。

2.0 原生（`docs/harness/memory.md`）：日志层 `memory/YYYY-MM-DD.md`（append/raw/不去重）→ 固化层 `MEMORY.md`（LLM 合并去重、整文件重写、**每步注入 system prompt**、工作区级跨会话）；flush（每 `call()` 末，异步，`flushTrigger` 可调）+ consolidation（后台节流 `consolidationMinGap≈30min`）两段 LLM，均可经 `MemoryConfig.model(...)` 用廉价模型；`memory_search`（关键词≤30）/`memory_get`/`session_search`。

版本现实：本机 m2 仅 `agentscope-1.0.12.jar`，2.0 jar 不在本地——原生记忆 API **文档可证、字节码不可证**。

## Goals / Non-Goals

**Goals**
- 修跨会话 bug：会话 A 陈述的用户事实在 `/session new` 后的会话 B 被召回（`CrossSessionMemoryTest` 第三条断言转 GREEN）。
- 采用 2.0 原生两层记忆（工作区级 `MEMORY.md`），flush/consolidation 用廉价模型（Doubao lite）。
- 修短事实丢失（flush 由 LLM 决定落什么，无 `MIN_TEXT_LENGTH` 字数过滤）。
- 重开 `memory_search`/`memory_get`/`session_search`。
- 退役自建两层记忆 + A4 抽取，删冗余代码；保留 A5 压缩。

**Non-Goals**
- 用户画像 `USER.md`（独立 spec `user-profile`）。
- 混合 BM25+向量检索（独立 spec `hybrid-memory-search`）。
- 自主沉淀 skills（独立 spec `autonomous-skills`）。
- 改 A5 压缩逻辑 / 权限 / 渠道 fail-closed。
- 多用户/多租户记忆隔离（pig 单用户）。

## Decisions

- **D0 — 前置 javap 验证（P0 阻塞）**：编码前必须对真实 2.0 jar `javap` 确认 `HarnessAgent.Builder.memory(MemoryConfig)`、`MemoryConfig`（字段/默认/`model`/`flushTrigger`）、`MemoryFlushManager`/`MemoryConsolidator`、`memory_search`/`memory_get`/`session_search` 工具名与签名。文档默认值（`consolidationMaxTokens=4000`、`consolidationMinGap=30min`、`flushTrigger=always` 等）均以 javap/真 jar 为准。
- **D1 — 采用原生、退役自建（设计 OD1=P1-A）**：`PigAgent.Builder` 去 `disableMemoryHooks()/disableMemoryTools()`，接 `MemoryConfig`；退役 `CompositeLongTermMemory`/`FileSystemLongTermMemory`/A4。理由：修 bug 开箱、对齐蓝本、北极星 harness 外包、删自建。备选（扩自建/外接）见设计 P1，均劣。
- **D2 — 工作区级固化层修跨会话**：`MEMORY.md` 为工作区单文件、按日期日志、**无会话隔离**——个人助理要事实跨会话，这正是修复方向。会话层退役（设计 P6-A）。
- **D3 — 注入策略默认原生 system-prompt（设计 OD2=P2-A，待用户确认）**：`MEMORY.md` 冻结进 system prompt（会话内稳定→前缀缓存友好；记忆进入可缓存前缀比 pig 现「末尾每轮重发」更省；consolidation 时偶发一次 miss）。若用户选 OD2-B，则保留 `EphemeralMemoryMiddleware` 读 `MEMORY.md` 注入 user 侧（复杂度更高）。
- **D4 — 廉价模型接线**：`MemoryConfig.model(Doubao lite Model 实例)`；pig 用自有 `ModelManager.modelFor(...)` 取 `Model` 实例注入（非原生 `ModelRegistry.resolve("provider:model")`）。flush/consolidation 走廉价模型，主推理模型不变（设计 OD8）。
- **D5 — flush 无字数过滤**：原生 flush 由 LLM 按 `flushPrompt` 决定抽什么，替代 `MIN_TEXT_LENGTH=20`；短事实（「我叫 X」）不再被丢。
- **D6 — 保留 A5、正交**：继续 `disableCompaction()`；pig A5 作用内存对话，原生记忆作用长期落盘，二者不冲突（`MemoryConfig` 与 `CompactionConfig` 为独立 builder 项）。
- **D7 — `/memory` 开关映射**：`/memory off` → `flushTrigger(NEVER)` + 不注入（或按 javap 结果用 `disableMemoryHooks` 语义），`retrieve`/flush 均 no-op；`/memory on` 恢复。对用户可见语义（记忆参与/关闭）不变。
- **D8 — 旧数据迁移（设计 OD9，待用户确认）**：默认一次性把旧 `workspace/context/memory.md` 迁入 `MEMORY.md`（幂等、带备份 `.bak`、失败降级为「新库从空开始、旧文件只读保留」，沿用 2.0 session 迁移的容错做法）；会话层 `temp-memory.md` 不迁。
- **D9 — 退役兼容 shim**：若 `pig-agent-core`/session 别处仍依赖 `LongTermMemory` 读记忆，提供一个实现 `LongTermMemory` 的薄 shim 转调原生（或改调 `memory_search`），避免大爆炸式改动；否则直接删除。**实现结论**：无需 shim——`LongTermMemory` 依赖只有 pig 自建栈（`CompositeLongTermMemory`/`FileSystemLongTermMemory`/`CachingLongTermMemory`/`EphemeralMemoryMiddleware`/A4）本身与其单测，全部随本变更删除；session 层改用新的 `MemoryToggle`（见附录 B）。直接删除，不留 shim。

## 附录 B — Task 0 javap 验证结果（真 2.0 jar，编码前）

> 本机 m2 **确有** 2.0 jar + sources：`agentscope-harness-2.0.0.jar`(+sources)、`agentscope-core-2.0.0.jar`(+sources)、五个 `agentscope-extensions-model-*-2.0.0.jar`。以下经 `javap` + sources 交叉核对，**与文档假设一致，无实质性偏差** → 按计划推进。

**B1 `io.agentscope.harness.agent.memory.MemoryConfig`（+ `Builder`）** — 字段/默认与文档一致：
- `Builder`: `model(io.agentscope.core.model.Model)`（**接受 Model 实例，正是 OD8 所需**——pig 用自有 `ModelManager.modelFor` 注入 Doubao lite）、`model(String)`（走 `ModelRegistry.resolve`，pig 不用）、`flushPrompt(String)`、`consolidationPrompt(String)`（须含 2 个 `%d`）、`consolidationMaxTokens(int)`、`consolidationMinGap(Duration)`、`dailyFileRetentionDays(int)`、`sessionRetentionDays(int)`、`flushTrigger(FlushTrigger)`、`build()`；`MemoryConfig.defaults()`。
- 默认：`DEFAULT_CONSOLIDATION_MAX_TOKENS=4000`、`DEFAULT_CONSOLIDATION_MIN_GAP=Duration.ofMinutes(30)`、`DEFAULT_DAILY_FILE_RETENTION_DAYS=90`、`DEFAULT_SESSION_RETENTION_DAYS=180`、`flushTrigger` 默认 `FlushTrigger.always()`；`model()` 默认 `null` → 用 agent 主模型。
- `FlushTrigger`：`always()`/`never()`/`throttled(Duration)`（`Duration.ZERO`→`always`）；`FlushMode{ALWAYS,NEVER,THROTTLED}`。

**B2 `HarnessAgent.Builder`** — `memory(MemoryConfig)`、`disableMemoryHooks()`、`disableMemoryTools()`、`workspace(Path)` 均确认。`build()` 内（`HarnessAgent.java:2132-2166`）：`Model memoryModel = memoryConfig.model()!=null ? memoryConfig.model() : model;` `if (memoryModel!=null && !disableMemoryHooks)` → 装 `MemoryFlushMiddleware`（flush，写日志层）+ `MemoryMaintenanceMiddleware`（consolidation，重写 `MEMORY.md`）；`if (!disableMemoryTools)`（`:2236-2240`）→ 注册 `memory_search`/`memory_get`/`memory_save`/`session_search`(+`session_list`/`session_history`)。**记忆钩子/工具装到 `agentToolkit = this.toolkit.copy()`（`:1959`）——即 pig 原 toolkit 的副本，故原生记忆工具不经 pig `ToolContractGuard` 包裹、也不入 pig 权限上下文的 tool-names（无 per-tool 规则→走 mode 默认；均为记忆文件读写，安全）**。

**B3 记忆工具签名/工具名**（javap + sources）：`MemorySearchTool.memorySearch(RuntimeContext, String)` → `@Tool name="memory_search" readOnly=true`（关键词，扫 `listMemoryFilePaths`）；`MemoryGetTool.memoryGet(RuntimeContext, path, startLine, endLine)` → `memory_get`；`MemorySaveTool.memorySave(RuntimeContext, content)` → `memory_save`（**纯文件 append，无 LLM**：`appendUtf8WorkspaceRelative(rc, MEMORY.md, ...)` + 日志层 `memory/YYYY-MM-DD.md`）；`SessionSearchTool` → `session_search`/`session_list`/`session_history`。工具均为反射 `@Tool`（非 `ToolBase`）。

**B4 落盘布局**（`WorkspaceConstants`）：`MEMORY_MD="MEMORY.md"`（工作区根）、`MEMORY_DIR="memory"`（日志层 `memory/YYYY-MM-DD.md`）；**工作区级、非按会话**——`memorySave(rcA)` 写、`memorySearch(rcB)` 读命中同一 `MEMORY.md`，即跨会话修复点。这使**确定性离线测试**成立：走真 `WorkspaceManager` + 原生 `MemorySave/SearchTool`（无 LLM），验证会话 A 写、会话 B 读召回。

**B5 注入路径的关键发现（对计划的调整，已按 Task 0 指示记录）**：原生把 `MEMORY.md` 注入 system prompt 的**唯一路径是 `WorkspaceContextMiddleware`（`onSystemPrompt`），仅受 `!disableWorkspaceContext` 开关**（与记忆钩子无关）。但该中间件同时注入一大段 workspace/session/guidance 文本，其 `GUIDANCE_TEMPLATE`/workspace 段**引用原生工具名 `read_file`/`grep`/`glob`/`write_file`/`edit_file`——pig 并不注册这些（pig 用 `readFile`/`writeFile`/`listDirectory`/`executeCommand`）**，整体开启会向模型注入「不存在工具」的指引=质量回归。→ **调整**：pig **保留 `disableWorkspaceContext()`**（继续自控 system prompt），**采用原生存储（flush/consolidation 钩子 + memory 工具全开、写 `MEMORY.md`/日志层）**，但**注入由 pig 自有极简中间件 `NativeMemoryContextMiddleware.onSystemPrompt` 完成**——只把原生 `MEMORY.md` 内容追加进 system prompt（OD2-A 的忠实实现：会话内稳定、consolidation 后偶发一次失效），不带原生的错误工具指引。存储全原生、仅注入格式化归 pig，符合北极星与 spec「固化层为注入来源」。

**B6 `/memory on|off` 语义映射**：`flushTrigger` 在 build 时固定、无法运行时改；故 `/memory` 切换 = 更新 `memory-enabled` 配置 + **重建 interactive/channel agent**（复用 `/mcp` 变更同款重建回调）。重建时按当前 `memory-enabled` 决定：开 → 传非 null `MemoryConfig`（记忆钩子/工具 + 注入中间件全上）；关 → 传 `null`（`disableMemoryHooks/Tools` + 无注入中间件）= 退役前的禁用态，**关闭即不注入、不 flush**（满足 spec）。

## Risks / Trade-offs

- **R0 — 2.0 记忆 API 与文档不符**：文档默认值/方法名可能与真 jar 有出入。→ D0 前置 javap；tasks 第 0 组阻塞后续。
- **R1 — 注入进 system prompt 破坏 pig 字节恒定洁癖**：consolidation 重写 `MEMORY.md` 后下一轮 system prompt 变一次。→ consolidation 后台节流（≥30min）罕见；Hermes 同法且称保护前缀缓存；净收益（记忆进可缓存前缀）为正。测试断言**会话内**（consolidation 未触发时）system prompt 稳定。
- **R2 — 记忆跨会话「串味」**：工作区级记忆对所有会话可见。→ 对个人助理（单用户）**正是所需**；非 bug。多用户隔离非目标。
- **R3 — flush LLM 质量/成本**：每回合 flush LLM 调用累积成本 + 抽取质量依赖模型。→ 廉价模型（Doubao lite）+ `flushTrigger` 节流（THROTTLED）可调；质量由真模型 `*IT` 验证（离线只测接线/开关/迁移的确定性逻辑）。
- **R4 — 退役自建引入回归**：删 `CompositeLongTermMemory`/A4 可能牵动 session/compression-lineage/`CachingLongTermMemory` 等依赖。→ D9 薄 shim 或逐依赖迁移 + 回归既有单测；`context-memory-efficiency` 的记忆缓存需求随之消解（归档时同步主 spec）。
- **R5 — 数据迁移丢失/损坏**：迁移旧 `memory.md` 出错。→ D8 幂等 + `.bak` 备份 + 失败降级只读；迁移单测。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| `record` 只写会话层是跨会话根因 | D1/D2；采用工作区级 `MEMORY.md` | 落 tasks 1/2 |
| `MIN_TEXT_LENGTH=20` 丢短事实 | D5；flush 无字数过滤 | 落 tasks 2 |
| pig `disableMemoryHooks/Tools` 关掉了原生两层记忆 | D1；FLIP 开关 | 落 tasks 1 |
| 历史理由针对旧废弃 `StaticLongTermMemoryHook`（非 2.0 两层记忆） | proposal Why + 设计 §3/P1 | 已澄清（OD1 待用户） |
| 2.0 记忆 API 本地不可 javap | R0/D0 | **P0 前置**（tasks 0） |
| 注入策略权衡（原生 system-prompt vs pig user 侧 ephemeral） | D3/R1；OD2 | 待用户拍板 |
| 廉价模型 flush/consolidation | D4；OD8 | 待用户确认型号 |
| A5 压缩保留正交 | D6 | 落 tasks 1 |
| 会话层记忆去留 | D2；设计 P6-A | 落 tasks 2（去掉） |
| 旧数据迁移策略 | D8/R5；OD9 | 待用户拍板 |
| 退役牵动依赖（session/lineage/cache） | D9/R4 | 落 tasks 2/3 |
| 用户画像/混合检索/自主 skills 不在本 spec | Non-Goals | 延后 spec-2/3/4 |
</content>
