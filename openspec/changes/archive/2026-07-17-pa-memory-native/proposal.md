## Why

pig 作为「24h 个人助理」却**记不住你**：会话 A 说「我叫罗湘赣」，`/session new` 后会话 B 问「我叫什么」→ 记不住（`bug/20260717-cross-session-memory` 的 RED `CrossSessionMemoryTest` 复现）。**根因（结构性，非 v2 回归）**：pig 自建 `CompositeLongTermMemory.record()` **只写会话层**，全局层（`workspace/context/memory.md`）只被 `retrieve` 读、从不被 `record` 自动写；`/session new` 换掉会话层 → durable 事实随之消失。附带诱因：`FileSystemLongTermMemory.MIN_TEXT_LENGTH=20` 把「我叫 X」这类短事实直接丢弃。这是 **v1 就有的设计缺口**。

按行业蓝本重设计（非打补丁）：AgentScope 2.0 `HarnessAgent` **原生**就实现了跨会话两层记忆——日志层 `memory/YYYY-MM-DD.md` → 固化层 `MEMORY.md`（**工作区级、每步注入 system prompt**），配 flush/consolidation 两段 LLM 流水线与 `memory_search`/`memory_get`/`session_search`。这与 Hermes（`MEMORY.md`+`USER.md`）、OpenClaw（日志→`MEMORY.md`）三家蓝本高度同构，且正是北极星「harness 外包给 2.0」的落点。**pig 目前主动 `disableMemoryHooks()/disableMemoryTools()` 关掉了它**——正是缺口来源；其历史理由（「native STATIC memory 注入被持久化累积」）针对的是**被废弃的 1.x `StaticLongTermMemoryHook`**，而非 2.0 两层记忆（后者 flush 落日志、consolidation 重写 `MEMORY.md`、只把 `MEMORY.md` 注入 system prompt，不写会话历史）。详见 `docs/design/personal-assistant-core-design.md`（§1/§3/P1）。

本 spec 为「个人助理核心」拆分的**首个、可独立上线**的能力（拆分见设计文档 §9），**直接修跨会话 bug**；用户画像 `USER.md`、自主 skills、混合向量检索为后续独立 spec。

## What Changes

- **采用 2.0 原生两层记忆**：`PigAgent.Builder` 去掉 `disableMemoryHooks()/disableMemoryTools()`，改由 `MemoryConfig`（`HarnessAgent` builder）驱动——日志层 `memory/YYYY-MM-DD.md` + 固化层 `MEMORY.md`（工作区级、跨会话、每步注入 system prompt）。
- **flush/consolidation 用廉价模型**：`MemoryConfig.model(Doubao lite 的 Model 实例)`（pig 用自有 `ModelManager` 注入 `Model` 实例，而非原生 `ModelRegistry`）跑 flush（每回合抽事实→日志层，**无字数过滤**）与 consolidation（后台节流合并→`MEMORY.md`）。
- **重开检索工具**：`memory_search`（关键词）/`memory_get`（行区间）/`session_search`（历史转录）随 memory tools 重开。
- **退役自建记忆**：`CompositeLongTermMemory` 两层、`FileSystemLongTermMemory`（含 `MIN_TEXT_LENGTH=20`）、A4 抽取（`memory-extraction`）——被原生 flush/consolidation 取代（去重、固化、无字数过滤，且工作区级）。会话层隔离退役（个人助理要事实跨会话）。可留实现 `LongTermMemory` 的薄 shim 过渡。
- **保留 A5 上下文工程压缩**：继续 `disableCompaction()`（pig `CompressionService`/`ContextEngineer` 与原生长期记忆正交）。
- **注入策略**（OPEN，见设计 OD2）：默认改采原生 system-prompt 注入（`MEMORY.md` 会话内稳定 → 前缀缓存友好，consolidation 时偶发一次失效）；`/memory on|off` 语义映射到 `flushTrigger`/hooks 开关。
- **旧数据迁移**（OPEN，见设计 OD9）：默认把旧全局 `workspace/context/memory.md` 一次性迁入新 `MEMORY.md`；会话层不迁。

**BREAKING**：记忆存储布局与注入位置变化（`MEMORY.md`/`memory/*.md` 取代 `context/memory.md`+`sessions/{id}/temp-memory.md`；记忆从 user 侧末尾改进 system prompt）。`/memory` 开关与「记忆进入模型输入」的对用户可见语义不变；跨会话记忆从「不可用」变为「可用」（修 bug）。

> ⚠ **实现前 P0（阻塞）**：2.0 的 `HarnessAgent`/`MemoryConfig`/`MemoryFlushManager`/`memory_search` 等 API 在本机**仅有文档、无字节码可证**（本地 m2 只缓存 `agentscope-1.0.12.jar`，2.0 jar 不在本地）。tasks 第 0 组要求先对真实 2.0 jar `javap` 确认 API 后再编码。

## Capabilities

### New Capabilities
- `pa-memory-native`: 采用 AgentScope 2.0 原生两层记忆（日志层→固化层、工作区级、跨会话），配廉价模型 flush/consolidation 与 `memory_search`/`memory_get`/`session_search`，使个人助理**跨 `/session new` 记住用户事实**；退役 pig 自建两层记忆 + A4 抽取 + 短事实字数过滤。

### Modified Capabilities
<!-- 归档时消解：本能力取代/收敛 `prefix-cache-context`（注入策略改原生，见 OD2）、`memory-extraction`（A4 退役，OD3），并使 `context-memory-efficiency` 的「回合内记忆检索缓存」（`CachingLongTermMemory`）随自建退役失去意义。这些主 spec 的调整在本 spec 归档（`/ls:archive`）时同步，不在本变更内改写它们。 -->

## Impact

- **代码**：`pig-agent-core`（`PigAgent.Builder` 记忆开关；退役 `memory/CompositeLongTermMemory`、`memory/FileSystemLongTermMemory`、`memory/EphemeralMemoryMiddleware`（注入策略见 OD2）、`memory/extraction/*`；接线 `MemoryConfig`）；`pig-agent-cli`（`AgentBootstrap` 记忆接线、廉价模型注入、旧数据迁移）；`pig-agent-session`（`SessionManager`/`SessionMemoryFactory` 不再 `setSessionMemory` 换层）；`pig-agent-model`（提供 Doubao lite `Model` 实例给 `MemoryConfig`）。
- **不改**：A5 压缩（`CompressionService`/`ContextEngineer` 继续 `disableCompaction()`）；skills 子系统（自主 skills 在独立 spec）；权限/沙箱/渠道 fail-closed 语义。
- **测试**：复用 `CrossSessionMemoryTest`（第三条断言转 GREEN）；新增 system-prompt 会话内稳定、`/memory off` 不注入不 flush、flush 无字数过滤、旧 `memory.md` 迁移、退役 shim 兼容 等断言；真模型 `*IT` 验证 flush/consolidation 实际落盘与跨会话召回。
- **文档**：`CLAUDE.md` 两层记忆段落改写为「采用 2.0 原生两层记忆（工作区级 `MEMORY.md` 跨会话 + 廉价模型 flush/consolidation），退役自建两层 + A4」；`docs/design/personal-assistant-core-design.md` 为设计事实源。
</content>
