# mcp-management Specification

## Purpose

定义 Pig Agent 对 MCP（Model Context Protocol）服务器的动态管理能力：在运行时对 MCP 服务器进行增删改查与连通测试，将配置持久化为唯一真源并保持向后兼容，通过扁平命名空间的工具名碰撞检测保证安全注册，为 agent 自助接入提供受控安全门，对凭据脱敏，并使连接健康对用户可见。
## Requirements
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
</content>
</invoke>

### Requirement: MCP 工具命名空间化共存
当新增/连接一个 MCP 服务器时，若其**任一**工具名与**已注册的任何工具**（含其它 MCP 服务器工具与内置工具）撞名，系统 MUST NOT 整服务器拒绝，而是把该服务器的**全部**工具以命名空间名 `mcp__{server}__{tool}` 注册，使多个服务器的同名工具共存。**无撞名**的服务器 MUST 照旧以**原始工具名**扁平注册（不改变既有行为）。命名空间工具对模型/Toolkit 暴露命名空间名，但对 MCP 服务器的实际调用 MUST 使用**原始工具名**（命名空间仅是本地呈现，不改变协议调用）。

#### Scenario: 撞名服务器命名空间化后共存
- **WHEN** 服务器 A 已注册工具 `search`，随后新增服务器 B 也暴露 `search`
- **THEN** B 不被拒绝；B 的工具以 `mcp__B__search`（及 B 其余工具的 `mcp__B__*`）注册，A 的 `search` 与 B 的 `mcp__B__search` 并存可调用

#### Scenario: 无撞名服务器保持扁平（逐字节不变）
- **WHEN** 新增服务器 C，其所有工具名均不与已注册工具撞名
- **THEN** C 的工具以其**原始名**扁平注册，行为与引入本能力前逐字节一致（不命名空间化、不改名）

#### Scenario: 命名空间工具以原始名调用服务器
- **WHEN** 模型调用 `mcp__B__search`
- **THEN** 系统以**原始名** `search` 向服务器 B 发起 MCP 调用（命名空间不泄漏进协议层），返回结果正常回传

#### Scenario: 命名空间工具保真只读/来源元信息
- **WHEN** 某命名空间 MCP 工具由底层被标记 `readOnlyHint`（只读）
- **THEN** 其 `isReadOnly()`/`isMcp()`/`getMcpName()` 保真底层工具（供风险分类与原生只读放行正确判定）

### Requirement: 命名空间化的向后兼容与移除安全
系统 MUST 保证命名空间化**向后兼容**：既有 `mcp.json`、`permissions.allowlist`、`tool-overrides` 与用户对 MCP 工具名的既有引用 MUST NOT 因本能力被破坏——采用「**仅撞名时命名空间化**」策略即可满足（撞名服务器在旧行为下整个被拒、零工具注册，故无既有配置能指向它）。系统 MUST 保证命名空间工具的**热移除**：因命名空间工具经装饰器注册、不携带原生 `mcpClientName`，系统 MUST 自行登记 `server→[命名空间名]` 并在 `remove`/`disable`/`edit` 时按登记名注销之；无撞名（原生注册）服务器 MUST 仍由原生 `removeMcpClient` 热移除。

#### Scenario: 既有配置零破坏
- **WHEN** 用户升级到本能力，其现有单服务器/无撞名配置（含 `allowlist`/`tool-overrides` 对某 MCP 工具名的引用）照旧运行
- **THEN** 这些工具名不变、引用仍生效，无需改动任何配置文件

#### Scenario: 命名空间服务器的工具热移除
- **WHEN** 一个被命名空间化的服务器 B 被 `/mcp remove` 或 `/mcp disable`
- **THEN** 其 `mcp__B__*` 工具从运行中的 Toolkit 全部注销（经 pig 自管的 `removeTool` 按登记名），不残留

#### Scenario: 无撞名服务器仍走原生热移除
- **WHEN** 一个未命名空间化（原生扁平注册）的服务器被移除
- **THEN** 其工具经原生 `removeMcpClient` 注销（`mcpClientName` 路径不变），行为与引入本能力前一致

### Requirement: MCP 连接列表显示命名空间标记
`/mcp list` MUST 让用户可见某服务器的工具是否已被命名空间化（例如标注该服务器为「namespaced」或显示其工具的 `mcp__{server}__` 前缀），以便用户理解为何一个工具以命名空间名出现，并据此正确书写 `allowlist`/`tool-overrides`。该显示 MUST 沿用既有密钥脱敏（不回显 URL/header 凭据）。

#### Scenario: 列表标记命名空间服务器
- **WHEN** 服务器 B 因撞名被命名空间化后，用户执行 `/mcp list`
- **THEN** B 被可见标注为已命名空间化（其工具以 `mcp__B__*` 呈现），凭据仍脱敏

