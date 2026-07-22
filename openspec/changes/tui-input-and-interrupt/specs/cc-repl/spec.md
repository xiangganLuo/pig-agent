## MODIFIED Requirements

### Requirement: 斜杠命令补全菜单

REPL SHALL 在用户输入 `/` 时提供补全菜单，覆盖全部斜杠命令（`/model` `/session` `/mcp` `/permission` `/memory` `/compress` `/status` `/agent` `/tasks` `/skills` `/config` `/protocols` `/channels` `/help` `/clear` `/quit`）。补全由 JLine `Completer` + picocli 命令元数据驱动。

类型即听（type-ahead）补全的按键重绑 MUST NOT 干扰普通（非斜杠）输入的即时回显：当当前缓冲**不是**斜杠命令缓冲（`SlashCommands.isCommandBuffer` 为假）时，每个可打印字符 SHALL 在**首次**按键即回显到输入行（不得出现"按两下才出字"）。自定义的 self-insert / 补全触发 SHALL **仅**在斜杠命令缓冲态介入（自动列出候选并随键收窄）；离开斜杠缓冲态后普通聊天输入 SHALL 走原生逐字回显。真 TTY 下方向键在斜杠缓冲态导航候选、否则回落历史的既有行为 MUST 保持。

#### Scenario: 触发斜杠补全
- **WHEN** 用户在提示符输入 `/` 并触发补全
- **THEN** 显示涵盖全部斜杠命令的候选菜单

#### Scenario: 普通输入首键即出字
- **WHEN** 用户在提示符输入一个非斜杠开头的普通字符（如英文字母/数字/标点）
- **THEN** 该字符在首次按键即回显到输入行，无需按第二下；补全菜单不弹出

#### Scenario: 斜杠补全不影响普通输入
- **WHEN** 用户先输入并删除一个斜杠命令后继续输入普通聊天文本
- **THEN** 后续普通字符仍逐字即时回显，补全链路不再介入

### Requirement: Ctrl-C 打断当前回合

一次对话回合进行中 WHEN 用户按 `Ctrl-C`，REPL SHALL 调用 `AgentKernel.interruptCurrent()` 取消在飞的模型调用、取消当前流式订阅、回到可接受输入的提示符，且 MUST NOT 退出程序。为使中断真实生效，流式 MUST 经 `AgentKernel.chat(activeId, msg)` 订阅（注册可中断回合），而非绕过内核的裸 agent 流。回合执行期的 Ctrl-C MUST NOT 向终端打印 `UserInterruptException` 或任何异常堆栈，仅显示本地化 `[已中断]`。空闲态的 `Ctrl-C` 与 `Ctrl-D` 行为 MUST 保持（丢弃当前行 / 退出）。

#### Scenario: 回合内打断
- **WHEN** 对话流进行中用户按 `Ctrl-C`
- **THEN** 当前模型调用被取消，界面回到提示符，程序继续运行，且不打印异常堆栈

#### Scenario: 空闲态按键不受影响
- **WHEN** 提示符空闲时用户按 `Ctrl-C`（或 `Ctrl-D`）
- **THEN** 丢弃当前行、REPL 存活（或按 `Ctrl-D` 退出）

## ADDED Requirements

### Requirement: ESC 键行为

REPL SHALL 支持 `ESC` 键，其语义随状态而定，并与 Ctrl-C 复用同一中断路径以避免逻辑重复：

- **任务执行期**：一次对话回合进行中 WHEN 用户按 `ESC`，REPL SHALL 调用 `AgentKernel.interruptCurrent()` 取消在飞模型调用、取消当前流式订阅、回到提示符并显示本地化 `[已中断]`，效果等效于执行期 Ctrl-C，且 MUST NOT 退出程序、MUST NOT 打印异常堆栈。
- **提示符空闲态**：无回合进行、光标在输入行 WHEN 用户按 `ESC`，REPL SHALL 清空当前输入行（不退出、不产生回合）；空行时为无操作。
- **斜杠补全菜单打开时**：候选菜单展示中 WHEN 用户按 `ESC`，REPL SHALL 清空当前斜杠输入行，从而关闭候选菜单（放弃当前命令、不执行）。

ESC 处理 MUST 仅在真 TTY 安装；非交互/哑终端 SHALL 降级为不处理 ESC（不得因缺失能力抛错或破坏输入）。执行期的 ESC 监听通过字符输入模式（关 `ICANON`/`ISIG`/`ECHO`、保留输出标志）读取按键实现；空闲/菜单态的 ESC 通过 LineReader 键位绑定实现，JLine 的歧义键超时使方向键转义序列不受影响。

#### Scenario: 执行期 ESC 中断回合
- **WHEN** 对话流进行中用户按 `ESC`
- **THEN** 当前模型调用被取消、界面回到提示符、显示 `[已中断]`，程序继续运行且不打印异常堆栈

#### Scenario: 空闲态 ESC 清空输入行
- **WHEN** 提示符空闲且输入行已有文本时用户按 `ESC`
- **THEN** 当前输入行被清空，REPL 存活，不产生对话回合

#### Scenario: 菜单态 ESC 关闭补全
- **WHEN** 斜杠补全候选菜单展示中用户按 `ESC`
- **THEN** 当前斜杠输入行被清空，候选菜单随之关闭，不执行命令

### Requirement: 回合执行期中断不喷异常堆栈

一次对话回合执行期间的中断（Ctrl-C 或 ESC）MUST NOT 向终端输出 `org.jline.reader.UserInterruptException` 或任何异常堆栈；REPL SHALL 仅显示本地化 `[已中断]`。若中断路径产生任何异常，其堆栈 SHALL 仅写入日志文件（`warn`/`debug` 级），MUST NOT 到达终端。空闲态的 Ctrl-C（丢弃当前行）与 Ctrl-D（退出）行为 MUST 保持不变。

#### Scenario: 执行期 Ctrl-C 不打印堆栈
- **WHEN** 一次对话回合执行中用户按 `Ctrl-C`
- **THEN** 终端仅显示 `[已中断]`，绝不出现 `UserInterruptException` 或任何 Java 异常堆栈；堆栈（若有）仅入日志文件

#### Scenario: 空闲态中断键行为不回归
- **WHEN** 提示符空闲时用户按 `Ctrl-C` 或 `Ctrl-D`
- **THEN** 分别丢弃当前行（REPL 存活）/ 退出程序，且不打印异常堆栈
