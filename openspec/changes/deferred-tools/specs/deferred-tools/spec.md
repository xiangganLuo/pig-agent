## ADDED Requirements

### Requirement: 默认不改变行为（向后兼容）

当延迟工具能力未启用（配置 `tools.deferred.enabled=false`，为默认）时，系统 MUST NOT 隐藏任何工具、MUST NOT 注册 `tool_search`、MUST NOT 对 MCP 工具分组——模型看到的初始工具 schema 与引入本能力前逐字节一致。

#### Scenario: 未配置时一切照旧
- **WHEN** `tools.deferred` 未配置或 `enabled=false`
- **THEN** 所有工具照常出现在模型初始 schema 中，不注册 `tool_search`，MCP 工具不被分组，行为与引入本能力前一致

#### Scenario: 决策纯函数在禁用时产出空计划
- **WHEN** 以 `enabled=false` 调用延迟决策
- **THEN** 产出的延迟计划为空（不延迟任何工具），无论显式清单或工具总数如何

### Requirement: 延迟工具从初始 schema 隐藏但仍可按需调用

被标记为「延迟」的工具 MUST NOT 出现在交给模型的**初始**工具 schema 中（以省提示词 token）。该工具 SHALL 仍注册在 Toolkit 中；在被 `tool_search` 揭示之前，对它的调用 MUST 被拒绝；被揭示之后，它 MUST 既出现在 schema 中、又可被成功调用。揭示 MUST NOT 移除或重注册该工具对象（保持其执行链路与既有包装不变）。

#### Scenario: 延迟工具不在初始 schema
- **WHEN** 某工具被标记为延迟并组装传给模型的工具 schema
- **THEN** 该工具不出现在初始 schema 中，模型初始看不到它

#### Scenario: 揭示后重新可见且可调用
- **WHEN** `tool_search` 揭示了某个延迟工具
- **THEN** 该工具重新出现在 schema 中，且对它的调用被放行（因为它自始至终注册在 Toolkit 中）

### Requirement: tool_search 按需发现工具

系统 SHALL 提供一个只读内置工具 `tool_search`：输入查询/关键词，返回匹配的延迟工具的**名称与描述**，并使这些工具对后续步骤可用（揭示）。当查询为空或无匹配时，`tool_search` MUST 返回有帮助的提示（例如列出可供搜索的工具名），而非报错或空结果。`tool_search` MUST 被风险分级为只读（`READ_ONLY`）。

#### Scenario: 关键词命中返回结果并揭示
- **WHEN** 以命中某些延迟工具的关键词调用 `tool_search`
- **THEN** 返回这些工具的名称与描述，且它们被揭示（对后续步骤可见可调用）

#### Scenario: 无匹配返回有帮助提示
- **WHEN** 以无任何匹配的查询（或空查询）调用 `tool_search`
- **THEN** 返回一条有帮助的提示（含可供搜索的工具名线索），不报错、不返回空

#### Scenario: tool_search 分级为只读
- **WHEN** 对工具名 `tool_search` 做风险分级
- **THEN** 结果为 `READ_ONLY`

### Requirement: 配置驱动的延迟决策

系统 SHALL 依配置决定延迟哪些工具：支持一份**显式工具名清单**，以及一条**阈值规则**——当工具总数超过阈值（默认 25）且开启自动延迟 MCP 时，自动延迟全部 MCP 工具。两者取并集；`tool_search` 自身 MUST 永不被延迟。

#### Scenario: 显式清单被延迟
- **WHEN** 配置的显式清单包含某个存在的工具名
- **THEN** 该工具被纳入延迟计划

#### Scenario: 超阈值时自动延迟 MCP 工具
- **WHEN** 开启自动延迟 MCP 且工具总数超过阈值
- **THEN** 全部 MCP 工具被纳入延迟计划

#### Scenario: 未超阈值时不自动延迟
- **WHEN** 开启自动延迟 MCP 但工具总数不超过阈值且显式清单为空
- **THEN** 延迟计划为空（不延迟任何工具）

### Requirement: 延迟工具元数据与搜索

系统 SHALL 维护一个延迟工具登记表，持有每个延迟工具的**名称、描述、关键词**及其分组标识。搜索 SHALL 依查询与「名称/关键词/描述」的匹配度排序返回；工具被揭示后 MUST 从「仍延迟」集合中移除（不再被搜索返回为待发现）。MCP 工具在 attach 时按服务器分组以保证延迟对其执行/热移除安全（不丢失其 MCP 归属）。

#### Scenario: 搜索按匹配度返回延迟工具
- **WHEN** 以某关键词搜索登记表
- **THEN** 返回匹配的延迟工具（名称+描述），且更相关者排在前

#### Scenario: 揭示后不再被搜索为待发现
- **WHEN** 某延迟工具已被揭示后再次搜索
- **THEN** 该工具不再出现在「待发现」搜索结果中

#### Scenario: MCP 工具延迟不破坏其热移除
- **WHEN** 某 MCP 服务器的工具被延迟（其分组被停用）后，该服务器被移除或禁用
- **THEN** 其工具照常被从 Toolkit 注销（延迟不影响 MCP 生命周期管理）
