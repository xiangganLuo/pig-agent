## MODIFIED Requirements

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

## ADDED Requirements

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
