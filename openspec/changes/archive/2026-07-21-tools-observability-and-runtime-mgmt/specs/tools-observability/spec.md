## ADDED Requirements

### Requirement: /tools 运维命令——工具进程表

系统 SHALL 提供一个面向运维的 `/tools` 命令（镜像 `/mcp` 的运维风格），支持子命令 `list | info | enable | disable | groups`：

- `list`（缺省）SHALL 逐行列出每个工具的**名称、风险分级、可用性、是否被延迟**。风险分级 SHALL 读取中央目录 `ToolRiskClassifier` 且 MUST NOT 修改它。
- `info <tool>` SHALL 展示单个工具的详情：描述、风险分级、可用性（含缺失前置的**原因**）、所属延迟/能力组、度量摘要（调用数/延迟/错误率）。
- `enable <tool>` / `disable <tool>` SHALL 运行时切换一个工具的**可见性**：`enable` 对被**延迟**的工具执行揭示（复用延迟工具的分组揭示机制），`disable` 把工具移入延迟（inactive 分组）。对因**可用性前置缺失**而隐藏的工具，`enable` MUST NOT 凭空开启，而 SHALL 返回一条提示说明缺失的前置项（不含凭据值）并指向运行时可用性重评。
- `groups` SHALL 列出能力包（tool group）：组名、激活/停用状态、成员工具名，与延迟工具的分组衔接。

`/tools` 的一切输出 MUST 经凭据脱敏（`CredentialSanitizer`），可用性原因 MUST 只说明缺失的前置项、MUST NOT 包含任何凭据值。

#### Scenario: list 展示工具名+风险+可用性+延迟状态

- **WHEN** 运维执行 `/tools list`
- **THEN** 每个工具以「名称 + 风险分级 + 可用性 + 是否延迟」一行呈现，风险分级取自 `ToolRiskClassifier`（只读），输出不含任何凭据值

#### Scenario: info 展示单工具详情

- **WHEN** 运维执行 `/tools info <tool>`
- **THEN** 返回该工具的描述、风险分级、可用性（缺前置时含原因、不含凭据）、所属组、度量摘要

#### Scenario: enable 揭示被延迟的工具

- **WHEN** 某工具当前被延迟（在 inactive 分组中），运维执行 `/tools enable <tool>`
- **THEN** 该工具被揭示（其分组激活），重新出现在模型 schema 中且可被调用

#### Scenario: disable 延迟一个工具

- **WHEN** 某工具当前可见，运维执行 `/tools disable <tool>`
- **THEN** 该工具被移入延迟（inactive 分组），从模型初始 schema 隐藏

#### Scenario: enable 对可用性隐藏的工具给出提示而非凭空开启

- **WHEN** 某工具因前置（如某环境变量）缺失而被可用性门隐藏，运维执行 `/tools enable <tool>`
- **THEN** 返回一条提示说明缺失的前置项（不含凭据值）并指向运行时可用性重评，MUST NOT 在前置缺失时把该工具开为可用

#### Scenario: groups 列出能力包

- **WHEN** 运维执行 `/tools groups`
- **THEN** 返回各 tool group 的组名、激活/停用状态与成员工具名

### Requirement: per-tool 度量聚合

系统 SHALL 按**工具名**聚合每工具的度量：调用数、延迟、错误率。度量 SHALL 由一个**纯观察**的中间件采集（扩展或补充既有的工具调用日志中间件），MUST NOT 修改工具的入参、返回结果或事件流，MUST NOT 改变既有工具调用日志的行为。度量的键 MUST 仅为工具名——MUST NOT 记录任何入参值、返回内容或凭据值。`/tools list|info` 与内核工具清单 SHALL 可查询这些度量摘要。度量为进程内内存态（重启清零），非持久化审计日志。

#### Scenario: 成功调用累加调用数与延迟

- **WHEN** 某工具被成功调用一次
- **THEN** 该工具的调用数加一，其延迟被计入聚合

#### Scenario: 失败调用累加错误计数

- **WHEN** 某工具调用以错误结束
- **THEN** 该工具的错误计数加一（错误率据此计算）

#### Scenario: 度量键仅工具名、不含凭据或入参

- **WHEN** 查看任一工具的度量
- **THEN** 度量仅按工具名聚合计数/延迟，不包含任何入参值、返回内容或凭据值

#### Scenario: 度量采集不改变既有日志行为

- **WHEN** 度量中间件已接入并发生工具调用
- **THEN** 既有工具调用日志中间件的日志行为逐字保持，工具的入参/返回/事件流不被度量采集改动

### Requirement: 运行时可用性热重评

系统 SHALL 提供一个**触发式**入口，运行时重新评估工具的可用性判据（`ToolAvailability`），使新满足的前置（如某 API key 被设置）后**无需重启**即可让此前被隐藏的工具重新可见。当重评导致**可见工具集合发生变化**时，系统 SHALL 复用 MCP 运行时工具集变化的重建机制（`toolsChangedCallback` 同款路径）重建 agent，使重评后新现/复现的工具进入新 toolkit 并获得正确的逐工具原生权限上下文。重评 MUST 为触发式——系统 MUST NOT 引入自动轮询或后台文件监听来周期性重评。重评的任何输出中，可用性原因 MUST 只说明缺失的前置项、MUST NOT 包含凭据值。

#### Scenario: 前置满足后重评使工具现身

- **WHEN** 某工具的前置此前缺失（被隐藏），前置被补齐后运维触发可用性热重评
- **THEN** 重评后该工具变为可用并对模型可见、可被调用

#### Scenario: 前置仍缺失时保持隐藏且原因不含凭据

- **WHEN** 触发热重评但某工具的前置仍缺失
- **THEN** 该工具保持隐藏，其原因只说明缺失的前置项、不含任何凭据值

#### Scenario: 重评复用重建使新现工具获得权限规则

- **WHEN** 热重评导致可见工具集合变化
- **THEN** 系统复用 MCP 同款重建机制重建 agent，重评后新现/复现的工具进入新 toolkit 并获得逐工具的原生权限上下文（而非静默丢失其权限规则）

#### Scenario: 无自动轮询

- **WHEN** 未触发热重评入口
- **THEN** 系统不自行周期性重评可用性（无后台轮询/文件监听）

### Requirement: 内核 façade 暴露工具清单

内核 faç SHALL 暴露一个只读方法返回当前活跃 agent 的工具清单，供 `/tools` 与未来 frontend **同源消费**；frontend MUST 只依赖该 faç 方法，MUST NOT 直接依赖内部的 Toolkit / 可用性门 / 延迟登记表 / 度量登记表。清单的每个条目 SHALL 至少携带：工具名、风险分级、可用性（是否可用 + 缺前置时的原因）、延迟状态（延迟/已揭示/无）、度量摘要（调用数/延迟/错误率）。清单条目 MUST NOT 包含任何凭据值（可用性原因只名缺失前置）。

#### Scenario: façade 返回带风险/可用性/延迟/度量的清单

- **WHEN** frontend 经内核 faç 请求工具清单
- **THEN** 返回当前活跃 agent 的工具条目列表，每项含 名称/风险/可用性/延迟状态/度量摘要

#### Scenario: frontend 只依赖 façade

- **WHEN** `/tools` 或未来 frontend 需要工具清单
- **THEN** 它经内核 faç 的只读方法获取，不直接访问内部 Toolkit/可用性门/延迟登记表/度量登记表

#### Scenario: 清单不含凭据值

- **WHEN** 检视 faç 返回的任一工具清单条目
- **THEN** 条目不含任何凭据值，可用性原因只说明缺失的前置项

### Requirement: 默认安全——不改变默认行为

本能力 SHALL 默认安全叠加、不改变既有行为：`/tools` 是新增命令；度量采集为纯观察 additive（不改工具的入参/返回/事件流与既有日志）；可用性热重评为触发式（仅显式调用才运行）。当运维既未使用 `/tools`、也未触发热重评时，模型看到的工具 schema、工具调用行为与既有日志 MUST 与引入本能力前逐字节一致。若度量提供配置开关，其默认值 SHALL 使度量启用但保持纯观察（无行为变化），关闭时不接入度量中间件（零开销）。

#### Scenario: 不使用任何入口时逐字节无感

- **WHEN** 运维既不执行 `/tools`、也不触发可用性热重评
- **THEN** 工具 schema、工具调用行为与既有日志与引入本能力前逐字节一致

#### Scenario: 度量中间件纯观察

- **WHEN** 度量中间件已接入并发生工具调用
- **THEN** 它只旁路采集按工具名的计数/延迟，MUST NOT 修改 acting 输入、工具结果或事件流
