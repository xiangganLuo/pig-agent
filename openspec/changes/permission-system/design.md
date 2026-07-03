## Context

需求经 office-hours 会话确认（2026-07-03）：对标 Claude Code / opencode / hermes 的权限级别。当前工具执行链：`AgentRepl.streamToAgent` → `PigAgent.stream` → `ReActAgent.stream` → 工具执行；除 MCP 的 D-SEC 门（`McpTool.applyAddPolicy`）外无通用审批。约束：AgentScope 1.0.12；领域类型不可变（record + `withXxx`）；`application.yaml` 向后兼容；本机 Maven 经 wrapper 可编译验证。可复用形态：`McpConfirmer` + `AtomicReference<LineReader> readerRef`（PigAgentCli 现有 y/N 确认）、`ConfigurationManager.updateConfig(Consumer)`、Hook 体系（`LoggingHook`/`ToolCallLoggingHook` 示范 `PreActingEvent` 用法）。

已确认的关键事实（javap 静态确认 `agentscope-1.0.12.jar`）：
- `Hook.onEvent(T):Mono<T>`，`priority()` 越小越先执行；现有 hook 只观察不改。
- `PreActingEvent extends ActingEvent`，暴露 `getToolUse():ToolUseBlock`、`getToolkit()`，并有 **可写** `setToolUse(ToolUseBlock)` → 能在工具执行前改写待调用工具。
- 存在 `io.agentscope.core.interruption.InterruptContext` 与 `ReActAgent.handleInterrupt(...)` → "中止一次动作"是框架一等公民，否决工具调用有可靠落点。

## Goals / Non-Goals

**Goals:**
- 四种权限模式 `plan`/`ask`/`auto`/`bypass`，运行时经 `/permission mode` 热切换。
- 单一拦截点统一覆盖**内置工具 + MCP 工具**；逐工具风险分级 + 逐命令粒度审批。
- plan 模式为真·只读：拦截所有可变工具，agent 只产出计划。
- allowlist（工具 / 命令）持久化到全局配置；`a`（always）一次确认长期免问。
- 复用 `McpConfirmer`/`readerRef` 做交互确认；非交互渠道有明确兜底。

**Non-Goals:**
- 独立 `exitPlanMode` 工具与"计划卡片 → 一键执行"的富交互（v2 增强；v1 用 REPL 提示 + 手动切模式）。
- 会话级 / 目录级权限覆盖（本次仅全局配置，已决策）。
- 逐参数细粒度策略引擎、正则命令白名单 DSL（v1 用规范化命令键做前缀匹配）。
- 审计日志持久化（v1 仅 stderr 记录 deny/confirm）。

## Decisions

- **A：Hook 统一门（`PreActingEvent`）。** 一个 `ToolPermissionHook`（`priority` 取小值，早于 `LoggingHook(50)`）在每次工具执行前判定。备选 B（逐工具装饰器）被否——MCP 工具经 `McpClientWrapper` 注册，装饰器覆盖不到，而 MCP 是本产品重点；备选 C（回合级）被否——做不了一个回合内的逐工具审批。A 是唯一同时满足"四模式 + 逐工具/逐命令 + 内置&MCP 统一覆盖"的路径。
- **模式语义。**
  - `plan`：READ_ONLY 放行；WRITE/EXEC/NETWORK/MCP_ADMIN **一律否决**，回传"plan 模式下不执行，请先产出计划"。
  - `ask`（默认）：READ_ONLY 放行；可变工具人工确认（`y` 本次 / `n` 拒绝 / `a` 始终允许该工具或命令）。
  - `auto`：READ_ONLY + WRITE + NETWORK 自动放行；EXEC + MCP_ADMIN 仍确认（对标 acceptEdits）。
  - `bypass`：全部放行、无提示（完全信任）。
- **风险分级（`ToolRiskClassifier`）。** 默认按工具名映射：READ_ONLY（read/list/search/test/skills-list…）、WRITE（writeFile 及文件变更）、EXEC（shell executeCommand）、NETWORK（web fetch 出网）、MCP_ADMIN（mcp add/remove/edit）。`permissions.tool-overrides` 可重分类。未知工具**默认按 EXEC**（fail-safe，最严）。
- **D-SEC 组合而非重复。** MCP_ADMIN 工具（`McpTool.addMcpServer`/`removeMcpServer`）已有 D-SEC 门（allow-add/allow-remove/白名单/人工确认）。权限 hook 对 MCP_ADMIN **不再重复弹确认**，仅按模式决定"是否进入 D-SEC"（plan 否决；ask/auto 放行给 D-SEC 自行门控；bypass 放行给 D-SEC——D-SEC 默认仍关，双保险）。
- **逐命令粒度。** EXEC 确认时展示实际命令；`a` 记住**规范化命令键**（首 token，如 `git`；或整条命令，视实现 spike）写入 `allowlist.commands`。匹配用前缀/键相等，v1 不做正则 DSL。
- **否决机制（承重，step-0 spike 定）。** 候选：(a) `setToolUse` 改为一个"权限拒绝"哨兵，让 toolkit 解析出拒绝结果回传模型；(b) 返回 `Mono.error(PermissionDeniedException)` 经 `handleInterrupt` 冒泡；(c) 让工具本身返回拒绝串（需工具配合，最不干净）。spike 选出既能"不执行"又能"把拒绝原因回传给模型继续对话"的最稳写法。
- **交互确认在响应式流内阻塞。** 复用 `McpConfirmer`（现有 `McpTool` 已在工具执行中同款阻塞 `readerRef.readLine`，有先例）。spike 一并确认 hook 内阻塞不会死锁 reactor 线程（必要时 `subscribeOn`/`publishOn` 到 blocking scheduler）。
- **非交互渠道兜底。** 渠道回合用 `permissions.channel-mode`（默认 `auto`）。当判定需要交互确认但**无 confirmer**（渠道无终端）时，**fail-closed 拒绝**并回传清晰说明（EXEC/MCP_ADMIN 在渠道 auto 下即属此列）。运维要让渠道完全放行须显式设 `channel-mode=bypass`。

## Risks / Trade-offs

- **[承重] `PreActingEvent` 否决语义未验证** → step-0 spike；若三种写法都无法干净否决，回退：hook 改为在 `PreActingEvent` 把工具参数替换为拒绝 + 依赖工具侧读取"权限上下文"短路（次优，需最小工具改造）。
- **[兼容/意外] 默认 `ask` 改变现有行为** → 老用户升级后危险工具开始要确认（此前等同 bypass）。缓解：文档/首启提示明确说明，并提供 `/permission mode bypass` 一键恢复旧行为。
- **[安全] `channel-mode=auto` 放行面** → 非交互渠道可自动跑 WRITE/NETWORK。缓解：EXEC/MCP_ADMIN 默认 fail-closed；`channel-mode` 可设 `deny`(=plan)/`ask`(无确认→全 fail-closed)/`bypass`。用户已知悉并选择 auto 为默认。
- **[并发] 渠道线程与 REPL 并发读 `readerRef`** → 确认交互串行化（同一时刻仅一个回合等待输入）；渠道回合不走 `readerRef`（走 channel-mode 兜底），无争用。
- **[可用性] plan 模式下 agent 反复撞否决** → 否决回传的消息要明确引导"产出计划而非执行"，并在 REPL 打印"plan 模式，切 `/permission mode ask` 执行"。

## Migration Plan

- 纯增量：`application.yaml` 无 `permissions` 块时用默认（`mode=ask`、`channel-mode=auto`、空 allowlist）。
- 行为变更提示：首次在默认 `ask` 下触发危险工具确认时，REPL 说明可用 `/permission mode` 调整；README 写明升级注意。
- 回滚：`/permission mode bypass` 或删除 `permissions` 块并设 bypass，即回到无门行为；功能可整体回退。

## Open Questions

- 否决机制最终写法（step-0 spike 结论）——决定 hook 是"改写 toolUse"还是"抛错中断"。
- `a`（always）记住命令的粒度：首 token vs 整条命令（spike 时按实际 `ToolUseBlock.getInput()` 结构定）。
- v2 是否引入 `exitPlanMode` 工具与计划卡片交互（对标 Claude Code 完整 plan→执行流）。
