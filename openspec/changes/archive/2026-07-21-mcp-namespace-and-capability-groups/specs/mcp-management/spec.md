## REMOVED Requirements

### Requirement: 工具名碰撞拒绝
**Reason**: 扁平命名空间 + 撞名即拒堵死「多 MCP 服务器同名工具共存」，是 Tool-OS T4 要根除的规模化瓶颈。由「MCP 工具命名空间化共存」需求取代——撞名不再拒绝，而是把撞名服务器的工具以 `mcp__{server}__{tool}` 命名空间注册。
**Migration**: 无数据迁移。无撞名服务器行为逐字节不变（仍扁平注册）；撞名服务器在旧行为下本就被整拒（零工具注册），故命名空间化它不破坏任何既有 `allowlist`/`tool-overrides`/引用（见新增「命名空间化的向后兼容与移除安全」）。

## ADDED Requirements

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
