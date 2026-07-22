## Why

`pig-agent-cli` 的 Claude-Code 风格 REPL 存在三处影响日常可用性的交互缺陷：普通输入要按两下才出字（输入几乎不可用）、任务执行期缺少 ESC 终止、执行期 Ctrl-C 会把 `org.jline.reader.UserInterruptException` 堆栈喷到终端。三者都在 REPL 输入/中断层，且互相牵连（ESC 与 Ctrl-C 共用中断路径），应作为一个变更一并修复。

## What Changes

- **修复「输入要按两下才出字」**：`SlashCompletionWidgets` 目前把整个可打印 ASCII 区间（`KeyMap.range(" -~")`）重绑到自定义 `slash-self-insert`（内部 `callWidget(SELF_INSERT)` + 全局 `AUTO_LIST`/`AUTO_MENU`），导致**普通非斜杠聊天输入**也被补全链路接管而首键被吞。改为：非斜杠缓冲的普通输入走原生即时回显（首键即出字），自定义补全**仅**在斜杠命令缓冲（`SlashCommands.isCommandBuffer`）时介入。
- **新增 ESC 键处理**：任务执行期按 ESC = 中断当前回合（等效 Ctrl-C，显示 `[已中断]`）；提示符空闲时 ESC 清空当前输入行；斜杠补全菜单打开时 ESC 关闭菜单。ESC 与 Ctrl-C 复用同一 `AgentKernel.interruptCurrent()` 中断路径，不重复逻辑。
- **修复执行期 Ctrl-C 喷堆栈**：回合执行期的 Ctrl-C MUST NOT 向终端打印 `UserInterruptException` 或任何异常堆栈，仅显示本地化 `[已中断]`；堆栈（若有）仅入日志文件。
- 上述改动全部限于终端交互层，MUST NOT 改动任何业务逻辑、回合钩子顺序或既有斜杠命令。

## Capabilities

### New Capabilities
<!-- 无新增能力：三项均为既有 cc-repl 能力的行为修正/增强 -->

### Modified Capabilities
- `cc-repl`: 三项需求变更——① 修正「斜杠命令补全菜单」使其不干扰普通输入的即时回显；② 扩展「Ctrl-C 打断当前回合」以覆盖执行期不喷异常堆栈；③ 新增「ESC 键行为」需求（中断任务 / 空闲清行 / 关补全菜单）。

## Impact

- **代码**：`pig-agent-cli` 的 `repl/SlashCompletionWidgets`（self-insert 重绑收敛到斜杠缓冲）、`repl/AgentRepl`（`renderStream` 增加 ESC 监听并与 INT 统一收口、执行期异常吞并）。可能新增一个流式期 ESC 输入监听的小组件。
- **测试**：扩展 `AgentReplInterruptTest`（ESC 中断用例、执行期 Ctrl-C 不喷栈断言）；新增 `SlashCompletionWidgets` 普通输入即时回显的单测。
- **不影响**：其余 `pig-agent-*` 模块、回合钩子顺序、既有命令、渲染管线。
- **诚实边界**：真 TTY 按键手感（首键即显、ESC 键序）离线单测无法完全覆盖，需真机验证并在 tasks 中标注。
