## ADDED Requirements

### Requirement: 覆盖率度量
构建 MUST 用 `jacoco-maven-plugin` 度量单元测试的代码覆盖率：`prepare-agent` MUST 插桩 surefire 测试 JVM，`report` MUST 在每个模块生成行覆盖率报告（html/csv/xml）。插桩 MUST NOT 改变测试计数或结果（`mvn test` 前后测试数一致）。无测试的模块（无 `jacoco.exec`）MUST 被容错跳过而非使构建失败。

#### Scenario: mvn test 生成每模块覆盖率报告
- **WHEN** 执行 `mvn test`
- **THEN** 每个含测试的模块在 `target/site/jacoco/` 生成 `jacoco.csv`/`jacoco.xml`/`index.html`，且测试计数与未插桩时相同（0 失败）

#### Scenario: 无测试模块不使构建失败
- **WHEN** 一个模块没有任何测试（无 `jacoco.exec`）
- **THEN** 该模块的 jacoco 度量与门检查被跳过（"Skipping JaCoCo execution due to missing execution data file"），构建继续

### Requirement: 覆盖率棘轮门
构建 MUST 用 `jacoco:check`（绑定 `verify` 阶段）对每个有可测面的模块施加一条**棘轮基线**规则（`BUNDLE`/`LINE`/`COVEREDRATIO`），其最低值 MUST 设为**不高于该模块当前实测行覆盖率**（选定为 `floor(current) − 0.02`），使覆盖率**只进不退**且门在设定当日必过。低于地板 MUST 使 `mvn verify` 失败（BUILD FAILURE）。无离线可测面的模块地板 MUST 为 `0.00`（不阻断）。地板值 MUST 记录在变更的 `design.md`。

#### Scenario: mvn verify 施加棘轮门且今天通过
- **WHEN** 在当前代码上执行 `mvn verify`（无 profile）
- **THEN** 每个有覆盖率数据的模块 jacoco check 报告 "All coverage checks have been met"，BUILD SUCCESS

#### Scenario: 覆盖率跌破地板则构建失败
- **WHEN** 某模块的行覆盖率低于其配置地板（等价地，人为设一个高于当前的地板）
- **THEN** jacoco check 报告 "Rule violated ... lines covered ratio is X, but expected minimum is Y" 并使构建 FAILURE

#### Scenario: 地板不追求 80%，只锁当前水位
- **WHEN** 选定某模块的地板
- **THEN** 地板 = `floor(该模块当前实测行覆盖率) − 0.02`（而非 0.80），既锁死回退又不在当日阻断

### Requirement: 集成测试可选进入 verify
`*IT` 集成测试 MUST 由 `maven-failsafe-plugin`（`integration-test` + `verify` goals）承载，且因其依赖真实 API 凭据与 `~/.pig-agent/workspace/models.json` 而 **默认跳过**（`skipITs` 默认 `true`）。无凭据的默认 `mvn verify` MUST 通过且 MUST NOT 运行任何 `*IT`。系统 MUST 提供显式启用途径（`it` profile，或 `-DskipITs=false`）在具备凭据的环境运行 `*IT`。

#### Scenario: 默认 mvn verify 不跑 *IT 且通过
- **WHEN** 在无 API 凭据的环境执行 `mvn verify`（无 profile）
- **THEN** failsafe 报告 "Tests are skipped."，无 `*IT` 运行，BUILD SUCCESS

#### Scenario: -Pit 显式启用集成测试
- **WHEN** 执行 `mvn verify -Pit`（或 `-DskipITs=false`）
- **THEN** failsafe 运行 `*IT`（在具备真实凭据 + `models.json` 的环境）

### Requirement: 核心编排器离线单测覆盖
最高风险的运行时编排器 MUST 具备**离线、确定性、零网络**的单元测试：会话生命周期编排（`SessionManager`）、模型运行时切换路径（`ModelManager` 的 `ensureModel`/`attachChannel`）、权限门 adapter（`ToolPermissionHook`）。这些测试 MUST 用 `@TempDir`/fake/mock/stub 隔离外部依赖，MUST NOT 调用真实模型或网络，并抬高对应模块的棘轮地板。

#### Scenario: SessionManager 生命周期离线可测
- **WHEN** 运行 `SessionManagerTest`
- **THEN** 用真 `JsonSession`/`FileSystemSessionRepository`@TempDir + stub 模型 + 记录式 `AgentModelSwitcher` 覆盖 initialize/activate/create/fork/delete/rename/memory 开关/lineage，全绿且无网络

#### Scenario: ModelManager 切换路径离线可测
- **WHEN** 运行 `ModelManagerSwitchTest`
- **THEN** 用假 `ModelProtocol`→stub 模型覆盖 `ensureModel` 切换/no-op/默认回退/未知 target/构建失败保号/未 attach，以及 `attachChannel` 联动重建，全绿且无网络

#### Scenario: ToolPermissionHook adapter 离线可测
- **WHEN** 运行 `ToolPermissionHookTest`
- **THEN** 直构 `PreActingEvent` 覆盖否决改写哨兵（保 id）/放行不改写/mode-override 优先与回退/channel fail-closed/denialListener 通知，全绿且无网络

### Requirement: 门与度量自身确定
覆盖率度量与质量门所依赖的测试套件 MUST 在并行构建（`mvn -T 1C test`）下确定通过，不得有时序 flaky。特别地，`AgentKernelInterruptTest.chat_normalTermination_clearsTurn` MUST 确定性地等待 turn 真正清理（异步 `doFinally` 完成）后再断言，而非依赖 `block()` 返回与 turn 清理之间的偶然时序。

#### Scenario: 并行构建下无 flaky
- **WHEN** 反复执行 `mvn -T 1C test`
- **THEN** 全套测试稳定通过（含 `AgentKernelInterruptTest`），无偶发失败
