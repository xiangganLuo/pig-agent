## Why

Pig Agent 目前有三条终端/前端并存：CLI 逐行 REPL（`AgentRepl`/`ReplCommands`/`ReplContext`）、`pig-agent-web` 本地控制台、channel 桥接。前端面过宽拖慢核心链路打磨。本变更**收敛前端面**，但方向与初版（Lanterna 全屏 TUI）**不同**：不引入全屏多面板 TUI，而是**把既有 CLI REPL 增强为 Claude Code 风格的行式对话前端**——保留 JLine3 + picocli 基座，叠加流式富渲染、斜杠补全菜单、状态行、Ctrl-C 真打断与轻量行内交互；同时**移除 `pig-agent-web` 模块**，Web 后置到生态扩展阶段（web-console-v2）重建。CLI REPL 是又一 `AgentKernel` adapter，不改内核。依据路线图 `docs/planning/tui-and-core-roadmap.md`。

> **方向修正说明**：初版提案曾计划以 Lanterna 全屏 TUI 替代并删除 CLI REPL。经用户拍板改为「在既有 REPL 之上增强」，弃用 Lanterna 全屏方案（分支已 reset 到 `origin/main`，Lanterna 代码未并入主干）。本提案为重写后的版本。

## What Changes

- **保留并增强既有 CLI REPL（非替换）**：以 `pig-agent-cli` 的行式 REPL 为基座，全程行式、不接管全屏。既有全部斜杠命令与回合流程（`noteUserMessage → maybeCompress → kernel.chat → saveCurrent`）保持不变。
- **流式富渲染**：助手回答做基础 markdown→ANSI（粗体 / 行内代码 / 代码块 / 列表 / 标题）并**增量出字**（不再整体缓冲到回合结束）；工具调用以缩进块显示（`⏺ 工具名(参数)` + `└ 结果摘要`）；推理阶段显示 spinner（`⋯ thinking`）。凭据不出现在任何渲染。
- **斜杠补全菜单**：输入 `/` 弹出补全菜单，覆盖全部斜杠命令（JLine Completer + picocli 元数据）。
- **状态行**：一行显示 `model · session · 权限mode`，随切换即时反映。
- **Ctrl-C 真打断**：回合进行中 Ctrl-C 调 `AgentKernel.interruptCurrent()`（`interruptible-run` 已落地为真实现）取消在飞模型调用、回到提示符、不退出程序。为使中断生效，流式改经 `agentKernel.chat(activeId, msg)`（注册可中断回合），替换现有绕过内核的 `agentHolder.get().stream(...)`。
- **轻量行内交互**：`/model`（及可选 `/session`）无参时提供行内选择器（方向键选择，行式）；破坏性操作沿用 y/N 行内确认。
- **BREAKING — 移除 `pig-agent-web` 模块**：从 parent POM `<modules>` 摘除；删除 `pig-agent-web/` 整目录、`cli.WebLauncher`、`PigAgentConfig.WebConfig` 及 `web.*` 配置读取、`AgentBootstrap.webContext()`、logback 中 web 相关条目。

## Capabilities

### New Capabilities
- `cc-repl`: 以增强后的 CLI 行式 REPL 作为唯一终端前端（`AgentKernel` adapter），提供 CC 风格流式富渲染、斜杠补全、状态行、Ctrl-C 真打断与轻量行内交互；能力≥原 REPL，凭据不外露。

### Modified Capabilities
- `web-console`: 移除该能力——`pig-agent-web` 模块及其 REST/SSE/静态前端整体删除；其 REQUIREMENT 以 delta 标记 REMOVED，后置到生态扩展阶段（web-console-v2）重建。

## Impact

- **代码**：`pig-agent-cli`（增强 `repl` 包：富渲染器、补全、状态行、Ctrl-C 中断、行内选择器；删除 `WebLauncher`；`AgentBootstrap` 去除 `webContext()`）；移除 `pig-agent-web` 整模块；`pig-agent-config`（移除 `WebConfig` 与 `web.*`）。**不新增模块**（无 `pig-agent-tui`）。
- **依赖**：parent POM 移除对 `pig-agent-web` 的聚合；`pig-agent-cli` 去掉对 `pig-agent-web` 的依赖。**不引入 Lanterna**。
- **只读复用（不改）**：`AgentKernel`（含 `interruptCurrent()` 真实现）+ `ModelManager/SessionManager/CompressionService/McpManager/TaskManager/ProtocolRegistry/ConfigurationManager`；`KernelEvent`/流式 `Event`。
- **配置/数据**：`application.yaml` 去除 `web.*` 段；无会话/模型/任务等数据格式变更。
- **测试**：新增富渲染器（markdown→ANSI，纯函数）、状态行组装、补全候选、Ctrl-C 中断接线、行内选择器→manager 委托的单测；移除 `pig-agent-web` 后无残留引用，`mvn compile`/`mvn test` 绿。
- **文档**：`README` / `CLAUDE.md` 删去 Web 章节与 `WebLauncher` 命令；REPL 描述更新为 CC 风格增强；模块表去掉 web。
