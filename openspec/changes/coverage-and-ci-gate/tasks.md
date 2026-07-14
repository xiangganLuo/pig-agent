## 1. jacoco 度量接入 + 基线测量（先做）

- [x] 1.1 父 `pom.xml` 增 `jacoco.version=0.8.12`、`failsafe.version=3.5.2`、`skipITs=true`、`jacoco.line.min=0.00` 属性
- [x] 1.2 父 `pom.xml` `<build><plugins>` 增 `jacoco-maven-plugin`：`prepare-agent`（插桩 surefire）+ `report`（phase=test，每模块 html/csv/xml）
- [x] 1.3 `mvn -q test` 跑通、生成 9 个模块 `jacoco.exec` + `jacoco.csv`；聚合行覆盖率**基线 0.4913**（covered 1916/total 3900）；测试计数 323/0/0/2（插桩不改计数）

## 2. 修复已知 flaky（core，确定性化）

- [x] 2.1 `AgentKernelInterruptTest.chat_normalTermination_clearsTurn`：`collectList().block()` 后**短轮询**共享 `InterruptController.currentTurn()`（无副作用读、`Thread.onSpinWait`、≤5s）直到清空，再断言 `interruptCurrent()` 为 false——语义不变、消除 `doFinally` 滞后 race
- [x] 2.2 `AgentKernelInterruptTest` 3/3 绿；`mvn -q -T 1C test -pl pig-agent-core -Dtest=AgentKernelInterruptTest` 连跑 3 次均 BUILD SUCCESS

## 3. 回补 SessionManager 离线单测（session）

- [x] 3.1 `SessionManagerTest`（22 测试）：真 `JsonSession`+`FileSystemSessionRepository`+`ConfigurationManager`@TempDir、stub `Model`、记录式 `AgentModelSwitcher`；覆盖 initialize（空/配置/最近活跃）、activate（ensureModel 握手 + 落盘 + 保存上一会话）、createBlank、bindCurrentSessionModel（含无当前 no-op）、rename（含空白忽略）、noteUserMessage（默认名改写 vs 非默认保留）、delete（有替补 / 建空）、fork（拷模型绑定 + temp-memory / 无当前建空）、setMemoryEnabled、clearConversation（删/留 temp）、lineageOf（未知/null/已知）、list 排序
- [x] 3.2 22/22 绿；session 行覆盖率 0.3874 → **0.9144**

## 4. 回补 ModelManager 切换路径离线单测（model）

- [x] 4.1 `ModelManagerSwitchTest`（8 测试）：假 `ModelProtocol`→stub `Model`、mock `ModelStore`、真 `AgentFactory`+`AgentHolder`；覆盖 `ensureModel` 切换（重建 + current 更新）、同模型 no-op、null→default、未知 target no-op、**build 失败保号**（throwing 协议、不抛、保旧 agent+modelId）、未 attach no-op、`attachChannel` 联动重建、`modelFor` 经协议构建
- [x] 4.2 8/8 绿；model 行覆盖率 0.5802 → **0.7786**

## 5. 回补 ToolPermissionHook adapter 离线单测（tools）

- [x] 5.1 `ToolPermissionHookTest`（10 测试）：直构 `PreActingEvent(mock(Agent), null, ToolUseBlock)` 驱动 hook；覆盖 priority=0、plan 否决改写哨兵（保 id）、bypass 放行不改写、哨兵不二次处理、null toolUse no-op、modeOverride 优先 / null 回退全局、channel EXEC 无 confirmer fail-closed、denialListener 否决通知 / 放行不通知
- [x] 5.2 10/10 绿；tools 行覆盖率 0.6017 → **0.6497**（纯 resolver 判定仍由既有 `PermissionResolverTest` 覆盖，不重复）

## 6. 设定棘轮基线门（每模块 floor = floor(current) − 0.02）

- [x] 6.1 父 `pom.xml` 增 `jacoco:check` 执行（phase=verify，`BUNDLE`/`LINE`/`COVEREDRATIO`，`minimum=${jacoco.line.min}`）
- [x] 6.2 各模块 `pom.xml` `<properties>` 覆盖 `jacoco.line.min`：config=0.84、core=0.69、tools=0.62、mcp=0.41、model=0.75、session=0.89、workspace=0.71、task=0.41、cli=0.27；无测试模块（channel/onboarding/providers）保持父默认 0.00（`check` 因无 `jacoco.exec` 自动跳过）
- [x] 6.3 `mvn verify` 全 9 个有数据模块 jacoco check = MET；无 rule 违反；BUILD SUCCESS
- [x] 6.4 反证棘轮生效：`mvn verify -pl pig-agent-workspace -Djacoco.line.min=0.999` → `Rule violated ... ratio is 0.738, but expected minimum is 0.999` → BUILD FAILURE（门确实拦得住回退）

## 7. failsafe：*IT 可选进入 verify（默认跳过）

- [x] 7.1 父 `pom.xml` 增 `maven-failsafe-plugin`（`integration-test`+`verify` goals，`<skipITs>${skipITs}</skipITs>`）
- [x] 7.2 父 `<profiles>` 增 `it` profile（`skipITs=false`）
- [x] 7.3 默认 `mvn verify`（无 profile）：failsafe「Tests are skipped.」、无 `*IT` 运行、BUILD SUCCESS（无凭据可过）

## 8. 集成与回归

- [x] 8.1 全量 `mvn test`（单线程）：**363 测试 / 0 失败 / 0 错误 / 2 skip**（较接入前 323 增 40：session 22 + model 8 + tools 10）
- [x] 8.2 全量 `mvn verify`（无 profile）：BUILD SUCCESS，jacoco 棘轮门全 MET，`*IT` 跳过
- [x] 8.3 聚合行覆盖率 0.4913 → **0.5364**（covered 2092/total 3900）
- [x] 8.4 未改任何产品源；仅增测 + 修测 + 构建配置（父/各模块 `pom.xml`）；SLF4J 纪律不受影响（未引入 System.out）

## 9. 诚实边界与文档

- [x] 9.1 `AgentBootstrap` 等 wiring-heavy 装配类**不做离线单测**（构造需真 workspace/模型/网络）——cli floor=0.27 覆盖其面，`design.md` 落实追踪表标注「延后，`*IT`/后续批次覆盖」，不伪造
- [x] 9.2 `design.md` 记录聚合率基线/终值 + 每模块地板表 + D2（jacoco 无原生跨模块 aggregate-check，故用每模块棘轮）
- [ ] 9.3 人工冒烟（可选，留 `/ls:itest`）：`mvn verify -Pit` 在有真凭据环境跑 `*IT`——本地无凭据不跑，核心门逻辑已由 8.1/8.2/6.4 离线验证
