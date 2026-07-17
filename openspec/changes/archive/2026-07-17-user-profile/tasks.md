## 1. 配置块（`pig-agent-config`）

- [x] 1.1 `PigAgentConfig` 新增 `user-profile` 块 `UserProfileConfig`（`enabled` 默认 true、`path` 默认 `USER.md`、`max-chars` 默认 4000）+ 嵌套 `ProfileConsolidationConfig`（`enabled` 默认 false、`min-gap-minutes` 默认 60、`model-id` 默认空）；getter/setter（null-tolerant）。
- [x] 1.2 `UserProfileConfigTest`：默认值、YAML 反序列化、缺块用默认、null setter 容错（5 测试）。

## 2. 画像存储 + 注入（`pig-agent-core.profile`）

- [x] 2.1 `UserProfileStore`：`read(maxChars)`（有界、凭据脱敏、缺失→空、不抛）、`readFull()`、`updateField(field,value)`（确定性 set/merge、字段大小写不敏感、凭据脱敏、换行折叠、原子写、容错）、`write(body)`（整文件重写，consolidation 用）。凭据脱敏用核心内新增 `SecretRedactor`（core 不能依赖 tools 的 CredentialSanitizer）。
- [x] 2.2 `UserProfileContextMiddleware implements MiddlewareBase`：`onSystemPrompt` 自门控（`enabled` supplier → 禁用恒等）+ 注入（HEADER + 有界画像）；缺失/空 → 恒等；无状态可复用；位于 memory 中间件之前。
- [x] 2.3 `ProfileDistiller`（函数式 seam）+ `ProfileConsolidationService`（节流 `Clock` 可注入 + 读 MEMORY.md/USER.md → distill → store 写 + 容错）+ `ModelProfileDistiller`（live，throwaway agent on 廉价模型，镜像 `CompressionService.ModelSummarizer`）。
- [x] 2.4 单测：`UserProfileStoreTest`（10）、`UserProfileContextMiddlewareTest`（7）、`ProfileConsolidationServiceTest`（5）。

## 3. `updateProfile` 工具（`pig-agent-tools.profile`）

- [x] 3.1 `ToolContext` 加 `userProfileFile`(Path)+`userProfileEnabled`(BooleanSupplier) 访问器 + 构造重载（向后兼容）。
- [x] 3.2 `UserProfileTool`（`@Tool updateProfile(field,value)` → store.updateField；成功行/`{"error"}`；`implements ToolAvailability` 按 `enabled` 门控）。
- [x] 3.3 `ToolRiskClassifier`：`updateProfile` = WRITE。
- [x] 3.4 `UserProfileToolProvider`（SPI）+ `META-INF/services/io.pigagent.tool.spi.ToolProvider` 增一行；`userProfileFile==null` → 返回 null。
- [x] 3.5 单测：`UserProfileToolTest`（5：写/合并/`{"error"}`/禁用 availability 隐藏/provider 门控）、`ToolRiskClassifierTest` 增 `updateProfile`=WRITE 断言。

## 4. 接线（`pig-agent-cli` + `pig-agent-workspace`）

- [x] 4.1 `WorkspaceManager.getUserMd()` 便捷路径。
- [x] 4.2 `AgentBootstrap`：建 `UserProfileStore`（configured path）；一个共享 `UserProfileContextMiddleware`（enabled supplier + maxChars）加入 interactive + channel + peer 中间件链（在 memory 中间件之前）；把 `userProfileFile`+`userProfileEnabled` 注入 `ToolContext`。
- [x] 4.3 `AgentBootstrap`：`user-profile.consolidation.enabled` 时建 `ProfileConsolidationService`（廉价模型 = consolidation.model-id → memory.model-id → 主）+ 经 `TaskScheduler` 后台按 `min-gap` 调度；默认关 = 不调度。
- [x] 4.4 `WorkspaceManager.defaultAgentMd()` 增一处 `updateProfile` + 画像简述（仅新工作区，不覆盖既有）。

## 5. 单测跨会话 + 回归

- [x] 5.1 `UserProfileCrossSessionTest`：会话 A `updateProfile` 写名字 → 会话 B `UserProfileContextMiddleware.onSystemPrompt` 注入含该名字（同一 USER.md，镜像 `CrossSessionMemoryTest`）。
- [x] 5.2 whole reactor `mvn test` 全绿（报告数目 + 新增测试数）。

## 6. 集成测试（真模型 `*IT`，外环）

- [ ] 6.1 `UserProfileIT`（真廉价模型）：后台蒸馏把 `MEMORY.md` 里稳定的名字/偏好蒸馏进 `USER.md`（去重、种子身份）；断言 `USER.md` 实际落盘（延后 `/ls:itest`）。
- [ ] 6.2 `updateProfile` 真模型链路：用户说「叫我 X」→ agent 调 `updateProfile` → 下一会话注入画像含 X（延后 `/ls:itest`）。

## 7. 验收 + 文档

- [x] 7.1 `mvn -pl pig-agent-cli -am compile` 绿；`mvn test` whole reactor 绿（`*IT` 走 `-Pit`，第 6 组延后）。
- [x] 7.2 `CLAUDE.md` 新增「用户画像 `USER.md`」段落 + 配置段 `user-profile` 同步；模块表 `pig-agent-core`/`pig-agent-tools` key types 补 profile 类。
- [x] 7.3 `openspec validate user-profile --strict` 通过；归档时（`/ls:archive`）同步主 spec → `openspec/specs/user-profile/`。
