# tool-permissions Specification

## Purpose
TBD - created by archiving change permission-system. Update Purpose after archive.
## Requirements
### Requirement: 工具权限模式
系统 SHALL 提供四种全局工具权限模式 `plan`/`ask`/`auto`/`bypass`（默认 `ask`），并 SHALL 允许通过 `/permission mode <mode>` 在运行时热切换，无需重启。模式 MUST 应用于**内置工具与 MCP 工具**的每一次调用。缺省（无 `permissions` 配置）时 MUST 采用 `ask`。

#### Scenario: 运行时切换模式即时生效
- **WHEN** 用户执行 `/permission mode auto`
- **THEN** 后续工具调用立即按 `auto` 规则判定，无需重启，配置持久化以便跨重启恢复

#### Scenario: 缺省配置采用 ask
- **WHEN** `application.yaml` 无 `permissions` 块
- **THEN** 系统以 `mode=ask` 运行，危险工具调用触发人工确认

### Requirement: 工具风险分级
系统 SHALL 将每个工具归类为 READ_ONLY / WRITE / EXEC / NETWORK / MCP_ADMIN 之一。READ_ONLY 工具 MUST 在所有模式下放行且从不触发确认。未在默认表中的未知工具 MUST 按最严级别（EXEC）处理。`permissions.tool-overrides` SHALL 允许对具体工具重新分类。

#### Scenario: 只读工具永不被拦
- **WHEN** 任意模式（含 plan）下 agent 调用 READ_ONLY 工具（如读文件、列目录、搜索、连通测试）
- **THEN** 工具直接执行，不产生确认

#### Scenario: 未知工具按最严处理
- **WHEN** 某工具名不在默认风险表也未被 `tool-overrides` 覆盖
- **THEN** 系统按 EXEC 处理（在 ask/auto 下要求确认，在 plan 下否决）

### Requirement: plan 只读模式
在 `plan` 模式下系统 MUST 否决所有可变工具（WRITE/EXEC/NETWORK/MCP_ADMIN），并向模型回传清晰说明（plan 模式下不执行、应产出计划），使 agent 仅进行只读调查并输出计划。被否决的工具 MUST NOT 产生任何副作用。

#### Scenario: plan 模式拦截可变工具
- **WHEN** `mode=plan` 时 agent 试图写文件或执行 shell 命令
- **THEN** 该工具不执行、无副作用，模型收到"plan 模式下不执行"的说明并继续产出计划

#### Scenario: plan 模式允许只读调查
- **WHEN** `mode=plan` 时 agent 读文件、列目录以理解代码
- **THEN** 这些只读工具正常执行，支撑 agent 形成计划

### Requirement: ask 模式人工确认
在 `ask` 模式下，可变工具在执行前 MUST 经人工确认。确认 SHALL 提供三态：`y`（本次允许）/ `n`（拒绝）/ `a`（始终允许）。选择 `a` 后，系统 MUST 使**同一会话内后续回合**对该工具的相同调用**立即免确认**（不得再弹确认），而不仅是本次调用内免确认、也不仅是跨重启才生效。选择 `a` MUST 同时将该工具或该命令写入持久化配置 allowlist，使其在下次 agent 重建/重启后仍免确认。拒绝时工具 MUST NOT 执行，且拒绝原因 MUST 回传模型。首次调用（选 `a` 之前）MUST 仍触发人工确认。

#### Scenario: 逐次确认危险工具
- **WHEN** `mode=ask` 时 agent 请求执行 shell 命令
- **THEN** 向用户展示实际命令并等待 y/n/a；选 n 则不执行并告知模型被拒

#### Scenario: 始终允许后同会话后续回合免确认
- **WHEN** 用户在回合 1 对某工具选择 `a`，随后在**同一会话的回合 2** agent 再次调用该工具
- **THEN** 回合 2 该工具**不再弹确认**、直接执行；且该「始终允许」跨重启保留（写入配置 allowlist）

#### Scenario: 始终允许不越权首次确认
- **WHEN** 用户尚未对某工具选 `a`，agent 首次调用它（`ask` 模式、非只读、无 allowlist 命中）
- **THEN** 仍触发人工确认（`a` 的免确认只对选择之后的相同调用生效）

### Requirement: auto 模式
在 `auto` 模式下系统 MUST 自动放行 READ_ONLY / WRITE / NETWORK 工具，但对 EXEC 与 MCP_ADMIN 仍要求确认（对标编辑自动接受、执行仍需把关）。

#### Scenario: auto 放行写文件但仍拦 shell
- **WHEN** `mode=auto` 时 agent 先写文件、再执行 shell 命令
- **THEN** 写文件自动执行，shell 命令仍触发人工确认

### Requirement: bypass 模式
在 `bypass` 模式下系统 MUST 放行所有工具且不产生任何确认，用于完全信任场景与恢复旧行为。

#### Scenario: bypass 全放行
- **WHEN** `mode=bypass` 时 agent 调用任意工具
- **THEN** 全部直接执行，无确认

### Requirement: 逐命令粒度 allowlist
对 EXEC 工具，确认提示 MUST 展示实际命令；选择 `a` SHALL 记住规范化命令键（而非仅工具名），使相同命令模式后续免确认，不同命令仍需确认。allowlist（工具与命令）MUST 持久化到全局配置。

#### Scenario: 记住命令后同命令免确认、异命令仍问
- **WHEN** 用户对 `git status` 选择 `a`
- **THEN** 后续 `git status` 免确认，但 `rm -rf /` 等其它命令仍触发确认

### Requirement: MCP 自助接入门组合
权限体系 MUST 与既有 MCP 的 D-SEC 门（`mcp.agent-management`）组合而非重复：对 MCP_ADMIN 工具，权限 hook MUST NOT 重复弹出确认，仅按模式决定是否放行进入 D-SEC；D-SEC 的 `allow-add`/`allow-remove`/白名单/人工确认保持不变。`plan` 模式 MUST 否决 MCP_ADMIN。

#### Scenario: 不重复确认 MCP 添加
- **WHEN** `mode=ask` 且 agent 调用 `addMcpServer`
- **THEN** 由 D-SEC 门处理（allow-add/白名单/人工确认），权限 hook 不额外弹一次确认

#### Scenario: plan 否决 MCP 变更
- **WHEN** `mode=plan` 时 agent 调用 `addMcpServer`/`removeMcpServer`
- **THEN** 请求被否决，不进入 D-SEC，无副作用

### Requirement: 非交互渠道兜底
渠道（Telegram/Discord 等无终端）回合 MUST 使用 `permissions.channel-mode`（默认 `auto`）而非交互模式，并经一个独立的渠道 agent（其权限 hook 无 confirmer）执行。当某决策需要人工确认但渠道无 confirmer 时，系统 MUST fail-closed 拒绝，除非 `channel-mode=bypass`。渠道 agent 随模型切换一并重建（`attachChannel`），仍跟随活动模型。默认渠道关闭。

#### Scenario: 渠道 auto 下 shell 被拒
- **WHEN** `channel-mode=auto`、来自 Telegram 的回合触发 shell 执行（EXEC 在 auto 下需确认）
- **THEN** 因渠道无 confirmer，请求被 fail-closed 拒绝，不执行

#### Scenario: 渠道 auto 放行写文件
- **WHEN** `channel-mode=auto`、渠道回合触发写文件（WRITE 在 auto 下自动放行）
- **THEN** 工具直接执行，无需确认

#### Scenario: 渠道 bypass 完全放行
- **WHEN** 运维显式设 `channel-mode=bypass`
- **THEN** 渠道回合的所有工具直接执行，无确认

### Requirement: 权限运维命令
系统 SHALL 提供 `/permission` 命令：`status`（默认，展示当前模式与 allowlist 概要）、`mode <plan|ask|auto|bypass>`、`allow <tool|command>`、`revoke <tool|command>`、`reset`（清空 allowlist）、`list`（展示工具风险分级与 allowlist）。`/status` MUST 展示当前权限模式。

#### Scenario: 查看与调整权限
- **WHEN** 用户执行 `/permission status`
- **THEN** 显示当前模式、channel-mode 与 allowlist 概要

#### Scenario: 手动加入 allowlist
- **WHEN** 用户执行 `/permission allow git`
- **THEN** `git` 命令键写入持久化 allowlist，后续免确认

### Requirement: 会话级逐工具 ASK→ALLOW 置换（PermissionContextFactory 不变）
「始终允许」的运行时免确认 MUST 通过在**当前会话槽的权限上下文**中对**被选中的那一个工具**做 **ASK→ALLOW 置换**实现——删除该工具的 per-tool ASK 规则并加入一条该工具的 ALLOW 规则（原生优先级 `deny>ask>allow`：若保留 ASK 规则则 ALLOW 被遮蔽，故置换 MUST 同时删 ASK、加 ALLOW）。系统 MUST NOT 通过全局停止发出 per-tool ASK 规则（即 MUST NOT 依赖 native 模式默认来对无规则工具兜底确认）来实现免确认——因为 agent 的推理循环对**纯模式默认 ASK 不弹人工确认、会直接执行工具**（只有显式 ASK 规则或工具内建 ASK 检查才触发暂停），全局移除将使可变工具无确认执行（安全回归）。因此风险分级→per-tool ASK 规则的映射（`PermissionContextFactory`）MUST 保持不变：所有工具的首次调用仍受各自的 ASK 规则把关；仅被用户显式选 `a` 的工具、仅在其所在会话槽解除该工具的确认。

#### Scenario: 全局 ASK 规则保持不变，其它工具仍确认
- **WHEN** 用户对工具 A 选择 `a` 后，同会话 agent 调用**另一个**未被选 `a` 的可变工具 B
- **THEN** 工具 B 仍触发人工确认（其 ASK 规则未被改动；置换只作用于工具 A）

#### Scenario: 置换必须同时删 ASK、加 ALLOW
- **WHEN** 对某工具执行「始终允许」置换
- **THEN** 该会话槽上下文中该工具的 ASK 规则被删除、并新增一条该工具的 ALLOW 规则；仅加 ALLOW 而不删 ASK MUST NOT 被视为完成（否则 `deny>ask>allow` 使其仍被确认）

#### Scenario: 不得全局移除 ASK 规则
- **WHEN** 实现「始终允许」
- **THEN** MUST NOT 通过停止发出 per-tool ASK 规则（改依模式默认）来实现——该路径会使可变工具无确认直接执行；`PermissionContextFactory` 的风险→规则映射保持不变

### Requirement: 跨回合持久化「始终允许」到会话权限上下文
系统 SHALL 提供一个能力：当用户对某工具选择 `a`（始终允许）时，把上述 ASK→ALLOW 置换后的权限上下文写入**当前会话槽 `(userId, sessionId)` 的持久权限上下文**（`AgentState.permissionContext`）、刷新该会话槽的权限引擎缓存并保存（`saveAgentState(userId, sessionId)`），使该会话**下一回合**从该槽取用/重建的权限引擎放行该工具。该运行时「始终允许」作用域 MUST 为**本会话**（不隐式泛化到其它会话；跨会话/跨重启的持久由写入配置 allowlist 在下次 agent 重建时生效承担）。写入 MUST 幂等（重复选 `a` 不产生重复放行副作用或规则堆叠），且 MUST NOT 放松同一槽已有的 deny 规则或降低其它工具的把关级别。

#### Scenario: 选 a 后下一回合引擎放行
- **WHEN** 用户在某会话对工具 T 选择 `a`，系统把 T 的 ASK→ALLOW 置换写入该会话槽的权限上下文、刷新引擎缓存并保存
- **THEN** 该会话下一回合的权限引擎对 T 判定为 ALLOW，不再弹确认

#### Scenario: 始终允许按会话隔离
- **WHEN** 会话 A 对工具 T 选择 `a`（未写入配置 allowlist 生效前），随后切到会话 B 且 B 的槽尚无 T 的置换
- **THEN** 会话 B 首次调用 T 仍按其风险与模式判定（可能仍弹确认）——运行时「始终允许」不隐式跨到会话 B

#### Scenario: 重复选 a 幂等
- **WHEN** 用户在同一会话对同一工具多次选择 `a`
- **THEN** 会话槽权限上下文对该工具至多产生一条等效 ALLOW 放行、不残留 ASK 规则，不产生重复或冲突规则

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

