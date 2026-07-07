## 1. 依赖与配置（后端接入）

- [ ] 1.1 父 POM：加 `logback.version`（对齐 slf4j-api 版本）+ `dependencyManagement` 管 `logback-classic`；显式声明 `slf4j-api`（编译期门面）。
- [ ] 1.2 `pig-agent-cli`：加 `logback-classic` 运行时依赖。库模块只保留/新增 `slf4j-api`（多数已随 AgentScope 传递，按需显式化）。
- [ ] 1.3 `pig-agent-cli/src/main/resources/logback.xml`：CONSOLE + RollingFile（`${user.home}/.pig-agent/workspace/logs/pig-agent.log`，按天+大小滚动，保留 7 天/10MB），`io.pigagent`=INFO、噪声库=WARN。
- [ ] 1.4 测试期消除 NOP：`logback-test.xml`（静默到 WARN）或 test-scope 后端。`mvn -pl pig-agent-cli -am compile` 绿。

## 2. 迁移打印为 SLF4J（逐模块，按 D6 映射）

- [ ] 2.1 core：`hook/LoggingHook`、`hook/ToolCallLoggingHook`、`memory/FileSystemLongTermMemory`、`compression/CompressionService`、`agent/runner/FileReportWriter`。
- [ ] 2.2 config：`ConfigurationManager`（load/save 警告 → warn/error）。
- [ ] 2.3 mcp：`McpManager`、`JsonMcpStore`。
- [ ] 2.4 model：`ModelManager`、`JsonModelStore`。
- [ ] 2.5 task：`TaskScheduler`。
- [ ] 2.6 channel：`TelegramChannel`、`DiscordChannel`、`ChatChannel`。
- [ ] 2.7 cli：`AgentBootstrap`、`PigAgentCli`、`WebLauncher` 的状态/错误/关停信息 → 日志（保留 banner splash；REPL 的 `Ansi.println(terminal,…)` 不动）。
- [ ] 2.8 onboarding：`OnboardingWizard` 的**非交互**信息 → 日志；紧邻 `readLine` 的交互提示保留控制台（D3）。
- [ ] 2.9 每组后 `mvn -pl <module> -am compile` 绿；`grep System.out/err` 复核仅剩豁免项（banner/交互提示/terminal 输出）。

## 3. 校验 + 文档

- [ ] 3.1 全模块 `mvn test` BUILD SUCCESS，无回归；无 SLF4J NOP 告警。
- [ ] 3.2 手动冒烟：启动 CLI 与 WebLauncher，确认控制台有分级日志、`workspace/logs/pig-agent.log` 生成；引导/REPL 交互显示正常。
- [ ] 3.3 文档：`CLAUDE.md`（约定：SLF4J 门面、后端只在入口模块、日志位置）+ `README`（日志文件位置/级别调整）。
