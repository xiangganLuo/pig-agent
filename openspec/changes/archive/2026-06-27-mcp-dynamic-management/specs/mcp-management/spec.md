## ADDED Requirements

### Requirement: 运行时管理 MCP 服务器
系统 SHALL 允许通过 `/mcp` CLI 命令在运行时对 MCP 服务器执行 `list`/`add`/`remove`/`edit`/`enable`/`disable`，无需重启进程或手改配置文件。`add`/`edit` 在持久化之前 MUST 先通过连通测试。命令 MUST 支持按名称或按列表序号（全数字按 1 起的序号，否则按名称）定位服务器。

#### Scenario: 新增服务器后工具即时可用
- **WHEN** 用户执行 `/mcp add` 添加一个可连通的服务器
- **THEN** 该服务器的工具在**同一会话**内不重启即可被调用，且配置持久化以便跨重启恢复

#### Scenario: 删除服务器后工具即时消失
- **WHEN** 用户执行 `/mcp remove <name>` 并确认
- **THEN** 该服务器的工具立即从运行中的 agent 注销（若环境不支持热删除，则按回退在下次重启生效，并明确提示用户）

#### Scenario: 连通测试不通过则不持久化
- **WHEN** `add` 的连通测试失败
- **THEN** 系统 MUST 关闭已建立的 client、不写入存储，并返回清晰错误

### Requirement: 连通测试
系统 SHALL 提供 `/mcp test <name|spec>` 对 MCP 服务器做轻量连通测试，报告成功/失败与工具数量，且对任何坏服务器 MUST NOT 使 REPL 崩溃。

#### Scenario: 测试成功
- **WHEN** 对一个可连通服务器执行 test
- **THEN** 返回 ok 与该服务器暴露的工具数量

#### Scenario: 测试坏服务器
- **WHEN** 对一个无法连接或超时的服务器执行 test
- **THEN** 返回清晰失败原因，REPL 继续正常运行

### Requirement: 本地持久化与向后兼容
系统 SHALL 将 MCP 服务器配置持久化到 `workspace/mcp.json`（按 `name` 作键），并以其为唯一真源。首次运行（无 `mcp.json`）时 MUST 一次性导入 `application.yaml` 的 `mcp.servers`。`mcp.json` 损坏时 MUST 备份并从空开始，不阻断 REPL 启动。

#### Scenario: 跨重启持久化
- **WHEN** 用户 `add` 一个服务器后重启程序
- **THEN** 该服务器从 `mcp.json` 恢复

#### Scenario: 一次性导入旧配置
- **WHEN** 首次运行且 `mcp.json` 不存在、但 `application.yaml` 含 `mcp.servers`
- **THEN** 这些服务器被导入 `mcp.json` 并按既有行为连接

#### Scenario: 配置文件损坏容错
- **WHEN** `mcp.json` 内容损坏不可解析
- **THEN** 系统将其备份为 `mcp.json.bak`、从空配置开始，REPL 仍可运行

### Requirement: 工具名碰撞拒绝
MCP 工具共用扁平命名空间（不按 client 加前缀）。新增服务器时系统 MUST 做碰撞预检，若其某工具名与已注册工具冲突则**拒绝**该服务器，并在错误中指明冲突的工具名及其所属服务器。

#### Scenario: 工具名冲突
- **WHEN** 新增的服务器暴露的某工具名已被另一已注册服务器占用
- **THEN** 添加被拒绝，错误指明冲突工具名与所属服务器，已注册工具集不受影响

### Requirement: agent 自助接入安全门
系统 SHALL 暴露 `McpTool`（`@Tool`）让 agent 进行 `listMcpServers`/`testMcpServer`/`addMcpServer`/`removeMcpServer`。其中 `addMcpServer` 与 `removeMcpServer` MUST 受 `mcp.agent-management` 门控：`allow-add` 默认关闭、`allow-remove` 默认关闭。`allow-add` 开启时，agent 发起的添加 MUST 仅限 URL 传输（绝不允许 stdio/`command`），URL 的 host MUST 在 `allowed-hosts` 白名单内，且 MUST 经人工确认后才连接。`listMcpServers`/`testMcpServer` 为只读，始终允许。

#### Scenario: 默认拒绝 agent 添加
- **WHEN** `allow-add=false`（默认）时 agent 调用 `addMcpServer`
- **THEN** 请求被拒绝并说明原因，不发生连接

#### Scenario: 拒绝 agent 添加 stdio 服务器
- **WHEN** `allow-add=true` 但 agent 提供的是 `command`/stdio 服务器
- **THEN** 请求被拒绝（agent 只能添加 URL 服务器）

#### Scenario: 拒绝非白名单 host
- **WHEN** `allow-add=true`、为 URL 服务器，但其 host 不在 `allowed-hosts`
- **THEN** 请求被拒绝

#### Scenario: 白名单 URL 经人工确认后通过
- **WHEN** `allow-add=true`、URL host 在白名单、且人工在 REPL 确认
- **THEN** 服务器被添加，新工具同一回合可被 agent 调用

#### Scenario: 默认拒绝 agent 删除
- **WHEN** `allow-remove=false`（默认）时 agent 调用 `removeMcpServer`
- **THEN** 请求被拒绝

### Requirement: 密钥脱敏
`list()` 输出以及任何 agent `@Tool` 的返回载荷 MUST 对 `env` 与 `headers` 的值脱敏（显示键、掩码值），防止凭据回显进对话。

#### Scenario: 列表不泄露 token
- **WHEN** 某服务器的 `headers` 含 Bearer token，用户或 agent 列出服务器
- **THEN** 输出显示该 header 的键但其值被掩码，不出现明文 token

### Requirement: 连接健康可见
`/mcp list` 与 `/status` SHALL 展示每个服务器的实时健康（已连接/失败/工具数）。失败或断开的服务器 MUST 在列表中被可见标记，而非静默消失。

#### Scenario: 失败服务器可见
- **WHEN** 某 MCP 服务器连接失败或中途断开
- **THEN** 它在 `/mcp list` 中被标记为失败状态，而不是从列表消失
