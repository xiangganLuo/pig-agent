## Context

pig 的 MCP 注册栈（`pig-agent-mcp` `McpManager`）：

- **注册（扁平 + 撞名即拒 D-NS）**：`attach(spec)` 连接 → `toolNamesOf(client)` 取**原始工具名** → 短临界区内 `existing = toolkit.getToolNames()`，任一 `incoming` 撞 `existing` 就 `throw`（整服务器拒绝，`McpManager.java:326-332`），否则 `registerClient`。
- **分组（可选，T2 延迟用）**：`toolGroupNamer`（`server→group`）默认 `null`（不分组，工具入 `basic`）；非 `null` 时 `registration().mcpClient(client).group(g).apply()` 把工具分入 group，`managedGroups` 登记，供 `DeferredToolGate` 停用以隐藏。
- **移除（原生热移除）**：`remove/disable` 走 `toolkit.removeMcpClient(name)`——框架按 `RegisteredToolFunction.getMcpClientName()` 找回该 client 的工具并注销。

权限侧：`PermissionContextFactory.build(cfg, mode, toolkit.getToolNames(), interactive)` 对 **toolkit 每个工具名**（含 MCP 工具，经 `McpManager` 的 `toolsChangedCallback` 重建后纳入）逐个 `ToolRiskClassifier.classify` → `PermissionPolicy.decide` 生成 per-tool 规则。`ToolRiskClassifier` 是静态 `name→ToolRisk` 中央表，**未登记 → EXEC**（fail-safe）。MCP 服务器工具名不在表中 → 一律 EXEC。

Wave-3 Tool-OS T4 要：(1) 命名空间化让多 server 同名工具共存；(2) 把 group 暴露为用户可见能力包（嫁接 T3 `/tools groups`）。

## Goals / Non-Goals

**Goals**
- 撞名不再整服务器拒绝：多个 MCP 服务器的同名工具经 `mcp__{server}__{tool}` 命名空间**共存**。
- **向后兼容 backward-safe**：既有（无撞名）配置逐字节不变，`allowlist`/`tool-overrides`/用户引用零破坏。
- **命名空间感知、fail-safe 的风险分类**：命名空间 MCP 工具绝不因命名空间/base 名巧合被误放行；readOnly MCP 工具的放行保真。
- tool group 成为用户可见「能力包」（整组启停），**嫁接 T3、不重造 `/tools`**。
- D-SEC MCP_ADMIN 门在命名空间后仍治理危险的 MCP 自管。

**Non-Goals**
- 不引入原生命名空间开关（2.0.0 无——见 Spike；由 pig 实现）。
- 不给 MCP 服务器工具做语义检索/延迟策略变更（T2/T3 各自负责；本 spec 只让命名空间与其正交共存）。
- 不默认启用原生 `enableMetaTool`/`reset_tools`（模型侧整组切换）——评估、非必须（D7）。
- 不改 `PermissionContextFactory` 的规则映射、`PermissionPolicy` 决策表、D-SEC 门、P0 `GuardedAgentTool` 语义。
- 不做命名空间的持久化迁移工具（因选「仅撞名时命名空间化」不需要迁移——见 D2）。

## Spike（前置 Task 组 1 —— 承重，已在本机源码核验）

> 立项承重假设是「采纳**原生** `mcp__{server}__{tool}` 命名空间」。此假设**被证伪**，且牵出第二个承重点（移除安全）。以下结论已核对 `agentscope-core:2.0.0` 的编译产物 + sources jar（`D:\env\apache-maven-3.9.10\repository\io\agentscope\agentscope-core\2.0.0\`）。

**S1 —（否）2.0.0 无原生 `mcp__` 命名空间。** `McpClientManager.registerMcpClient(...)` 对每个 MCP 工具 `new McpTool(mcpTool.name(), …, clientWrapper, …, clientWrapper.getName(), readOnly)`——`name`=**原始工具名**；`McpTool.callAsync` 用 `clientWrapper.callTool(getName(), …)`（即以 `getName()` 调服务器）。注册回调 `registerAgentTool` 用 `String toolName = tool.getName()` 作为 `ToolRegistry` 的键（`ToolRegistry.java:56 tools.put(toolName, tool)`）。全仓 sources 中 `mcp__` 仅出现在**无关**的 `McpMeta`（请求元数据类型命名空间）；`ToolkitConfig` 无命名空间开关（javap 仅 `isParallel/getExecutorService/…/isAllowToolDeletion`）。**故 MCP 工具在 2.0.0 里以原始名扁平注册，命名空间必须 pig 实现。** agentscope-expert 文档的「以 `mcp__{server}__{tool}` 命名空间注册」是**文档漂移**，对 2.0.0 不成立。

**S2 — 命名空间只能改 `getName()`，不能改注册键。** 公共 API 里 `registerAgentTool`/`registration().agentTool(tool)`/`.mcpClient(client)` 都以 `tool.getName()` 为注册键与 schema 名，无「另设注册名」的旋钮（`ToolRegistration` 只有 `tool/agentTool/mcpClient/enableTools/disableTools/group/presetParameters/extendedModel/apply`）。因此命名空间必须让工具**自身** `getName()` 返回 `mcp__server__tool`——但 `McpTool.callAsync` 又用 `getName()` 调服务器（会用错名）。**解法：装饰器**——`NamespacedMcpTool extends ToolBase`，`getName()=mcp__server__tool`，`callAsync` 委托一个底层「原始名」`McpTool`（或直接 `clientWrapper.callTool(rawName,…)`）；同时**保真** `isReadOnly()/isMcp()/getMcpName()`（供分类 + 原生 readOnly 放行 + P0 权限门——`extends ToolBase` 保证 `PermissionEngine` 实际治理它）。

**S3 —（承重②）装饰器路径丢 `mcpClientName` → 原生热移除失效。** `.mcpClient(client)` 路径经回调 `registerAgentTool(tool, group, null, mcpClientName, presets)` **携带** `mcpClientName`（`removeMcpClient` 靠它找回工具）；而 `.agentTool(decorator)` 路径 `mcpClientName=null`。故 pig 命名空间装饰器一旦经 `.agentTool` 注册，`removeMcpClient(server)` **找不到**它。**解法**：命名空间工具的移除由 pig 自管——登记 `server→[命名空间名]`，`remove/disable` 时对每个名 `toolkit.removeTool(name)`；**非命名空间（无撞名）服务器仍走原生 `.mcpClient` + `removeMcpClient`（`mcpClientName` 保真）**。这与 deferred-tools 早已记录的「重注册 MCP 工具会丢 `mcpClientName`」同源约束，此处以「pig 自管移除」化解。

**S4 —（是）命名空间对分类默认 fail-safe。** `mcp__server__deleteAll` 与原始 `deleteAll` **都不在** `ToolRiskClassifier` 内置表 → 都落 `unknown→EXEC`。故命名空间**默认不改变** MCP 服务器工具的风险级别（恒 EXEC，绝不误放行）。需处理的仅两处：(a) 用户 `tool-overrides`/`allowlist` 若按**原始名**写、又因撞名被命名空间化，则失配——但「仅撞名时命名空间化」（D2）令被命名空间化的服务器**本就是旧行为下被整拒、无既有配置可指向者**，故失配面为空；(b) **反冒充**：不得为「命名空间工具回落 base 名查内置表」——否则恶意 server 造名 `readFile` 会被降级 READ_ONLY 误放行（安全洞）。故命名空间 MCP 工具的内置表查找 **fail-safe 到 EXEC，不做 base 名回落**（D3）。

**S5 —（是）分入 active group 是 schema-neutral。** `getToolSchemas()` 含 `basic` + 所有 active group。把 MCP 工具从 `basic` 移入 active 的 `mcp:<server>`（`createToolGroup(g, …, true)` + `registration().group(g)`）**不改可见 schema**（deferred-tools 已依赖此性质）。故「无条件按服务器分组」对模型 schema 逐字节无感，能力包停用（`updateToolGroups(g,false)`）时才隐藏。

**Task 组 1 的验收动作**（`/ls:code` 阶段）：离线 PoC 证 (1) `NamespacedMcpTool` 的 `getName` 命名空间化、`callAsync` 用原始名回调、`isReadOnly/isMcp/getMcpName` 保真；(2) 经 `.agentTool` 注册的命名空间工具 `removeMcpClient` 找不到、`removeTool(namespacedName)` 能移除（承重②回归护栏）；(3) 命名空间工具 `classify → EXEC`（fail-safe）、base 名 `readFile` 冒充不降级；(4) 分入 active group 后 `getToolSchemas` 含之、`updateToolGroups(g,false)` 后不含。走主路径。

## Decisions

- **D1 — 命名空间由 pig 装饰器实现（`NamespacedMcpTool extends ToolBase`）**。`getName()=mcp__{server}__{tool}`；`callAsync` 委托底层以**原始名**调 MCP 服务器；`isReadOnly/isMcp/getMcpName/getParameters/description` 保真底层 `McpTool`。**`extends ToolBase`**（而非仅 `AgentTool`）对齐 P0——`PermissionEngine` 只治理 `ToolBase`，且 `ToolContractGuard` 的 `GuardedAgentTool` 同理。*备选*：改框架/等 2.0.x 出原生命名空间——否决（阻塞、不可控，且 pig 钉 2.0.0）；后处理重命名注册键——否决（S2：注册键=`getName()`，无旋钮）。
- **D2 —（向后兼容命门）仅撞名时命名空间化，且撞名服务器整服务器命名空间化**。判据：`attach` 时若该服务器**任一**原始工具名 ∈ 已注册工具名 → 该服务器**全部**工具走 D1 装饰器（命名空间 + pig 自管移除）；**否则**（无撞名）走原生 `registerMcpClient`（扁平、`mcpClientName` 保真、逐字节不变）。**backward-safe 论证**：撞名服务器在旧行为下**整个被拒、零工具注册**，故无任何既有 `allowlist`/`tool-overrides`/用户引用能指向它——命名空间化它**不破坏任何既有配置**；无撞名服务器路径逐字节不变。「整服务器命名空间化」（而非只命名空间化撞名的那一个工具）令**每服务器注册/移除口径统一**（要么全原生、要么全 pig 自管），避免同一服务器混合注册的移除歧义；其非撞名工具改用命名空间名也无回归（该服务器旧行为下本就零注册）。*备选*：**全命名空间化 + flat 别名**——否决（每工具双名、别名重新引入撞名歧义、`getToolNames` 膨胀）；**全命名空间化 + 迁移 allowlist**——否决（`allowlist: [search]` 在两 server 都有 `search` 时迁移目标歧义、破坏既有引用，违 backward-safe）；**只命名空间化撞名的单个工具**——否决（同服务器混合注册，移除/分类口径分裂，复杂度高于收益）。
- **D3 — 命名空间感知、fail-safe、反冒充的分类**。`ToolRiskClassifier.classify`：先按**完整名**（含 `mcp__server__tool`）查 `tool-overrides` → 查内置表 → **命名空间 MCP 工具未命中则 fail-safe 到 EXEC，MUST NOT 回落 base 名查内置只读表**（防恶意 server 造名 `readFile`/`listDirectory` 冒充降级）。`allowlist.tools`/`tool-overrides` 若要放行/重分类命名空间工具，**按完整命名空间名**指定（文档明示约定）。readOnly MCP 工具的放行**不**依赖分类器（分类器对 MCP 服务器工具恒 EXEC），而由工具自身 `readOnlyHint`（装饰器保真 `isReadOnly()` → 原生 `McpTool.checkPermissions` allow-readonly，或 pig 侧据 `isReadOnly()` 生成规则的既有路径）承载。**中央表只动查表逻辑**（新增命名空间分支），不新增/改动内置条目，与记忆 M-*/技能 S* 三线协调（仅登记：本 spec 改 `classify` 命名空间分支）。*备选*：命名空间工具回落 base 名查内置表以「智能继承」——**否决（安全洞：冒充降级）**。
- **D4 — 移除按服务器是否命名空间化分流**。`McpManager` 登记 `namespacedServers: Set<server>` + `namespacedNames: server→List<name>`。`remove/disable/edit`：命名空间服务器 → 对每个登记名 `toolkit.removeTool(name)` + 从登记表清除；非命名空间服务器 → 原生 `removeMcpClient(server)`（不变）。`edit` 的非破坏性回滚（L-3）沿用，仅移除分支按此分流。
- **D5 — MCP 工具无条件按服务器分入 active 组 `mcp:<server>`，即「能力包」单元；能力包 UX 嫁接 T3**。扩展既有 `toolGroupNamer`/`registerClient`：无论是否延迟、是否命名空间化，MCP 工具都注册进 active 的 `mcp:<server>`（`createToolGroup(g,…,true)`；命名空间路径用 `registration().agentTool(decorator).group(g)`，原生路径用 `.mcpClient(client).group(g)`）。因 active 组 schema-neutral（S5），默认逐字节无感。**能力包 = 该组的用户可见整组启停**：T3 的 `/tools groups <list|on|off> <group>` 对 `mcp:<server>` 组 `updateToolGroups([g], active)`——停用即隐藏该服务器全部工具、启用即恢复。**T4 rebase 到 T3 合并后的 main 再编码**：不新增 `/tools`，只让 MCP 服务器组成为 T3 groups 列表里的一等能力包（附「MCP server」类型标注）。与 T2 延迟正交：延迟=按规模自动令组 inactive；能力包=用户显式令组 inactive（同一 `updateToolGroups` 底座，二者对同组的最终 active 态由最后一次操作决定，属可接受语义——design 记录，不引入组合状态机）。
- **D6 — D-SEC MCP_ADMIN 门与命名空间正交、不变**。命名空间只作用于 MCP **服务器工具**；pig 自管工具 `addMcpServer`/`removeMcpServer`（`ToolRisk.MCP_ADMIN`）**不**命名空间化（它们是 pig `@Tool`、恒扁平），仍由 `mcp.agent-management`（`allow-add`/`allow-remove`/`allowed-hosts`/人工确认）治理。命名空间后，一个 MCP 服务器工具若名含 `deleteAll` 等危险语义，其治理走 D3 的 fail-safe EXEC + 模式（ASK/DENY），**不**经 MCP_ADMIN 门（MCP_ADMIN 专治 pig 自管，不治服务器工具）——澄清立项措辞里「危险 MCP 工具漏过 D-SEC」实为「危险服务器工具必须仍 fail-safe 分级、不误放行」，由 D3 保证。
- **D7 — 不默认启用原生 `enableMetaTool`/`reset_tools`**。原生 meta tool（`reset_tools`）让**模型**运行时整组切换，**全量覆写**非 basic 组 active 态（非 delta）；这与 pig 的「能力包=用户显式启停 + 权限/可见性受控」模型语义冲突（模型可绕过用户意图整组翻状态），且全量覆写风险高。**决策：本 spec 不启用**，能力包为**用户侧**（`/tools groups`）。是否给模型开受控的组切换留 Open Question。*备选*：启用 `enableMetaTool` 让模型自助整组——否决（越权/全量覆写风险，非 T4 必须）。

## 交叉依赖（写进本节，供并发协调）

| 依赖对象 | 关系 | 处理 |
|---|---|---|
| **T3（并发，先合，`/tools groups`）** | T4 的能力包 UX **嫁接**其上，不重造 `/tools` | design 明示：**T4 rebase 到 T3 合并后的 main 再编码**；本 spec 的 `tool-capability-packs` 只定义「MCP 服务器组为一等能力包 + 整组启停语义」，命令面复用 T3。 |
| **T2（已合，`mcp:<server>` 延迟组）** | 命名空间与延迟正交 | 复用 `setToolGroupNamer`/`managedGroups`；D5 把「按服务器分组」从「仅延迟时」提升为「无条件」。延迟（自动 inactive）与能力包（用户 inactive）共用 `updateToolGroups`，最终 active 态由最后操作决定（可接受，记录）。 |
| **`ToolRiskClassifier` 中央表**（记忆 M-*/技能 S* 三线共用） | T4 改其**查表逻辑**（命名空间分支） | 仅本 spec 动 `classify` 的命名空间分支，**不动内置条目**；记忆/技能线只读消费同一表，无冲突。风险登记：三线并发时协调 `classify` 的唯一改动方为本 spec。 |
| **D-SEC `mcp.agent-management` 门** | 命名空间后仍治理 pig 自管危险操作 | D6：正交、不变。 |
| **P0 `GuardedAgentTool extends ToolBase`** | 命名空间装饰器须 `extends ToolBase` | D1：`NamespacedMcpTool extends ToolBase`，`PermissionEngine` 实际治理之。 |

## Risks / Trade-offs

- **R1 — 命名空间装饰器丢 `mcpClientName` 致热移除失效**（承重②）。→ D4：命名空间服务器由 pig 自管移除（`removeTool` by 登记名）；非命名空间走原生 `removeMcpClient`。tasks 组 1 加回归护栏（`removeMcpClient` 找不到命名空间工具、`removeTool` 能移除）。
- **R2 — 命名空间工具被误分类降级放行**（安全）。→ D3 fail-safe EXEC + **反 base 名冒充**；tasks 加「`mcp__x__readFile` 不因 base 名 `readFile` 降级为 READ_ONLY」用例。
- **R3 — 「仅撞名时命名空间化」致命名不一致（同名工具在不同环境有时扁平有时命名空间）**。→ 这是 backward-safe 的代价：名取决于注册顺序（先到者扁平、后撞名者命名空间）。可接受——旧行为下后撞名者**根本不存在**（被拒），故无「既有名被改」。`/mcp list` 显示命名空间标记让用户可见。若未来要一致性，另起「全命名空间化 + 迁移」spec（本 spec Non-Goal）。
- **R4 — 能力包与 T2 延迟对同组 active 态互相覆盖**。→ 二者共用 `updateToolGroups`，最终态由最后操作定（D5）。不引入组合状态机（YAGNI）；design 记录此语义，`/tools groups` 显示当前 active 态即可。
- **R5 — T3 未合并即编码致返工**。→ 硬约束：**T4 rebase 到 T3 合并后的 main 再进入 `/ls:code`**（本 spec 只出规格，编码阶段等 T3）。`tool-capability-packs` delta 只约束**语义**（组即能力包、整组启停可观察结果），命令细节留 T3。
- **R6 — 命名空间改初始工具名影响 prefix-cache**。→ 仅撞名服务器的工具名变（且其旧行为下零注册，无 cache 基线可比）；无撞名逐字节不变。net 无回归。

## Migration Plan

无数据迁移（D2「仅撞名时命名空间化」的核心红利）：既有 `mcp.json`/`allowlist`/`tool-overrides` **零改动**——无撞名服务器行为不变，撞名服务器旧行为下本就未注册。部署即生效；回滚=恢复撞名即拒逻辑（无残留状态，因命名空间工具仅在运行时 toolkit，`mcp.json` 不记命名空间）。

## Open Questions

- 是否给**模型**提供受控的能力包/组切换（原生 `enableMetaTool` 的受控子集，或 pig 自建只读的「组列举」工具）？本 spec 决策为**用户侧优先、模型侧不开**（D7）；若后续需模型自助扩能力，另评估（须解决全量覆写 + 权限越权）。
- `/tools groups` 对 `mcp:<server>` 组的展示是否附「已连接/工具数/命名空间」等 MCP 元信息？归 T3 UX + 本 spec 的「能力包可见」需求交界，编码期（T4 rebase 后）与 T3 对齐。
