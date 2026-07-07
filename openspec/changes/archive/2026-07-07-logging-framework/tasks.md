## 1. 依赖与配置（后端接入）

- [x] 1.1 父 POM：加 `slf4j.version`(2.0.16) + `logback.version`(1.5.34) + dependencyManagement 管 slf4j-api/logback-classic。
- [x] 1.2 `pig-agent-cli`：slf4j-simple → logback-classic（后端只在入口模块）；`config`/`task` 补显式 `slf4j-api`（无 agentscope 传递）；`providers` 排除 dashscope 自带的 slf4j-simple（消除 multiple providers）。
- [x] 1.3 `pig-agent-cli/src/main/resources/logback.xml`：CONSOLE + RollingFile（`${user.home}/.pig-agent/workspace/logs/pig-agent.log`，10MB/7 天滚动），`io.pigagent`=INFO、jline/agentscope=WARN。
- [x] 1.4 `pig-agent-cli/src/test/resources/logback-test.xml`（WARN、console-only）+ 父 POM 全局 test-scope logback，消除 NOP 告警。`compile` 绿。

## 2. 迁移打印为 SLF4J（逐模块，按 D6 映射）

- [x] 2.1 core：LoggingHook/ToolCallLoggingHook（→debug）、FileSystemLongTermMemory、CompressionService、FileReportWriter。
- [x] 2.2 config：ConfigurationManager（load/save/listener 警告）。
- [x] 2.3 mcp：McpManager、JsonMcpStore。
- [x] 2.4 model：ModelManager、JsonModelStore。
- [x] 2.5 task：TaskScheduler。
- [x] 2.6 channel：TelegramChannel、DiscordChannel、ChatChannel。
- [x] 2.7 cli：AgentBootstrap、PigAgentCli、WebLauncher 状态/错误/关停 → 日志；保留启动 banner（D3）；REPL 的 `Ansi.println(terminal,…)` + 交互式 retry 提示不动。
- [x] 2.8 onboarding：`OnboardingWizard` 全程为交互式终端对话（提示紧邻 readLine），按 D3 整体保留控制台，不迁移。
- [x] 2.9 复核：main 仅剩豁免项——OnboardingWizard(11 交互) + PigAgentCli banner(1)；其余 50 处全部迁 SLF4J。

## 3. 校验 + 文档

- [x] 3.1 全模块 `mvn test` BUILD SUCCESS，无回归；无 SLF4J NOP / multiple-providers 告警。
- [x] 3.2 手动冒烟：`WebLauncher` 启动，控制台见分级日志，`workspace/logs/pig-agent.log` 生成（logback pattern 正确）。
- [x] 3.3 文档：`CLAUDE.md`（Conventions 增日志约定）+ `README`（日志位置/级别）。
