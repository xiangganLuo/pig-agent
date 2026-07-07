# tool-autoregister Specification

## Purpose
TBD - created by archiving change tool-autoregister. Update Purpose after archive.
## Requirements
### Requirement: 工具自动发现与注册

系统 SHALL 通过 SPI（`ServiceLoader`）或受控 classpath 扫描自动发现内建工具并注册进 `Toolkit`，无需在装配处为每个工具手写注册。新增符合约定的工具类 SHALL 在无需修改手动注册清单的情况下被发现。

#### Scenario: 新工具落文件即被发现
- **WHEN** 新增一个符合自动发现约定的内建工具类并启动
- **THEN** 该工具被自动注册进 Toolkit，无需改装配处的手动清单

#### Scenario: 预期工具集齐全
- **WHEN** 启动并自动发现工具
- **THEN** 现有内建工具集被完整注册（不缺失）

### Requirement: 手动注册兜底且可覆盖

系统 SHALL 保留手动注册路径作为兜底。当自动发现与手动注册出现同名工具时，SHALL 有明确的优先/覆盖规则（手动可覆盖自动），且覆盖 SHALL 记录 INFO 级审计日志；MUST NOT 静默重复注册同名工具。

#### Scenario: 手动覆盖自动
- **WHEN** 某工具名既被自动发现、又被手动注册（意在覆盖）
- **THEN** 按优先规则采用手动实现，并记录一条 INFO 审计日志

#### Scenario: 拒绝静默重复
- **WHEN** 出现未预期的同名重复注册
- **THEN** 系统按规则取一个并记录，而非静默叠加两个同名工具

### Requirement: 单个工具加载失败被隔离

某个工具类的加载或实例化失败时，系统 MUST 捕获并记录，且 MUST NOT 因此中断其它工具的发现与注册（fail-safe）。

#### Scenario: 可选工具加载失败不影响其它
- **WHEN** 某可选工具因缺依赖而实例化失败
- **THEN** 系统记录该失败并继续注册其它工具，Toolkit 正常可用

