## Why

生产就绪度审查（`docs/review/production-readiness-2026-07-14.md`）判定 P2 工程化阻断项 #10–#11：**80% 覆盖率是纸面规约、从未度量**（无 jacoco），核心编排器（`SessionManager` / `ModelManager` 切换路径 / `ToolPermissionHook` adapter）仅被要真 key 的网络 `*IT` 覆盖；而那 4 个 `*IT` 被默认 `mvn test` 排除、且**没配 failsafe → `mvn verify` 也不会跑**，不在任何自动门内。本项目当前无本地量化门，覆盖率可以静默滑坡。本变更把覆盖率从口号变成 **可持续演进的保障**：接入 jacoco 度量 + **棘轮（ratchet）门**（锁死当前水位、只进不退，但今天不会挂），补齐 failsafe 让 `*IT` 可选进入 `mvn verify`，并回补三个最高风险编排器的离线单测抬高棘轮基线。同时修掉本次审查发现的一个 **并行构建下偶发 flaky 单测**，让度量与门本身确定、可信。

## What Changes

- **jacoco 度量 + 棘轮基线门（非 80%）**：父 `pom.xml` 接入 `jacoco-maven-plugin`（版本属性 + `<build><plugins>`）——绑定 `prepare-agent`（surefire 被插桩）与 `report`（每模块生成 html/csv/xml）。度量当前水位后，为每个有可测面的模块设一条 `check` 规则，最低行覆盖率 = **该模块当前实测值向下取整后再减 0.02 的地板**（floor ≤ current，锁死只进不退但今天必过）；无离线可测面的模块（`pig-agent-cli`/`pig-agent-channel`/`pig-agent-onboarding`/`pig-agent-providers`，其编排/网络代码仅 `*IT` 覆盖）floor=0.00（不阻断、待后续批次补）。`check` 目标绑定到 `verify`，`mvn verify` 即强制执行。选定的地板值写进 `design.md`/`tasks.md` 与本 spec。
- **maven-failsafe-plugin 让 `*IT` 可跑于 `mvn verify`**：接入 failsafe（`integration-test` + `verify` 目标）。因 `*IT` 需真 API key + `~/.pig-agent/workspace/models.json`，**默认跳过**（`skipITs` 默认 true）；`-Pit` profile（或 `-DskipITs=false`）显式启用。**无凭据的默认 `mvn verify` 必须不跑 `*IT` 且通过**（本变更验证之）。
- **回补最高风险编排器的离线单测**：`SessionManager`（会话生命周期 activate/create/fork/delete/rename/memory 开关，用 `@TempDir` + `JsonSession` + fake `AgentModelSwitcher`，零网络）、`ModelManager` 切换路径（`ensureModel` 切换/no-op/失败保号 + `attachChannel` 联动，用 fake `ModelProtocol` 返回 mock `Model`）、`ToolPermissionHook` adapter（`PreActingEvent` 否决改写为哨兵、mode-override、channel、denialListener、放行不改写，构造 `PreActingEvent` 直测）。每组抬高对应模块的棘轮地板。
- **修复已知 flaky**：`AgentKernelInterruptTest.chat_normalTermination_clearsTurn`（`pig-agent-core`）在并行构建下 race——`kernel.chat(...).collectList().block()` 返回后，`doFinally` 的 `interrupts.end(...)` 可能尚未执行，`interruptCurrent()` 偶发读到未清理的 turn。改为**确定性等待 turn 真正清理**（短轮询断言），不弱化其验证语义。

## Capabilities

### New Capabilities
- `ci-quality-gate`: 工程化质量门——jacoco 覆盖率**度量** + **棘轮基线 check**（每模块 floor ≤ 当前实测，锁死只进不退、今天必过），绑定 `verify` 强制执行；failsafe 让 `*IT` **可选**进入 `mvn verify`（默认跳过，`-Pit` 启用），保证无凭据默认 `verify` 仍绿。作为 CI/本地可持续演进的自动门。

### Modified Capabilities
<!-- 无：本变更为正交新增的工程化度量层，不改任何现有能力的 requirement；回补的单测与 flaky 修复不改产品行为。 -->

## Impact

- **构建/POM**：父 `pom.xml`（jacoco 版本属性 + `prepare-agent`/`report`/`check` 执行 + failsafe + `it` profile）；各模块 `pom.xml`（覆盖 `jacoco.line.min` 地板属性，无可测面模块设 0.00）。
- **测试**：新增 `SessionManagerTest`（session）、`ModelManagerSwitchTest`（model）、`ToolPermissionHookTest`（tools）离线单测；`AgentKernelInterruptTest` flaky 修复（core）。**纯增测 + 修测，不改产品源。**
- **行为**：`mvn verify` 现在会强制覆盖率棘轮门（今天必过）；默认 `mvn verify` 仍不跑 `*IT`（无凭据可跑）；`mvn verify -Pit` 才跑 `*IT`。`mvn test` 行为不变（surefire 现被 jacoco 插桩，计数与产出一致）。
- **依赖/前提**：jacoco-maven-plugin（新增 build 插件，无运行时依赖）；failsafe（build 插件）。无新运行时依赖、无 API 变更。
- **不回归**：既有 300+ 单测全绿、离线可跑；jacoco 插桩不改测试计数；flaky 修复只增确定性等待、不弱化断言。
- **可测性诚实边界**：`AgentBootstrap` 等 wiring-heavy 装配类**不做离线单测**（构造需真 workspace/模型/网络，得不偿失），其对应模块 floor=0.00，明确记录待后续批次或 `*IT` 覆盖，不伪造覆盖。
