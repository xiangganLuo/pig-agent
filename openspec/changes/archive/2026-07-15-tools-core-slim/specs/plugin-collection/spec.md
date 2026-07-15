## MODIFIED Requirements

### Requirement: 模块缺席时零行为变更

当 `pig-agent-plugin-builtin` 不在 classpath 上时，系统行为 MUST 与未引入该模块时完全一致：不新增任何工具，插件加载结果不含内置插件。

#### Scenario: 模块缺席
- **WHEN** 运行时 classpath 不含内置插件模块
- **THEN** 无内置插件工具被注册，`plugin-system` 加载路径与今天行为一致
