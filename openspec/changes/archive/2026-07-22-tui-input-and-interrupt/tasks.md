## 1. Spike（承重验证 · 卡点，不过不进编码）

- [x] 1.1 复现「输入按两下才出字」根因：`SlashCompletionWidgets` 把整段可打印 ASCII 重绑到自定义 `slash-self-insert`，普通输入被 `callWidget(SELF_INSERT)` 重派发 + 全局 `AUTO_LIST/AUTO_MENU` 补全链路接管（用户确认"所有输入"皆如此，与该链路一致）。结论：普通输入不应经补全链路（D1）。
- [x] 1.2 定论 D1 实现路径：普通字符走 `reader.getBuffer().write(getLastBinding())` 直插（首键即出字），补全介入仅在 `willActivateSlash()`（斜杠缓冲或在空行键入 `/`）为真时；保留 AUTO 选项与斜杠菜单不变。
- [x] 1.3 定论 D2 实现路径：`TurnKeyWatcher` 在回合执行期进入**字符输入模式**（仅关 `ICANON`/`ISIG`/`ECHO`，**保留输出标志**避免 stairstep）+ 守护读键线程，`0x1B`(ESC)/`0x03`(Ctrl-C) 收口同一中断闭包；`ISIG` 关 → Ctrl-C 为字节、不触发 SIGINT/`readLine` → 消除 `UserInterruptException`（D3）。`close()` 恢复属性。
- [x] 1.4 结论已写回 design.md 的 D1/D2（与假设一致，无需回改 spec 结构；仅将 ESC 菜单态语义细化为"清行即关菜单"）。
- [ ] 1.5 **（真机手动验收，无法离线）** 真 TTY 复现确认：普通输入首键即出字；执行期 ESC/Ctrl-C 即时中断且属性正确恢复、不吃下一个 prompt 输入。→ 见 4.2。

## 2. 修复 #1：普通输入首键即出字

- [x] 2.1 单测：`SlashCompletionWidgetsTest` 断言 `slash-escape` 组件注册 + ESC 绑定；`SlashCommandsTest` 覆盖 `isCommandBuffer` 门（普通输入不触发补全）。
- [x] 2.2 改 `SlashCompletionWidgets.selfInsert`：非斜杠且有 lastBinding → `getBuffer().write`（首键即出字）；否则 `callWidget(SELF_INSERT)` + `maybeList()`；新增 `willActivateSlash()`。保留方向键/菜单既有行为。
- [x] 2.3 `mvn -pl pig-agent-cli test` 绿（全模块 18/相关用例通过）；真 TTY 首键即出字见 4.2。

## 3. 修复 #2 + #3：ESC/Ctrl-C 执行期统一中断、不喷堆栈

- [x] 3.1 单测：`AgentReplInterruptTest`（dumb terminal `raise(Signal.INT)` 回退路径显示 `[已中断]`）保持通过，未回归。
- [x] 3.2 单测：`AgentReplConfirmInterruptTest` 断言 `confirm()` 的 `readLine` 抛 `UserInterruptException`/`EndOfFileException` 时被就地吞并 fail-closed（当前+剩余全部拒绝）、显示 `[已中断]`、不上抛。
- [x] 3.3 实现 D2：新增 `TurnKeyWatcher`，`renderStream` 真 TTY 下起字符输入模式读键线程，ESC/Ctrl-C 收口 `doInterrupt`；`finally` 关闭 + 恢复属性；保留 `Signal.INT` 作哑终端回退。
- [x] 3.4 实现 D3/D4：`confirm()` 就地捕获中断异常；`SlashCompletionWidgets` 新增 `escape` 组件（ESC 清行即关菜单，真 TTY 绑定）。
- [x] 3.5 核查执行期中断全链路（`renderStream`/`confirm`/顶层）：无路径向终端输出 `UserInterruptException` 或异常堆栈；堆栈仅入日志。

## 4. 收尾与验证

- [x] 4.1 `mvn -pl pig-agent-cli -am compile` 通过；`mvn -pl pig-agent-cli test` 全绿。
- [ ] 4.2 **（真机手动验收，交用户）** 真 TTY（Windows Terminal / PowerShell）：普通输入首键即出字；执行期 ESC 中断显示 `[已中断]`；执行期 Ctrl-C 显示 `[已中断]` 且无异常栈；空闲态 ESC 清行、Ctrl-C 丢行、Ctrl-D 退出；斜杠补全菜单正常收窄/方向键导航。
- [x] 4.3 更新 `CLAUDE.md` 的 cc-repl 段（补 ESC 键行为 + 执行期不喷堆栈 + `TurnKeyWatcher`）。
