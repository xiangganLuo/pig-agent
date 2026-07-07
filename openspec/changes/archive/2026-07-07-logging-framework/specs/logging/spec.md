## ADDED Requirements

### Requirement: 统一日志门面与后端

系统 SHALL 使用 SLF4J 作为日志门面、Logback 作为后端。库模块 MUST 只依赖 `slf4j-api`，日志后端（`logback-classic`）MUST 只由应用入口模块（`pig-agent-cli`）提供，避免多后端冲突。项目自身的诊断、状态与错误输出 MUST 经 SLF4J 记录，而非 `System.out`/`System.err`。

#### Scenario: 库模块只依赖门面
- **WHEN** 一个库模块（core/task/mcp/model/session/config/channel/web）需要记录日志
- **THEN** 它经 `org.slf4j.Logger` 记录，不直接依赖或配置任何日志后端

#### Scenario: 诊断输出经日志门面
- **WHEN** 代码需要输出状态/警告/错误（原先用 `System.out`/`System.err`）
- **THEN** 它经 `LoggerFactory.getLogger(类).info/warn/error(...)` 记录，使用参数化占位符而非字符串拼接

### Requirement: 控制台与滚动文件输出

系统 SHALL 通过 `logback.xml`（位于入口模块运行时 classpath）同时向控制台与滚动文件输出日志。文件 MUST 落在 `${user.home}/.pig-agent/workspace/logs/` 下并按时间/大小滚动。默认级别 SHALL 为 INFO（`io.pigagent` INFO；嘈杂的第三方库压到 WARN）。

#### Scenario: 启动后日志双路输出
- **WHEN** 启动 CLI 或 Web 控制台
- **THEN** 日志同时出现在控制台，并写入 `~/.pig-agent/workspace/logs/pig-agent.log`

#### Scenario: 日志文件滚动
- **WHEN** 日志文件达到滚动条件（跨天或超过大小上限）
- **THEN** 归档为历史文件并保留有限份数，不无限增长

#### Scenario: 无绑定后端的告警消除
- **WHEN** 运行应用或测试
- **THEN** 不再出现 `SLF4J: No providers found → NOP` 告警（存在绑定后端或测试期显式后端）

### Requirement: 保留交互式终端 I/O

交互式的终端输入/输出 MUST NOT 被当作日志改写：首次运行引导（`OnboardingWizard`）中紧邻输入读取的交互提示、启动 ASCII banner、以及 REPL 经 `Ansi.println(terminal, …)` 的彩色/流式终端输出，SHALL 保持终端/控制台呈现，不经日志门面（它们是人机交互界面，非日志）。

#### Scenario: 引导提示仍可交互
- **WHEN** 首次运行进入引导，提示用户输入 API key / 选择协议
- **THEN** 提示在控制台原样显示且能在同一处读取输入，不被日志格式包裹

#### Scenario: REPL 交互输出不变
- **WHEN** 用户在 REPL 与 agent 对话
- **THEN** 彩色提示符与流式回答仍经终端呈现，表现与接入日志前一致
