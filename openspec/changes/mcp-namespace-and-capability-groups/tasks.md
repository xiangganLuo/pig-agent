# Tasks

> **前置硬约束**：本 spec 的能力包 UX 嫁接 T3（`/tools groups`，并发、先合）。进入 `/ls:code` 前 **MUST 先 rebase 到 T3 合并后的 main**，再细化并执行组 5（能力包）。组 1–4 不依赖 T3，可在 rebase 前先行。

## 1. Spike —— 承重 PoC（命名空间机制 + 移除安全 + 分类，前置）

- [ ] 1.1 命名空间装饰器 PoC：`NamespacedMcpTool extends ToolBase`——`getName()` 返回 `mcp__{server}__{tool}`，`callAsync` 委托底层以**原始名**调服务器，`isReadOnly()`/`isMcp()`/`getMcpName()`/`getParameters()`/`getDescription()` 保真底层。离线断言：命名空间名对外、原始名对内、只读/来源元信息保真。
- [ ] 1.2 移除安全 PoC（承重②回归护栏）：经 `toolkit.registration().agentTool(decorator).group(g).apply()` 注册的命名空间工具，`toolkit.removeMcpClient(server)` **找不到**它（证 `mcpClientName` 丢失），而 `toolkit.removeTool("mcp__server__tool")` 能移除。确立「命名空间服务器由 pig 自管移除」路径。
- [ ] 1.3 分类 fail-safe + 反冒充 PoC：`ToolRiskClassifier.classify("mcp__fs__deleteAll", …)` → EXEC；`classify("mcp__evil__readFile", …)` → **不**降级为 READ_ONLY（不回落 base 名查内置表）；`tool-overrides`/`allowlist` 按完整命名空间名命中。
- [ ] 1.4 schema-neutral 分组 PoC：把工具分入 active 组 `mcp:<server>` 后 `getToolSchemas()` 仍含之；`updateToolGroups([g], false)` 后不含（能力包停用=隐藏）。
- [ ] 1.5 记录结论：2.0.0 无原生命名空间（sources 核验）→ pig 装饰器实现；仅撞名时命名空间化（backward-safe，无迁移）；命名空间服务器 pig 自管移除；分类 fail-safe + 反冒充；分组 schema-neutral。走主路径，无回退方案。

## 2. 命名空间化共存（`pig-agent-mcp` `McpManager`）

- [ ] 2.1 新增 `NamespacedMcpTool extends ToolBase`（或等价装饰器）——按 1.1 定形；命名空间名工具函数 `namespacedName(server, tool)`。
- [ ] 2.2 `attach`：撞名判定——该服务器任一原始工具名 ∈ `toolkit.getToolNames()` → 走命名空间路径（整服务器命名空间化 + 登记 `namespacedServers`/`server→[命名空间名]`）；否则走原生 `registerMcpClient`（不变）。移除旧的「撞名即 `throw`」。
- [ ] 2.3 命名空间注册：对该服务器每个原始工具建 `NamespacedMcpTool`，经 `registration().agentTool(decorator).group("mcp:"+server).apply()` 注册进 active 组。
- [ ] 2.4 移除分流：`remove`/`disable`/`edit` 的注销路径——命名空间服务器 → 对登记名逐个 `toolkit.removeTool(name)` + 清登记；非命名空间服务器 → 原生 `removeMcpClient`（不变）；`edit` 的 L-3 非破坏性回滚沿用。
- [ ] 2.5 单测：撞名共存（A 的 `search` + B 的 `mcp__B__search` 并存）；无撞名逐字节不变（原始名、原生路径、`mcpClientName` 保真）；命名空间服务器 `removeTool` 移除干净；命名空间工具以原始名 `callTool`（mock 服务器断言收到原始名）；只读/来源元信息保真。
- [ ] 2.6 `mvn -pl pig-agent-mcp -am compile` 绿。

## 3. 命名空间感知分类（`pig-agent-tools` `ToolRiskClassifier`）

- [ ] 3.1 `classify` 增命名空间分支：命名空间 MCP 工具按**完整名**查 `tool-overrides` → 查内置表 → 未命中 fail-safe EXEC，**不回落 base 名查内置只读表**（反冒充）。中央表**条目不增删**，仅改查表逻辑。
- [ ] 3.2 `allowlist.tools`/`tool-overrides` 按完整命名空间名匹配（`PermissionContextFactory`/`PermissionPolicy` 映射不变，仅经改后的 `classify` 取风险）。
- [ ] 3.3 单测：`mcp__fs__deleteAll`→EXEC；`mcp__evil__readFile` 不降级 READ_ONLY；完整名 override/allowlist 命中；未命名空间工具分类逐字不变（回归护栏）。
- [ ] 3.4 交叉协调登记：确认记忆 M-*/技能 S* 线只读消费中央表、不改 `classify`；本 spec 是 `classify` 命名空间分支的唯一改动方。
- [ ] 3.5 `mvn -pl pig-agent-tools -am compile` 绿。

## 4. MCP 服务器无条件分组（能力包单元，`McpManager`）

- [ ] 4.1 扩展 `registerClient`/`toolGroupNamer`：无论是否延迟、是否命名空间化，MCP 工具都注册进 active 的 `mcp:<server>`（`createToolGroup(g,…,true)`；原生路径 `.mcpClient(client).group(g)`、命名空间路径 `.agentTool(decorator).group(g)`）；`managedGroups` 登记。
- [ ] 4.2 与 T2 延迟正交：延迟仍可令 `mcp:<server>` inactive；能力包/延迟共用 `updateToolGroups`，不引入组合状态机。
- [ ] 4.3 单测：MCP 工具分入 `mcp:<server>` 后 `getToolSchemas` 含之（schema-neutral）；`updateToolGroups(g,false)` 后不含；命名空间与非命名空间工具都归入其服务器组。

## 5. 能力包 UX 嫁接 T3（`pig-agent-cli`，**T4 rebase 到 T3 合并后再做**）

- [ ] 5.1 rebase 到 T3 合并后的 main；核对 T3 `/tools groups` 的命令面与 groups 数据结构。
- [ ] 5.2 让 `mcp:<server>` 组成为 T3 groups 列表里的一等能力包（附「MCP server」类型 + 连接/工具数/命名空间标注，凭据脱敏），复用 T3 的整组启停（`updateToolGroups`）。**不新增 `/tools`**。
- [ ] 5.3 `/mcp list` 显示命名空间标记（mcp-management 需求）。
- [ ] 5.4 单测：能力包列举含 MCP 服务器组 + 状态；停用/启用 `mcp:<server>` → 该服务器工具 schema 隐藏/恢复；用户启用覆盖延迟的自动停用。

## 6. 验收（离线，硬约束=全绿）

- [ ] 6.1 `mvn -pl pig-agent-mcp -am test` + `mvn -pl pig-agent-tools -am test` 全绿。
- [ ] 6.2 现有 `mcp-management`/`tool-permissions`/`deferred-tools` 单测保持绿（无回归）。
- [ ] 6.3 `mvn -pl pig-agent-cli -am compile` 绿（覆盖下游 import）。
- [ ] 6.4 `CLAUDE.md`「Dynamic MCP management」段更新：命名空间化共存（仅撞名）+ 能力包 + 命名空间感知分类；记录 2.0.0 无原生命名空间、由 pig 实现的承重结论。
- [ ] 6.5 D-SEC / 权限真模型验证留 `/ls:itest`（命名空间后危险 MCP 工具仍 fail-closed；只读 MCP 工具放行）——offline 已覆盖结构，真模型行为外环补。
