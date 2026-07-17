## 1. 真源 / 确定性读取（HIGH）

- [x] 1.1 `/config`（`ReplCommands.ConfigCommand`）：Model / Protocol 读 `ctx.modelManager().getCurrentModel()`，MCP 读 `ctx.mcpManager().list()` 服务器名；去掉过期的 `cfg.getModel()`/`cfg.getMcp()`。管理器为 null 时降级为 `(n/a)`。
- [x] 1.2 `/tasks`（`ReplCommands.TasksCommand`）：删除 `agent.call(...)`，改为 `new TaskManager(new FileSystemTaskRepository(<root>/tasks)).getAllTasks()` 并按 createdAt+id 排序格式化；空则 `(none)`。
- [x] 1.3 `/skills`（`ReplCommands.SkillsCommand`）：删除 `agent.call(...)`，改为 `new SkillsTool(<root>/skills).listSkills()`（→ `SkillRegistry.all()`）；追加指向 `/skill` 的交叉引用。
- [x] 1.4 移除 `ReplCommands.userMsg` 及不再使用的 `Msg`/`MsgRole`/`TextBlock` import。

## 2. 凭证不外泄（HIGH / LOW）

- [x] 2.1 `/mcp list`（`McpCommand`）：新增 `redactUrl`（丢弃 userinfo、query 脱敏为 `?<redacted>`、坏 URL → `<redacted>`），URL 服务器改用它。
- [x] 2.2 `/notify test`（`NotifyCommand`）：发送前预检 `outreach.enabled` 与 channel/recipient；接收人保持掩码不回显。

## 3. 输入校验与如实反馈（HIGH / MED）

- [x] 3.1 `/agent model`（`AgentCommand`）：应用前 `ctx.modelManager().findById(modelId)` 校验，未知则拒绝并列出有效 id（`isValidModel`/`validModelsHint` 复用）。
- [x] 3.2 `/agent new`（`AgentCommand`）：join `args[1..]` 支持多词名字；仅当末尾 token 是已保存模型时当作 modelId。
- [x] 3.3 `/plan exit`（`PlanCommand`）：先 `safeActive()` 检查，未激活则提示 “Not in Plan Mode”，不再无条件打印 “Exited”。

## 4. 帮助 / 提示一致性（MED）

- [x] 4.1 `/help`：`/agent` 补 `run|report`、`/permission` 补 `channel-mode`。
- [x] 4.2 `CompressCommand`/`MemoryCommand`：default 分支统一为 “Unknown action: X” + `usage()`。
- [x] 4.3 `PermissionCommand` 帮助：`auto(放行编辑/网络, 执行仍需确认)`。
- [x] 4.4 `/skill` usage 交叉引用 `/skills`。

## 5. 测试

- [x] 5.1 `ReplCommandsTest`：`/tasks`、`/skills` 确定性读取（agentHolder=null，证明无 agent.call）、空态；`/config` 读实时管理器；`/mcp list` URL 脱敏（无原始 token）。
- [x] 5.2 `AgentCommandTest`：`/agent model` 拒绝 bogus id 且不 update、接受有效 id；`/agent new` 保留多词名字、识别末尾已保存模型。
- [x] 5.3 `McpCommandSensitiveKeyTest`：`redactUrl` 去 userinfo/query、保留正常 URL、坏 URL 降级。
- [x] 5.4 `NotifyCommandTest`：发送需配置齐全；outreach 未启用 / 无接收人时拒绝且不触达服务。
- [x] 5.5 `PlanCommandTest`：未进入 Plan Mode 时 `/plan exit` 如实提示。
- [x] 5.6 `mvn -pl pig-agent-cli -am test` 全绿。
