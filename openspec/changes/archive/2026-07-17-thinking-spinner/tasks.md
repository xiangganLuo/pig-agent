## 1. 配置块（default-safe，向后兼容）

- [x] 1.1 `PigAgentConfig` 新增 `ReplConfig` 嵌套类：`spinner`(默认 true) + getter/setter；新增 `@JsonProperty("repl")` 字段 + `getRepl()`。
- [x] 1.2 缺 `repl` 块时读回默认 `spinner=true`（复用既有 `ConfigurationManager` 容错，无需新增解析代码）。

## 2. 纯 Spinner（帧集，TDD）

- [x] 2.1 `SpinnerTest`（先写，RED）：`glyph(0)` 为首帧 `⠋`；`glyph(0..9)` 为 10 个不同帧；`glyph(10)==glyph(0)`、`glyph(11)==glyph(1)`（环绕）；`glyph(-1)==glyph(9)`（负 tick 经 floorMod 容错）；`frameCount()==10`。
- [x] 2.2 `Spinner`（GREEN）：braille 帧 `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`，`glyph(int)` 用 `Math.floorMod`，纯函数。

## 3. ThinkingSpinner（状态机 + 注入调度器/时钟，TDD）

- [x] 3.1 `ThinkingSpinnerTest`（先写，RED，Mockito mock `ScheduledExecutorService` + fake `LongSupplier`）：
  - `start()`（animated）→ `scheduleAtFixedRate` 以 ~90ms 周期被调一次；sink 首帧含 `⠋` + `thinking` + `(0s)`。
  - 捕获 tick Runnable → 推进 fake clock 3s → 触发一次 → sink 末条含下一帧 `⠙` + `(3s)`。
  - `stop()` → `future.cancel(false)` 被调；sink 收到清行序列（含 `[2K`）。
  - 非 TTY（animated=false）→ `start()` 从不触碰调度器；sink 仅一条静态行；`stop()` 清行、不 cancel。
  - `start()` 幂等（两次只 schedule 一次）；`start→stop→start` 可再次 schedule（循环）。
  - `stop()` 未 `start()` → no-op（sink 无输出）。
  - `frameContent(glyph, 12s)` 含 `(12s)`（纯静态格式化）。
- [x] 3.2 `ThinkingSpinner`（GREEN）：构造 `(ScheduledExecutorService, LongSupplier nanoClock, Consumer<String> sink, boolean animated)`；`active` 状态 + 单锁；`start()` 记 `startNanos`、`tick=0`、animated 则绘首帧 + `scheduleAtFixedRate(90ms)`，非 animated 则绘一条静态行；`onTick()` 锁内 `tick++` + 重绘；`stop()` 锁内取消任务 + 清行；`start/stop` 幂等可循环；`frameContent` 纯静态。

## 4. 接线（AgentRepl）

- [x] 4.1 `AgentRepl` 新增单守护线程 `spinnerScheduler` 字段（`pig-repl-spinner`，daemon）；`run()` 收尾 `shutdownNow()`。
- [x] 4.2 `newSpinner(terminal)` 工厂：`animated = (configManager==null || config.getRepl().isSpinner()) && InlineSelector.isInteractive(terminal)`；sink = `text -> Ansi.print(terminal, text)`。
- [x] 4.3 `renderStream`/`onEvent`/`onChildEvent`：删 `AtomicBoolean spinnerOn` + `showSpinner`/`clearSpinner`，改为每回合 `ThinkingSpinner spinner = newSpinner(terminal)`；`ModelCallStartEvent`/`ThinkingBlockStartEvent` → `spinner.start()`；原每处 `clearSpinner` → `spinner.stop()`（首答案文本/工具调用/工具结果/回合结束/需确认/子 agent 起止/错误/中断/完成）。
- [x] 4.4 `mvn -q -pl pig-agent-cli -am compile` 绿。

## 5. 文档 + 验收

- [x] 5.1 `CLAUDE.md` cc-repl 段：把「`⋯ thinking` spinner cleared when…」更新为「动态 braille spinner + 计时 + 单守护线程重绘 + 停并清行 + 仅 TTY / `repl.spinner`」。
- [x] 5.2 `mvn -q test` 单线程（`clean` cli 模块避免陈旧类；`-DforkCount=1`）全量绿，读 surefire XML 计数确认；`openspec validate thinking-spinner --strict` 通过。
