# Design — thinking-spinner

## 背景与约束

- 现状（`AgentRepl`）：`showSpinner`/`clearSpinner` 两个静态方法 + 一个 `AtomicBoolean spinnerOn`，`ModelCallStartEvent`/`ThinkingBlockStartEvent` 打印一行静态 `\r⋯ thinking`，首个真实输出到达调 `clearSpinner`（`\r`+空格+`\r`）。无动画。
- 约束：CC-REPL 是**行式**（非全屏）；`Ansi` 仅作字符串构造器，**不**调 `AnsiConsole.systemInstall()`（Windows 下与 JLine 争终端会乱码）；渲染在 reactive 线程（`Flux.subscribe` 的 onNext）上发生；不得阻塞流。

## 设计决策

### D1：拆成纯 `Spinner` + 有状态 `ThinkingSpinner`（Strategy/纯核 + 状态机）
- `Spinner`：只管帧集与 `glyph(tick)`（`Math.floorMod` 环绕），纯函数、零依赖、完全可单测——把「转什么」与「何时转」分离。
- `ThinkingSpinner`：状态机 + I/O 适配，管「何时转、如何清行、TTY 降级」。

### D2：单守护线程 `ScheduledExecutorService`，构造注入（DI 便于 mock）
- `AgentRepl` 持有**一个** `newSingleThreadScheduledExecutor`（daemon，名 `pig-repl-spinner`），每回合新建的 `ThinkingSpinner` 共用它；`run()` 收尾 `shutdownNow()`。
- `ThinkingSpinner` 构造接收 `(scheduler, LongSupplier nanoClock, sink, animated)`——测试注入 mock scheduler + fake clock，**不**需要真终端/真线程（对齐既有 render 测试都避开真终端的做法）。

### D3：无交错乱码——单锁 + 「打印前先 stop」双保险
- `start()`/`onTick()`/`stop()` 全部在同一把 `lock` 下：重绘 tick 的 `sink.accept` 与 `stop()` 的清行**互斥**。
- `stop()` 置 `active=false` + 取消定时任务 + 清行，返回后任何后续 tick 取锁见 `!active` 即空返，不再写终端。
- 渲染循环在打印任何真实输出（答案/工具/子 agent/完成/错误/中断）**前**都先 `stop()`（1:1 复用今日 `clearSpinner` 的调用点），因此 spinner 行总是先被清、再打印内容——不交错。

### D4：`active` 布尔支持一个回合内多次循环
- ReAct 多步推理：`ModelCallStart→答案/工具→再 ModelCallStart…`。`start()` 幂等（`active` 已真则空返）、`stop()` 幂等（`active` 已假则空返），二者可反复交替；每次 `start()` 重置 `tick=0` + `startNanos`（每个思考阶段各自计时）。

### D5：优雅降级——仅真 TTY 动画
- `animated = (configManager==null || config.getRepl().isSpinner()) && InlineSelector.isInteractive(terminal)`。
- 非 animated（dumb/重定向/或 `repl.spinner:false`）：`start()` **从不**触碰调度器（零线程），只绘一条静态 `\r⋯ thinking`（保持今日行为），`stop()` 用 `\r`+空格+`\r` 清行。→ 既有 dumb-终端测试（`AgentReplTurnTest`/`AgentReplInterruptTest`）不回归。

### D6：清行序列
- animated：`\r[2K`（回车到列 0 + 清整行）——比数固定空格数更稳（已用秒数会增宽）；由 JLine 在各平台渲染。
- 非 animated：`\r` + 12 空格 + `\r`（逐字节保持今日静态清行）。

## 帧与文案
- 帧：`⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`（Claude-Code 风格 braille）。
- 动态行内容：`glyph + " thinking… (" + elapsedSeconds + "s)"`（`frameContent` 纯静态方法，便于单测）；实际绘制外面套 `Ansi.dim(...)` + 前置 `\r`。
- 静态降级行：`⋯ thinking`（今日文案，无计时）。
- 重绘周期：`90ms`（落在 ~80–120ms 区间）。

## 落实追踪表（评审/风险 → 落点 + 状态）

| 项 | 落点 | 状态 |
|----|------|------|
| 无交错乱码 | D3 单锁 + 打印前 stop（tasks 4.3） | 已实现 |
| 不阻塞 reactive 流 | D2 独立调度器 + `stop()` 快返（锁内仅取消+清行） | 已实现 |
| TTY 降级不刷屏 | D5 `animated` 门 + 非 animated 零调度器（tasks 3、4.2；ThinkingSpinnerTest 非 TTY 用例） | 已实现 |
| Windows 安全 | D6 `\r`+ANSI 清行由 JLine 渲染；不 `systemInstall`（沿用 `Ansi`） | 已实现 |
| 无凭据泄漏 | 文案固定（`thinking…`），无变量插值 | 已实现 |
| 多步推理循环 | D4 `active` 幂等 + 可循环（ThinkingSpinnerTest 循环用例） | 已实现 |
| 可关闭 | `repl.spinner`（tasks 1）；`false` → 静态指示 | 已实现 |
| 仅纯测 vs 需真终端 | `Spinner`/`ThinkingSpinner`（fake scheduler+clock）纯测覆盖状态/调度/清行序列；真终端逐帧动画属肉眼/集成，不单测（诚实标注） | 已实现（诚实局限） |

## 诚实局限
- 单测覆盖纯 `Spinner`、`ThinkingSpinner` 的状态机/调度/清行**序列**（fake scheduler + fake clock），**不**验证真实终端上逐帧动画的视觉效果与无闪烁——那需要一个真 TTY，属肉眼/集成层。
- native 中断是协作式的（既有约束）；spinner 的 `stop()` 由中断路径调用即时清行，但底层模型调用的取消时机不受本能力影响。
