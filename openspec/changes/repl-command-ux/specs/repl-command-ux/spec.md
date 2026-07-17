## ADDED Requirements

### Requirement: 配置展示读实时真源
`/config` MUST 从实时管理器展示当前模型与 MCP 服务器（`ModelManager` / `McpManager`，对应真源 `models.json` / `mcp.json`），MUST NOT 依赖运行时切换后不再更新的旧 YAML `model:` / `mcp.servers` 块。管理器不可用时 MUST 降级为占位符而非抛错。

#### Scenario: 展示当前生效的模型与 MCP
- **WHEN** 用户执行 `/config`
- **THEN** 展示的模型来自 `ModelManager.getCurrentModel()`（含协议）、MCP 服务器名来自 `McpManager.list()`，反映运行时切换后的真实状态

#### Scenario: 管理器缺失时降级
- **WHEN** 模型或 MCP 管理器不可用
- **THEN** 对应行显示 `(n/a)`，命令不抛异常

### Requirement: 列表命令确定性且离线
`/tasks` 与 `/skills` MUST 通过确定性的本地读取产出结果——`/tasks` 读文件型任务库（`TaskManager.getAllTasks()`），`/skills` 经 `SkillsTool.listSkills()`（`SkillRegistry.all()`，工作区 + 内置技能）——MUST NOT 发起 LLM 往返（不消耗 token、不依赖在线模型、不会打印 `Error: null`）。

#### Scenario: 列出任务不调用模型
- **WHEN** 用户执行 `/tasks`
- **THEN** 直接从任务库读取并格式化列出（无任务则提示 none），全程无 LLM 调用

#### Scenario: 列出技能不调用模型
- **WHEN** 用户执行 `/skills`
- **THEN** 经技能注册表枚举工作区与内置技能，全程无 LLM 调用

### Requirement: 命令输出不泄漏凭证
斜杠命令的输出 MUST NOT 回显凭证。`/mcp list` 展示 URL 型服务器时 MUST 去除 userinfo（`user:pass@`）并脱敏 query（如 `?token=…` → `?<redacted>`）；`/notify` MUST NOT 回显接收人值（只显示是否已配置）。

#### Scenario: MCP 列表脱敏 URL 中的 token
- **WHEN** 一个 URL 型 MCP 服务器的 URL 含 `?token=…` 或 userinfo，用户执行 `/mcp list`
- **THEN** 输出保留 `scheme://host[:port]/path` 但不含该 token / 口令，query 显示为 `?<redacted>`

#### Scenario: 坏 URL 降级
- **WHEN** URL 无法解析
- **THEN** 显示 `<redacted>` 而非回显原始字符串

### Requirement: modelId 校验与如实反馈
`/agent model` 与 `/agent new` 在应用 `modelId` 前 MUST 用 `ModelManager.findById` 对照已保存模型校验。`/agent model` 对未知 id MUST 拒绝并列出有效 id（MUST NOT 打印假的“成功”）。`/agent new` MUST 接受未加引号的多词名字（join 其余参数），并**仅**当末尾 token 是已保存模型时才将其当作 `modelId`。

#### Scenario: 拒绝不存在的模型
- **WHEN** 用户执行 `/agent model <id> <未知modelId>`
- **THEN** 命令拒绝、列出有效模型 id，且不更新该 agent

#### Scenario: 保留多词 agent 名字
- **WHEN** 用户执行 `/agent new <id> My Cool Bot`（名字多词、无引号）
- **THEN** 新 agent 名字为 `My Cool Bot`（不被截断为 `My`），无 modelId

#### Scenario: 识别末尾已保存模型
- **WHEN** 用户执行 `/agent new <id> Cool Bot <已保存modelId>`
- **THEN** 名字为 `Cool Bot`、modelId 取末尾 token

### Requirement: 状态感知与一致提示
命令反馈 MUST 反映真实状态并保持一致：`/plan exit` 未处于 Plan Mode 时 MUST 如实提示而非声称已退出；`/help` 的子命令列表 MUST 与实际子命令一致（`/agent` 含 `run|report`，`/permission` 含 `channel-mode`）；对未知动作的命令 MUST 采用“Unknown action + 带标题 usage”的统一模式；`/notify test` 在 outreach 未启用或渠道/接收人未配置时 MUST 先给出具体提示再尝试发送。

#### Scenario: 未进入 Plan Mode 时退出
- **WHEN** 当前会话未处于 Plan Mode，用户执行 `/plan exit`
- **THEN** 提示 “Not in Plan Mode”，不声称已退出

#### Scenario: outreach 未配置时的测试发送
- **WHEN** outreach 未启用或缺渠道/接收人，用户执行 `/notify test`
- **THEN** 给出具体的未启用 / 未配置提示，不触达通知服务，且不回显接收人值
