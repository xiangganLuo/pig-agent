## ADDED Requirements

### Requirement: 工具可用性判据

工具 SHALL 可声明一个可用性判据（检查其运行前置条件，如所需环境变量/二进制/服务是否就绪）。求值判据抛出异常时，系统 MUST 将该工具视为不可用（fail-safe）。未声明判据的工具 SHALL 默认视为可用（向后兼容）。

#### Scenario: 前置缺失判为不可用
- **WHEN** 某工具的判据检查所需前置（如 `BRAVE_API_KEY`）缺失
- **THEN** 该工具被判为不可用

#### Scenario: 判据异常 fail-safe
- **WHEN** 某工具判据求值抛出异常
- **THEN** 系统将其视为不可用，不因此中断其他工具的处理

#### Scenario: 无判据默认可用
- **WHEN** 某工具未声明可用性判据
- **THEN** 该工具视为可用，行为与引入本能力前一致

### Requirement: 不可用工具不进入模型 schema

组装传给模型的工具定义时，系统 SHALL 过滤掉判据不通过的工具，使模型完全看不到它们。既有 `ToolPermissionHook` 的执行期 veto（风险分级、权限 mode）行为不受影响，与本过滤叠加。

#### Scenario: 不可用工具对模型不可见
- **WHEN** 某工具不可用且构建传给模型的工具 schema
- **THEN** 该工具不出现在 schema 中，模型无法调用它

#### Scenario: 可用工具正常暴露且仍受权限约束
- **WHEN** 某工具可用
- **THEN** 它出现在 schema 中，且其执行仍受既有权限 mode/veto 约束

### Requirement: 被隐藏工具及原因对用户可见

系统 SHALL 允许用户查看被隐藏的工具及其不可用原因（如「缺 BRAVE_API_KEY」），供 TUI/status 面板与 `/status` 展示。原因 MUST NOT 包含凭据值本身，只说明缺失的前置项。

#### Scenario: status 展示隐藏工具与原因
- **WHEN** 用户查看状态（TUI status 面板或 `/status`）
- **THEN** 可看到被隐藏的工具及其原因（如缺哪个环境变量），且不显示任何凭据值
