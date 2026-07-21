## Context

pig 用户画像现状（`user-profile` 已在 main，`io.pigagent.core.profile`）：

- **`USER.md` 存储** `UserProfileStore`：工作区级单文件，`# User Profile` 标题 + 若干 `- **<Field>**: <value>` 行；`updateField`（确定性 set/merge，同名替换、否则追加）、`write(body)`（整体覆盖，用于后台蒸馏）、`read(maxChars)`（有界 + `SecretRedactor` 脱敏，供注入）。所有写路径经 `SecretRedactor` + 原子写 + POSIX `0600`；缺失/不可读读作空，写失败记 warn 返 false（容错，绝不抛）。
- **注入** `UserProfileContextMiddleware`（`onSystemPrompt`）：把 `USER.md` 注入 system prompt（在 `MEMORY.md` 之前），有界、脱敏、字节稳定、自门控于 `user-profile.enabled`。
- **`updateProfile`** 工具（`pig-agent-tools`，WRITE）：即时确定性单字段落盘。
- **后台蒸馏**（**默认关**）：`ProfileConsolidationService`（`maybeConsolidate` 按 `minGap` 节流 / `consolidateNow` / 读 `MEMORY.md` + 当前 `USER.md` → `distiller.distill(current, memory)` → `store.write(distilled)`；`AtomicLong` 节流、`Clock` 可注入、任何失败记 warn 吞掉）+ `ModelProfileDistiller`（`DISTILL_PROMPT` 驱动的一次性廉价模型 agent，`distill` 返回 null/blank 即中止）+ `ProfileDistiller` seam（可 mock，文档已写明「`currentProfile` 可空——空即从 `memory` seed 身份」）。
- **接线** `AgentBootstrap`：仅当 `upCfg.getConsolidation().isEnabled()` 才解析廉价模型（`consolidation.model-id` → `memory.model-id` → 主模型）、构建 `ProfileConsolidationService` 并 `taskScheduler.schedule("user-profile:consolidation", cron, maybeConsolidate)`。
- **配置** `ProfileConsolidationConfig`：`enabled=false`（默认关）、`min-gap-minutes=60`、`model-id=""`。

**M-E 缺口**：Hermes 式「自动用户建模」的临门一脚——(1) **首次 seeding**：空画像时从 `MEMORY.md` 的姓名/偏好 seed 身份，无需用户显式 `updateProfile`；(2) **隐私/幻觉加固**：`USER.md` 被注入 system prompt 且每回合常驻，自动写入的一条错误/敏感字段是持久、放大的风险；(3) **默认开与否**：是否把 `consolidation.enabled` 翻成默认开。

## Goals / Non-Goals

**Goals**
- 首次身份 seeding：空/缺失画像 + 后台蒸馏运行时，从 `MEMORY.md` 蒸馏**高置信** identity（姓名/称呼）+ durable 偏好写入 `USER.md`。复用 `ProfileDistiller` seam 现有契约（`currentProfile` 空 = seeding 分支），不新造引擎。
- 写入 `USER.md` 前的**确定性保守过滤**（无模型、可离线测）：只放行白名单内的 identity/preference 字段行，丢弃自由文本/不合规行，叠加 `SecretRedactor`。
- 无可解析廉价模型 → 蒸馏 **no-op** 降级（不写、不崩）的规范化。
- 容错/脱敏加固：蒸馏/读记忆失败 → `USER.md` 原样、记 warn。
- 就「默认开」做承重 spike，据结论定 `consolidation.enabled` 缺省值。

**Non-Goals**
- **真实蒸馏/seeding 质量的离线验证**（LLM 抽取正确性、从 `MEMORY.md` 正确 seed 姓名、语义去重）——属 live-model，交 `/ls:itest`，也是默认翻转的前置门。
- 改 `USER.md` 存储/注入/`updateProfile`/`SecretRedactor`/`TaskScheduler`（复用不改）。
- 改 `pa-memory-native` 的 `MEMORY.md` 写路径（只读作蒸馏源）。
- 程序性「怎么做某事」的建模（归 `SKILL.md`，边界见 R4）。
- 语义级隐私分类/PII 检测（超出确定性守卫范畴；本 spec 的过滤是**结构性**保守门，非语义 PII 识别）。

## Spike（阻塞前置 Task 0 — 全案最关键决策：`consolidation.enabled` 是否默认开）

> **承重问题**：自动写 `USER.md`（被注入 system prompt、每回合常驻）有**幻觉/隐私误报**风险，且真实质量只能 live 模型验。判据：**离线能否充分兜住隐私/幻觉？** 能 → 默认开；不能 → **保持默认关、只交付机制**（默认翻转延后到 live 模型时一行开启）——安全优先，不硬翻默认。

**S1 — 离线确定性守卫能兜住什么（可离线证）**
- **`SecretRedactor` 覆盖**：所有写入（`UserProfileStore.write`）已强制脱敏——`sk-` 形密钥、`Bearer <token>`、`api_key=/token:/secret/password` 赋值都被掩码。→ 形似凭据的内容**结构上无法**落入 `USER.md`。可离线全证。
- **写入前的确定性保守过滤**（本 spec 新增纯逻辑 `DistilledProfileGuard`）：逐行解析蒸馏产出，**只保留**形如 `- **<Field>**: <value>` 且 `<Field>` 落在保守白名单（name / 称呼-how-to-address / language / output-style / tech-preference / working-style / timezone / role 等 identity∪preference 类目）内的行 + `# User Profile` 标题；**丢弃**任何自由文本段落、非白名单字段、超长值。→ 自由发挥的散文、越界字段**结构上无法**落盘。可离线全证。
- **无模型 no-op**：seam 返回 null（含 `ModelProfileDistiller` 在 `modelSupplier.get()==null` 时返回 null）→ `consolidateNow` 直接 return，不写。可离线全证。
- **容错回滚**：蒸馏抛错/读 `MEMORY.md` 出错 → 记 warn 吞掉、`USER.md` 原样。可离线全证。
- **节流**：`minGap` + 服务自节流。可离线全证。

**S2 — 离线确定性守卫兜不住什么（残余风险，仅 live 可验）**
- **语义幻觉**：过滤器只约束**结构/字段名**，不约束**语义真值**——一条 `- **Name**: <把记忆里第三方误当成用户的名字>` 是合规字段行，会通过过滤。「抽取是否抽对了人/对了偏好」只能 live 模型 + IT 验。
- **非密形隐私内容**：`SecretRedactor` 只识别凭据形；一条一次性敏感事实（健康/住址/雇主）若被模型当成「偏好/身份」蒸出，是合规字段行、非凭据形，过滤器与脱敏都**兜不住**，会被永久注入 system prompt。这类只能靠 prompt 纪律（保守抽取）+ live 验证。

**S3 — 放大效应**
`USER.md` 注入 system prompt 且**每回合常驻**（非一次性），错误/敏感字段的 blast radius 是持久、放大的（不像 `MEMORY.md` 检索式注入可被相关性稀释）。这抬高了「默认开」的门槛。

**Spike 净结论（默认关，只交付机制 —— 采纳任务给定的可接受结论）**：
离线能**充分兜住**的是「**凭据泄漏**」「**结构越界/自由发挥**」「**崩溃/破坏 `USER.md`**」「**无模型空转**」四类（S1，全部确定性、可离线证，本 spec 全部交付）；**兜不住**的是「**语义抽取正确性**」与「**非密形隐私内容**」两类（S2，属 live-model）。因这两类恰是「默认开」最危险的部分，且 `USER.md` 注入具放大效应（S3），故：
- **保持 `user-profile.consolidation.enabled` 默认 `false`**；本 spec 只交付 **seeding + 加固机制**（保守过滤 / no-op / 容错 / 脱敏 / seeding 分支）。
- **默认翻转延后到 live 模型时一行开启**：`/ls:itest` 用真廉价模型验证「seeding 抽取正确 + 无非密隐私误写」达标后，翻 `ProfileConsolidationConfig.enabled` 缺省值（+ 新工作区种子）即可，**无需再改机制**。
- 这是**安全优先**的诚实结论：机制到位、门槛留给 live 验证，不硬翻默认。

## Decisions

- **D1 —（首条，承重）`consolidation.enabled` 保持默认关，只交付机制。** 依据 Spike S1–S3：离线守卫兜住凭据/结构/崩溃/空转，但兜不住语义幻觉与非密形隐私，而 `USER.md` 注入具放大效应。默认翻转是**一行**改动（配置缺省值 + 新工作区种子），延后到 live-model IT 达标。**备选**：(a) 直接默认开——否决（离线无法兜住语义/隐私残余风险，违反安全优先）；(b) 加语义 PII 检测再默认开——否决（离线不可靠、超出确定性守卫范畴，属 YAGNI 的过度工程）。默认关 + 一行翻转 = 机制齐备、风险可控。
- **D2 — 首次 seeding 复用 `ProfileDistiller` seam 现有契约，不新造引擎。** `distill(currentProfile, memory)` 的 `currentProfile` 为空即 seeding 分支（seam 文档已预留该语义）；`ModelProfileDistiller.DISTILL_PROMPT` 强化「空画像时从 MEMORY seed 身份（姓名/称呼）+ durable 偏好；只输出高置信 identity/preference；DROP 瞬时/任务性/非关于用户的内容」。seeding 与增量精炼是同一 seam 的两个入参形态，无需第二条路径。**备选**：独立 `ProfileSeeder`——否决（与蒸馏同源同 seam，分裂即重复造轮子）。
- **D3 — 写入 `USER.md` 前经确定性保守过滤（纯逻辑 `DistilledProfileGuard`，无模型）。** 蒸馏产出（`ProfileConsolidationService` 拿到 `distilled` 后、`store.write` 前）过一遍纯逻辑过滤：保留 `# User Profile` 标题 + 白名单字段行，丢弃其余；空过滤结果（无任何合规行）视同 seam 声明中止（不写）。这是隐私/幻觉的**确定性前置门**，可单测。**白名单**是**结构性**保守门（约束「是不是 identity/preference 类字段」），**非语义真值门**（不判「这个名字对不对」——那属 live，见 R1）。**备选**：信任 prompt 自律不加过滤——否决（模型可能输出散文/越界字段，无确定性兜底）；语义分类器——否决（离线不可靠、Non-Goal）。
- **D4 — 无可解析廉价模型 → 蒸馏 no-op（规范化既有行为）。** `consolidation.model-id` → `memory.model-id` → 主模型三级回落都解析不到时，seam 返回 null → 蒸馏不写、不崩；`AgentBootstrap` 亦可在无模型时不调度。把今日「返回 null 即中止」的实现细节提升为**规范保证**（spec 明列），确保「无模型环境」零副作用。**备选**：无模型时报错/阻塞——否决（违背 fault-tolerant 惯例）。
- **D5 — 容错/脱敏加固，复用不改语义。** 蒸馏/读记忆失败 → 记 warn 吞掉、`USER.md` 原样（`ProfileConsolidationService` 既有 try/catch）；所有写入经 `SecretRedactor` + `0600`（`UserProfileStore` 既有）。本 spec 不改这些语义，仅在 spec 中把它们对 seeding/过滤路径的适用性写实。
- **D6 — 复用既有调度与配置块，不新增 config 字段（除非默认值翻转）。** 沿用 `TaskScheduler.schedule("user-profile:consolidation", ...)` + `ProfileConsolidationConfig`（`enabled`/`min-gap-minutes`/`model-id`）。本 spec 因 D1 **不改** `enabled` 缺省值（保持 `false`）。保守过滤白名单可硬编码为保守常量（YAGNI：暂不做成 config，避免过度可配）。**备选**：新增 `seeding`/`filter` 子配置——否决（YAGNI，白名单是安全常量不宜放松）。
- **D7 — 交叉依赖显式登记（见 Risks R3/R4）。** 蒸馏源 `MEMORY.md` 属记忆线（声明性事实）；画像产出属身份/偏好；程序性 how-to 归 `SKILL.md`。廉价模型经 `memory.model-id` → 主模型回落，与记忆线共享同一廉价模型约定。

## Architecture

```
user-profile.consolidation.enabled=true   (默认 false — D1/Spike)
        │  AgentBootstrap: 解析廉价模型(consolidation.model-id→memory.model-id→主) ; 无模型→不调度/no-op(D4)
        ▼  TaskScheduler.schedule("user-profile:consolidation", cron(min-gap), maybeConsolidate)
ProfileConsolidationService.consolidateNow()                       ← io.pigagent.core.profile (既有)
  ├─ memory  = read MEMORY.md            (缺失/失败 → ""，容错 D5)
  ├─ current = store.readFull()          (空 = 首次 → seeding 分支 D2)
  ├─ if memory.blank && current.blank → return (无源可蒸)
  ├─ distilled = distiller.distill(current, memory)               ← ProfileDistiller seam (可 mock)
  │       ModelProfileDistiller: 廉价模型一次性 agent；modelSupplier==null → null (no-op D4)
  │       DISTILL_PROMPT: 空画像→从 MEMORY seed 身份+durable偏好；只输出高置信 identity/preference (D2)
  ├─ if distilled null/blank → return (seam 中止 / 无模型)
  ├─ kept = DistilledProfileGuard.filter(distilled)               ← 纯逻辑保守过滤 (新增, D3)
  │       只留 "# User Profile" + 白名单字段行；丢自由文本/越界字段
  ├─ if kept 无合规行 → return (视同中止, 不写 USER.md)
  └─ store.write(kept)   → SecretRedactor 脱敏 + 原子写 + 0600 (既有 D5)
```

## Risks / Trade-offs

- **R1 —（承重残余）语义幻觉：蒸馏抽错人/错偏好。** 过滤器只约束结构不约束语义。→ **默认关兜底**（D1）；`DISTILL_PROMPT` 保守抽取降低概率；真实抽取正确性延后 `/ls:itest`（默认翻转前置门）。这是「离线兜不住」的第一类，也是默认关的核心理由。
- **R2 —（承重残余）非密形隐私内容被注入。** `SecretRedactor` 只识别凭据形；一次性敏感事实若被当作偏好蒸出会永久注入 system prompt。→ **默认关兜底**（D1）；`DISTILL_PROMPT` 明令「只 durable identity/preference、DROP 非关于用户/瞬时内容」；白名单类目收窄可写字段面；真实无误写延后 `/ls:itest`。这是「离线兜不住」的第二类。
- **R3 —（交叉依赖）记忆线 `MEMORY.md` 为蒸馏源。** → 本 spec **只读** `MEMORY.md`（`pa-memory-native` 的固化层），不动其 flush/consolidation 写路径；`MEMORY.md` 缺失/空 → 蒸馏无源可蒸即 no-op（容错）。依赖层：本 spec 依赖记忆线**产出内容**但不依赖其内部实现，二者可独立演进。
- **R4 —（交叉边界）与技能线 X3 划分。** 画像只建模**声明性**身份/偏好（「用户是谁/偏好什么」）；程序性「怎么做某事」归 `SKILL.md`（`listSkills`/`loadSkill`），**不在本能力内**。避免画像蒸馏与技能蒸馏职责重叠。
- **R5 — 廉价模型不可解析（无模型环境 / 配置错）。** → D4 no-op 降级（不写、不崩、不调度），零副作用；离线可证。
- **R6 — 蒸馏产出格式漂移（模型不按 `- **Field**: value` 输出）。** → D3 过滤器丢弃不合规行；若全部不合规 → 空结果 → 不写（`USER.md` 原样）。格式漂移最坏是「本次不更新」，不会破坏既有画像。
- **R7 — seeding 与 `updateProfile` 冲突（用户已手设某字段，蒸馏又 seed 同名）。** → `store.write` 是整体覆盖（后台蒸馏路径），蒸馏拿的是**当前 `USER.md` + `MEMORY.md`**，`DISTILL_PROMPT` 已含「current profile 已述则保留，除非 memory 明确 supersede」；用户手设值随 current 传入蒸馏，语义上被保留。真实「保留 vs 覆盖」判断质量属 live（R1 同源），默认关兜底。
- **R8 — 默认关 = 特性默认不生效。** 交付但默认不开，用户无感。→ **有意为之**（D1 安全优先）：机制齐备、`/ls:itest` 达标后一行翻默认；`CLAUDE.md`/配置文档写明如何手动开启（`user-profile.consolidation.enabled=true`）供尝鲜。

## Migration Plan

- **部署**：默认 `enabled=false` → 与今日逐字节一致（无调度、无 LLM、无写盘），零迁移动作。手动 `enabled=true` 即启用 seeding + 加固蒸馏。
- **回滚**：`enabled=false` 即完全停用；`USER.md` 内容不受影响（既有文件保留，`updateProfile` 与注入照常）。
- **默认翻转（未来，非本 spec）**：`/ls:itest` 用真廉价模型验证 seeding 抽取正确性 + 无非密隐私误写达标后，改 `ProfileConsolidationConfig.enabled` 缺省值为 `true` + 新工作区种子——一行改动，机制无需再动。

## Open Questions

- 保守白名单字段类目的确切集合（name/称呼/language/output-style/tech-preference/working-style/timezone/role…）——编码期据 `DISTILL_PROMPT` 与 `USER.md` 实际字段收敛；spec 只要求「保守 identity/preference 白名单」，不钉死清单。
- `AgentBootstrap` 在「无可解析廉价模型」时是**不调度**还是**调度但 seam no-op**——二者对外等价（都不写），编码期择简实现；spec 只要求「无模型 → 不写、不崩」。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 承重 spike：离线能否兜住隐私/幻觉，决定默认开否 | Spike S1–S3；D1 | **spike 结论：兜不住语义/非密隐私 → 保持默认关、只交付机制**（落 tasks 0） |
| 首次身份 seeding（空画像从 `MEMORY.md`） | D2；ADDED「首次身份 seeding」 | 落 tasks 2（seam 契约 + prompt）|
| 写入前确定性保守过滤（隐私/幻觉护栏） | D3；ADDED「写入前确定性保守过滤」 | 落 tasks 3（纯逻辑 `DistilledProfileGuard`）|
| 无可解析廉价模型 → no-op 降级 | D4；MODIFIED「可选的后台画像蒸馏」 | 落 tasks 4 |
| 容错回滚 + `SecretRedactor` 覆盖 + `0600` | D5；MODIFIED（复用不改） | 落 tasks 3/4（断言）|
| 复用 `TaskScheduler` + `ProfileDistiller` seam + 廉价模型回落 | D6/D7 | 复用既有接线（不改）|
| 默认值保持 `false`、一行翻转延后 IT | D1；MODIFIED「可选的后台画像蒸馏」默认关场景 | 落 tasks 1（默认值断言）|
| 真实 seeding/蒸馏质量 + 无隐私误写 → live 验 | R1/R2；Non-Goals | 落 tasks 5（IT，延后，默认翻转前置门）|
| 交叉依赖记忆线（只读 `MEMORY.md`）/ X3 边界（声明性 vs 程序性） | D7；R3/R4 | 已登记（依赖层 + 边界）|
| 格式漂移 / seeding 与 `updateProfile` 冲突 | R6/R7；D3 过滤兜底 | 落 tasks 3（过滤丢不合规）+ 默认关兜底 |
