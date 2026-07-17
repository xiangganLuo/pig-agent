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

### Requirement: 移除 per-tool ASK 遮蔽，改依模式默认兜底
为使「始终允许」写入的 ALLOW 规则不被同名 ASK 规则遮蔽（原生优先级 `deny>ask>allow`），系统在构建会话权限上下文时 MUST NOT 为「判定为 ASK」的工具发出 per-tool ASK 规则（把既有对 `executeCommand` 的 M-1 豁免推广到所有 ASK 判定的工具），改由 native **模式默认**兜底。此改动 MUST 保全既有安全语义：交互回合（`DEFAULT`/`ACCEPT_EDITS`，有人工 confirmer）对无匹配规则的可变工具 MUST 仍触发人工确认（HITL `RequireUserConfirmEvent`）；非交互回合（渠道/自主，`DONT_ASK` 基线、无 confirmer）对无匹配规则的可变工具 MUST 仍 fail-closed 拒绝；`plan`（EXPLORE）下的 DENY 规则与只读语义 MUST 不受影响；`bypass` 的 ALLOW 与 allowlist 命中的 ALLOW MUST 照常发出；deny 规则与危险路径的不可绕过性 MUST 不被削弱。

#### Scenario: 交互 ASK 工具移除显式规则后仍弹确认
- **WHEN** `mode=ask`（交互、有 confirmer），agent 调用一个判定为 ASK 的可变工具，且该工具无 allowlist 命中、无 deny/ask 规则
- **THEN** 请求经模式默认到达 ASK，仍弹出人工确认（HITL），行为与移除显式 ASK 规则前一致

#### Scenario: 非交互 ASK 工具移除显式规则后仍 fail-closed
- **WHEN** 渠道/自主回合（`DONT_ASK` 基线、无 confirmer），触发一个判定为 ASK 的可变工具且无匹配放行规则
- **THEN** 请求经 `DONT_ASK` 模式默认被拒绝（fail-closed），工具不执行、无副作用

#### Scenario: plan 只读与 deny 不受影响
- **WHEN** `mode=plan`（EXPLORE）时 agent 调用可变工具，或存在一条 deny 规则的工具在任意模式被调用
- **THEN** plan 下可变工具仍被否决（DENY 规则照发、只读语义不变），deny 规则仍不可绕过（含 `bypass`）

### Requirement: 跨回合持久化「始终允许」到会话权限上下文
系统 SHALL 提供一个能力：当用户对某工具选择 `a`（始终允许）时，把一条 `ALLOW` 规则写入**当前会话槽 `(userId, sessionId)` 的持久权限上下文**（`AgentState.permissionContext`），使该会话**下一回合**从该槽重建的权限引擎仍放行该工具。写入 MUST 持久到会话状态存储（`saveAgentState(userId, sessionId)`），使其对后续回合可见。该运行时「始终允许」作用域 MUST 为**本会话**（不隐式泛化到其它会话；跨会话/跨重启的持久由写入配置 allowlist 在下次 agent 重建时生效承担）。写入 MUST 幂等（重复选 `a` 不产生重复放行副作用），且 MUST NOT 放松同一槽已有的 deny 规则或降低其它工具的把关级别。

#### Scenario: 选 a 后下一回合引擎放行
- **WHEN** 用户在某会话对工具 T 选择 `a`，系统把 T 的 ALLOW 规则写入该会话槽的权限上下文并保存
- **THEN** 该会话下一回合从该槽重建的权限引擎对 T 判定为 ALLOW，不再弹确认

#### Scenario: 始终允许按会话隔离
- **WHEN** 会话 A 对工具 T 选择 `a`（未写入配置 allowlist 生效前），随后切到会话 B 且 B 的槽尚无 T 的 ALLOW 规则
- **THEN** 会话 B 首次调用 T 仍按其风险与模式判定（可能仍弹确认）——运行时「始终允许」不隐式跨到会话 B

#### Scenario: 重复选 a 幂等
- **WHEN** 用户在同一会话对同一工具多次选择 `a`
- **THEN** 会话槽权限上下文对该工具至多产生一条等效 ALLOW 放行，不产生重复或冲突规则
