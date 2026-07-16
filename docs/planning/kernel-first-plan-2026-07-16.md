# 内核优先执行方案（2026-07-16 用户定调）

> 用户四点定调：①「CC 编排」只是背景举例，真正的编排取决于**内核的 tool 体系 + skills 体系**（**操作系统 ↔ 应用**关系）；②**AgentScope 2.0 全量推进**；③**生产化搁置**，先解决内核；④**主动外呼 + 通知**是刚需。
> 北极星见 `product-north-star.md` / 记忆 `pig-agent-positioning`。迁移地图见 `agentscope-v2-migration.md`（在 `av2/20260715-foundation` 分支）。

## 操作系统 ↔ 应用（心智模型）
- **内核（OS）** = tool 体系 + skills 体系 + harness（=AgentScope）。
- **应用（App）** = 编排（CC 或任何外部 agent）、数字员工、渠道机器人……都是跑在内核上的应用。
- 结论：**把内核做成强 OS，应用自然长出来**。不把"CC 编排"当基础设施，它只是内核之上的一个 app（= 一个工具 + 一个技能）。

## 优先级（本阶段）
1. **内核 · AgentScope 2.0 全量推进**（Track A，首要）。
2. **主动外呼 + 通知**（Track B，刚需，可与 A 并行——channel/scheduler 为主，框架耦合低）。
3. **tool/skill OS 强化**——**并入 v2 的 tools 阶段（Phase 2）** 做，用 v2 原生 Toolkit/permission，避免在 v1 重做。
4. **生产化：搁置**（`production-roadmap-2026-07-15.md` 标记 SHELVED）。

## Track A：AgentScope 2.0 全量推进

### 拓扑决策（关键）
- Phase-0 地基（`av2/20260715-foundation`）基于**旧 main**（不含今夜 6 个 deerflow 改进）。全量推进要求 v2 线携带全部差异化 → **v2 集成线重建在当前 `main`（`a4f82ee`，含 6 改进）之上**，把 Phase-0 的 POM→2.0 + core/providers 迁移作为**已验证模板**重新施加到当前 main。
- 旧 Phase-0 分支保留作参考；v1 `main` 保持稳定线；`main-v1-backup`+tag 为迁移前备份。

### 阶段序列（按迁移地图，L/High 集中在 tools-permission / session-state / cli-event-model）
- **Phase 0-redux（进行中）**：v2 线基于当前 main；POM→2.0（parent + providers→5 个 model extensions）；core + providers（含 6 改进的新 core 代码：loop/memory-extraction/context-engineering）在 2.0 编译 + 单测绿；保留 legacy hook 桥接。
- **Phase 1 · 叶子模块**：config / workspace / task / onboarding / model 在 2.0 编译。
- **Phase 2 · tools 内核（keystone）**：先编译过（legacy `PreActingEvent` 桥），再**原生权限重构**（删 `ToolPermissionHook`/`PermissionDeniedTool` → 原生 `PermissionEngine`+`PermissionContextState`+`PermissionMode`），带安全评审 + 真模型权限 IT；mcp/plugin/plugin-builtin/skills-builtin 随后重编。**tool/skill OS 强化在此阶段一并做**。
- **Phase 3 · session 状态重写**：`JsonSession`→`AgentStateStore`（`JsonFileAgentStateStore`，`(userId,sessionId)`）；per-session `RuntimeContext`；旧 `workspace/sessions/*` 数据迁移；此处定 compression/memory「原生 vs 自研 port」。
- **Phase 4 · 前端**：cli（`renderStream` 改吃 `streamEvents` 的 ~28 个 typed `AgentEvent`；`AgentBootstrap` 接原生 retry/permission/state）→ web（共用 event mapper）+ channel；重表达 `*IT`。
- **Phase 5 · 清理**：删被取代的 decorator/hook（RetryingModel/InterruptibleModel/EphemeralMemoryContextHook 注入半→middleware）；按选择接原生 compaction/skills；移除 1.x `agentscope` 依赖 + parent POM 的 `agentscope.version`。

### 保留（差异化，port 不删）
CC 风格 REPL/渲染、`/ls` 流水线、数字员工三段式晨报、运维命令、中文 UX、kernel façade、多 agent（`Toolkit.copy()`）、协议按标准设计、工具契约/可用性/SSRF/凭据守卫、MCP 动态管理 + 插件 SPI + 内置技能。

### 删（换原生）
`RetryingModel`+`InterruptibleModel`（原生 `.maxRetries`/`.fallbackModel`/`interrupt()`）、`ToolPermissionHook`+`PermissionDeniedTool`（原生 `PermissionEngine`）、自建 session store（`AgentStateStore`）、`EphemeralMemoryContextHook` 注入半（`onReasoning` middleware，已有 PoC）；候选：`CompressionService`/两层记忆（原生 `CompactionConfig`，Phase 3 定夺——port UX、换底层机制）。

### 推进方式
v2 是 breaking 大迁移、跑在独立 v2 线（不进 main 直到验证完）。以**迁移地图为设计**，按阶段用自主 subagent 施工、每阶段"2.0 编译 + 单测绿"为验收，v2 线上提交；**每阶段完向用户汇报再进下一阶段**（高风险，不一次性盲跑五阶段）。

## Track B：主动外呼 + 通知（刚需）
- 能力：助手**主动发起**联系——定时/事件触发 → 通过渠道**出站推送**（晨报、提醒、遇事请示 y/n）。
- 落点：pig-owned 为主（`TaskScheduler` 触发 + 渠道出站 + `ChannelRegistry`/`ChannelType` 枚举 + 策略），框架耦合低。
- landing：先在 **v1 `main`** 作为独立 `/ls` spec 交付（ship 刚需 + 保持稳定线），port 到 v2 成本低（非 v2 重写的部分）。设计模式导向（枚举登记 + 策略 + 触发器抽象）。

## 分支拓扑
- `main` = v1 稳定线（含 6 改进，`a4f82ee`）——Track B 落这里。
- `av2/20260716-*` = v2 集成/各阶段（基于当前 main）——Track A。
- `av2/20260715-foundation` = Phase-0 参考。`main-v1-backup`+tag = 备份。
