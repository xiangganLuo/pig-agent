## Why

这是一个 tool 密集的编码 agent，MCP 服务器可动态增删——工具越多，**每回合**塞进模型 system/tool schema 的工具定义就越大，白白吃 prompt token（且稀释模型注意力、诱发幻觉调用）。`ToolAvailabilityGate` 只做启动期「装/不装」的静态可见性过滤，一旦装上就每回合全量暴露；没有「装了但先不给模型看、需要时再拿出来」的动态层。

deerflow 的 `DeferredToolFilterMiddleware` 给了范式：把大/罕用工具的 schema 从初始工具列表里拿掉，只留一个 `tool_search` 让模型**按需发现并启用**它们。本能力把这套「延迟工具 + 按需揭示」引入 pig-agent。

## What Changes

- **`DeferredToolRegistry`**：持有被标记为「延迟」的工具元数据（名称 + 描述 + 关键词）。`search(query)` 按关键词/名称/描述匹配打分排序；工具被揭示后从「待搜索」集合移除。纯逻辑、可离线单测。
- **`tool_search` 内置工具（只读）**：输入查询/关键词 → 返回匹配的延迟工具**名称 + 描述**，并**揭示**它们（激活其分组）使其对后续步骤可见且可调用；空查询/无匹配返回**有帮助的提示**（列出可搜索的工具名，而非报错或空串）。在 `ToolRiskClassifier` 分级为 `READ_ONLY`。
- **配置驱动的延迟决策**（`tools.deferred`）：`enabled`（总开关，默认 **false** → 一切照旧）、显式清单 `tools`、以及阈值规则 `auto-defer-mcp` + `threshold`（默认 25）——当**总工具数**超阈值时自动延迟 MCP 工具。`DeferredToolPlanner` 是纯函数，据此产出「该延迟哪些工具」的 `DeferralPlan`。
- **机制 = AgentScope 原生 tool group**（详见 design.md D1，经反编译 1.0.12 逐条证实）：延迟 = 把工具放进一个 **inactive 分组** → 它被 `getToolSchemas()` 排除（省 token）**且**在激活前不可调用；揭示 = **激活该分组**（`updateToolGroups(...,true)`，纯 flag 翻转，工具对象**从不被移除/重注册** → MCP 执行链路与 contract-guard 包装均不受影响）。
- **MCP 工具的安全延迟**：MCP 工具的分组必须在 **attach 时**由 `McpManager` 通过 `registration().mcpClient(...).group(g)` 完成（保留 `mcpClientName`，`removeMcpClient` 热移除照常），因此给 `McpManager` 加一个**可选**的「按服务器名 → 分组名」函数（默认 null → 零行为变化）。事后对已注册 MCP 工具补分组会走重注册、丢 `mcpClientName`，故禁止（见 design.md D2）。
- **`DeferredToolGate`**：启动期在全部工具（内置 + 插件 + MCP）注册完毕后，据 `DeferralPlan` 把选中的工具移入 inactive 分组（内置/插件走「重注册当前 `AgentTool` 到 per-tool 分组」，MCP 走「停用 `mcp:<server>` 分组」），并据此填充 `DeferredToolRegistry`。
- **`AgentBootstrap` 接线**：启用时在 contract-guard 之前注册 `tool_search`（自然被 guard 包裹），给 `McpManager` 注入分组函数；MCP attach 后运行 planner + gate。

无 **BREAKING**：`enabled=false`（默认）时不隐藏任何工具、不注册 `tool_search`、不对 MCP 分组——行为与引入本能力前逐字节一致。权限 veto / 可用性过滤 / 返回契约维度均不变，本能力只决定「工具是否进入模型初始 schema」。

## Capabilities

### New Capabilities
- `deferred-tools`：把大/罕用工具（尤其随 MCP 增长的工具）移出模型初始 schema、经 `tool_search` 按需发现并揭示的延迟工具层，省提示词 token。

### Modified Capabilities
<!-- 不改 tool-availability（启动期装/不装的静态过滤）、tool-permissions（执行期 veto）、tool-json-contract（返回契约）、mcp-management（增删改/热插拔/扁平命名空间）的既有契约；本能力为正交新增层，叠加在它们之上。McpManager 的分组函数为可选、默认关，不改既有 MCP 语义。 -->

## Impact

- **代码**：
  - `pig-agent-tools` 新增包 `io.pigagent.tool.deferred`：`DeferredTool`、`Keywords`、`DeferredToolRegistry`、`ToolInfo`、`DeferralPlan`、`DeferredToolPlanner`、`DeferredToolReveal`、`DeferredToolGate`、`ToolSearchTool`；改 `permission/ToolRiskClassifier`（`tool_search` → READ_ONLY）。
  - `pig-agent-config` 改 `PigAgentConfig.ToolsConfig`：新增 `DeferredToolsConfig`（`enabled`/`tools`/`auto-defer-mcp`/`threshold`）。
  - `pig-agent-mcp` 改 `McpManager`：可选分组函数（默认 null）+ attach 时按服务器分组 + `managedToolGroups()`。
  - `pig-agent-cli` 改 `AgentBootstrap`：接线（启用时注册 `tool_search` + 注入分组函数 + 跑 planner/gate）。
- **不改**：`ToolAvailabilityGate`、`ToolPermissionHook`、`ToolContractGuard`、`Toolkit.copy()` 的既有语义；`McpManager` 默认路径（不传分组函数时逐字节不变）。
- **测试**（离线，必要处用真实 `Toolkit`/mock）：registry 匹配/揭示；planner 显式清单 + 阈值规则 + 禁用 no-op；gate 对真实 Toolkit 延迟后 `getToolSchemas()` 排除、揭示后恢复；`tool_search` 关键词→结果、空→提示、揭示回调触发；`ToolRiskClassifier` 认得 `tool_search`。
- **文档**：`CLAUDE.md` 增补一段「Deferred tools + tool_search」说明。
