## ADDED Requirements

### Requirement: 首次身份 seeding（空画像时从 `MEMORY.md` 蒸馏身份/偏好）

启用后台蒸馏时，若 `USER.md` 为空/缺失，系统 SHALL 在一次蒸馏中从长期记忆 `MEMORY.md` **seed 身份**：蒸馏出**高置信**的身份（姓名 / 如何称呼）与 durable 偏好（语言 / 输出风格 / 技术偏好 / 工作方式）写入 `USER.md`，无需用户显式调用 `updateProfile`（Hermes 式自动用户建模）。seeding MUST 复用既有 `ProfileDistiller` seam 契约（`currentProfile` 为空即 seeding 分支），MUST NOT 新造第二条蒸馏路径。抽取 MUST 保守——只 seed 高置信 identity/preference，MUST 丢弃瞬时/任务性/非关于用户的内容。当 `MEMORY.md` 无可 seed 的身份/偏好（空/无相关内容）时，系统 MUST NOT 写入（`USER.md` 保持空/原样）。

#### Scenario: 空画像时从记忆 seed 身份
- **WHEN** `USER.md` 为空且 `MEMORY.md` 含用户身份（如姓名/称呼），触发一次蒸馏
- **THEN** 蒸馏经 seam 从记忆 seed 出身份/偏好，写入 `USER.md`（有界、脱敏、经保守过滤）

#### Scenario: 无可 seed 内容则不写
- **WHEN** `USER.md` 为空且 `MEMORY.md` 无可提取的身份/偏好（空或无相关内容）
- **THEN** 蒸馏不写入 `USER.md`（保持空），不抛异常

#### Scenario: seeding 复用现有 seam 且保守抽取
- **WHEN** 触发 seeding（空画像）
- **THEN** 走既有 `ProfileDistiller.distill(currentProfile=空, memory)` 契约（非新路径），且只 seed 高置信 identity/preference，丢弃瞬时/非关于用户内容

### Requirement: 写入 `USER.md` 前的确定性保守过滤（隐私/幻觉护栏）

自动蒸馏产出在写入 `USER.md` **之前**，系统 SHALL 经过一个**确定性、无模型调用**的保守过滤：只保留画像标题（`# User Profile`）与形如 `- **<Field>**: <value>` 且字段名落在**保守 identity/preference 白名单**内的行，MUST 丢弃自由文本段落、白名单外字段及超长/不合规行。该过滤 MUST 与既有 `SecretRedactor` **叠加**（凭据形内容与非合规内容都不落盘）。过滤后**无任何合规行**时，系统 MUST NOT 写入 `USER.md`（视同蒸馏中止，原文件原样）。该过滤 MUST 为纯逻辑（可离线单测，不依赖模型）。此过滤为**结构性**保守门（约束「是否 identity/preference 类字段」），语义真值（抽取是否抽对）不在其保证范围（属 live-model，见默认关的安全前提）。

#### Scenario: 只保留白名单字段行
- **WHEN** 蒸馏产出含合规的 identity/preference 字段行（如 name / 语言偏好）与其它内容
- **THEN** 合规字段行与画像标题被保留写入，其余被丢弃

#### Scenario: 丢弃自由文本/越界字段
- **WHEN** 蒸馏产出含自由文本段落或白名单外的字段
- **THEN** 这些内容被过滤丢弃，MUST NOT 落入 `USER.md`

#### Scenario: 过滤 + 脱敏双重保障
- **WHEN** 蒸馏产出含形似密钥/token 的值
- **THEN** 该值经 `SecretRedactor` 脱敏（叠加于保守过滤之上），凭据 MUST NOT 落入 `USER.md`

#### Scenario: 过滤结果为空则不写
- **WHEN** 蒸馏产出经保守过滤后无任何合规字段行
- **THEN** 不写入 `USER.md`（视同中止），既有画像原样保留

## MODIFIED Requirements

### Requirement: 可选的后台画像蒸馏（廉价模型，可 mock、节流、容错）

系统 SHALL 提供一个**可选的**后台画像蒸馏能力：周期性（节流、后台）用一个**廉价模型**把稳定的身份/偏好从长期记忆蒸馏进去重后的 `USER.md`。蒸馏的模型调用 MUST 位于一个**可 mock 的 seam** 之后（离线可测）。蒸馏 MUST 节流（最小间隔），MUST NOT 阻塞回合，MUST 容错（任何失败记日志并吞掉，绝不破坏画像或崩溃）。蒸馏产出 MUST 在写入前经确定性保守过滤与凭据脱敏（见「写入 `USER.md` 前的确定性保守过滤」）。当**无可解析的廉价模型**（`consolidation.model-id` → `memory.model-id` → 主模型三级回落都解析不到）时，蒸馏 MUST 为 **no-op**（不写、不崩、不阻塞）。首次（空画像）MAY 从 `MEMORY.md` seed 身份（见「首次身份 seeding」）。

该能力 MUST **默认关闭**。默认关闭是一个**有意的安全姿态**：离线已交付强确定性守卫（脱敏 / 保守过滤 / no-op / 容错），但**语义抽取正确性**与**非密形隐私内容**离线无法充分验证（`USER.md` 注入 system prompt 且每回合常驻，误写具放大效应），故默认翻转 MUST 延后到 **live-model 集成测试**验证抽取正确性与无隐私误写达标后**一步开启**（配置缺省值 + 新工作区种子），本能力交付时机制齐备而默认不开。

#### Scenario: seam 可 mock 且蒸馏结果落盘
- **WHEN** 配置了一个（mock 的）蒸馏 seam 并触发一次蒸馏
- **THEN** seam 的输出经保守过滤 + 脱敏后写入 `USER.md`（有界、凭据安全）

#### Scenario: 蒸馏节流
- **WHEN** 在最小间隔内连续触发蒸馏
- **THEN** 第二次不实际执行（受最小间隔节流）

#### Scenario: 蒸馏失败安全降级
- **WHEN** 蒸馏 seam 抛错或读记忆出错
- **THEN** 不崩溃、不破坏既有 `USER.md`（记 warn 日志）

#### Scenario: 无可解析廉价模型则 no-op
- **WHEN** 廉价模型三级回落（`consolidation.model-id` → `memory.model-id` → 主模型）都解析不到
- **THEN** 蒸馏为 no-op——不写 `USER.md`、不崩溃、不阻塞，零副作用

#### Scenario: 默认关闭
- **WHEN** 未开启 `user-profile.consolidation.enabled`
- **THEN** 不进行任何后台蒸馏（零 LLM 调用、零调度、零写盘），行为逐字节等于本能力引入前

#### Scenario: 默认关是有意的安全姿态且一步可翻
- **WHEN** 查询后台蒸馏的默认开关状态
- **THEN** 默认关闭；机制（seeding/保守过滤/no-op/容错）已交付，默认翻转延后到 live-model 集成测试达标后经一处配置缺省值变更开启
