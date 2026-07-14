## Context

需求源：`docs/review/production-readiness-2026-07-14.md` 的 P2 工程化项 #10（无覆盖率度量，80% 是纸面）+ #11（`*IT` 无 failsafe、不在自动门）+ 本次会话发现的并行构建 flaky。批次 C「质量门工程化」，前置批次 A（`tool-sandbox-and-secrets`）已归档，本分支基于其之上。

当前构建事实（已读 `pom.xml` 确认）：
- 父 `pom.xml` `<build><plugins>` 仅 `maven-compiler-plugin` + `maven-surefire-plugin:3.5.2`，**无 jacoco、无 failsafe、无 profile**。测试 JUnit5+Mockito+AssertJ 经 surefire 默认跑，`*IT` 因命名（Failsafe 约定 `*IT`）未被 surefire 匹配（surefire 默认只跑 `*Test`/`Test*`），故默认 `mvn test` 天然排除 `*IT`——但 `mvn verify` 也没 failsafe，`*IT` 落在真空。
- 4 个 `*IT`（`pig-agent-cli` 的 `PermissionVetoSpikeIT`/`PermissionEnforcementIT` 等）需真 API key + `~/.pig-agent/workspace/models.json`。
- 12 子模块，其中 `pig-agent-channel`/`pig-agent-onboarding`/`pig-agent-providers` **无任何测试**（0 jacoco.exec），编排类 `AgentBootstrap`（cli）装配极重、离线不可单测。
- 基线全量 `mvn test` = 300 测试（批次 A 后 323）；本机 Windows（2 个 POSIX 断言 `assumeTrue` 跳过）。

**实测基线（本变更接入 jacoco 后，回补单测前）**：聚合行覆盖率 **0.4913**（covered 1916 / total 3900）。

约束：Java 17 target / Java 21 运行；不可变领域类型；SLF4J（禁 System.out）；JUnit5+Mockito+AssertJ AAA；文件 <800 行；离线单测为主（`*IT` 归外环）；**不改产品源**（纯增测 + 修测 + 构建配置）。

## Goals / Non-Goals

**Goals:**
- jacoco 接入并度量：`prepare-agent` 插桩 surefire、`report` 出每模块 html/csv/xml。
- **棘轮基线门**：每模块一条 `check` 规则（`BUNDLE`/`LINE`/`COVEREDRATIO`），最低值 = floor(该模块当前实测) − 0.02，锁死只进不退、**今天必过**；无可测面模块 floor=0.00（不阻断）。`check` 绑 `verify`。
- failsafe 接入，`*IT` **默认跳过**（`skipITs=true`）、`-Pit`（`skipITs=false`）启用；无凭据 `mvn verify` 必过且不跑 `*IT`。
- 回补三个最高风险编排器离线单测（`SessionManager`/`ModelManager` 切换路径/`ToolPermissionHook`），抬高对应模块棘轮。
- 修复 `AgentKernelInterruptTest.chat_normalTermination_clearsTurn` 并行 flaky，不弱化断言。

**Non-Goals:**
- 达成 80% 覆盖率（棘轮只锁当前水位、渐进抬升，不一次性冲 80%）。
- 跨模块**真聚合** `check`（jacoco `check` 只分析本模块 classes，见 D2；本次用每模块棘轮，等价且更强）。
- 为 `AgentBootstrap` 等 wiring-heavy 装配类硬凑离线单测（构造需真 workspace/网络，得不偿失；诚实留 0.00 + `*IT`）。
- 改动任何产品源行为 / 新增运行时依赖。
- 把 `*IT` 塞进默认门（需真凭据，只能 `-Pit` 显式跑）。

## Decisions

- **D1 棘轮 = 每模块 `check`（BUNDLE/LINE），floor(current)−0.02。** 父 `pom.xml` 声明一条继承的 `jacoco-maven-plugin` `check`（`element=BUNDLE`、`counter=LINE`、`value=COVEREDRATIO`、`minimum=${jacoco.line.min}`），绑 `verify`。父默认 `${jacoco.line.min}=0.00`（永不阻断）；**每个有可测面的模块在自己的 `pom.xml` `<properties>` 覆盖为 floor(current)−0.02**。这样棘轮天然防每个模块回退（比单一聚合地板更强：任一模块掉线即挂），且今天全过。无 `jacoco.exec` 的模块（channel/onboarding/providers/根 pom）`check` 自动跳过（jacoco 缺 exec 即 skip，不失败）。
- **D2 不做跨模块聚合 `check`（技术约束）。** jacoco 的 `check` goal 只分析 `${project.build.outputDirectory}`（本模块 classes），无原生 aggregate-check；`report-aggregate` 只出报告不含 `check`。要真聚合门须引入聚合模块 + `merge` + 自定义分析，超出本批 ROI。故**聚合率仅用于文档基线记录**，enforcement 用每模块棘轮。聚合率经各模块 `jacoco.csv` 求和 `LINE_COVERED/(LINE_COVERED+LINE_MISSED)` 得到（见 Migration/证据）。
- **D3 failsafe 默认跳过 + `it` profile。** 父声明 `maven-failsafe-plugin`（`integration-test`+`verify` goals），`<skipITs>${skipITs}</skipITs>`，父属性 `skipITs=true`。`it` profile 置 `skipITs=false`。故 `mvn verify` 跳过 `*IT`（无凭据也绿），`mvn verify -Pit`（或 `-DskipITs=false`）才跑。选 profile 而非纯 `<skipITs>` 常量，是为让「跑 IT」是**显式、可发现**的一步。
- **D4 flaky 修复 = 确定性等待 turn 清理，不弱化断言。** `AgentKernel.chat` 在流的 `doFinally` 里 `interrupts.end(handle)`；`collectList().block()` 在终止信号传达即返回，`doFinally` 可能滞后一瞬 → `interruptCurrent()` 偶读到未清 turn（并行构建更易触发）。修复：`block()` 后**短轮询**共享 `InterruptController.currentTurn()`（无副作用读）直到为空（≤5s，`Thread.onSpinWait`），再断言 `kernel.interruptCurrent()` 为 false。语义不变（仍验证「正常完成后无残留 turn」），只是等异步 `doFinally` 真正跑完，消除 race。**不**改产品 `AgentKernel`（race 是测试对异步终止的时序假设过强，非产品 bug）。
- **D5 三组回补单测的隔离手法（零网络）。**
  - `SessionManagerTest`（session）：真 `JsonSession`+`FileSystemSessionRepository`+`ConfigurationManager` 于 `@TempDir`，stub `Model`（`Flux.empty()`，从不被调），记录式 `AgentModelSwitcher` lambda。覆盖 initialize（空/配置/最近活跃三分支）、activate（ensureModel 握手 + 落盘）、create/bind/rename/noteUserMessage（默认名改写 vs 保留）/delete（有替补 vs 建空）/fork（拷模型绑定 + temp-memory）/memory 开关/clearConversation（删/留 temp）/lineageOf/list 排序。
  - `ModelManagerSwitchTest`（model）：假 `ModelProtocol` 返回 stub `Model`，mock `ModelStore`，真 `AgentFactory`+`AgentHolder`。覆盖 `ensureModel` 切换（重建 + current 更新）/同模型 no-op/null→default/未知 target no-op/**build 失败保号**（throwing 协议，不抛、保持旧 agent+modelId）/未 attach no-op/`attachChannel` 联动重建/`modelFor`。
  - `ToolPermissionHookTest`（tools）：直构 `PreActingEvent(mock(Agent), null, ToolUseBlock)` 驱动 hook。覆盖 priority=0、plan 否决改写哨兵（保 id）、bypass 放行不改写、哨兵不二次处理、null toolUse no-op、modeOverride 优先 / null 回退全局、channel EXEC 无 confirmer fail-closed、denialListener 否决通知 / 放行不通知。纯 resolver 判定仍由既有 `PermissionResolverTest` 覆盖（不重复）。
- **D6 jacoco 版本与兼容。** `jacoco-maven-plugin` `0.8.12`（支持至 Java 22 字节码，兼容本机 Java 21 运行 + Java 17 target）；failsafe `3.5.2`（对齐既有 surefire 版本）。`prepare-agent` 设的 `argLine` 被现有 surefire 自动继承（surefire 未覆盖 `argLine`），插桩不改测试计数（实测 test 前后一致：接入前 323 / 回补后 363，均 0 失败）。

## 落实追踪表（评审/待优化发现项 → 落点 + 状态）

| 发现项（review） | 落点 | 状态 |
|---|---|---|
| #10 无 jacoco、80% 纸面 | D1 每模块棘轮 `check` + `report`；父/各模块 `pom.xml` | ✅ 已实现 |
| #10 重点补 `SessionManager` 离线单测 | D5 `SessionManagerTest`（22 测试，session 0.39→0.91） | ✅ 已实现 |
| #10 重点补 `ModelManager` 切换路径离线单测 | D5 `ModelManagerSwitchTest`（8 测试，model 0.58→0.78） | ✅ 已实现 |
| #10 重点补 `ToolPermissionHook` adapter 离线单测 | D5 `ToolPermissionHookTest`（10 测试，tools 0.60→0.65） | ✅ 已实现 |
| #10 重点补 `AgentBootstrap` 离线单测 | wiring-heavy，构造需真 workspace/网络 → **延后**：cli floor=0.27（含其 REPL/装配），标注 `*IT`/后续批次覆盖 | ⏸ 延后（诚实不伪造） |
| #11 `*IT` 配 failsafe、可进 `verify` | D3 failsafe + `it` profile；默认 `skipITs=true` | ✅ 已实现 |
| 本次会话 flaky（并行 `AgentKernelInterruptTest`） | D4 确定性等待 turn 清理 | ✅ 已实现 |

## 棘轮地板（本变更定稿，回补单测后实测）

聚合行覆盖率：接入 jacoco 基线 **0.4913** → 回补后 **0.5364**（covered 2092 / total 3900）。每模块 `check` 地板（= floor(current)−0.02，Windows 实测，Linux 因 POSIX 断言运行只增不减，故为保守下界）：

| 模块 | 当前 | 地板 | 模块 | 当前 | 地板 |
|---|---|---|---|---|---|
| pig-agent-config | 0.8644 | **0.84** | pig-agent-session | 0.9144 | **0.89** |
| pig-agent-core | 0.7167 | **0.69** | pig-agent-model | 0.7786 | **0.75** |
| pig-agent-tools | 0.6497 | **0.62** | pig-agent-workspace | 0.7381 | **0.71** |
| pig-agent-mcp | 0.4368 | **0.41** | pig-agent-task | 0.4384 | **0.41** |
| pig-agent-cli | 0.2944 | **0.27** | channel/onboarding/providers | 0（无测试） | **0.00** |

## Risks / Trade-offs

- **[覆盖广度] 棘轮起点低（聚合 0.54，cli 0.29）** → 与 80% 目标有差距。**缓解**：棘轮是「只进不退 + 渐进抬升」机制，本批目标是**装门 + 抬高最高风险编排器**，不是一次冲 80%；后续每批自然抬升地板。诚实记录当前值而非虚标。
- **[聚合 vs 每模块] 门是每模块而非单一聚合** → 与 review 字面「aggregate ratio」略有出入。**缓解**：jacoco 技术约束（D2）；每模块棘轮实为更强约束（任一模块回退即挂），聚合率仍在文档留痕。
- **[flaky 残留] 轮询等待仍有 5s 上限** → 若 `doFinally` 真的永不跑（产品 bug），轮询超时后断言 `currentTurn` 非空即失败——这正是我们**想**捕获的真失败，非 flaky。**缓解**：5s 对本地/CI 均宽裕；正常路径毫秒级清理。
- **[Windows/Linux 覆盖差] 地板按 Windows 实测** → Linux 上 POSIX 断言测试会**运行**（Windows 跳过），只增覆盖。**缓解**：Windows 值是保守下界，Linux 只会更高，棘轮不会误挂。
- **[jacoco 插桩] argLine 冲突** → 若未来某模块自定义 surefire `argLine` 而不含 `@{argLine}`，会丢插桩。**缓解**：当前无模块覆盖 argLine；如需，遵循 `@{argLine}` 占位约定。

## Migration Plan

- 纯增量、默认安全：`mvn test` 行为不变（surefire 被插桩，计数一致）；`mvn verify` 新增棘轮门（今天必过）+ failsafe（默认跳 `*IT`）。`mvn verify -Pit` 显式跑 `*IT`（需真凭据）。
- 证据复现：`mvn -q test` 后，聚合率 = 各 `*/target/site/jacoco/jacoco.csv` 的 `LINE_COVERED / (LINE_COVERED + LINE_MISSED)` 求和；每模块地板见上表。
- 回滚：删父 `pom.xml` 的 jacoco/failsafe 插件与 profile + 各模块 `jacoco.line.min` 属性即回到旧行为；回补单测与 flaky 修复无害保留。

## Open Questions

- 是否在后续批次引入**真聚合门**（聚合模块 + `merge`）以 review 字面对齐——本次记录不做（ROI 低，每模块棘轮已够）。
- `pig-agent-channel`/`onboarding`/`providers` 的离线可测面（部分纯逻辑或可 mock）——留后续批次评估，本次不强凑。
