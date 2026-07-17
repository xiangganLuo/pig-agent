## Why

一轮真实使用评审暴露出一批斜杠命令的 UX / 正确性缺陷：`/config` 与 `/tasks` / `/skills` 读到**过期或非确定性**的数据（`/config` 读旧 YAML 而非真源 `models.json`/`mcp.json`；`/tasks`、`/skills` 走一次 LLM 往返——慢、非确定、可能打印 `Error: null`），`/mcp list` 会把 URL 里的 token 泄漏进滚屏历史，`/agent model`/`new` 对不存在的 modelId 静默回退却打印“成功”，`/plan exit` 在未进入 Plan Mode 时也谎称“已退出”，还有若干帮助文本漂移与不一致的错误提示。这些都是纯前端（CLI 命令层）问题，逐条修复即可，不动 harness/内核。

## What Changes

- **`/config`（HIGH）**：Model / MCP 改为读**实时管理器**（`ModelManager` / `McpManager`，即 `models.json`/`mcp.json` 真源），不再读切换后不更新的旧 YAML 块——镜像 `/status`。
- **`/tasks`（HIGH）**：去掉 `agent.call("List all tasks")` 的 LLM 往返，改为对文件型任务库的**确定性读取**（`TaskManager.getAllTasks()`），本地格式化。
- **`/skills`（HIGH）**：同样去掉 LLM 往返，改为经 `SkillsTool.listSkills()`（→ `SkillRegistry.all()`：工作区 + 内置 classpath 技能）**确定性枚举**。
- **`/mcp list`（HIGH）**：不再回显原始 `url=<url>`；对 URL 去掉 userinfo（`user:pass@`）并把 query 脱敏为 `?<redacted>`，凭证绝不进滚屏。
- **`/agent model` / `/agent new`（HIGH）**：应用 modelId 前先用 `ModelManager.findById` **校验**；`/agent model` 对未知 id 友好拒绝并列出有效 id（不再假成功）。`/agent new` 支持**多词（未加引号）名字**（join `args[1..]`），仅当末尾 token 是已保存模型时才当作 modelId。
- **`/plan exit`（MED）**：先检查当前会话是否处于 Plan Mode，未处于则如实提示“Not in Plan Mode”，不再无条件打印“Exited”。
- **帮助文本（MED）**：`/help` 中 `/agent` 补 `run|report`、`/permission` 补 `channel-mode`；`CompressCommand`/`MemoryCommand` 的 default 分支统一为“Unknown action: X + 带标题 usage()”模式。
- **`PermissionCommand` 帮助（MED）**：把错误的 `auto(放行编辑/网络,拦执行)` 改为 `auto(放行编辑/网络, 执行仍需确认)`（AUTO 下 EXEC→ASK，交互仍确认；仅非交互 fail-closed 为 DENY）。
- **`/notify test`（LOW）**：发送前预检 outreach 是否启用、渠道/接收人是否配置，给出具体提示（接收人保持掩码 `(set)`，绝不回显）。
- **`/skill` ↔ `/skills`（LOW）**：在彼此的 usage/输出里交叉引用，避免混淆。

## Capabilities

### New Capabilities
- `repl-command-ux`: 斜杠命令的正确性与 UX 保障——真源/确定性读取（`/config`、`/tasks`、`/skills`）、凭证不外泄（`/mcp list` URL 脱敏、`/notify` 接收人掩码）、输入校验与如实反馈（`/agent` modelId 校验、`/plan exit` 状态感知）、一致的帮助/错误提示。

### Modified Capabilities
<!-- openspec/specs 为空，无既有能力的需求变更 -->

## Impact

- **代码**：仅 `pig-agent-cli` 前端命令层——`repl/ReplCommands.java`（Config/Tasks/Skills/Help/Compress/Memory）、`repl/command/{McpCommand, AgentCommand, PlanCommand, NotifyCommand, PermissionCommand, SkillCommand}.java`。不动 harness/内核/`AgentRepl` 派发。
- **行为**：`/tasks`、`/skills` 不再消耗 token / 不再需要在线模型；`/config`、`/agent`、`/plan exit`、`/notify` 反馈更准确；`/mcp list` 不再泄漏 URL 凭证。
- **安全**：URL query/userinfo 脱敏、接收人掩码，符合“绝不回显凭证”约定。
- **测试**：新增/调整 `pig-agent-cli` 单测覆盖上述确定性读取、校验与脱敏。
