## 0. 前置：javap 验证 2.0 记忆 API（P0 阻塞，编码前必做）

- [ ] 0.1 定位真实 2.0 jar（`agentscope-core`/`agentscope-harness` 2.0.0；本机 m2 缺失，需先 `mvn -pl pig-agent-cli -am dependency:resolve` 或联网拉取），确认可解析。
- [ ] 0.2 `javap` 确认 `HarnessAgent.Builder`：`memory(MemoryConfig)`、`disableMemoryHooks()`/`disableMemoryTools()` 的真实签名与语义。
- [ ] 0.3 `javap` 确认 `MemoryConfig`（builder 字段：`model`/`flushPrompt`/`consolidationPrompt`/`consolidationMaxTokens`/`consolidationMinGap`/`dailyFileRetentionDays`/`sessionRetentionDays`/`flushTrigger`）与默认值（以字节码/真 jar 为准，非文档）。
- [ ] 0.4 `javap` 确认记忆工具名与签名：`memory_search`/`memory_get`/`session_search`（关键词 vs 向量、返回形状）。
- [ ] 0.5 确认 `MemoryConfig.model(...)` 接受 `Model` 实例（pig 注入 Doubao lite），落文档到设计 §附录；若仅接受 `"provider:model"` 字符串则设计 pig→原生 model 适配。

## 1. 采用原生两层记忆（接线）

- [ ] 1.1 `PigAgent.Builder.build()`（含 subagent 分支）去掉 `disableMemoryHooks()/disableMemoryTools()`；新增 `memory(MemoryConfig)` 接线；`disableCompaction()` **保留**（A5 差异化）。
- [ ] 1.2 `AgentBootstrap`：构建 `MemoryConfig`（工作区目录、`flushTrigger` 由 `/memory` 开关驱动、`model` = 廉价模型），注入 interactive/channel/autonomous 各 track；渠道/自主沿用 fail-closed（无 confirmer 不改权限语义）。
- [ ] 1.3 廉价模型接线：`ModelManager` 提供 Doubao lite `Model` 实例给 `MemoryConfig.model(...)`（OD8 确认后）。
- [ ] 1.4 注入策略（OD2）：默认原生 system-prompt 注入；若 OD2-B 则保留 `EphemeralMemoryMiddleware` 读 `MEMORY.md` 注入 user 侧。
- [ ] 1.5 `/memory on|off` 语义映射到 `flushTrigger`/hooks 开关（对用户可见语义不变）。

## 2. 退役自建 + 数据迁移

- [ ] 2.1 退役 `CompositeLongTermMemory` 两层模型 + `FileSystemLongTermMemory`（含 `MIN_TEXT_LENGTH`）；如别处仍依赖 `LongTermMemory`，提供薄 shim（D9）否则删除。
- [ ] 2.2 退役 A4 抽取 `memory/extraction/*`（被原生 flush/consolidation 取代）；`SessionMemoryFactory` 不再包 `ExtractingLongTermMemory`；`AsyncMemoryExtractionScheduler` 清理关闭。
- [ ] 2.3 `SessionManager`/`SessionMemoryFactory` 不再 `setSessionMemory` 换会话层（会话层退役）；确认 compression-lineage 等仍工作。
- [ ] 2.4 旧数据迁移（OD9/D8）：一次性把 `workspace/context/memory.md` 幂等迁入 `MEMORY.md`（带 `.bak`、失败降级只读）；会话层不迁。
- [ ] 2.5 `CachingLongTermMemory` 随自建退役移除或改造（原生已有自己的读路径）。

## 3. 单测（离线回归；确定性逻辑，模型走 mock/seam）

- [ ] 3.1 跨会话：复用 `CrossSessionMemoryTest`——会话 A 陈述事实、`/session new`、会话 B 召回（第三条断言 GREEN）；迁移到新记忆栈的等价测试。
- [ ] 3.2 短事实：一条 <20 字的用户事实经 flush 路径可被记住（不再被字数过滤丢弃）——以 mock flush 抽取器断言无字数门。
- [ ] 3.3 注入：`MEMORY.md` 内容进入模型输入；**会话内**（consolidation 未触发）system prompt 字节稳定（R1）。
- [ ] 3.4 `/memory off`：不注入、flush no-op；`/memory on` 恢复。
- [ ] 3.5 迁移：旧 `memory.md` → `MEMORY.md` 幂等 + `.bak` + 失败降级只读。
- [ ] 3.6 退役兼容：shim（若有）转调正确；session/compression-lineage 既有单测回归通过。
- [ ] 3.7 覆盖率：变更模块 ≥80%（`mvn -pl pig-agent-core -am test` 绿）。

## 4. 集成测试（真模型 `*IT`，外环）

- [ ] 4.1 `PaMemoryNativeIT`（真 Doubao）：会话 A 说名字 → `/session new` → 会话 B 召回；断言 `MEMORY.md`/`memory/*.md` 实际落盘。
- [ ] 4.2 flush/consolidation 真实运行：多回合后日志层增长、consolidation 后 `MEMORY.md` 去重合并；`memory_search`/`session_search` 命中。
- [ ] 4.3 廉价模型：flush/consolidation 走 Doubao lite、主推理走主模型（各自模型正确）。

## 5. 验收 + 文档

- [ ] 5.1 `mvn verify` 绿（jacoco floor 不回退）；`mvn verify -Pit` 下 `*IT` 绿。
- [ ] 5.2 `CLAUDE.md` 两层记忆段落改写为「采用 2.0 原生两层记忆 + 廉价模型 flush/consolidation，退役自建两层 + A4」。
- [ ] 5.3 归档时（`/ls:archive`）同步消解主 spec：`prefix-cache-context`（注入改原生）、`memory-extraction`（A4 退役）、`context-memory-efficiency`（记忆缓存消解）。
</content>
