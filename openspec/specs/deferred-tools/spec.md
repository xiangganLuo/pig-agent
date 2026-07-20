# deferred-tools Specification

## Purpose
把大/罕用工具（尤其随 MCP 增长的工具）移出模型的**初始** tool schema，以省每回合提示词 token，
同时保留其可被模型**按需发现并调用**：延迟的工具经内置只读工具 `tool_search` 搜索、揭示后重新可见可调用。
默认关闭时行为与引入本能力前逐字节一致。
## Requirements
### Requirement: 默认不改变行为（向后兼容）

延迟工具能力**默认以「随规模智能开」策略运行**（配置 `tools.deferred.enabled=true`，见新增「延迟能力的智能默认策略」），但采用**阈值制**保证向后兼容：当延迟计划为空——即工具总数未超过阈值**且**显式清单无命中——时，系统 MUST NOT 隐藏任何工具、MUST NOT 注册 `tool_search`、MUST NOT 停用/新增任何影响 schema 的工具分组，模型看到的初始工具 schema 与引入本能力前逐字节一致。当配置显式关闭（`enabled=false`）时，系统 MUST 同样完全禁用——不注册 `tool_search`、不分组、不隐藏任何工具。

#### Scenario: 小工具集默认逐字节无感

- **WHEN** 采用默认配置（`enabled=true`）但工具总数未超过阈值且显式清单为空
- **THEN** 不隐藏任何工具、不注册 `tool_search`、不停用任何分组，模型初始工具 schema 与引入本能力前逐字节一致

#### Scenario: 显式关闭完全禁用

- **WHEN** 配置 `tools.deferred.enabled=false`
- **THEN** 完全禁用：不注册 `tool_search`、不对 MCP 工具分组、不隐藏任何工具，行为与引入本能力前一致

#### Scenario: 决策纯函数在禁用或无命中时产出空计划

- **WHEN** 以 `enabled=false` 调用延迟决策，或以 `enabled=true` 但工具总数不超阈值且显式清单为空调用
- **THEN** 产出的延迟计划为空（不延迟任何工具）

### Requirement: 延迟工具从初始 schema 隐藏但仍可按需调用

被标记为「延迟」的工具 MUST NOT 出现在交给模型的**初始**工具 schema 中（以省提示词 token）。该工具 SHALL 仍注册在 Toolkit 中；在被 `tool_search` 揭示之前，对它的调用 MUST 被拒绝；被揭示之后，它 MUST 既出现在 schema 中、又可被成功调用。揭示 MUST NOT 移除或重注册该工具对象（保持其执行链路与既有包装不变）。

#### Scenario: 延迟工具不在初始 schema
- **WHEN** 某工具被标记为延迟并组装传给模型的工具 schema
- **THEN** 该工具不出现在初始 schema 中，模型初始看不到它

#### Scenario: 揭示后重新可见且可调用
- **WHEN** `tool_search` 揭示了某个延迟工具
- **THEN** 该工具重新出现在 schema 中，且对它的调用被放行（因为它自始至终注册在 Toolkit 中）

### Requirement: tool_search 按需发现工具

系统 SHALL 提供一个只读内置工具 `tool_search`：输入查询/关键词，返回匹配的延迟工具的**名称与描述**，并使这些工具对后续步骤可用（揭示）。其排序 SHALL 复用内核共享检索原语 `io.pigagent.core.search`（BM25，可选叠加混合 ranker），与 `memory_search` **同源**——MUST NOT 另造一套关键词计分。当查询为空或无匹配时，`tool_search` MUST 返回有帮助的提示（例如列出可供搜索的工具名），而非报错或空结果。`tool_search` MUST 被风险分级为只读（`READ_ONLY`），且该分级 MUST NOT 因本次排序升级而改变。

#### Scenario: 关键词命中返回结果并揭示

- **WHEN** 以命中某些延迟工具的关键词调用 `tool_search`
- **THEN** 返回这些工具的名称与描述，且它们被揭示（对后续步骤可见可调用）

#### Scenario: 混合排序令更相关的工具排在前

- **WHEN** 以某查询调用 `tool_search`，多个延迟工具部分匹配
- **THEN** 经共享检索原语（BM25/混合）打分后，与查询更相关的工具排在返回结果的前面

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

系统 SHALL 维护一个延迟工具登记表，持有每个延迟工具的**名称、描述、关键词**及其分组标识。搜索 SHALL 把每个延迟工具建成一个通用检索文档 `SearchDocument`（`id`=工具名、`text`=名+描述+关键词），复用内核共享检索原语 `io.pigagent.core.search`（`Bm25Index` + `HybridRanker` + CJK `Tokenizer`）对查询打分排序返回，MUST NOT 自造关键词计分；工具被揭示后 MUST 从「仍延迟」集合中移除（不再被搜索返回为待发现）。MCP 工具在 attach 时按服务器分组以保证延迟对其执行/热移除安全（不丢失其 MCP 归属）。

#### Scenario: 搜索按相关度返回延迟工具

- **WHEN** 以某关键词搜索登记表
- **THEN** 返回匹配的延迟工具（名称+描述），且经共享检索原语排序后更相关者排在前

#### Scenario: 中文查询命中含中文描述的工具

- **WHEN** 登记表含描述为中文的延迟工具，以对应中文关键词搜索
- **THEN** 该工具被命中并返回（CJK unigram+bigram 分词生效，与记忆检索一致）

#### Scenario: 揭示后不再被搜索为待发现

- **WHEN** 某延迟工具已被揭示后再次搜索
- **THEN** 该工具不再出现在「待发现」搜索结果中

#### Scenario: MCP 工具延迟不破坏其热移除

- **WHEN** 某 MCP 服务器的工具被延迟（其分组被停用）后，该服务器被移除或禁用
- **THEN** 其工具照常被从 Toolkit 注销（延迟不影响 MCP 生命周期管理）

### Requirement: 延迟能力的智能默认策略

系统 SHALL 默认以「随规模智能开」策略运行延迟工具能力：`tools.deferred.enabled` 默认为 `true`、`auto-defer-mcp` 默认为 `true`、`threshold` 默认为 25。当启用且**工具总数超过阈值**时，系统 SHALL 把全部 MCP 工具纳入延迟计划（与显式清单取并集）；未超过阈值且显式清单为空时，延迟计划 SHALL 为空。`tool_search` 自身 MUST 永不被延迟。配置 `enabled=false` MUST 完全禁用本能力（不产出任何延迟）。此默认变化 MUST 经阈值制保证对小工具集用户逐字节无感（见「默认不改变行为（向后兼容）」）。

#### Scenario: 默认启用且超阈值时自动延迟 MCP 工具

- **WHEN** 采用默认配置（`enabled=true`、`auto-defer-mcp=true`、`threshold=25`）且工具总数超过阈值
- **THEN** 全部 MCP 工具被纳入延迟计划（`tool_search` 除外）

#### Scenario: 默认启用但未超阈值时不延迟

- **WHEN** 采用默认配置但工具总数未超过阈值且显式清单为空
- **THEN** 延迟计划为空，不延迟任何工具

#### Scenario: 显式关闭覆盖智能默认

- **WHEN** 配置 `tools.deferred.enabled=false`
- **THEN** 无论工具总数多少，延迟计划为空，本能力完全禁用

### Requirement: 揭示状态跨 Toolkit.copy() 派生实例一致

当某 agent 经 `tool_search` 揭示一个延迟工具时，该工具的"可见/可调用"状态 MUST 在**该 agent 实际运行的 toolkit 实例**上生效——即被揭示工具在该上下文中真正出现在 schema 中、且对它的调用被放行。对经 `Toolkit.copy()` 派生（peer 的工具白名单子集、子agent 的继承 toolkit）且仍包含该延迟工具的实例，揭示 MUST NOT 因 copy 而静默作用于另一（如原始）实例并在当前上下文丢失。

#### Scenario: peer 实例中揭示的工具在该 peer 可见可调用

- **WHEN** 一个经 `Toolkit.copy()` 派生、其工具集仍包含某延迟工具的 peer agent，在其上下文中调用 `tool_search` 揭示该工具
- **THEN** 该工具在该 peer 运行的 toolkit 实例中变为可见且可调用（揭示不因 copy 丢失）

#### Scenario: 子agent 实例中揭示的工具在该子agent 可见可调用

- **WHEN** 一个经父 toolkit `copy()` 继承、仍包含某延迟工具的子agent，在其上下文中调用 `tool_search` 揭示该工具
- **THEN** 该工具在该子agent 运行的 toolkit 实例中变为可见且可调用（揭示不因 copy 丢失）

