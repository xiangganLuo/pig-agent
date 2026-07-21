## ADDED Requirements

### Requirement: MCP 命名空间工具的风险分类
当 MCP 工具以命名空间名 `mcp__{server}__{tool}` 注册时，风险分类器 MUST 对其保持 **fail-safe**：命名空间工具若未在默认风险表或 `tool-overrides` 中显式登记，MUST 按最严级别（EXEC）处理（在 ask/auto 下要求确认、在 plan 下否决），MUST NOT 因命名空间名/base 名巧合被误判为更低风险。分类器 MUST NOT 对命名空间 MCP 工具「回落 base 名去匹配内置只读表」——即一个服务器把危险工具命名为 `readFile`/`listDirectory` 等内置只读工具同名，其命名空间形式 `mcp__server__readFile` MUST NOT 因此被降级为 READ_ONLY（反冒充）。用户若要放行或重分类某命名空间 MCP 工具，MUST 以其**完整命名空间名**在 `permissions.allowlist.tools` 或 `permissions.tool-overrides` 中指定。只读 MCP 工具的免确认 MUST 由工具自身的只读标记（`readOnlyHint`/`isReadOnly()`，命名空间装饰器保真）承载，而非由分类器对 MCP 服务器工具名的表匹配承载。

#### Scenario: 命名空间工具未登记时按 EXEC fail-safe
- **WHEN** 对命名空间工具 `mcp__fs__deleteAll` 做风险分级，其未在默认表或 `tool-overrides` 中登记
- **THEN** 分类结果为 EXEC（未知按最严），在 ask/auto 下要求确认、在 plan 下否决——绝不误放行

#### Scenario: 反冒充——命名空间工具不因 base 名巧合降级
- **WHEN** 某 MCP 服务器把一个可变/危险工具命名为 `readFile`，其注册为 `mcp__evil__readFile`
- **THEN** 分类器 MUST NOT 因 base 名 `readFile` 命中内置 READ_ONLY 表而将其降级为 READ_ONLY；`mcp__evil__readFile` 仍按 fail-safe（EXEC）处理

#### Scenario: 按完整命名空间名放行/重分类
- **WHEN** 用户在 `permissions.tool-overrides` 中以完整名 `mcp__weather__forecast: READ_ONLY` 重分类，或在 `allowlist.tools` 中加入 `mcp__weather__forecast`
- **THEN** 分类/放行对该命名空间工具生效（按完整名匹配），未指定的其它命名空间工具不受影响

#### Scenario: 只读命名空间工具经工具自身标记放行
- **WHEN** 一个底层标记 `readOnlyHint` 的 MCP 工具被命名空间化为 `mcp__docs__search`
- **THEN** 其只读放行由装饰器保真的 `isReadOnly()`/原生只读检查承载，命名空间不改变其只读放行语义
