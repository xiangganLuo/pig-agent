# TUI / UX 与现有功能正确性硬化 — 2026-07-17

> 一轮由**真实使用视角**驱动的全量梳理：不只看代码是否整洁，更看**每项功能是否真能跑通、用户是否够得着**。方法 = 5 路只读评审（渲染 / 命令 / 会话状态 / 模型错误权限 / 启动生命周期）+ 2 路「功能可达性审计」（核心交互回路 / 触达与自动化），去重后落成多个文件归属互不重叠的修复批次，并行实现、逐个回归后合并 `main`。

## 修复清单

| # | 问题（真实使用发现） | 修法 | 验证 |
|---|---|---|---|
| F1 | 模型调用出错（429/502/403）把整坨异常堆栈喷进终端 | logback 控制台加 `%nopex`（堆栈仅进日志文件）；后又将控制台阈值设为 `ERROR`，启动/重建/未知字段等日志不再漏进 TUI；seed 空 `AGENTS.md` 消除原生每次 rebuild 的 WARN | 离线 |
| F1b | 模型错误裸奔（`getMessage()` 可能是 `null`→"Error: null"），无本地化/脱敏 | 纯 `ModelErrorMessages.friendly(Throwable)`（429/5xx/401/403/400/404/timeout/network 分类→中文一行，脱敏，null 安全）在 REPL 与 `ModelManager.test` 两处套用；重试可见（spinner 6s 后转「模型繁忙，重试中…」）；连通性探针加 25s 超时（不再永久卡住） | `ModelErrorMessagesTest` 15 |
| F2 | 退出重进会话「最新消息没加载」 | 源码坐实为**显示回放缺失**（数据/上下文一直安全，原生每轮从 store 重载）：新增会话进入时回放最近 8 条（`SessionReplay`，`⟳ 已恢复会话` CC 风格），启动 + `/session switch` 两处入口 | `SessionReplay`7 + `Replay`4 |
| F3 | 后台子 agent 运行时在 TUI 完全不可见（像卡死） | 后台 `agent_spawn`（`timeout_promoted`）渲染「已派发后台子 agent（task…）运行中」而非裸 JSON；完成经 `<system-reminder>` 自然浮现 | `BackgroundSpawn`3 |
| F4 | 单会话选「始终允许」后，下一轮又弹确认 | 安全评审 + spike 纠正（原方案「全局移除 ASK」= 安全回归，因裸模式默认 ASK 会**直接执行不弹确认**）→ 改为**会话级逐工具 ASK→ALLOW 置换**（`PermissionContextFactory` 不动，仅当前会话当前工具解遮蔽 + 写回会话槽持久化） | `AllowToolForSession`9 + spike7 + `ConfirmAlways`3 |
| F5 | 思考中是静态 `⋯ thinking` | braille 转圈动画 + 计秒（`⠹ thinking… (3s)`），仅真 TTY 动画、非 TTY 降级静态、单锁防交错 | `Spinner`4 + `ThinkingSpinner`12 |
| F6 | **渠道根本连不上**：无 `/channel` 命令、seed 只文档化的两个渠道是空壳、网关开关默认关且无处说明 | `/channel list\|add\|remove\|enable\|disable\|test`（仿 `/mcp`，掩码输入、list 只显安全标签、test 真连）；seed 露出**可用**渠道（钉钉/飞书/Webhook/Stdin）示例、明确标注 Telegram/Discord/Slack 为**占位未实现**；enabled+functional 渠道下轮自动启动（不再被网关开关静默拦死） | `ChannelCommand`9 等 92 |
| F7 | 定时任务「执行」只翻状态不干活；数字员工无法从 REPL 创建 | `/agent new … --mandate/--schedule/--allow` 让数字员工可创建（校验 cron）；`TaskScheduler` 增执行 seam，默认诚实「仅提醒」（不再假装执行） | `AgentCommand`9 + `TaskScheduler`4 |
| F8 | 渠道凭据明文写 `application.yaml`（无 0600） | 写入后 `restrictToOwner`→0600（POSIX；Windows no-op），对齐 `models.json`/`mcp.json` | `applicationYamlIsOwnerOnlyOnPosix` |

伴随的命令面/正确性修复（Fix-Cmd 批次）：`/config` 读实时态而非过期 YAML、`/tasks`+`/skills` 去 LLM 往返改直读、`/mcp list` 脱敏 URL、`/agent model` 校验 modelId、`/plan exit` 条件化、help 文本同步、`/notify test` 预检 + 真投递（查 robot `errcode`）+ 无出站能力提示。压缩差异化修复（Fix-State）：改用会话槽 + compress 后落盘（此前用错默认槽 + 不落盘 → `/compress` 永远 0、自动压缩从不触发）。

## 功能可达性审计结论（现状）
- **绝大多数功能确实可达可用**（审计已逐一清点）：`/model`/`/permission`（4 模式+HITL+命令级 allowlist）/`/memory`/`/session`/`/mcp`/`/agent`/`/plan`/`/skills`/`/notify`、工具 SPI、可用性降级、工具结果驱逐、Ctrl-C 中断、Web 控制台、钉钉/飞书真实收发、数字员工调度→执行→晨报。
- **本轮修复的「坏了/死代码/够不着」**：压缩（用错槽）、fallbackModel（全程 null 死代码→已接 `model.fallback-model-id`）、`/channel`（够不着→已建）、后台子 agent 隐身（已可见）、始终允许（跨回合失效）、定时任务执行（空转→诚实化）。

## 我替你拍的产品决策（可在 GitHub 复审后推翻）
1. **空壳渠道**（Telegram/Discord/Slack 出站是 log-only 假实现）→ **诚实降级**：明确标注「未实现/占位」，只把真能用的作为可用项。**未实现新网络客户端**（属新功能，留作后续）。
2. **定时任务执行** → 加执行 seam + 默认「仅提醒」并如实标注；真派发（经 agent 跑任务意图）需在 `AgentBootstrap` 接线，留作后续（跨模块、且更接近新能力）。
3. **「始终允许」作用域** = 本会话本工具（更安全）；跨重启仍靠 `rememberTool` 写配置 allowlist。

## 验证状态
- **离线全量回归**：`mvn clean test` 全 16 模块绿（本文档提交时的定版闸门）。
- **待真机验证**（需一个可用的默认模型——当前默认 doubao/火山 ARK 账户欠费 403）：模型错误分类真值、记忆 flush/consolidation 质量、渠道真实收发、压缩质量。切一个可用模型为默认后跑 `FullLinkAgentIT`/`PermissionEnforcementIT` 即可确认。

## 遗留 / 后续项
- 真派发定时任务（`TaskExecutor` 接线）、`snapshotResetHook` 生产接线（均为行为中性的可选增强，seam 已就位）。
- 实现真实 Telegram/Discord/Slack 客户端（新功能）。
- 子 agent「在线切进会话直连」（新功能；本轮只做了可见性）。
- 权限/记忆/画像/混合检索的真模型 `*IT`。

## 如何复审
`main` 分支的提交历史即评审材料——每个批次一个 `feat/fix/merge` 提交；归档的 spec 在 `openspec/changes/archive/2026-07-17-*/`，主 spec 已同步到 `openspec/specs/`。
