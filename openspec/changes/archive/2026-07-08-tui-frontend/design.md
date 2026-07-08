## Context

现状三前端：`pig-agent-cli` 的 JLine3 逐行 REPL、`pig-agent-web` 的 `com.sun.net.httpserver` 控制台、channel 桥接。本变更**掉头**：不做 Lanterna 全屏 TUI，改为把既有 REPL 增强成 Claude Code 风格的行式前端，并移除 web。

已勘定的既有资产（本设计的直接基座）：
- `cli/repl/AgentRepl`：JLine `Terminal`（jna，`jansi(false)`）+ picocli 命令树 + `while(running)` 读行循环；非斜杠行经 `streamToAgent` 流式。
- `cli/repl/AgentRepl.streamToAgent`：当前经 **`agentHolder.get().stream(userMsg)`** 直接流式，`REASONING→[thinking]`、`TOOL_RESULT→[tool]`、`AGENT_RESULT` 累加后在 `doOnComplete` **整体打印**（非增量），`blockLast()` 阻塞主线程。
- `cli/repl/ReplCommands`：16 条斜杠命令（`/help /tasks /skills /config /protocols /model /agent /channels /session /mcp /permission /memory /compress /status /clear /quit`）+ 已有 y/N 确认与数字选择（`/model add`）。
- `cli/Ansi`：Jansi 字符串构造器（`prompt/info/success/warn/error/dim/heading/bold`），无 markdown。
- `AgentKernel.interruptCurrent()`：**已是真实现**（`interruptible-run` 已并入 main）——经 `InterruptController`/`TurnHandle` 取消在飞模型调用；但仅在经 `AgentKernel.chat()` 订阅的流上注册（`doOnSubscribe(interrupts.begin())`）。**REPL 现走 `agentHolder.get().stream()` 绕过了这一注册**。
- `AgentKernel.subscribeEvents()`：`KernelEvent` 热流（状态行可选订阅）。
- web 引用面（移除范围）：parent `pom.xml`、`pig-agent-cli/pom.xml`、`cli/PigAgentCli`、`cli/AgentBootstrap`（`webContext()`）、`cli/WebLauncher`、`config/PigAgentConfig`（`WebConfig`）、`pig-agent-cli/.../logback.xml`、`pig-agent-web/` 整目录。

## Goals / Non-Goals

**Goals:**
- 既有 CLI REPL 增强为 CC 风格行式前端：流式富渲染、斜杠补全菜单、状态行、Ctrl-C 真打断、轻量行内交互。
- 移除 `pig-agent-web` 整模块，前端面收敛到「内核 + 单行式 REPL adapter + channel」。
- 复用同一批 manager 实例（`ReplContext`），零业务逻辑重复；回合流程与既有斜杠命令行为不回归。
- Ctrl-C 经 `AgentKernel.interruptCurrent()` 真打断（借力已落地的 `interruptible-run`）。

**Non-Goals:**
- 不引入 Lanterna / 全屏多面板 TUI。
- 不删除 CLI REPL（它就是被增强的基座）。
- 不重建 Web 控制台（后置 web-console-v2）。
- 不改内核 / model / session / mcp / task / permission 的既有契约。
- 不移除 channel 桥接（`/channels` 只读展示照旧）。

## Decisions

- **D1 — 增强而非替换**：所有新能力叠加在 `cli/repl` 之上；`PigAgentCli.main` 仍启动 `AgentRepl`。不新增模块。渲染沿用 `Ansi`（Jansi 字符串构造器，绝不 `AnsiConsole.systemInstall()`——Windows 下 JLine 已拥有终端，双包裹会乱码）。
- **D2 — 流式改经内核 `chat()`**：`streamToAgent` 由 `agentHolder.get().stream(msg)` 改为 `agentKernel.chat(agentKernel.activeId(), msg)`。理由：只有经 `chat()` 订阅的流才注册可中断回合（`InterruptController`），Ctrl-C 才能真取消。事件类型不变（`Event`/`EventType`），映射逻辑复用。
- **D3 — 增量出字 + markdown→ANSI**：新增纯函数渲染器 `MarkdownAnsiRenderer`（`**粗体**`、`` `行内代码` ``、```` ``` 代码块 ````、`- 列表`、`# 标题` → `Ansi` 转义）。`AGENT_RESULT` 事件到达即增量写出（不再缓冲到 `doOnComplete`）。代码块按整块渲染（进入/离开 fence 时刷新），行内标记按已完成的行渲染，避免半个转义。渲染器是纯 `String→String`，单测覆盖。
- **D4 — 工具块与 spinner**：`TOOL_RESULT` 渲染为缩进块 `⏺ 工具名(参数摘要)` + `└ 结果摘要`；`REASONING` 阶段在行尾显示 spinner（`⋯ thinking`，用 `\r` 重绘），出答案/工具时清除。**凭据脱敏**：工具参数/结果里 apiKey、mcp env/headers 等敏感字段一律不渲染（与既有 D-SEC/脱敏口径一致）。
- **D5 — Ctrl-C 真打断**：回合进行中不再简单 `blockLast()` 于主线程；改为 `subscribe()` 拿 `Disposable` + `CountDownLatch`，并 `terminal.handle(Terminal.Signal.INT, s -> { agentKernel.interruptCurrent(); disposable.dispose(); latch.countDown(); })`。回合结束（complete/error/interrupt）后**恢复默认 INT handler**，使空闲时 Ctrl-C 仍走 JLine 的 `UserInterruptException`（丢弃当前行），Ctrl-D 仍 `EndOfFileException` 退出。中断后回到提示符、进程不退出。
- **D6 — 斜杠补全菜单**：沿用 `SystemRegistry.completer()`（picocli 元数据已提供），确认输入 `/` 时补全候选覆盖全部命令；必要时补 `Completer` 让空 `/` 也弹全量菜单。以单测断言候选集合 = 全部斜杠命令名。
- **D7 — 状态行**：新增 `StatusLine`（纯函数组装 `model · session · perms`，读 `ModelManager/SessionManager/ConfigurationManager`）。锚定策略取**简单可靠**者：每个提示符上方打印一行（不做终端底部固定，避免与 JLine 行编辑/滚动冲突）。凭据不出现。
- **D8 — 轻量行内选择器**：`/model` 无参 → 行内选择器。JLine 无内置单选菜单，故用 `Terminal` 原始按键读取（上/下方向键移动高亮、Enter 选定、Esc 取消）在当前行区域重绘候选；**降级**：非交互/异常时回退到既有数字输入选择。全程行式，不接管全屏。`/session` 无参同理（次要，若成本高可留最小实现）。破坏性操作沿用既有 y/N。
- **D9 — web 移除边界**：删除 `pig-agent-web/` 目录 + parent POM `<module>` + `pig-agent-cli` 对 web 的依赖；删 `cli.WebLauncher`、`AgentBootstrap.webContext()`、`config.WebConfig` 与 `web.*` 读取、`application.yaml` 的 `web.*`、logback 里 web 相关条目。全量搜 `WebConsole/WebContext/WebLauncher/WebConfig/web.` 确保 `mvn compile` 无残留。**CLI REPL 不删**。

## Risks / Trade-offs

- **R1 — 增量 markdown 渲染的边界**：markdown 需完整块才好渲染，流式逐 token 到达可能切碎标记。→ 缓解：按「完成的行」渲染行内标记、按 fence 边界渲染代码块；未闭合的标记先原样出字，闭合后由行渲染修正。渲染器纯函数、单测覆盖典型用例。
- **R2 — Ctrl-C 与 JLine 信号处理的交叠**：回合内自装 INT handler、回合后恢复，二者切换若遗漏会导致空闲 Ctrl-C 失灵或回合内 Ctrl-C 误退。→ 缓解：`try/finally` 保证 handler 复位；单测/手验覆盖「回合内打断→回提示符」「空闲 Ctrl-C 丢行」「Ctrl-D 退出」三态。中断语义借力已落地的 `interruptible-run`，非本变更新造。
- **R3 — 方向键行内选择器的可移植性**：Windows PowerShell / conhost 下原始按键序列差异。→ 缓解：用 JLine 的 `BindingReader`/`KeyMap` 读键（已抽象跨平台），并保留数字输入降级路径；itest/手验在真实 PowerShell 跑一次。
- **R4 — 移除 web 的残留引用**：web 被 parent 聚合、被 cli 依赖/装配引用（`webContext`/`WebLauncher`/`WebConfig`）。→ 缓解：全量搜关键字 + `mvn -am compile` 守绿。
- **R5 — 流式改经 `chat()` 的行为差异**：`chat()` 会 `emit(CHAT_STARTED)` 并注册可中断回合；需确认与既有 `agentHolder.get().stream()` 的可见行为等价（同一活动 agent、同一事件序列）。→ 缓解：`chat(activeId, msg)` 解析到的正是活动实例的 agent；单测 mock kernel 返回假 `Flux<Event>` 验证映射与钩子顺序不变。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|--------|------|------|
| Ctrl-C 需真打断而非丢行 | D5 + 任务 5.1；依赖 `interruptible-run`（已并入 main） | 设计已定，待实现 |
| 现有流式绕过内核中断注册 | D2 + 任务 2.5（`streamToAgent` 改走 `chat()`） | 设计已定，待实现 |
| 增量出字 vs 现整体缓冲 | D3 + 任务 2.2 | 设计已定，待实现 |
| 凭据不得出现在渲染/日志 | D4/D7（工具块、状态行脱敏）+ 全任务约束 | 设计已定，贯穿实现 |
| 方向键选择器跨平台风险 | D8 + R3；保留数字输入降级 | 设计已定，待实现+手验 |
| web 残留引用 | D9 + 任务 7.4（全量搜 + 编译守绿） | 设计已定，待实现 |
| 弃 Lanterna / rebase | 任务 1（分支已 reset 到 origin/main，Lanterna 未并入主干） | 已完成 |
