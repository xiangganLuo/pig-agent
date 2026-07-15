## Context

工具组装在 `AgentBootstrap.build`：`ToolRegistrar` 自动注册内置工具 → 插件贡献 → `ToolAvailabilityGate.applyTo`（`removeTool` 掉不可用的）→ 注册 `McpTool` → `ToolContractGuard.install`（把每个已注册工具替换为 `GuardedAgentTool`，**MCP attach 之前**）→ `mcpManager.initialize`（MCP 服务器 attach，工具注册进同一 `Toolkit`）。模型每回合从 `Toolkit.getToolSchemas()` 拿全量工具 schema——工具越多，prompt 越大。

对 AgentScope 1.0.12 `io.agentscope.core.tool.*` 的反编译确认了一套原生「分组」设施（`Toolkit.createToolGroup/updateToolGroups/getActiveGroups/getToolGroup`、`ToolGroupManager`、`MetaToolFactory.createResetEquippedToolsAgentTool`），本设计据此选定机制。

## Goals / Non-Goals

**Goals:**
- 把「大/罕用工具」移出模型**初始** schema（省每回合 token），但保留其可**按需**被模型发现并调用。
- 选用 AgentScope 原生、最干净、对 MCP 安全的机制；把机制细节与延迟决策/搜索解耦（设计模式表达，非面向功能硬写）。
- 配置驱动：显式清单 + 阈值规则；默认 `enabled=false` 时逐字节向后兼容。

**Non-Goals:**
- 不做运行期重新评估 / 热 schema 交换（与 `ToolAvailabilityGate` 一致：启动期评估一次）。启用后热加的 MCP 服务器在下次重启前保持可见。
- 不引入精确的工具相关性排序（关键词重叠打分即可）。
- 不改权限/可用性/返回契约维度。

## Decisions

- **D1 — 机制：AgentScope 原生 tool group（inactive = 隐藏且需揭示才可调用）。** 反编译逐条证实（AgentScope 1.0.12）：
  - `ToolSchemaProvider.getToolSchemas()`：遍历已注册工具，`if (isGroupedTool(name) && !getActiveToolNames().contains(name)) continue;`——**分组内且不活跃**的工具被排除出 schema。未分组工具恒在 schema（默认，向后兼容）。
  - `ToolExecutor.executeCore(...)`：`if (registeredTool != null && !groupManager.isActiveTool(name))` → 返回 `ToolResultBlock.error("Unauthorized tool call: '%s' is not available")`——**不活跃分组的工具不可调用**。
  - `ToolGroupManager.isActiveTool(name)`：未分组 → true；分组 → 其任一分组活跃则 true。`updateToolGroups(list,true)` 是**增量激活**（把分组加入 active 集，不影响其他分组）。
  - 结论：**延迟 = 把工具放进一个 inactive 分组**（既隐藏 schema、又在揭示前禁止调用——这正是「模型不该调用它还没发现的工具」的期望 UX）；**揭示 = 激活该分组**。揭示是**纯 flag 翻转**，工具对象自始至终留在 `ToolRegistry`，**从不被 `removeTool`/重注册** → 执行链路、`GuardedAgentTool` 包装、MCP `mcpClientName` 全不受影响。
  - 相对「present-or-absent（removeTool 后再重注册）」的备选方案更干净：备选在揭示时要重注册，而重注册会新建 `RegisteredToolFunction` 并**丢掉 `mcpClientName`**（见 D2），破坏 MCP 热移除。分组机制在揭示期零重注册，从根本上规避。
  - **不启用 AgentScope 自带的 `registerMetaTool()`/`reset_equipped_tools`**：我们要自己的 `DeferredToolRegistry` + `tool_search`（可控的关键词搜索 + 元数据 + 揭示语义），且不启用 meta tool 就不会把分组 notes 自动注入 prompt，保持初始 schema 最小。

- **D2 — MCP 工具只能在 attach 时分组（安全边界）。** 反编译 `Toolkit.registerAgentTool(tool, group, extendedModel, mcpClientName, preset)` + `RegisteredToolFunction`：`mcpClientName` 存在 `RegisteredToolFunction`，而 `McpClientManager.removeMcpClient(name)` 靠 `getAllRegisteredTools().values().filter(f -> name.equals(f.getMcpClientName()))` 找出该 client 的工具来移除。给已注册 MCP 工具「补分组」只能走 `registration().agentTool(getTool(name)).group(g)` 的重注册，而该路径 `mcpClientName=null` → 热移除/热禁用会漏删该工具（残留隐藏工具，且再次 add 同名服务器会因扁平命名空间冲突被拒）。**故 MCP 工具的分组必须在 `McpManager.attach` 通过 `registration().mcpClient(client).group(g)` 完成**（原始注册，`mcpClientName` 正确）。分组名 `mcp:<server>`，创建时**active**（active 分组 = 可见 + 可调用，与未分组等价 → 向后兼容），由 gate 按需停用来实现延迟。
  - 内置/插件工具无 `mcpClientName`/`extendedModel`/`preset`（全 null），重注册当前 `AgentTool`（已是 `GuardedAgentTool`）到 per-tool 分组是**无损**的 → 内置工具走「per-tool inactive 分组 + 重注册」。

- **D3 — 分组粒度：内置 per-tool、MCP per-server。** 内置延迟工具各自一个分组 `deferred__<tool>` → `tool_search` 揭示恰好命中的那个工具。MCP 工具按服务器共享 `mcp:<server>`（`mcpClient().group()` 一次给一个 client 的全部工具打同一组，无法 per-tool）→ 揭示某 MCP 工具会连带揭示同服务器其余工具（可接受的粗粒度，见 R2）。

- **D4 — 组件与设计模式（与决策解耦）：**
  - `DeferredTool`（record：`name` + `description` + `keywords`(Set) + `groupName`）：延迟工具的不可变元数据；`groupName` 供揭示时激活。
  - `DeferredToolRegistry`（可变、线程安全）：`add`；`search(query,limit)`（只在**仍延迟**的工具中按 query 打分）；`find`；`markRevealed(name)`（连带把同 `groupName` 的其余工具一并标记为已揭示）；`all/deferredNames/revealedNames`。线程安全因 `tool_search` 在回合内被调用。
  - `ToolInfo`（record：`name` + `description` + `keywords` + `mcpGroup`(nullable)）：planner/gate 的输入；`mcpGroup!=null` ⇔ 该工具是 MCP 工具（其 attach 分组名）。
  - `DeferralPlan`（record：`Set<String> deferredToolNames`）：纯决策结果。
  - `DeferredToolPlanner.plan(enabled, explicit, autoDeferMcp, threshold, allTools)`（**Strategy/纯函数**）：`!enabled` → 空计划；否则 = 显式清单命中的工具 ∪（`autoDeferMcp && allTools.size()>threshold` 时的全部 MCP 工具）；恒不延迟 `tool_search` 自身。
  - `DeferredToolReveal`（**函数式接口** `boolean reveal(String toolName)`）：揭示的副作用 seam，便于 `tool_search` 离线单测（fake reveal）。
  - `DeferredToolGate.applyTo(toolkit, plan, allTools)`（**类比 `ToolAvailabilityGate`**）：据计划隐藏工具并返回填充好的 `DeferredToolRegistry`；内置走 per-tool inactive 分组 + 重注册，MCP 走收集 `mcp:<server>` 分组后一次性 `updateToolGroups(...,false)`。
  - `Keywords.from(name, description)`：把驼峰/分隔符/描述切词、小写、去短词 → 关键词集合（DRY，registry 构建时用）。
  - `ToolSearchTool`（`@Tool("tool_search")`）：`query → registry.search → 对每个匹配 reveal.reveal(name) → 拼「名称 + 描述 + 已启用」文本`；空/无匹配 → 提示 + 可搜索工具名清单；自身 try/catch → `ToolErrors.message`（满足返回契约，不抛）。

- **D5 — 接线顺序（`AgentBootstrap`）。** 读 `enabled`（配置期即知）。启用时：先建空 `DeferredToolRegistry` + reveal lambda（绑定 `toolkit`+registry）→ **在 `ToolContractGuard.install` 之前**注册 `tool_search`（于是自然被 guard 包裹，且不违反「guard 必须在 MCP attach 前」）→ 给 `McpManager` 注入分组函数 `name -> "mcp:"+name` → `mcpManager.initialize`（MCP 工具分组 active）→ 从 `toolkit.getToolSchemas()`（此刻全 active，全部可见）取每个工具 name+description，结合 `mcpManager.managedToolGroups()`+`toolkit.getToolGroup(g).getTools()` 标出 MCP 工具的 `mcpGroup`，构造 `List<ToolInfo>` → `DeferredToolPlanner.plan` → `DeferredToolGate.applyTo`（填充 registry + 隐藏）。未启用：整段跳过（无 `tool_search`、无分组、无隐藏）。

- **D6 — `tool_search` 恒可见、恒不被延迟。** planner 显式排除 `tool_search`；且它注册为未分组工具 → 永远在 schema。

## Risks / Trade-offs

- **R1 — 启动期评估一次，热加 MCP 不自动延迟。** 与 `ToolAvailabilityGate` 一致的取舍：启用后经 `/mcp add` 热加的服务器其工具进 active 分组（可见），下次重启才可能被阈值规则延迟。→ 保持实现简单、无运行期 schema 抖动；文档说明。
- **R2 — MCP 揭示是 per-server 粗粒度。** 揭示某 MCP 工具会连带揭示同服务器其余工具（受限于 `mcpClient().group()` 一个 client 一组）。→ 初始 schema 缩减（主要收益）不受影响；揭示后仅该服务器的工具回到 schema，其余服务器仍隐藏，仍显著省 token。
- **R3 — 分组 MCP attach 走 `registration().apply()` 而非 `registerMcpClient(...).block(TIMEOUT)`，失去 30s 超时上界。** 仅在**启用延迟**时的分组路径；attach 之前的 `connect()`/`listTools()` 已验证连通并有超时。→ 风险小；默认路径（不传分组函数）逐字节不变，超时保留。
- **R4 — per-agent `Toolkit.copy()` 的揭示不跨实例传播。** `tool_search` 揭示的是共享交互 Toolkit 的分组；`AgentWiring.toolkitFor` 的副本各自持有分组 active 态，reveal 不传播到副本。→ 多 agent + 跨实例揭示超出本能力范围；文档标注。
- **R5 — 不回归既有语义。** guard/可用性/权限/MCP 增删改热插拔全绿；`McpManagerTest` 用 3 参 `initialize`（不传分组函数）→ 不受影响；`Toolkit.copy()` 复制分组（`copyTo`）——延迟态随副本保留，无害。

## 落实追踪表（评审/需求发现项 → 落点 + 状态）

| 发现项 / 需求 | 落点 | 状态 |
|---|---|---|
| 大/罕用工具吃每回合 token | D1 inactive 分组隐藏 schema；spec Req 2 | 已实现 |
| 借鉴 deerflow：tool_search 按需发现 | D4 `ToolSearchTool` + `DeferredToolRegistry`；spec Req 3 | 已实现 |
| 配置：显式清单 + 阈值自动延迟 MCP | D4 `DeferredToolPlanner`；config `tools.deferred`；spec Req 4 | 已实现 |
| 揭示后仍可调用（因仍注册） | D1 分组机制（揭示=激活，零重注册）；spec Req 2 | 已实现 |
| MCP 工具安全延迟（不丢 mcpClientName） | D2 attach 时分组 + 可选分组函数；spec Req 5 | 已实现 |
| 向后兼容（无配置=零变化） | D5 `enabled=false` 全跳过；spec Req 1 | 已实现 |
| tool_search 分级 READ_ONLY | `ToolRiskClassifier` 增条目；spec Req 3 | 已实现 |
| 「用设计模式，别面向功能硬写」 | Strategy（planner）+ 函数式 seam（reveal）+ 类比 gate（`DeferredToolGate`）+ DRY（`Keywords`） | 已实现 |
| 揭示粒度 MCP per-server | D3；R2 | 已实现（粗粒度，接受） |
| 运行期热加 MCP 自动延迟 | 非目标；R1 记录 | 延后 |
| 精确工具相关性排序 | 非目标；关键词重叠打分 | 延后 |
