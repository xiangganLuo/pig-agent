## Why

当前所有已注册工具都会进入传给模型的工具 schema，即使其运行前置条件缺失（如 `webSearch` 缺 `BRAVE_API_KEY`、某工具缺二进制/服务）。模型因此可能幻觉调用不可用工具，白白消耗 schema token 并在执行时才失败。借鉴 Hermes 的 `check_fn` 门控：工具声明可用性判据，不可用则**不进 schema**（模型看不到），作为权限 veto 之外的**第一道过滤**。同时向用户展示被隐藏工具及原因，便于排查。依据路线图 `docs/planning/tui-and-core-roadmap.md`（Spec B②）。

## What Changes

- **新增工具可用性判据**：工具可声明一个可用性检查（缺 key/依赖/服务时返回不可用）；判据抛异常一律视为不可用（fail-safe）。
- **不可用工具不进 schema**：组装传给模型的 `Toolkit`/工具定义时，过滤掉判据不通过的工具——模型完全看不到它们（防幻觉、省 token）。
- **用户可见的隐藏原因**：被隐藏的工具及其原因（如「缺 BRAVE_API_KEY」）对用户可见（供 TUI/status 面板与 `/status` 展示）；凭据本身 MUST NOT 展示，只说明「缺哪个前置」。
- **与权限系统互补**：本能力是「对模型是否可见」的过滤；既有 `ToolPermissionHook` 的执行期 veto（risk 分级、mode）行为不变，二者叠加。

无 **BREAKING**：无判据的工具默认视为可用，行为不变。

## Capabilities

### New Capabilities
- `tool-availability`: 工具可声明可用性判据；不可用工具不进入传给模型的 schema（模型不可见），判据异常 fail-safe 视为不可用；被隐藏工具及原因对用户可见（凭据不展示）。

### Modified Capabilities
<!-- 无：不改 tool-permissions 的执行期 veto 契约；本能力是其之前的可见性过滤，属新增维度。 -->

## Impact

- **代码**：`pig-agent-tools`（工具可用性判据声明 + 判据求值；被隐藏工具/原因的查询）；Toolkit 组装处（`AgentWiring`/`AgentBootstrap` 等）在构建工具定义时应用过滤；`pig-agent-cli`/TUI 的 status 展示被隐藏工具。
- **协作/不改**：`ToolPermissionHook`/`ToolRiskClassifier`/权限 mode 与 allowlist 行为不变。
- **测试**：判据求值（可用/不可用/异常 fail-safe）、schema 过滤（不可用不出现）、隐藏原因查询（不含凭据）。
- **文档**：`CLAUDE.md` 工具/权限段落补「可用性门控」。
