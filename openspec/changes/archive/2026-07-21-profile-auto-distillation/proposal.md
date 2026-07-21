## Why

用户画像 `user-profile` 已经有完整的两条维护路径：即时确定性的 `updateProfile` 写工具，和一个**可选、默认关**的后台蒸馏（`ProfileConsolidationService` + `ModelProfileDistiller` + 可 mock 的 `ProfileDistiller` seam，把 `MEMORY.md` 里的稳定偏好蒸馏进去重后的 `USER.md`，已挂在 `TaskScheduler` 上）。缺的是 Hermes 式「**自动用户建模**」的临门一脚——助理越用越懂你，**无需**用户显式说「记住我叫 X」：首次就能从记忆里的姓名/偏好 **seed 身份**，之后后台持续精炼。

本 spec 是内核路线图 **Wave-2 的 M-E「画像自动蒸馏」**。机器已在，本 spec 只做三件事：**(1) 首次身份 seeding**（空画像时从 `MEMORY.md` 蒸馏 identity/偏好）；**(2) 隐私/节流加固**（写入前的确定性保守过滤、无可解析廉价模型时 no-op 降级、`SecretRedactor` 覆盖、容错回滚）；**(3) 就「后台蒸馏默认开」做承重 spike 并据结论定默认值**。

**承重红线是隐私/幻觉**：`USER.md` 被注入 system prompt 且每回合常驻，一条错误/敏感的画像字段是**持久、放大**的。故默认翻转必须以「强离线守卫充分兜住风险」为前提；spike 若判定离线兜不住，则**保持默认关、只交付机制**（默认翻转延后到有 live 模型时一行开启）——安全优先，不硬翻默认。

## What Changes

- **首次身份 seeding（`io.pigagent.core.profile`）**：当画像为空/缺失且后台蒸馏运行时，蒸馏 seam 从 `MEMORY.md` 蒸馏出**高置信**的身份（姓名/称呼）与 durable 偏好（语言/输出风格/技术偏好/工作方式）写入 `USER.md`。复用现有 `ProfileDistiller.distill(currentProfile, memory)` 契约（`currentProfile` 为空即 seeding 分支，seam 文档已预留），不新造引擎。
- **写入 `USER.md` 前的确定性保守过滤（新增纯逻辑守卫）**：蒸馏产出在落盘前，先经一个**无模型、可离线测**的保守过滤——只保留形如 `- **<Field>**: <value>` 且字段名落在**保守 identity/preference 白名单**内的行，丢弃自由文本/不合规行；再叠加既有 `SecretRedactor`。这是隐私/幻觉加固的确定性前置门。
- **无可解析廉价模型 → no-op 降级（规范化既有行为）**：当 `consolidation.model-id` → `memory.model-id` → 主模型都解析不到时，蒸馏 MUST 为 no-op（不写、不崩），把今日「seam 返回 null 即中止」提升为规范保证。
- **容错/脱敏加固（复用不改语义）**：蒸馏/读记忆失败 MUST 记 warn 并吞掉、`USER.md` 保持原样；所有写入经 `SecretRedactor` + `0600`（`UserProfileStore` 既有）。
- **spike 决定 `user-profile.consolidation.enabled` 默认值**：见 `design.md §Spike`。**本 spec 的默认结论：保持默认关（`false`），只交付上述 seeding + 加固机制**——离线已交付强守卫（脱敏/保守过滤/no-op/容错），但**语义抽取正确性**与**非密形隐私内容**离线无法充分验证，属 live-model 关注，默认翻转延后到集成测试通过后**一行**开启（`ProfileConsolidationConfig.enabled` 缺省值 + 新工作区种子）。

**非破坏**：默认关时行为逐字节等于今日（无蒸馏、无调度、无 LLM 调用）；启用后仅新增 seeding 分支 + 落盘前的保守过滤，既有节流/容错/`USER.md` 注入/`updateProfile` 均不变。

## Capabilities

### New Capabilities
<!-- 无。本能力是既有 user-profile 之上的加固叠加，不引入新 capability。 -->

### Modified Capabilities
- `user-profile`: 后台画像蒸馏能力**加固**——新增「首次身份 seeding（从 `MEMORY.md`）」与「写入 `USER.md` 前的确定性保守过滤（隐私/幻觉护栏）」两条要求，并强化既有「可选的后台画像蒸馏」要求（无可解析廉价模型 → no-op；产出经保守过滤 + 脱敏后落盘；默认仍关，spike 结论）。`USER.md` 文件/注入/`updateProfile`/开关与向后兼容诸要求**不变**。

## Impact

- **代码（产品代码本 spec 不写，仅登记落点）**：`pig-agent-core`（`io.pigagent.core.profile`：新增纯逻辑保守过滤器如 `DistilledProfileGuard`；`ProfileConsolidationService` 在写盘前经过滤器；`ModelProfileDistiller`/`DISTILL_PROMPT` 强化 seeding 与「只输出高置信 identity/preference」的约束）；`pig-agent-cli`（`AgentBootstrap`：无可解析廉价模型时不调度/no-op；默认值由配置决定）；`pig-agent-config`（`ProfileConsolidationConfig` 默认值——**本 spec 结论：保持 `enabled=false`**）。
- **不改**：`USER.md` 存储/注入（`UserProfileStore`/`UserProfileContextMiddleware`）、`updateProfile` 工具、`pa-memory-native` 的 `MEMORY.md` 写路径（只读作为蒸馏源）、`SecretRedactor`（复用）、`TaskScheduler`（复用既有调度）。
- **交叉依赖**（详见 `design.md`）：与记忆线——蒸馏源是 `MEMORY.md`（声明性事实），画像产出是**身份/偏好**；程序性「怎么做」归 `SKILL.md`，不在此。复用现有 `TaskScheduler` + `ProfileDistiller` seam（廉价模型经 `memory.model-id`→主模型回落）。凭据硬化沿用（`SecretRedactor` + `0600`）。
- **测试**：离线单测——**Group 1 承重 spike**：证明离线守卫（脱敏/保守过滤/no-op/容错）能兜住的边界，并据此定默认值。其后：保守过滤（只留白名单字段行、丢自由文本、脱敏叠加）；seeding（空画像从记忆 seed、无可 seed 内容不写）；no-model no-op；蒸馏失败 `USER.md` 原样；默认关逐字节等于今日。**真实蒸馏/seeding 质量（LLM 抽取/去重 + 从 `MEMORY.md` seed 姓名）→ live `*IT`（`/ls:itest`）延后**，也是默认翻转的前置门。
- **文档**：`CLAUDE.md` `user-profile` 段落补「自动蒸馏（seeding + 保守过滤 + no-op + 默认仍关的 spike 结论）」；配置段 `user-profile.consolidation` 同步。
