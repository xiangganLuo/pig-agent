# Tasks

> **前置硬约束**：本 spec 的能力包 UX 嫁接 T3（`/tools groups`，并发、先合）。进入 `/ls:code` 前 **MUST 先 rebase 到 T3 合并后的 main**，再细化并执行组 5（能力包）。组 1–4 不依赖 T3，可在 rebase 前先行。
>
> **进度**：组 1–4 + 6 已完成（离线全绿）；**组 5 DEFER**（待 T3 合并后 rebase 再做）。

## 1. Spike —— 承重 PoC（命名空间机制 + 移除安全 + 分类，前置）

- [x] 1.1 命名空间装饰器 PoC：`NamespacedMcpTool extends ToolBase`——`getName()` 返回 `mcp__{server}__{tool}`，`callAsync` 委托底层以**原始名**调服务器，`isReadOnly()`/`isMcp()`/`getMcpName()`/`getParameters()`/`getDescription()` 保真底层。→ `NamespacedMcpToolSpikeTest`（6/6 绿）。**注意**：必须用 `ToolBase.builder()`，位置构造器序为 `(readOnly,concurrencySafe,mcp,mcpName,externalTool,stateInjected)`（与 `GuardedAgentTool` 假设不同——见下方发现项）。
- [x] 1.2 移除安全 PoC（承重②回归护栏）：经 `registration().agentTool(decorator).group(g).apply()` 注册的命名空间工具，`removeMcpClient(server)` **找不到**它，`removeTool("mcp__server__tool")` 能移除。→ spike `removeMcpClientCannotRemoveNamespacedTool_butRemoveToolCan` 绿。
- [x] 1.3 分类 fail-safe + 反冒充 PoC：`classify("mcp__fs__deleteAll")`→EXEC；`classify("mcp__evil__readFile")` **不**降级 READ_ONLY；overrides 按完整命名空间名命中。→ `ToolRiskClassifierTest` 命名空间用例绿——**在既有 exact-match 分类器上即通过**（已 fail-safe，无需改分类器逻辑；组3 加显式分支只为永久自证）。
- [x] 1.4 schema-neutral 分组 PoC：active 组 `mcp:<server>` → `getToolSchemas()` 含之；`updateToolGroups([g],false)` 后不含；`true` 后恢复。→ spike `activeGroupIsSchemaVisible_deactivateHides_reactivateRestores` 绿。
- [x] 1.5 记录结论：2.0.0 无原生命名空间（sources 核验）→ pig 装饰器实现；仅撞名时命名空间化（backward-safe，无迁移）；命名空间服务器 pig 自管移除；分类 fail-safe + 反冒充；分组 schema-neutral。走主路径，无回退。

## 2. 命名空间化共存（`pig-agent-mcp` `McpManager`）

- [x] 2.1 新增 `NamespacedMcpTool extends ToolBase`——按 1.1 定形；`namespacedName(server, tool)` 单一命名真源。
- [x] 2.2 `attach`：`collidesWithRegistered(incoming)` 撞名判定 → 命名空间路径（`registerNamespacedTools` + 登记 `namespacedTools`）；否则原生 `registerClient`。移除旧的「撞名即 `throw`」。
- [x] 2.3 命名空间注册 `registerNamespacedTools`：每个原始工具（`listRawMcpTools` 经临时 Toolkit 复用框架构造）建 `NamespacedMcpTool`，`registration().agentTool(ns).group("mcp:"+server).apply()`。
- [x] 2.4 移除分流 `unregisterServerTools`：命名空间服务器 → 登记名逐个 `removeTool` + 清登记；非命名空间 → 原生 `removeMcpClient`。`remove`/`disable` 已改用之；`edit`（=remove+add）与 L-3 回滚（=attach）透传。
- [x] 2.5 单测 `McpManagerNamespaceTest`：撞名共存（flat `search` + `mcp__srvB__search` 并存）；命名空间注册进能力包 + 登记；`removeTool` 移除干净 + 清登记；非命名空间走原生路径安全；只读/来源元信息保真（spike）。
- [x] 2.6 `mvn -pl pig-agent-mcp -am test` 绿（25 run，1 POSIX skip）。

## 3. 命名空间感知分类（`pig-agent-tools` `ToolRiskClassifier`）

- [x] 3.1 `classify` 增显式命名空间分支：`mcp__` 前缀工具 → fail-safe EXEC，**不回落 base 名查内置只读表**（反冒充）。**中央表条目不增删**；行为与 `getOrDefault` 一致，仅使保证永久自证。
- [x] 3.2 overrides 上面已按完整名匹配；`allowlist.tools`/`PermissionContextFactory` 映射不变，经改后 `classify` 取风险。
- [x] 3.3 单测：`mcp__fs__deleteAll`→EXEC；`mcp__evil__readFile` 不降级；完整名 override 命中、base 名 override 不泄漏；未命名空间工具分类逐字不变。→ `ToolRiskClassifierTest`（10/10）。
- [x] 3.4 交叉协调登记：本 spec 是 `classify` 命名空间分支的唯一改动方；记忆/技能线只读消费中央表、不改 `classify`。
- [x] 3.5 `mvn -pl pig-agent-tools -am test` 绿（435 run，3 POSIX skip）。

## 4. MCP 服务器无条件分组（能力包单元，`McpManager`）

- [x] 4.1 `groupFor` 无条件把 MCP 工具分入 active `mcp:<server>`（`toolGroupNamer` 为覆盖，默认回退 `mcp:<server>`）；原生路径 `.mcpClient().group(g)`、命名空间路径 `.agentTool().group(g)`；`ensureGroup` 登记 `managedGroups`。
- [x] 4.2 与 T2 延迟正交：延迟仍可令 `mcp:<server>` inactive；共用 `updateToolGroups`，不引入组合状态机。
- [x] 4.3 单测：命名空间工具分入 `mcp:srvB` 后 `getToolSchemas` 含之（schema-neutral）+ `managedToolGroups` 含 `mcp:srvB`（`McpManagerNamespaceTest`）；停用/恢复由 spike 1.4 覆盖。

## 5. 能力包 UX 嫁接 T3（`pig-agent-cli`，**DEFER —— T4 rebase 到 T3 合并后再做**）

- [ ] 5.1 rebase 到 T3 合并后的 main；核对 T3 `/tools groups` 的命令面与 groups 数据结构。
- [ ] 5.2 让 `mcp:<server>` 组成为 T3 groups 列表里的一等能力包（附「MCP server」类型 + 连接/工具数/命名空间标注，凭据脱敏），复用 T3 的整组启停（`updateToolGroups`）。**不新增 `/tools`**。
- [ ] 5.3 `/mcp list` 显示命名空间标记（mcp-management 需求）。
- [ ] 5.4 单测：能力包列举含 MCP 服务器组 + 状态；停用/启用 `mcp:<server>` → 该服务器工具 schema 隐藏/恢复；用户启用覆盖延迟的自动停用。

## 6. 验收（离线，硬约束=全绿）

- [x] 6.1 `mvn -pl pig-agent-mcp -am test`（25 绿）+ `mvn -pl pig-agent-tools -am test`（435 绿）。
- [x] 6.2 现有 `mcp-management`（`McpManagerTest` 7/7）/`tool-permissions`/`deferred-tools` 单测保持绿（无回归）。
- [x] 6.3 `mvn -pl pig-agent-cli -am compile` 绿（覆盖下游 import）。
- [x] 6.4 `CLAUDE.md`「Dynamic MCP management」段更新：命名空间化共存（仅撞名）+ 能力包 + 命名空间感知分类 + 记录 2.0.0 无原生命名空间、由 pig 实现的承重结论。
- [ ] 6.5 D-SEC / 权限真模型验证留 `/ls:itest`（命名空间后危险 MCP 工具仍 fail-closed；只读 MCP 工具放行）——offline 已覆盖结构，真模型行为外环补。
