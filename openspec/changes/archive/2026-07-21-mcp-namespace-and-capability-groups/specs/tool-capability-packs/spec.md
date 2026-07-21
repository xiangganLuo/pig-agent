## ADDED Requirements

### Requirement: tool group 暴露为用户可见能力包
系统 SHALL 把 AgentScope tool group 暴露为**用户可见的「能力包」**——用户可查看当前能力包及其启用状态，并**整组启停**（激活/停用）某能力包。停用一个能力包 MUST 使该组工具从模型的工具 schema 中隐藏（不可见、调用被拒），启用 MUST 使其恢复可见可调用。此能力 SHALL **嫁接**在 tool 运维命令的 groups 子面（T3 `/tools groups`）之上，MUST NOT 另造一套 `/tools` 命令面。

#### Scenario: 查看能力包及状态
- **WHEN** 用户列出能力包
- **THEN** 展示每个能力包（tool group）及其当前启用/停用状态

#### Scenario: 停用能力包隐藏其工具
- **WHEN** 用户停用某能力包
- **THEN** 该组工具从模型工具 schema 中隐藏，后续对其中工具的调用被拒（直至重新启用）

#### Scenario: 启用能力包恢复其工具
- **WHEN** 用户启用一个先前被停用的能力包
- **THEN** 该组工具重新进入模型工具 schema、可被调用

### Requirement: MCP 服务器即能力包
系统 MUST 把每个 MCP 服务器的工具**无条件**分入一个以服务器命名的 active tool group（`mcp:<server>`），使每个 MCP 服务器成为一个一等的「能力包」，可作为整体启停。因该组默认 active 且 active 组对模型 schema 是 schema-neutral（等同 `basic` 组可见性），此分组 MUST NOT 改变模型看到的初始工具 schema（默认逐字节无感）。该分组 MUST 与命名空间化正交——无论某服务器的工具是扁平注册还是命名空间注册，其工具都归入该服务器的能力包。

#### Scenario: MCP 服务器工具默认分入其能力包且 schema 无感
- **WHEN** 一个 MCP 服务器连接、其工具被注册
- **THEN** 其工具归入 active 组 `mcp:<server>`，且模型看到的初始工具 schema 与未分组时逐字节一致

#### Scenario: 整组停用 MCP 服务器能力包
- **WHEN** 用户停用能力包 `mcp:<server>`
- **THEN** 该服务器的全部工具（无论扁平或命名空间名）从模型 schema 隐藏、调用被拒

#### Scenario: 分组与命名空间正交
- **WHEN** 服务器 B 因撞名被命名空间化（工具为 `mcp__B__*`）
- **THEN** 这些命名空间工具仍归入能力包 `mcp:B`，可随该能力包整组启停

### Requirement: 能力包与延迟机制共用底座且正交
能力包（用户显式启停）与延迟工具能力（按规模自动隐藏，capability `deferred-tools`）MUST 共用同一原生 tool group 底座（`updateToolGroups` 激活/停用），且语义**正交**：延迟是「按规模自动令组 inactive 以省 token」，能力包是「用户显式令组 inactive/active」。系统 MUST NOT 因引入能力包而改变延迟机制的既有行为；同一组的最终 active 态由最后一次操作（延迟或用户）决定。

#### Scenario: 用户启用被延迟隐藏的 MCP 能力包
- **WHEN** 某 MCP 服务器的组因延迟策略被自动停用（隐藏），用户随后显式启用该能力包
- **THEN** 该组变为 active、其工具重新可见可调用（用户操作覆盖延迟的自动停用）

#### Scenario: 引入能力包不改变延迟默认行为
- **WHEN** 未进行任何能力包操作、仅延迟能力按其配置运行
- **THEN** 延迟的隐藏/揭示行为与引入能力包前一致（能力包为纯附加，不改延迟语义）
