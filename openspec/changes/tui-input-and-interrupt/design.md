## Context

`pig-agent-cli` 的 REPL（`AgentRepl` + `SlashCompletionWidgets`，JLine3 + picocli，见 `cc-repl` 能力）是唯一终端前端。三处交互缺陷：

1. **输入要按两下才出字（所有输入）** — `SlashCompletionWidgets.install`（仅真 TTY 安装，`AgentRepl.java:208`）在 `bind()` 里 `main.bind(new Reference("slash-self-insert"), KeyMap.range(" -~"))` 把**整个可打印 ASCII 区间**重绑到自定义组件，且全局 `setOpt(AUTO_LIST|AUTO_MENU|LIST_PACKED)`。自定义组件对每个键 `callWidget(SELF_INSERT)` 后再 `maybeList()`。普通非斜杠输入本应只是一次原生插入，却被"重绑 + 重派发 + 全局补全选项"链路接管——这是"首键被吞、按两下才出字"的来源。
2. **无 ESC 终止** — 回合执行期主线程阻塞在 `renderStream` 的 `done.await()`（`AgentRepl.java:457`），此刻 JLine `LineReader` 未在读键，只有 `terminal.handle(Signal.INT)`（Ctrl-C）能中断（`AgentRepl.java:447`）。ESC 无处理。
3. **执行期 Ctrl-C 喷 `UserInterruptException` 堆栈** — 顶层 `catch (UserInterruptException ignored)`（`AgentRepl.java:242`）只覆盖 prompt 空闲态的 `reader.readLine`。执行期中断若走到 JLine 的 readLine（如 `confirm()` 的 `readLine`，`AgentRepl.java:713`）或未被吞的取消链，异常堆栈会喷到终端。

现有回归保护：`AgentReplInterruptTest`（dumb terminal，`raise(Signal.INT)` → 断言 `[已中断]`）。

## Goals / Non-Goals

**Goals:**
- 普通（非斜杠）输入在真 TTY 下**首键即出字**，斜杠补全菜单行为不变。
- 回合执行期按 **ESC** 中断当前回合（等效 Ctrl-C，显示 `[已中断]`）；空闲态 ESC 清空输入行；补全菜单态 ESC 关菜单。
- 回合执行期 Ctrl-C / ESC 中断**绝不**向终端打印 `UserInterruptException` 或任何异常堆栈。
- 复用现有 `AgentKernel.interruptCurrent()` 中断路径；ESC 与 Ctrl-C 收口到同一处理。
- 现有 dumb-terminal 中断测试保持通过。

**Non-Goals:**
- 不做全屏 TUI；不改回合钩子顺序、业务逻辑、任何既有斜杠命令。
- 不深挖 IME/多字节输入法专项（用户确认问题在"所有输入"，根因是 ASCII 区重绑；CJK 走原生 self-insert 不在重绑范围）。
- 不改 Windows 传统控制台 VT 启用逻辑。

## Decisions

### D1：普通输入不再经自定义 self-insert 链路（修复 #1）
把类型即听补全收敛为**只在斜杠缓冲态介入**：普通字符走 JLine 原生 `self-insert`（首键即出字），不再对整段可打印区间做"重绑 + `callWidget` 重派发 + 全局 `AUTO_MENU`"。候选自动列出/收窄仅当 `SlashCommands.isCommandBuffer` 为真时触发。方向键在斜杠态导航候选、否则回落历史的既有行为保留。
> **承重未验证点（Spike 卡点）**：JLine "按两下"确切机制（`callWidget(SELF_INSERT)` 重派发 vs 全局 `AUTO_MENU`/`AUTO_LIST`）需真 TTY 复现确认，并验证收敛后普通输入首键即出字、斜杠补全仍正常。实现细节（去掉全局 `AUTO_MENU` / 用 `buffer.write` 直插 / 仅在 `/` 触发重列）在 Spike 定论后于 `/ls:code` 固化。

### D2：回合执行期以原始模式后台读键，ESC/Ctrl-C 统一收口（修复 #2 + #3）
回合执行期（`renderStream`）在真 TTY 下进入 `terminal.enterRawMode()` 并起一个**守护读键线程**从 `terminal.reader()` 逐字节读取：`0x1B`(ESC) 与 `0x03`(Ctrl-C) 都触发**同一个中断闭包**（`interrupted=true` → `interruptCurrent()` → `sub.dispose()` → `done.countDown()`，与现有 INT handler 一致）；其它字节丢弃（回合执行期输入本就无意义）。回合结束在 `finally` 里停读键线程并恢复终端属性。
- 原始模式下 `ISIG` 关闭 → Ctrl-C 以字节 `0x03` 到达读键线程，**不再触发 SIGINT，也不经任何 JLine readLine**，从根上消除 `UserInterruptException` 堆栈（修复 #3）。
- **保留** `terminal.handle(Signal.INT)` 作为非原始模式/哑终端回退（`AgentReplInterruptTest` 的 dumb terminal 走此路，测试保持通过）。
- 读键线程仅真 TTY 起（`InlineSelector.isInteractive` 门，与补全安装同一判据）；非交互/哑终端不进原始模式、不起线程，行为不变。

### D3：`confirm()` 的 readLine 兜底吞中断（修复 #3 补强）
`confirm()` 内 `reader.readLine(...)` 的 `UserInterruptException`/`EndOfFileException` 就地捕获 → 按"拒绝/取消该确认"（fail-closed）处理并显示 `[已中断]`，绝不向上抛成终端堆栈。这是 D2 之外的第二道防线（HITL 确认阶段用户按 Ctrl-C 的路径）。

### D4：空闲态与菜单态 ESC（修复 #2 其余语义）
空闲态（`reader.readLine` 主循环）ESC 清空当前行、菜单态 ESC 关菜单，通过在 `SlashCompletionWidgets` / LineReader keymap 绑定 ESC 到对应 widget 实现（空闲清行 = `clear-buffer`/`kill-whole-line`；菜单态关闭 = 让原生 `AUTO_MENU` 的 ESC 退出生效或显式绑定）。仅真 TTY 安装。

## Risks / Trade-offs

- **原始模式读键的终端兼容性（承重）**：`enterRawMode` + 后台读字节在个别终端/`jna` 组合下可能有边界行为（属性未恢复、字节竞争）。缓解：`finally` 严格恢复属性；仅真 TTY 启用；哑终端完全走旧 Signal.INT 路径；Spike 真机验证 Windows Terminal / PowerShell / cmd。
- **离线单测覆盖有限（诚实边界）**：真按键手感（首键即显、ESC 键序、原始模式读键）无法在离线单测完全覆盖。单测覆盖可隔离的纯逻辑（`isCommandBuffer` 门、中断闭包被调用、`confirm` 吞异常、dumb-terminal Signal.INT 回退不回归）；真 TTY 行为在 tasks 标注为手动验证项。
- **回合执行期消费按键**：原始模式下用户在回合执行中敲的非 ESC/Ctrl-C 键会被读键线程丢弃（本就无处理），可接受；须保证回合结束后恢复，不吃掉下一个 prompt 的输入。
- **两问题共用中断路径的耦合**：ESC 与 Ctrl-C 收口同一闭包降低重复，但也意味着该闭包的回归会同时影响两者；由 `AgentReplInterruptTest` 扩展用例守护。
