## Context

62 处 `System.out/err.print*` 当日志用，跨 9 模块 18 文件。SLF4J API 已随 AgentScope 传递进来，但无绑定后端（曾见 NOP 警告）。目标：统一到 SLF4J + Logback，控制台 + 滚动文件，且不破坏 CLI 的交互式终端体验。分支 `opt/logging-slf4j` 叠在 `feat/agent-kernel-facade` 之上（日志改动横跨含 web/kernel 的全部现有模块）。

## Goals / Non-Goals

**Goals:**
- 项目自身的诊断/状态/错误输出统一走 SLF4J（参数化、分级、带来源）。
- 引入 Logback 后端 + `logback.xml`：控制台 + 滚动文件（`workspace/logs/`）。
- 库模块只依赖 `slf4j-api`；后端只在入口模块（`pig-agent-cli`）提供。

**Non-Goals:**
- 不改业务逻辑/控制流。
- 不引入结构化日志平台（ELK 等）、不引入 MDC 链路追踪（后续可加）。
- 不改 AgentScope 自身的日志。

## Decisions

- **D1：后端只在入口模块。** `slf4j-api` 作为门面（编译期显式声明，版本对齐 AgentScope 传递的版本）；`logback-classic` 只加到 `pig-agent-cli`（`PigAgentCli` + `WebLauncher` 共用其 classpath）。库模块（core/task/mcp/model/session/config/channel/web…）**只**用 `slf4j-api`，不绑后端——避免多后端冲突，符合"库不决定日志实现"惯例。
- **D2：每类一个 static final Logger。** `private static final Logger log = LoggerFactory.getLogger(X.class)`；`out→info`、`err→warn`（可恢复/非致命）或 `error`（异常/失败，带 throwable 参数）。字符串拼接改参数化 `{}`。
- **D3：交互式终端 I/O 不是日志，保留。** 三类保留为终端/控制台输出，不迁移：(a) `OnboardingWizard` 紧邻 `readLine` 的交互提示（logger 无法在同一行渲染提示再读输入）；(b) 启动 ASCII `BANNER`（一次性 splash）；(c) REPL 经 `Ansi.println(terminal, …)` 的彩色/流式输出（本就走 JLine `Terminal`，非 `System.out`）。**这是对"全部改 SLF4J"的工程化收敛**：把"日志"与"人机交互 I/O"区分开——需在 spec 门确认。
- **D4：`logback.xml` 配置。** 
  - `CONSOLE` appender：pattern 带时间/级别/logger/线程；默认级别 `INFO`，`io.pigagent` 可单独设级。
  - `FILE` appender：`RollingFileAppender` + `TimeBasedRollingPolicy`（+ 可选 `SizeAndTimeBased`），落 `${user.home}/.pig-agent/workspace/logs/pig-agent.log`，保留 N 天。
  - 压低第三方噪声（如 `org.jline`、AgentScope 内部）到 WARN，避免刷屏。
- **D5：测试期。** 测试 classpath 加 `logback-classic`（test scope，或 slf4j-nop）消除 NOP 警告；或提供 `src/test/resources/logback-test.xml` 静默到 WARN，保持测试输出干净。
- **D6：out→级别映射表**（迁移一致性）：纯状态/进度 → `info`；可恢复告警/跳过 → `warn`；异常/失败（catch 块、"Failed…"）→ `error(msg, throwable)`。

## Risks / Trade-offs

- [“全部改 SLF4J” vs 交互 I/O] → D3 明确豁免交互提示/banner/终端彩色输出；spec 门确认。若用户坚持字面全部，onboarding 提示仍无法做成 logger（会破坏 readLine），此为技术约束。
- [多模块多后端冲突] → D1 后端只在入口模块；库仅门面。
- [logback-classic 离线解析] → 父 POM 固定版本；本地仓（`D:\env\...\repository`）若已有则零下载，否则需一次联网。
- [日志文件路径依赖 user.home] → 与 workspace 默认位置一致（`~/.pig-agent/workspace/`）；`logback.xml` 用 `${user.home}` 属性。

## Migration Plan

- 纯输出层替换 + 新增后端依赖/配置；逐模块迁移，`mvn -pl <m> -am compile` 守绿。行为等价。
- 回滚：还原 `System.out/err`、移除 logback 依赖与 `logback.xml` 即可。

## Open Questions

- 默认日志级别 INFO 是否合适？（当前 out 多为 INFO 级状态）→ 采用 INFO，`io.pigagent` INFO、第三方 WARN。
- 文件保留天数 / 单文件大小上限？→ 暂定保留 7 天、单文件 10MB 滚动；code 阶段可调。
