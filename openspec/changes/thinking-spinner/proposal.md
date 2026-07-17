## Why

REPL 在推理阶段只显示一条**静态**的 `⋯ thinking` 行（`AgentRepl.showSpinner`：`spinnerOn` 布尔翻一次 → 打印一行，直到答案/工具输出到达才清除）。它不会动，用户无法区分「模型在思考」与「卡死了」，缺少 Claude Code 那种「转圈 + 计时」的活性反馈。本次把这条静态指示升级为一个**动态 spinner**（braille 转圈字形 + `thinking…` + 已用秒数），模仿 Claude Code；升级严格局限在 CLI 渲染层，复用既有 TTY 判定做优雅降级，不动内核/流式/权限/确认等任何其它行为。

## What Changes

本次是**面向设计模式的能力增强**（纯策略 + 状态机 + 依赖注入的调度器/时钟），非零散功能拼装：

- **纯 `Spinner`（`pig-agent-cli`，`io.pigagent.cli.render`，可离线单测）**：braille 帧集 `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏` + `glyph(tick)` 返回当前帧（`floorMod` 环绕，纯函数、无副作用）。
- **`ThinkingSpinner`（`io.pigagent.cli.repl`）状态机**：由一个**单守护线程 `ScheduledExecutorService`**（构造注入，便于 mock）驱动，`start()` 立即绘制首帧并按 ~90ms 定时重绘 `\r` + dim(`⠹ thinking… (Ns)`)，`stop()` 取消定时任务并**清行**（`\r` + ANSI 清行）。时钟经注入的 `LongSupplier`（`System::nanoTime`）取，便于测已用秒数格式化。所有绘制/取消都在同一把锁下，且渲染循环打印任何真实输出**前**先 `stop()`，因此重绘 tick 与答案/工具行**不会交错乱码**。`start()/stop()` 幂等、可在一个回合内多次循环（多步推理），从不跨回合泄漏。
- **优雅降级（关键）**：动画**仅在真实可交互 TTY** 上启用（复用 REPL 既有 `InlineSelector.isInteractive` —— 与只在真 TTY 装斜杠补全同一道门）；非 TTY（dumb / 重定向 stdout）降级为**单条静态指示**（保持今日行为），MUST NOT 输出 `\r` 动画刷屏。Windows 安全：`\r` + ANSI 清行在 Windows Terminal/PowerShell 下由 JLine 渲染；不调用 `AnsiConsole.systemInstall()`（`Ansi` 仅作字符串构造器）。
- **配置 `repl.spinner`（`PigAgentConfig`）**：布尔，缺省 `true`。缺块即启用动画（TTY 上），`false` 完全关闭动画（退化为静态指示）。全部可选、默认安全、向后兼容。
- **接线（`AgentRepl`）**：`renderStream`/`onEvent`/`onChildEvent` 里把原 `AtomicBoolean spinnerOn` + `showSpinner`/`clearSpinner` 换成一个每回合新建的 `ThinkingSpinner`（`ModelCallStartEvent`/`ThinkingBlockStartEvent` → `start()`；首个答案文本/工具调用/工具结果/回合结束/需确认事件 → `stop()`），保留全部既有 CC-REPL 渲染、`ToolResultEndEvent` 块、DENIED、子 agent 嵌套、中断路径。

无 **BREAKING**：spinner 文案固定（无凭据泄漏）、不阻塞 reactive 流（调度器独立、`stop()` 快返）、非 TTY 逐字节保持今日行为、`repl.spinner:false` 退回静态指示。

## Capabilities

### Modified Capabilities
- `cc-repl`: 「流式富渲染」的推理阶段指示由**静态** `⋯ thinking` 升级为**动态 spinner**（braille 转圈 + `thinking…` + 已用秒数，单守护线程定时重绘），首个真实输出到达即停并清行、无交错乱码；仅真 TTY 启用、非 TTY 降级为静态指示、可经 `repl.spinner` 关闭。

<!-- 不改：cc-repl 的其余需求（回合钩子顺序、工具块渲染脱敏、斜杠补全、状态行、Ctrl-C 中断、行内选择器）行为契约不变。 -->

## Impact

- **代码（`pig-agent-cli`）**：新增 `render/Spinner`（纯帧集）、`repl/ThinkingSpinner`（状态机 + 注入调度器/时钟）；`repl/AgentRepl` 改造 spinner 接线（删 `showSpinner`/`clearSpinner`/`spinnerOn`，新增单守护线程调度器字段 + `newSpinner(terminal)` 工厂）。
- **代码（`pig-agent-config`）**：`PigAgentConfig` 新增 `ReplConfig` 嵌套类（`spinner` 默认 true）+ `repl` 字段 + getter。
- **协作/不改**：`AgentKernel` 流式、权限/确认/中断、`StreamingMarkdownPrinter`/`ToolCallFormatter`/`SubagentEventRenderer`、状态行、斜杠补全，全部不变。
- **测试**：`SpinnerTest`（帧循环 / 环绕 / 负 tick 容错 / 帧数）；`ThinkingSpinnerTest`（fake scheduler + fake clock：start 定时重绘 + 首帧；tick 推进字形与已用秒数；stop 取消任务 + 清行序列；非 TTY → 无动画只静态行；start/stop 幂等 + 循环；stop 未 start 幂等；秒数格式化）。既有 `AgentReplTurnTest`/`AgentReplInterruptTest`（dumb 终端）不回归。
- **文档**：`CLAUDE.md` cc-repl 段更新「动态 spinner」；README 无 `⋯ thinking` 描述，无需改。
