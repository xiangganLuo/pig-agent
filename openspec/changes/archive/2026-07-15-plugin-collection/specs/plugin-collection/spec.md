## ADDED Requirements

### Requirement: 内置常用插件集合

系统 SHALL 随发行版提供一组常用、安全、离线、无凭据的内置插件，经统一的 `Plugin` SPI 贡献 `@Tool` 工具，覆盖至少：日期时间 & 时区换算、UUID 生成、Base64 编解码、哈希摘要（md5/sha-256）、JSON 美化/校验、Unix epoch↔ISO-8601 互转、随机数/随机串。每个插件 MUST 在 `META-INF/services/io.pigagent.plugin.Plugin` 声明，并 MUST 经既有 `PluginRegistry` → `ToolRegistrar` 路径注册（不另建注册机制）。这些工具 MUST 为纯计算：MUST NOT 发起网络请求、执行 shell、写文件或读取凭据。

#### Scenario: 集合插件被发现并注册其工具
- **WHEN** 模块在 classpath 上，经 `ServiceLoaderPluginSource` + `PluginRegistry` 加载
- **THEN** 集合中每个插件被发现、其 `@Tool` 工具被注册进 `Toolkit`（如时间、UUID、Base64、哈希、JSON、随机等工具名齐全）

#### Scenario: 工具为纯计算且离线
- **WHEN** 调用集合中任一工具
- **THEN** 仅做本地计算并返回结果，不触发任何网络/进程/文件写/凭据读取

### Requirement: 统一设计模式骨架

集合 SHALL 以设计模式（而非面向功能的散装代码）组织：SHALL 提供一个抽象基类以**模板方法**固化插件的 `register` 装配骨架、以**工厂方法**将「造哪些工具」下放给子类；每个具体插件 SHALL 是一个经 `ServiceLoader` 发现的可互换策略（加插件 = 加类 + 一条 service 行，不改中央装配）；SHALL 提供一个注册表/工厂作为插件集的单一事实源。工具本身 SHALL 为无状态、经组合注入，MUST NOT 依赖工具继承树。

#### Scenario: 抽象基类固化装配骨架
- **WHEN** 一个具体插件仅实现工厂方法（声明其工具集）并复用基类的 `register` 骨架
- **THEN** 其工具经统一骨架被贡献进 `PluginContext`，无需各自重写装配逻辑

#### Scenario: 注册表与 service 声明一致
- **WHEN** 读取注册表/工厂枚举的插件集合与 `META-INF/services` 声明的插件集合
- **THEN** 两者的插件 id 集合一致（新增插件必须同时出现在两处，否则视为漂移）

### Requirement: 遵循工具返回契约与权限分级

集合中每个工具 MUST 遵循 `tool-json-contract`：成功返回其正常输出，失败返回规范 `{"error":"<reason>"}`（凭据安全、JSON 合法），MUST NOT 以抛异常作为模型可见的错误路径。因均为纯计算，这些工具 SHALL 被 `ToolRiskClassifier` 归类为 `READ_ONLY`，从而在权限策略各模式下放行（`ask` 模式无需确认）。

#### Scenario: 非法输入返回规范错误
- **WHEN** 以非法输入调用工具（如非法时区、非法 Base64、非法 JSON、非法数值区间）
- **THEN** 工具返回 `{"error":"<reason>"}` 而非抛异常，reason 不含凭据

#### Scenario: 纯计算工具在 ask 模式放行
- **WHEN** 权限模式为 `ask` 且模型调用集合中的纯计算工具
- **THEN** 该工具被归类为 `READ_ONLY` 并直接放行，不触发确认

### Requirement: 模块缺席时零行为变更

当 `pig-agent-plugin-collection` 不在 classpath 上时，系统行为 MUST 与未引入该模块时完全一致：不新增任何工具，插件加载结果不含集合插件。

#### Scenario: 模块缺席
- **WHEN** 运行时 classpath 不含集合模块
- **THEN** 无集合工具被注册，`plugin-system` 加载路径与今天行为一致
