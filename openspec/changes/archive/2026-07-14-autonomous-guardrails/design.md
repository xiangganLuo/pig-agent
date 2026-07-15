## Context

需求源：`docs/review/production-readiness-2026-07-14.md` P1 #5–7 + 切分支时暴露的配置脆弱。承 A（`tool-sandbox-and-secrets`）已归档、B 基线叠在 A 之上（运行期共享 workspace 配置，B 必须含 A）。

已读源确认：
- `PigAgent.Builder.build()`（`pig-agent-core/.../PigAgent.java:119-148`）建 `ReActAgent.builder().name().sysPrompt().model().memory()...` — **从不调 `.maxIters`**。`ReActAgent$Builder.maxIters(int)` 存在（javap 确认，`io.agentscope.core.ReActAgent`）。
- 三条 agent 构建路径：`AgentFactory.create(model)`（交互 + 渠道共享，固定 name/sysPrompt/toolkit/hooks/mem，**无 maxIters 字段**）；`AgentInstanceFactory.create(spec)`（每 agent，直接 `PigAgent.builder()`，可取 `spec.maxIters()`）；`AgentRunner` 经其 `AgentBuilder.build(spec, recorder)` 建自主一次性 agent。
- `AgentSpec.maxIters`（`AgentSpec.java:31`，默认 `DEFAULT_MAX_ITERS=10`，构造器保证 `<=0 → 10`）已存在。`config.agent.max-iters` 默认 10。
- `FileSystemTaskRepository`（`pig-agent-task/.../FileSystemTaskRepository.java`）：`toMarkdown:60-63` 只写 `schedule().type()`（丢 cronExpression/delaySeconds），body 写 description；`parseMarkdown:65-75` 只 catch `IOException`，`TaskStatus.valueOf` 遇 null/坏值抛 RuntimeException，且重建时写死 `TaskSchedule.once()`/空 description/`Instant.now()`；`findAll:39-45` 在 stream 里 `map(parseMarkdown)`，RuntimeException 逃逸（只 catch IOException）。
- `TaskSchedule`（record：`type` + `cronExpression` + `delaySeconds`）；`Task`（record，8 字段）。
- `PigAgentConfig` 无 `@JsonIgnoreProperties`，`ConfigurationManager` 加载失败即回退默认（本轮实证：一个未知 `tools` 字段让整份配置回退）。

约束：不可变领域类型（record + `withXxx`）；离线单测为主；容错沿用 skip/backup 范式；不回归既有 300+ 单测。

## Goals / Non-Goals

**Goals:**
- 全部 agent（交互/渠道/自主）经统一构建路径受 `maxIters` 约束；值来自 `AgentSpec.maxIters` 或 `config.agent.max-iters`；仅 `> 0` 时施加。
- 坏/缺字段的任务 `.md` 被跳过 + warn，`findAll`/启动 `scheduleAll` 不崩。
- 任务 `.md` schedule（含 cron 表达式/delay）+ 时间戳 + 描述往返无损；CRON/DELAYED 任务重启后被正确重排。
- 未知配置字段被忽略、不整份回退默认。
- 每条行为有离线单测。

**Non-Goals:**
- 真·可中断超时（`ReActAgent` 不可中断的既有约束不变；`timeoutSeconds` 仍尽力而为，见 digital-employee spec）。
- maxIters 命中后的花式行为（依赖 AgentScope `maxIters` 的既有语义：到上限即结束本轮；不自定义"到上限追加提示再续"）。
- 任务 `.md` 格式的版本号/迁移框架（旧格式容错降级即可，不做 schema 迁移器）。
- 配置加密 / 校验 DSL（仅加 ignore-unknown 容错）。

## Decisions

- **D1 maxIters 下沉到 `PigAgent.Builder`，各构建路径按来源传值。** `PigAgent.Builder` 增 `int maxIters`（默认 0 = 不设，保持 AgentScope 默认）；`build()` 中 `if (maxIters > 0) reactBuilder.maxIters(maxIters)`。三路径分别传：
  - `AgentFactory`：构造器增 `int maxIters` 字段（`AgentBootstrap`/`ModelManager` 建 `AgentFactory` 时传 `config.agent.max-iters`）→ 覆盖交互 + 渠道共享 agent。
  - `AgentInstanceFactory.create`：`.maxIters(spec.maxIters())` → 覆盖每个注册 agent（含默认 agent）。
  - `AgentRunner` 的 `AgentBuilder`（wiring 层实现）：建自主 agent 时同样经 `PigAgent.builder().maxIters(spec.maxIters())`。
  备选"只在 AgentInstanceFactory 接入"被否——交互/渠道走的是 `AgentFactory` 而非 InstanceFactory，会漏。下沉到 `PigAgent.Builder` 是唯一让三路径一致的点。
- **D2 `AgentFactory` 增 maxIters 字段（新构造重载，保持旧构造兼容）。** 现有 `AgentFactory` 有 3 个构造重载；新增一个带 `maxIters` 的，旧重载委托为 `maxIters=0`（不设，向后兼容既有测试直构 `AgentFactory`）。`ModelManager` 重建 agent 时沿用同一 `maxIters`（保证模型切换后仍受限）。
- **D3 任务仓库容错：`parseMarkdown` 失败返回 `Optional`/null 由 `findAll` 过滤。** 改 `parseMarkdown` 捕获所有异常（含 `RuntimeException`）→ warn + 返回 `null`（或 `Optional.empty`）；`findAll` `map(...).filter(Objects::nonNull)` 跳过坏文件。`findById` 同样容错。对齐 `JsonMcpStore.findAll` 的"坏条目跳过"写法。**不**再用 `Task.create("Error",...)` 塞占位（那会污染任务列表）。
- **D4 任务无损往返：增强 `toMarkdown` + 重写 `parseMarkdown`。** `toMarkdown` 的 Schedule 行写全：`ONCE` / `CRON:<expr>` / `DELAYED:<seconds>`（单行可逆编码）。`parseMarkdown` 解析该行重建 `TaskSchedule`（`cron(expr)`/`delayed(n)`/`once()`），解析 `Created`/`Updated`（`Instant.parse`，失败回退 `Instant.now()`）与 description（metadata 块之后的正文）。旧格式（Schedule 仅 `ONCE`/`CRON` 无值）容错降级为 `once()`、不崩。status 用容错解析（坏值 → 该文件跳过，D3）。
- **D5 配置忽略未知字段。** `PigAgentConfig` 顶层加 `@JsonIgnoreProperties(ignoreUnknown = true)`（嵌套 static 配置类按需一并加）。未知字段被忽略而非抛 `UnrecognizedPropertyException`；`ConfigurationManager` 既有的"加载失败回退默认"仅在真正损坏（非未知字段）时触发。

## Risks / Trade-offs

- **[行为/UX] 交互 agent 现在受 maxIters 约束** → **已决策（用户拍板）**：`config.agent.max-iters` 默认由 10 **上调为 40**（交互/渠道够用、不截断复杂编码任务）；`AgentSpec.DEFAULT_MAX_ITERS` **保持 10**（自主/数字员工保守，防失控成本）。二者独立可调（per-agent `AgentSpec.maxIters` 覆盖）。
- **[兼容] 任务 `.md` 格式增强** → 新写的 Schedule 行带 cron/delay；旧文件读取容错（降级 ONCE，不崩）。**缓解**：解析对"有值/无值"都兼容；无迁移器（YAGNI）。
- **[范围] config-resilience 略超原 #5-7 范围** → 你此前建议"可纳入 B"，故收进本 spec（一处注解，直接根治本轮启动被回退默认的类问题）。若你想单列，我拆出即可。
- **[承重] 已解除** → `ReActAgent.maxIters(int)` 存在（javap 确认），无需 spike。AgentScope `maxIters` 命中后的确切行为沿用其默认语义（到上限结束本轮），与 digital-employee spec 的「超过迭代上限即停」一致。

## Migration Plan

- 纯增量：maxIters 仅在值 > 0 时施加；任务容错/无损对旧 `.md` 向下兼容；config ignore-unknown 只放宽、不改既有解析。旧 `application.yaml`/任务文件无需改。
- 行为提示：README/CLAUDE 补一句"maxIters 现约束全部 agent（默认 10，可调 `agent.max-iters`）"。
- 回滚：maxIters 设 0 或极大值即解除约束；任务格式增强只影响新写文件；config 注解可安全移除。

## Open Questions

- maxIters 默认值（10 是否对交互过紧）——审批时定；默认沿用 10，可上调。
- 是否给任务 `.md` 引入显式格式版本（本次不做，容错降级足够）。
