# AgentScope 2.0 全量迁移 —— 交付总结 & 提升就绪清单（2026-07-16）

> 用户指令：核心能力全量迁移 2.0、harness 外包给框架、pig 专注差异化；"全部推进，只验收最终结果"。
> 本文件 = 可验收的最终结果 + 提升为主线的前置清单。

## 一句话
**pig-agent 已完全跑在纯 AgentScope 2.0 制品上**（无 1.x 依赖、无 deprecated hook），自研 harness 大量换成原生，差异化全部保留，与 v1 main 特性对齐，**整库 1093 测试绿**，安全发现（含一个 P0）全部闭环。v2 线 = `av2/20260716-foundation-main`（HEAD `325fcd7`）。

## 已完成（v2 线，Phase 0-redux → 6c）
| 领域 | 做法 | 状态 |
|---|---|---|
| POM / 制品 | `agentscope` 1.0.12 → `agentscope-core`+`agentscope-harness`+5×`extensions-model-*` 2.0.0；**移除 1.x 依赖 + `agentscope.version`** | ✅ 纯 2.0 |
| Agent 载体 | `PigAgent` 包 `HarnessAgent`（直接 `builder()`，javap 证只一次 `toolkit.copy()`）；`disable` 原生 memory/compaction/filesystem/shell/subagents-default 保 pig 差异化 | ✅ |
| 权限 | 删 `ToolPermissionHook`/`PermissionDeniedTool` → 原生 `PermissionEngine`+`PermissionContextFactory`；plan→EXPLORE/ask→DEFAULT/auto→ACCEPT_EDITS/bypass→BYPASS；**P0：`GuardedAgentTool extends ToolBase` 恢复被守卫工具的引擎门控**；命令级 allowlist（`CommandPermissionTool`）；HITL 经 `RequireUserConfirmEvent` | ✅ 安全复核过 |
| 状态/会话 | `SessionManager`/`JsonSession` → 原生 `AgentStateStore`+`RuntimeContext`；pig `Session` 元数据/lineage/`/session` 作 sidecar | ✅ |
| retry/中断 | 删 `RetryingModel`/`RetryPolicy`/`TransientErrorClassifier`（原生 retry 分类是超集，含 429）+ `InterruptibleModel` → 原生 `.maxRetries`/`.fallbackModel`/`interrupt()`；保 `InterruptController`/`TurnHandle` | ✅ |
| hook→middleware | `EphemeralMemoryContextHook`（prefix-cache 语义保留，6 测证）/`LoopDetectionHook`/日志 hook → `MiddlewareBase` | ✅ |
| 事件模型 | `stream/Event` → `streamEvents/AgentEvent`（28 类）；CC-REPL 渲染保留 | ✅ |
| 子agent | 原生委派 `agent_spawn/agent_send/...` + `workspace/subagents/*.md` + 后台反向通知；**从 pig 侧注入父权限上下文**堵住 2.0.0 的 `inheritParentPermissions` 缺口（父 DENY 真绑子）；安全默认 ON；保 `/agent` 对等切换 | ✅ |
| 工具结果驱逐 | 原生 `.toolResultEviction`（>80K→磁盘占位）——补 pig 缺口 | ✅ |
| 差异化保留 | CC-REPL/TUI · 多协议大脑 `ModelManager` · **ephemeral prefix-cache 记忆 + A4 抽取** · **A5 上下文增强压缩** · 数字员工三段晨报 · `/ls` 流水线 · 工具契约/SSRF/凭据守卫 · MCP/插件/技能 SPI | ✅ 不动 |
| 主动外呼 | 移植到 v2 线（parity）；send seam 用 pig 自研渠道 | ✅ |

**关键决策（据北极星）**：压缩（A5）+ 记忆（ephemeral prefix-cache）是**差异化**，不换原生——外包只针对无差异化管道。已在迁移文档记录。

## 安全
- **P0（v2 迁移引入、提升前逮到）**：Phase 4 换原生权限后，`ToolContractGuard` 把工具包成非 `ToolBase` 的 `GuardedAgentTool` → 原生引擎对其自动 ALLOW，**权限执行在真实 toolkit 上失效**。Phase 6b 修复（`extends ToolBase`），RED→GREEN 实测。v1 main 用旧 hook，不受影响。
- Phase 2 安全复核 + 后续发现（C-1/H-1/H-2/M-1/M-2/M-3/L-1/L-2）**全部闭环**。子agent 权限继承实测"父禁子禁"。

## 分支拓扑
- `main` = **v1 稳定线**（`4976dc8`）：6 deerflow 改进 + 主动外呼 + `agentscope-expert` 技能，全绿；生产可用。
- `av2/20260716-foundation-main` = **v2 迁移线**（`325fcd7`）：纯 2.0、parity、1093 绿。
- `main-v1-backup` + tag `v1.0.12-final` = 迁移前备份。
- `av2/20260716-*`（tools-permission/session-state/frontends/cleanup/harness-adopt/subagents/subagent-perms/outreach-port/harness-spike）= 各阶段审计轨迹。

## 提升为主线（v2 → main）前置清单
> **提升是重大不可逆切换。P0 已证"离线绿 ≠ 生产正确"。故建议在真模型/真渠道验证通过后再切换，且需你确认。**

**必须（阻塞提升）——依赖额度/端点，当前离线做不了：**
1. **真模型 `*IT`**（待 API 额度）：权限 DENY 端到端（模型收 DENIED 继续）· 会话跨切换/重启持久化（`JsonFileAgentStateStore`）· 子agent 委派 + 后台反向通知 + 子agent 权限继承 DENY · 原生 retry 瞬时/永久分类 · >80K 工具结果驱逐往返。
2. **真渠道验证**（待端点）：主动外呼出站（飞书/钉钉 webhook）实发。

**可选增强（不阻塞，提升后迭代）：**
3. 原生 **Gateway 渠道内核 + 原生适配器**（飞书/钉钉/GitHub/GitLab/企业微信）替手搓渠道传输；`expose_to_user` 子agent 经 Channel 直连。
4. 原生 **Plan Mode**（`enablePlanMode` + `PLAN.md` + HITL），与 pig 现有 EXPLORE-plan 整合。
5. `JsonFileAgentStateStore` 旧 `workspace/sessions/*` 数据迁移工具（当前 fault-tolerant "读不了就重来"）。

**建议**：额度到位→跑真模型 `*IT`（上面 1）→绿则连同真渠道抽验→你确认后执行 v2→main 切换（v1 已 tag 备份，可回滚）。切换前不动 `main`。

## 结论
v2 迁移的**离线可达部分全部完成**：pig 在纯 2.0 上编译+单测全绿、与 v1 对齐、差异化保留、安全硬化（含 P0）。剩余只有**真模型/真渠道验证（额度/端点门）**与**可选增强**。`main` 保持 v1 稳定不动，直到你在真验证后拍板切换。
