## Why

工具目前**无条件执行**：`ShellTools.executeCommand`、`FileSystemTools.writeFile` 等被 LLM 选中即运行，除 MCP 自助接入的 D-SEC 门外没有任何通用审批层。主流 agent 产品（Claude Code、opencode、hermes 等）都提供**权限级别**（plan / ask / auto / bypass）让用户按信任度控制工具执行。本变更为 Pig Agent 引入统一的工具权限体系：一个作用于 `PreActingEvent` 的权限门，按当前模式对每次工具调用做 allow / deny / 人工确认，支持只读的 plan 模式、逐工具/逐命令粒度与 allowlist 持久化。

## What Changes

- 新增 `permissions` 配置块（`PigAgentConfig`）：全局 `mode`（`plan`/`ask`/`auto`/`bypass`，默认 `ask`）、`channel-mode`（非交互渠道默认模式，默认 `auto`）、`tool-overrides`（风险重分类）、`allowlist`（`tools` + `commands`，持久化的"始终允许"）。
- 新增 `ToolPermissionHook`（`io.agentscope.core.hook.Hook`，监听 `PreActingEvent`，高优先级，早于日志 hook）：统一拦截**内置工具与 MCP 工具**，按模式 + 工具风险 + allowlist 判定 allow / deny / ask。
- 工具风险分级（`ToolRiskClassifier`）：READ_ONLY / WRITE / EXEC / NETWORK / MCP_ADMIN。READ_ONLY 恒放行；plan 模式拦截所有可变工具；ask 模式对可变工具人工确认（y / n / a=始终允许该工具或命令）；auto 自动放行 WRITE/NETWORK、仍确认 EXEC/MCP_ADMIN；bypass 全放行。
- 逐命令粒度：EXEC 工具的确认展示实际命令，`a` 可记住"该命令模式"（写入 `allowlist.commands`），下次同模式命令免确认。
- plan 模式 = **真·只读 agent**：可变工具被否决并回传"plan 模式下不执行，请产出计划"，agent 只做只读调查并输出计划；用户 review 后切模式执行。
- 新增 `/permission` CLI 命令：`status`/`mode <m>`/`allow <…>`/`revoke <…>`/`reset`/`list`；`/status` 展示当前模式。
- 复用现有 `McpConfirmer` + `readerRef`（JLine）做 y/n/a 交互确认。
- MCP 自助接入的 **D-SEC 门保持不变**，作为 MCP_ADMIN 的内层门；权限 hook 对 MCP_ADMIN 工具不重复弹窗（委托 D-SEC）。

## Capabilities

### New Capabilities
- `tool-permissions`: 工具执行的权限模式（plan/ask/auto/bypass）、逐工具风险分级与逐命令粒度审批、只读 plan 模式、allowlist 持久化，以及非交互渠道的兜底策略。

### Modified Capabilities
<!-- openspec/specs 目前无独立 spec 需修改；与既有 mcp-management 的 D-SEC 门为组合关系（见 design.md） -->

## Impact

- **代码**：`pig-agent-config`（`PermissionConfig`）、`pig-agent-core`（`ToolPermissionHook` + `ToolRiskClassifier` + 权限判定）、`pig-agent-cli`（`/permission` 命令 + 接线，plan 模式 REPL 提示）、`pig-agent-channel`（渠道回合走 `channel-mode`）。
- **配置/数据**：`application.yaml` 新增 `permissions` 块；纯增量，缺省时 `mode=ask` 向后兼容（现有行为约等于 `bypass`，需在文档/迁移说明中明确"升级后默认变为 ask，危险工具会开始要确认"）。
- **依赖**：AgentScope 1.0.12 的 `PreActingEvent.setToolUse(...)` 与 hook 执行语义——"否决工具调用"的确切写法需 step-0 spike 验证（改写 toolUse 成拒绝 vs `Mono.error` 走 `handleInterrupt`）。
- **安全**：这是纵深防御的核心增强；唯一新增风险口子是 `channel-mode=auto` 下非交互渠道的放行面（见 design.md「Risks」，默认对 EXEC/MCP_ADMIN fail-closed）。
- **文档**：README（中文）新增权限章节 + `CLAUDE.md`。
