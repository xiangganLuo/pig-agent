## ADDED Requirements

### Requirement: 会话进入回放与后台子 agent 可见性

当用户进入一个**已有历史**的会话（REPL 启动恢复当前会话、或 `/session switch` 切到目标会话）时，REPL SHALL 回放该会话槽（`(pig, sessionId)`，经 `getMemory(sessionId).getMessages()` 读取）的**最近 K 条**消息（默认约 8 条，即数轮对话），冠以一行暗色标题（如 `⟳ 已恢复会话「<名称>」· 最近 <n> 条`）。回放 SHALL 为**纯渲染、无副作用**：USER 消息渲染为暗色 `› 文本`、ASSISTANT 文本经 markdown→ANSI 渲染、工具调用/结果经 `⏺ 工具名` / `└ 结果摘要` 渲染；MUST NOT 修改会话内容。回放 MUST 有界（仅最近 K 条，绝不整段转储长历史），文本 MUST 经凭据脱敏且截断（不切断代理对）。空会话 SHALL 不渲染任何内容。回放 SHALL 为**尽力而为**：任何失败被吞并降级，MUST NOT 破坏启动或会话切换本身。

当模型派发一个**后台**子 agent（`agent_spawn` 且 `timeout_seconds=0`，返回 `task_id`、状态常为 `timeout_promoted`）时，REPL SHALL 渲染一行清晰的运行中提示（如 `已派发后台子agent（task <id前8位>）· 运行中，完成后会回报`，暗色、凭据安全），而非静默或转储原始 JSON；后台任务完成时经 `<system-reminder>` 回灌为父 agent 的答案文本 MUST NOT 被抑制。同步子 agent 的嵌套暗色渲染 SHALL 保持不变。凭据 MUST NOT 出现在回放或提示的任何输出中。

#### Scenario: 启动恢复会话回放最近历史

- **WHEN** REPL 启动且当前会话槽已有历史消息
- **THEN** 在提示符前显示暗色标题 + 最近 K 条消息（用户/助手/工具分别渲染），凭据脱敏、有界；若会话为空则不渲染任何内容

#### Scenario: /session switch 回放目标会话

- **WHEN** 用户 `/session switch <id|index>` 切到一个已有历史的会话
- **THEN** 打印 "Switched to…" 后回放目标会话槽的最近 K 条消息；回放失败绝不影响切换本身

#### Scenario: 后台子 agent 派发即可见

- **WHEN** 一次 `agent_spawn` 结果表明是后台任务（含 `task_id` / `timeout_promoted`）
- **THEN** 渲染一行暗色运行中提示（含 task 短 id），不转储原始 JSON，且不抑制后续经 system-reminder 回灌的父答案文本
