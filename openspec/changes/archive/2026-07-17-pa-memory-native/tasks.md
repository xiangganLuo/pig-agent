## 0. 前置：javap 验证 2.0 记忆 API（P0 阻塞，编码前必做）

- [x] 0.1 定位真实 2.0 jar（本机 m2 **已有** `agentscope-harness-2.0.0.jar`+sources、`agentscope-core-2.0.0.jar`+sources），确认可解析。
- [x] 0.2 `javap` 确认 `HarnessAgent.Builder`：`memory(MemoryConfig)`、`disableMemoryHooks()`/`disableMemoryTools()` 的真实签名与语义（build 内 `memoryModel!=null && !disableMemoryHooks` 装 flush/consolidation；`!disableMemoryTools` 注册记忆工具）。
- [x] 0.3 `javap` 确认 `MemoryConfig`（builder 字段：`model`/`flushPrompt`/`consolidationPrompt`/`consolidationMaxTokens`/`consolidationMinGap`/`dailyFileRetentionDays`/`sessionRetentionDays`/`flushTrigger`）与默认值（4000/30min/90d/180d/always）——与文档一致。
- [x] 0.4 `javap` 确认记忆工具名与签名：`memory_search`(readOnly)/`memory_get`/`memory_save`/`session_search`(+`session_list`/`session_history`)——纯文件 IO、非向量。
- [x] 0.5 确认 `MemoryConfig.model(Model)` 接受 `Model` 实例（pig 注入 Doubao lite），落文档到设计 §附录 B。

## 1. 采用原生两层记忆（接线）

- [x] 1.1 `PigAgent.Builder`：`memory(MemoryConfig)` 非 null → 保留原生记忆钩子/工具（不再 `disableMemoryHooks/Tools`）+ 加 `NativeMemoryContextMiddleware`；null → 禁用（退役前行为）。`disableCompaction()` **保留**（A5 差异化）。
- [x] 1.2 `AgentBootstrap`：`buildMemoryConfig(...)` 构建 `MemoryConfig`（工作区目录、`flushTrigger`/consolidation 由配置、`model` = 廉价模型）；`memoryConfigSupplier` 注入 interactive/channel track；autonomous 保持无记忆（隔离）。
- [x] 1.3 廉价模型接线：`ModelManager.modelFor(memory.model-id)` 提供 Doubao lite `Model` 给 `MemoryConfig.model(...)`（空/不可解析 → 回退主模型）。
- [x] 1.4 注入策略（OD2-A）：pig 自有 `NativeMemoryContextMiddleware.onSystemPrompt` 注入 `MEMORY.md`（**不启用原生 `WorkspaceContextMiddleware`**——其指引引用 pig 不注册的原生工具名；见设计附录 B5）。
- [x] 1.5 `/memory on|off` 映射到 `memory-enabled` 配置 + 重建 interactive/channel agent（对用户可见语义不变；关=不注入不 flush）。

## 2. 退役自建 + 数据迁移

- [x] 2.1 退役 `CompositeLongTermMemory` + `FileSystemLongTermMemory`（含 `MIN_TEXT_LENGTH`）+ `CachingLongTermMemory`：直接删除（无别处依赖 `LongTermMemory`，无需 shim）。
- [x] 2.2 退役 A4 抽取 `memory/extraction/*`（10 类 + 10 测试）+ `EphemeralMemoryMiddleware`；`SessionMemoryFactory` 删除；配置 `memory.extraction` 块删除。
- [x] 2.3 `SessionManager` 不再 `setSessionMemory`/temp 记忆层（会话层退役）；compression-lineage/session 既有单测回归通过。
- [x] 2.4 旧数据迁移（OD9/D8）：`MemoryMigration` 一次性把 `context/memory.md` 幂等迁入 `MEMORY.md`（带 `.bak`、`.migrated` 标记、失败降级只读）；会话层不迁。
- [x] 2.5 `CachingLongTermMemory` 随自建退役移除（原生已有自己的读路径）。

## 3. 单测（离线回归；确定性逻辑，模型走 mock/seam）

- [x] 3.1 跨会话：`CrossSessionMemoryTest` 迁移到原生栈——会话 A `memory_save`、`/session new`（不同 RuntimeContext）、会话 B `memory_search` 召回（GREEN，2 测试）。
- [x] 3.2 短事实：一条 <20 字的用户事实经原生 `memory_save`/`memory_search` 可被记住（不再被字数过滤丢弃）——`CrossSessionMemoryTest.shortFact_notDroppedByLengthFilter`。
- [x] 3.3 注入：`NativeMemoryContextMiddlewareTest` 断言 `MEMORY.md` 进入 system prompt；**会话内**（文件未变）字节稳定；缺失/空白 → identity。
- [x] 3.4 `/memory off`：`SessionManagerTest.setMemoryEnabled_togglesConfig_andTriggersRebuild` 断言 config 翻转 + 重建钩子触发；`HarnessAgentWrapTest.memoryDisabledByDefault_noNativeMemoryMiddleware`。
- [x] 3.5 迁移：`MemoryMigrationTest` 幂等 + `.bak` + 追加不覆盖 + 失败/空档 no-op（5 测试）。
- [x] 3.6 退役兼容：session/compression-lineage 既有单测回归通过（`SessionManagerTest` 21 测试全绿）。
- [x] 3.7 覆盖率：变更模块 ≥ jacoco floor（`mvn verify` 门）；whole reactor `mvn test` 1062 测试全绿。

## 4. 集成测试（真模型 `*IT`，外环）

- [ ] 4.1 `PaMemoryNativeIT`（真 Doubao）：会话 A 说名字 → `/session new` → 会话 B 召回；断言 `MEMORY.md`/`memory/*.md` 实际落盘（延后 `/ls:itest`——需真模型跑 flush/consolidation）。
- [ ] 4.2 flush/consolidation 真实运行：多回合后日志层增长、consolidation 后 `MEMORY.md` 去重合并；`memory_search`/`session_search` 命中（延后 `/ls:itest`）。
- [ ] 4.3 廉价模型：flush/consolidation 走 Doubao lite、主推理走主模型（延后 `/ls:itest`）。

## 5. 验收 + 文档

- [x] 5.1 `mvn test` 绿（whole reactor 1062/1062）；`mvn -pl pig-agent-cli -am compile` 绿；`mvn verify`（jacoco floor）——见提交说明。`*IT` 走 `-Pit`（第 4 组，延后）。
- [x] 5.2 `CLAUDE.md` 两层记忆段落改写为「采用 2.0 原生两层记忆 + 廉价模型 flush/consolidation，退役自建两层 + A4」；模块表 + 配置段同步。
- [x] 5.3 归档时（`/ls:archive`）同步消解主 spec：`prefix-cache-context`（注入改原生）、`memory-extraction`（A4 退役）、`context-memory-efficiency`（记忆缓存消解）。
