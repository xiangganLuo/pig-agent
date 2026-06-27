## Why

MCP 服务器目前是**只读配置、仅启动期生效**：要增/改/删必须改 `application.yaml` 再重启，没有列表、连通测试、启停或运行时增删。对一个供其他开发者使用的框架，这是尖锐痛点。本变更让 MCP 连接可在运行时通过 CLI 动态管理并连通测试，并允许 agent 在安全门控下自助接入新能力。

## What Changes

- 新增独立 `mcp.json` 存储（`McpServerSpec` record + `McpStore`/`JsonMcpStore`），`mcp.json` 成为唯一真源；首启一次性导入 `application.yaml` 的 `mcp.servers`（向后兼容）。
- `McpManager` 升级为运行时协调者：`add`/`remove`/`edit`/`enable`/`disable`/`test`/`list`，实时增删工具（细锁 E1，I/O 在锁外），碰撞预检（扁平命名空间 D-NS，工具名冲突即拒绝并指明冲突项）。
- 新增 `/mcp` CLI 命令（`list`/`add`/`remove`/`edit`/`enable`/`disable`/`test`）+ 实时健康展示（`/mcp list`、`/status`）。
- 新增 `McpTool`（`@Tool`）让 agent 自助接入，受 **D-SEC 安全门** 控制：`mcp.agent-management.allow-add` 默认关；开启时仅 URL + `allowed-hosts` 白名单 + 人工确认，拒绝 stdio/`command`；`allow-remove` 默认关；`list`/`test` 始终允许。
- 新增 `mcp.agent-management` 配置块（`PigAgentConfig`）与 `WorkspaceManager.getMcpFile()`。
- 命令文件拆分（E2）：`/mcp`、`/model`、`/session` 抽为独立命令类，`ReplCommands` 仅做注册。
- 非破坏性变更：`application.yaml` 的 `mcp.servers` 仍兼容（一次性导入后转为只读）。

## Capabilities

### New Capabilities
- `mcp-management`: 运行时 MCP 服务器的增删改查、连通测试、启用/停用、健康可见，以及受安全门控制的 agent 自助接入与本地持久化（`mcp.json`）。

### Modified Capabilities
<!-- openspec/specs 目前为空，无既有能力的需求变更 -->

## Impact

- **代码**：`pig-agent-mcp`（存储 + `McpManager`）、`pig-agent-config`/`pig-agent-workspace`（配置块 + `getMcpFile()`）、`pig-agent-tools`（`McpTool`）、`pig-agent-cli`（`/mcp` + 接线 + 命令拆分）。
- **配置/数据**：新增 `workspace/mcp.json`；`application.yaml` 的 `mcp.servers` 一次性导入后转为只读。
- **依赖**：AgentScope 1.0.12 的 `Toolkit.removeMcpClient` / `allowToolDeletion`（需 step-0 spike 验证；不成立则 `remove`/`edit`/`disable` 回退为"重启生效"）。
- **安全**：agent 自助接入引入 RCE / 数据外泄攻击面，由 D-SEC 门控制；密钥（`env`/`headers`）在 `list`/工具输出中脱敏。
- **文档**：README（中文）MCP 章节 + `CLAUDE.md`。
