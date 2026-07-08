# cc-repl Specification

## Purpose
TBD - created by archiving change tui-frontend. Update Purpose after archive.
## Requirements
### Requirement: 增强既有 CLI REPL 为唯一终端前端

系统 SHALL 保留 `pig-agent-cli` 的 JLine3 + picocli 行式 REPL（`AgentRepl` / `ReplCommands` / `ReplContext`）作为唯一终端前端，并在其上增强为 Claude Code 风格的行式交互。系统 MUST NOT 引入全屏 TUI，MUST NOT 删除既有 REPL 或任何既有斜杠命令。增强 MUST 复用 CLI 现有的同一批 manager 实例（`AgentKernel` + `ModelManager` / `SessionManager` / `CompressionService` / `McpManager` / `TaskManager` / `ProtocolRegistry` / `ConfigurationManager`），保持单一真相源、零业务逻辑重复。一次对话回合 MUST 保持既有钩子顺序：`noteUserMessage` → `maybeCompress` → `kernel.chat` → `saveCurrent`。

#### Scenario: 启动进入增强 REPL
- **WHEN** 运行 `PigAgentCli.main`
- **THEN** 进入行式 REPL，显示状态行与提示符，既有全部斜杠命令可用，不进入任何全屏界面

#### Scenario: 回合钩子不回归
- **WHEN** 用户发送一条非斜杠消息
- **THEN** 依次记录用户消息 → 按需压缩 → 经 `kernel.chat` 流式返回 → 保存当前会话

### Requirement: 流式富渲染

REPL SHALL 对助手回答做基础 markdown→ANSI 渲染（粗体、行内代码、代码块、列表、标题）并**增量**出字（不整体缓冲到回合结束）；SHALL 将工具调用渲染为缩进块（`⏺ 工具名(参数摘要)` + `└ 结果摘要`）；SHALL 在推理阶段显示进度指示（spinner）。凭据（apiKey、MCP 的 env/headers）MUST NOT 出现在任何渲染输出或日志中。

#### Scenario: 答案富渲染并增量出字
- **WHEN** 助手回答包含 markdown 标记
- **THEN** 终端按 ANSI 渲染粗体/行内代码/代码块/列表/标题，且内容随事件增量显示

#### Scenario: 工具调用块渲染且脱敏
- **WHEN** 一次回合触发工具调用
- **THEN** 以 `⏺ 工具名(...)` + `└ 结果摘要` 缩进显示，且不展示 apiKey / env / headers 等敏感字段

### Requirement: 斜杠命令补全菜单

REPL SHALL 在用户输入 `/` 时提供补全菜单，覆盖全部斜杠命令（`/model` `/session` `/mcp` `/permission` `/memory` `/compress` `/status` `/agent` `/tasks` `/skills` `/config` `/protocols` `/channels` `/help` `/clear` `/quit`）。补全由 JLine `Completer` + picocli 命令元数据驱动。

#### Scenario: 触发斜杠补全
- **WHEN** 用户在提示符输入 `/` 并触发补全
- **THEN** 显示涵盖全部斜杠命令的候选菜单

### Requirement: 状态行

REPL SHALL 显示一行状态，包含当前 model、session 与权限 mode，并随切换即时反映。凭据 MUST NOT 出现在状态行。

#### Scenario: 状态行反映切换
- **WHEN** 用户切换模型或会话
- **THEN** 状态行显示新的 model / session（及当前权限 mode）

### Requirement: Ctrl-C 打断当前回合

一次对话回合进行中 WHEN 用户按 `Ctrl-C`，REPL SHALL 调用 `AgentKernel.interruptCurrent()` 取消在飞的模型调用、取消当前流式订阅、回到可接受输入的提示符，且 MUST NOT 退出程序。为使中断真实生效，流式 MUST 经 `AgentKernel.chat(activeId, msg)` 订阅（注册可中断回合），而非绕过内核的裸 agent 流。空闲态的 `Ctrl-C` 与 `Ctrl-D` 行为 MUST 保持（丢弃当前行 / 退出）。

#### Scenario: 回合内打断
- **WHEN** 对话流进行中用户按 `Ctrl-C`
- **THEN** 当前模型调用被取消，界面回到提示符，程序继续运行

#### Scenario: 空闲态按键不受影响
- **WHEN** 提示符空闲时用户按 `Ctrl-C`（或 `Ctrl-D`）
- **THEN** 丢弃当前行、REPL 存活（或按 `Ctrl-D` 退出）

### Requirement: 轻量行内交互

REPL SHALL 为 `/model`（并可选 `/session`）无参调用提供行内选择器（方向键选择，全程行式、不接管全屏）；破坏性操作 SHALL 沿用 y/N 行内确认。行内选择器在不可交互时 MUST 降级到既有的数字输入选择，不得中断使用。

#### Scenario: 行内选择模型
- **WHEN** 用户执行无参 `/model`
- **THEN** 行内列出模型供方向键选择，选定后经 `ModelManager` + 会话层生效（后续对话使用新模型）

#### Scenario: 破坏性操作确认
- **WHEN** 用户执行删除类操作（如删除模型 / 会话）
- **THEN** 以 y/N 行内确认后才执行，否则取消

