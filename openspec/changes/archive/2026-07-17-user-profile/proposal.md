## Why

pig 现在**记得住事实**（`pa-memory-native` 的工作区级 `MEMORY.md` 跨会话），但缺一个**专门的用户画像**：`MEMORY.md` 是通用事实日志，把「你是谁 / 你怎么称呼 / 你长期的偏好与工作方式」混在里面，检索/注入时既不稳定也不聚焦。行业蓝本里 Hermes 用一个独立的 **`USER.md`**（+ Honcho 辩证建模）显著更「懂你」——「专门的用户画像」比「混在通用事实里」更能个性化。

本 spec 是「个人助理核心」拆分的**第 2 个 spec**（设计事实源 `docs/design/personal-assistant-core-design.md` §6.2 / §9），**依赖 #1 `pa-memory-native`（已归档、在 main）**——复用其记忆基座与 system-prompt 注入位。用户已批准 **OD4-A：用户画像形态 = `USER.md` Markdown 文件**（结构化存储 / 外接 Honcho 为未来，非本 spec）。

## What Changes

- **`USER.md` 用户画像文件**（工作区根，路径可配 `user-profile.path`，默认 `USER.md`）：一份**策展的、结构化**画像——身份（名字、如何称呼你）、长期偏好（语言、输出风格、技术偏好）、工作方式，**区别于 `MEMORY.md`（事实日志）**。本地 Markdown、人可读、可手编、可版本化（Hermes 先例，贴合 pig 本地优先）。
- **稳定注入 system prompt**：pig 自有的 `UserProfileContextMiddleware`（`onSystemPrompt` 变换 hook，`pa-memory-native` 的 `NativeMemoryContextMiddleware` 的**兄弟**）把 `USER.md`（**有大小上限、凭据脱敏**）注入 system prompt，位于 `MEMORY.md` 之前——让助理「知道你是谁」。`USER.md` 不变则注入字节稳定（前缀缓存友好），与记忆同构。
- **`updateProfile` @Tool**（`pig-agent-tools`，`ToolRiskClassifier` 分类 **WRITE**，`{"error"}` 契约）：让 agent 在确认一条偏好/身份后**即时**写入画像（如用户说「叫我 X」/「以后都用中文回」）。设置/合并一个画像字段（存在即替换、不存在即追加），**确定性、无需模型**；凭据脱敏。经权限体系治理，`ToolAvailability` 按 `user-profile.enabled` 门控（关闭时从模型 schema 隐藏）。
- **可选的后台画像蒸馏（consolidation）**：一个**可 mock 的 seam**（`ProfileDistiller`）+ 节流的 `ProfileConsolidationService`——周期性（节流、后台、**廉价模型**，复用 `pa-memory-native` 的廉价模型套路）把 `MEMORY.md`/近期事实里稳定的身份/偏好蒸馏进去重后的 `USER.md`。**默认关**（`user-profile.consolidation.enabled=false`，保守——真实蒸馏质量属 live-model，交 `/ls:itest`）；开启时经 `TaskScheduler` 后台按 `min-gap` 触发，不阻塞回合。
- **Config `user-profile` 块**：`enabled`（默认 **true**）、`path`、`max-chars`（注入上限）、`consolidation`（`enabled` 默认 false + `min-gap-minutes` + `model-id`）。全部可选、默认安全。
- **首次运行 / 迁移**：无 `USER.md` → 从空开始（容错，不种子模板）；可选地在首次 consolidation 从 `MEMORY.md` 的名字种子身份（属蒸馏 prompt，live-model，交 IT）。

**非破坏**：`user-profile.enabled=false` → 不注入 `USER.md`、不暴露 `updateProfile`、不蒸馏 = 逐字节等于本 spec 引入前的行为。默认开启只新增一处 system-prompt 注入（`USER.md` 存在时）+ 一个 WRITE 工具；不改记忆/权限/沙箱/渠道语义。

## Capabilities

### New Capabilities
- `user-profile`: 一份工作区级、策展的 **`USER.md` 用户画像**（身份/偏好/工作方式），稳定注入 system prompt（有上限、凭据安全），配一个确定性的 `updateProfile` 写工具与一个可选的、廉价模型驱动的后台画像蒸馏 seam——让个人助理「越用越懂你」，区别于 `MEMORY.md` 的通用事实日志。

### Modified Capabilities
<!-- 归档时（/ls:archive）在 openspec/specs/ 新增 user-profile 主 spec；不改写既有 pa-memory-native 主 spec（本能力是其上的独立叠加：复用注入位与廉价模型套路，但用独立文件 USER.md + 独立中间件 + 独立工具，互不覆盖）。 -->

## Impact

- **代码**：`pig-agent-core`（新 `profile/{UserProfileStore, UserProfileContextMiddleware, ProfileDistiller, ProfileConsolidationService, ModelProfileDistiller}`）；`pig-agent-tools`（新 `profile/UserProfileTool` + `spi/providers/UserProfileToolProvider` + `META-INF/services` 一行；`ToolRiskClassifier` 加 `updateProfile`=WRITE；`ToolContext` 加 `userProfileFile`+`userProfileEnabled`）；`pig-agent-config`（`UserProfileConfig` 块）；`pig-agent-cli`（`AgentBootstrap` 接线：构建 store + 把 profile 中间件加入 interactive/channel/peer 中间件链、把 profile 文件+enabled 注入 `ToolContext`、consolidation 开启时建 service + 后台调度）；`pig-agent-workspace`（`getUserMd()` 便捷路径，可选）。
- **不改**：`pa-memory-native` 记忆基座（复用不改）；A5 压缩；skills；权限/沙箱/渠道 fail-closed。
- **测试**：离线单测——`USER.md` 存在且启用→注入 / 缺失或禁用→不注入；`updateProfile` 确定性 set/merge（无 live model）；注入 `max-chars` 上限；凭据脱敏；**跨会话**（会话 A `updateProfile` 写的名字在会话 B 注入的画像里出现，镜像记忆修复）；consolidation seam 可 mock（fake distiller 确定性、节流、容错）。真实蒸馏质量 → `*IT`（`/ls:itest`）。
- **文档**：`CLAUDE.md` 新增「用户画像 `USER.md`」段落 + 配置段同步。
