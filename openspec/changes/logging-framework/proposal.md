## Why

项目当前用 `System.out.print*` / `System.err.print*` 充当日志（62 处，散布 9 个模块 18 个文件）——无级别、无时间戳、无来源、无法落文件、无法按需静默。AgentScope 已经带来 SLF4J API（运行时曾出现 `SLF4J(W): No providers found → NOP`），说明缺的是**统一的日志门面接入 + 一个绑定后端（Logback）+ 配置**。本变更把项目自身的诊断/状态/错误输出统一到 **SLF4J + Logback**，输出到控制台 + 滚动文件。

## What Changes

- **接入日志后端**：显式引入 `logback-classic`（版本在父 POM 统一管理），确保 `slf4j-api` 在编译期可用；库模块只依赖 `slf4j-api`，日志后端只在应用入口模块（`pig-agent-cli`）提供。
- **`logback.xml` 配置**：放在 `pig-agent-cli/src/main/resources`（CLI 与 WebLauncher 两个入口共用的运行时 classpath）。**控制台 appender + 按天/大小滚动的文件 appender**，日志文件落在 `~/.pig-agent/workspace/logs/pig-agent.log`。
- **迁移打印为日志**：每个类加 `private static final Logger log = LoggerFactory.getLogger(X.class)`；`System.out.println(...)` → `log.info(...)`、`System.err.println(...)` → `log.warn/error(...)`（按语义）。参数化日志（`log.info("... {}", x)`），不再手工拼串。
- **保留真正的交互式终端 I/O（非日志）**：`OnboardingWizard` 首次运行的**交互提示**（紧邻 `readLine` 的问句）、启动 ASCII banner、以及 REPL 经 `Ansi.println(terminal, …)` 的彩色/流式输出（这些本就不是 `System.out.print`），属于用户界面而非日志，保持终端输出。见 design D3。

## Capabilities

### New Capabilities
- `logging`: 统一的应用日志——SLF4J 门面 + Logback 后端，分级、带时间戳/来源，同时输出控制台与滚动文件；库模块仅依赖门面，后端与配置由入口模块提供。

## Impact

- **代码**：9 个模块 18 个文件的 `System.out/err` 迁移为 SLF4J；父 POM 加 `logback.version` + 依赖管理；`pig-agent-cli` 加 `logback-classic` 依赖 + `logback.xml`。
- **运行**：启动后日志分级可控（默认 INFO），落 `workspace/logs/`；测试期无 provider 的 NOP 警告消除（test 作用域给一个后端或 NOP）。
- **无行为变更**：仅改变"信息如何被输出"，不改业务逻辑；交互式 CLI 体验不受影响。
- **依赖**：`logback-classic` 需能离线解析（父 POM 版本；本地仓已有则零下载）。
