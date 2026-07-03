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
在 `ask` 模式下，可变工具在执行前 MUST 经人工确认。确认 SHALL 提供三态：`y`（本次允许）/ `n`（拒绝）/ `a`（始终允许）。选择 `a` MUST 将该工具或该命令写入持久化 allowlist，使后续相同调用免确认。拒绝时工具 MUST NOT 执行，且拒绝原因 MUST 回传模型。

#### Scenario: 逐次确认危险工具
- **WHEN** `mode=ask` 时 agent 请求执行 shell 命令
- **THEN** 向用户展示实际命令并等待 y/n/a；选 n 则不执行并告知模型被拒

#### Scenario: 始终允许后免确认
- **WHEN** 用户对某工具或命令选择 `a`
- **THEN** 该工具/命令写入 allowlist，之后相同调用不再确认，且跨重启保留

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

