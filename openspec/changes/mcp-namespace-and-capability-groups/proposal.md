## Why

pig 的 MCP 工具注册用**扁平命名空间 + 撞名即拒（D-NS）**：`McpManager.attach` 把新 server 的**原始工具名**逐个与 `toolkit.getToolNames()` 比对，任一撞名就整服务器拒绝（`McpManager.java:326-332`）。这堵死了「多个 MCP 服务器暴露同名工具（`search`/`query`/`read`…）共存」——MCP 生态一膨胀就撞墙，是内核路线图 Wave-3 **Tool-OS T4** 要根除的规模化瓶颈。

同时，AgentScope 原生的 tool group（`mcp:<server>` 分组已被 T2 用于延迟）只是**内部机制**，用户看不见、不能整组启停。T4 顺带把 group 提升为用户可见的「**能力包**」（capability pack，整组开关），嫁接在并行推进的 T3 `/tools groups` UX 之上。

> **承重 spike 已核实（关键，改写了立项假设）**：立项设想「采纳**原生** `mcp__{server}__{tool}` 命名空间」。核对 pig 所钉的 `agentscope-core:2.0.0` 源码（`McpClientManager`/`ToolRegistry`/`Toolkit`/`ToolkitConfig`，javap + sources jar）后**证伪**：2.0.0 **不**做任何 `mcp__` 命名空间——MCP 工具经 `registerAgentTool(tool,…)` 以 `tool.getName()`=**原始工具名**为键**扁平注册**，`ToolkitConfig` 也无命名空间开关。agentscope-expert 文档「MCP 工具在 Toolkit 内以 `mcp__{server_name}__{tool_name}` 命名空间注册」是**文档漂移**，对 2.0.0 不成立。**结论：命名空间必须 pig 自己实现**（装饰器：对模型/Toolkit 暴露 `mcp__server__tool`，对 MCP 服务器仍用原始名 `callTool`）。详见 design.md「Spike」。

## What Changes

- **撞名即拒 → 命名空间化共存**：一个 MCP 服务器若其**任一**工具名与已注册工具撞名，则该服务器的**全部**工具以 `mcp__{server}__{tool}` 命名空间注册（不再整服务器拒绝）；**无撞名**的服务器**照旧扁平注册**（走原生 `registerMcpClient` 路径，逐字节不变）。→ 多 server 同名工具共存。
- **命名空间由 pig 实现**（承重）：一个 `ToolBase` 装饰器把 `getName()` 暴露为 `mcp__server__tool`、`callAsync` 委托底层以**原始名**调 MCP 服务器，并保留 `isReadOnly()`/`isMcp()`/`getMcpName()`（供分类与原生 readOnly 放行）。命名空间工具的**热移除由 pig 自管**（按登记的命名空间名 `removeTool`），因装饰器路径不携带原生 `mcpClientName`——非撞名（原生路径）仍由 `removeMcpClient` 热移除。
- **命名空间感知、fail-safe 的风险分类**：`ToolRiskClassifier` 对 `mcp__server__deleteAll` 等命名空间工具 MUST 仍 fail-safe（未登记 → EXEC，绝不误放行）；`tool-overrides`/`allowlist` 可按**完整命名空间名**指定；MUST NOT 让命名空间 MCP 工具因 base 名巧合命中内置只读表（如恶意 server 造名 `readFile`）而被降级为 READ_ONLY。readOnly MCP 工具的放行改由工具自身 `readOnlyHint`（原生 `isReadOnly`）承载，装饰器保真。
- **能力包（新 capability）**：MCP 工具**无条件**按服务器分入 active 组 `mcp:<server>`（schema-neutral），并把 tool group 暴露为用户可见「能力包」——经 T3 的 `/tools groups` 整组启停（`updateToolGroups`）。**嫁接 T3、不重造 `/tools`**。
- **D-SEC MCP_ADMIN 门不变**：命名空间只作用于 MCP **服务器工具**；pig 自管工具 `addMcpServer`/`removeMcpServer` 不命名空间化，仍由 `mcp.agent-management` 门治理。
- 可选评估原生 `enableMetaTool`/`reset_tools`（模型侧整组切换）——**非必须**，本 spec 不启用（见 design.md D7 / Open Questions）。

无 **BREAKING**：向后兼容经「**仅撞名时命名空间化**」保证——撞名服务器在旧行为下**整个被拒（零工具注册）**，故没有任何既有 `allowlist`/`tool-overrides`/用户引用能指向它，把它命名空间化不破坏任何既有配置；无撞名服务器**逐字节不变**。硬约束：现有 `mcp-management`/`tool-permissions`/`deferred-tools` 全部单测保持绿。

## Capabilities

### New Capabilities
- `tool-capability-packs`: 把 AgentScope tool group 暴露为**用户可见能力包**——每个 MCP 服务器为一个 `mcp:<server>` 能力包（整组启停），经 T3 的 `/tools groups` 操作（`updateToolGroups` active/inactive）；停用即隐藏该组工具（schema），启用即恢复。与 T2 延迟机制正交（延迟=按规模自动隐藏；能力包=用户显式启停），共用同一原生 group 底座。

### Modified Capabilities
- `mcp-management`: **移除**「工具名碰撞拒绝」需求；**新增**「MCP 工具命名空间化共存（仅撞名时）」「命名空间的向后兼容与移除安全」「MCP 服务器即能力包（按服务器分组）」需求。持久化/连通测试/密钥脱敏/D-SEC 门/健康可见等既有需求不变。
- `tool-permissions`: **新增**「MCP 命名空间工具的风险分类（fail-safe + 反冒充 + overrides 按命名空间名）」需求；既有「工具风险分级」的 unknown→EXEC fail-safe 语义保持不变（命名空间工具默认即落此路径）。

## Impact

- **代码**：
  - `pig-agent-mcp` `McpManager`：`attach` 增撞名判定 → 无撞名走原生 `registerMcpClient`（不变）、有撞名走 pig 命名空间装饰器注册（新增 `NamespacedMcpTool extends ToolBase` 或等价）+ 登记 `server→[命名空间名]`；`remove`/`disable`/`edit` 的移除路径按 server 是否命名空间化分流（原生 `removeMcpClient` vs pig `removeTool`）；MCP 工具无条件分入 `mcp:<server>` active 组（扩展既有 `toolGroupNamer`/`managedGroups`）。
  - `pig-agent-tools` `ToolRiskClassifier`：`classify` 增命名空间感知——按完整名查表/overrides，命名空间 MCP 工具 fail-safe 到 EXEC、不回落内置只读表（反冒充）。**中央 name→risk 表的查表逻辑**由本 spec 独家改动（与记忆/技能线协调，仅登记风险）。
  - `pig-agent-cli` `McpCommand`：`/mcp list` 显示命名空间标记；能力包操作复用 T3 的 `/tools`（T4 rebase 到 T3 合并后的 main 再编码，见 design.md）。
  - `pig-agent-config`：如需，能力包/命名空间相关开关（default-safe）。
- **不改**：原生 `registerMcpClient`/`removeMcpClient` 的默认（非撞名）路径与语义；`GuardedAgentTool extends ToolBase`（P0）对 `ToolBase` 的权限门；D-SEC `mcp.agent-management`；`PermissionContextFactory` 的规则映射；`PermissionEngine` 执行 veto。
- **交叉依赖**：T3（并发、先合，`/tools groups`）；T2（已合，`mcp:<server>` 延迟组）；`ToolRiskClassifier` 中央表（记忆 M-*/技能 S* 三线共用，仅本 spec 动查表逻辑）；D-SEC 门。详见 design.md「交叉依赖」。
- **测试（离线，硬约束=全绿）**：命名空间装饰器（`getName` 命名空间化、`callAsync` 用原始名、`isReadOnly`/`isMcp`/`getMcpName` 保真）；仅撞名时命名空间化 + 无撞名逐字节不变；命名空间工具的 pig 自管移除；命名空间感知 fail-safe 分类 + 反冒充；能力包整组启停（active/inactive → schema 变化）。承重 PoC 见 tasks 组 1。
- **文档**：`CLAUDE.md` 的「Dynamic MCP management」段落更新——命名空间化共存（仅撞名）+ 能力包 + 命名空间感知分类；记录「2.0.0 无原生命名空间、由 pig 实现」的承重结论。
