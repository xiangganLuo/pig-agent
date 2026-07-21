# user-profile Specification

## Purpose
维护一份**专门的、策展的用户画像** `USER.md`（工作区级：身份、长期偏好、工作方式），区别于 `pa-memory-native` 的通用事实日志 `MEMORY.md`，并把它稳定、有界、凭据安全地注入 system prompt（位于 `MEMORY.md` 之前），让个人助理「知道你是谁、越用越懂你」（Hermes `USER.md` 蓝本，OD4-A）。画像维护走两条路：一个确定性的 `updateProfile` 写工具（即时落盘一条确认的偏好/身份）与一个可选的、廉价模型驱动的后台蒸馏（把稳定偏好从 `MEMORY.md` 蒸馏进去重后的 `USER.md`）。
## Requirements
### Requirement: 用户画像文件（`USER.md`，区别于事实日志）

系统 SHALL 维护一份**专门的用户画像**文件 `USER.md`（工作区级，路径可配，默认工作区根 `USER.md`）——策展的、结构化的身份/长期偏好/工作方式，**区别于**通用长期事实日志 `MEMORY.md`。画像 MUST 为本地 Markdown（人可读、可手编、可版本化）。画像 MUST 为工作区级（不按会话隔离）。

#### Scenario: 画像与事实日志分离
- **WHEN** 系统同时维护用户画像与长期事实
- **THEN** 用户画像落在独立的 `USER.md`，MUST NOT 混入 `MEMORY.md`（事实日志），二者各自独立维护

#### Scenario: 画像为工作区级
- **WHEN** 一条画像字段被写入
- **THEN** 它进入工作区级 `USER.md`（跨会话可见），MUST NOT 仅落在随会话切换失效的会话层

### Requirement: 画像稳定注入 system prompt

启用画像时，系统 SHALL 把 `USER.md` 注入模型的 **system prompt**，位于 `MEMORY.md` 之前/一同，使助理「知道用户是谁」。注入 MUST 有大小上限（超出截断）且 MUST 凭据脱敏（绝不注入密钥/token）。`USER.md` 不变时注入 MUST 字节稳定（前缀缓存友好）。缺失/空白/不可读的 `USER.md` MUST 使 system prompt 保持不变（不注入、不抛）。

#### Scenario: 存在且启用则注入
- **WHEN** `USER.md` 存在且画像启用，组装一次 system prompt
- **THEN** 画像内容被追加进 system prompt（在长期记忆块之前），且带清晰的画像小标题

#### Scenario: 缺失则不注入
- **WHEN** `USER.md` 缺失或为空
- **THEN** system prompt 保持不变（不注入画像），不抛异常

#### Scenario: 注入有上限
- **WHEN** `USER.md` 内容超过配置的注入上限
- **THEN** 注入内容被截断到上限内（其余不注入），system prompt 不被画像撑爆

#### Scenario: 注入不含凭据
- **WHEN** `USER.md` 含形似密钥/token 的内容
- **THEN** 注入前该内容被脱敏（掩码），凭据 MUST NOT 进入 system prompt

### Requirement: `updateProfile` 写工具（确定性 set/merge，权限治理）

启用画像时，系统 SHALL 向 agent 暴露一个 `updateProfile` 工具，用于**即时**设置/合并一个画像字段（如「叫我 X」「以后用中文回」）。合并 MUST 确定性：同名字段存在则替换其值、不存在则追加，**无需调用模型**。该工具 MUST 在 `ToolRiskClassifier` 中分类为 **WRITE**（受权限体系治理）。失败 MUST 返回 canonical `{"error":"<reason>"}`（凭据脱敏），MUST NOT 抛异常。写入的字段/值 MUST 凭据脱敏。画像禁用时，该工具 MUST 从模型 schema 隐藏。

#### Scenario: 设置一个新字段
- **WHEN** agent 调 `updateProfile("name", "罗湘赣")` 且画像原本无 name 字段
- **THEN** `USER.md` 追加一条 name 字段 = 罗湘赣，返回成功

#### Scenario: 合并（替换）已有字段
- **WHEN** name 字段已存在，agent 再调 `updateProfile("name", "新名字")`
- **THEN** 该字段的值被替换为新值（不产生重复字段），返回成功

#### Scenario: WRITE 分类
- **WHEN** 查询 `updateProfile` 的风险级别
- **THEN** 为 WRITE（受权限体系治理：ask 确认 / auto 放行 / plan 拒绝）

#### Scenario: 禁用则隐藏
- **WHEN** 画像禁用（`user-profile.enabled=false`）
- **THEN** `updateProfile` 不出现在模型工具 schema 中

### Requirement: 画像跨会话可见

用户经 `updateProfile` 在某会话写入的画像字段 SHALL 在**另一会话**注入的画像中可见——因为 `USER.md` 是工作区级单文件、不随 `/session new` 丢失（镜像 `pa-memory-native` 的跨会话修复）。

#### Scenario: 会话 A 写的名字在会话 B 注入
- **WHEN** 会话 A 中 `updateProfile("name", "罗湘赣")`，随后切到会话 B 组装 system prompt
- **THEN** 会话 B 注入的画像包含「罗湘赣」（同一工作区级 `USER.md`）

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

### Requirement: 画像开关与向后兼容

`user-profile.enabled=false` 时，系统 SHALL NOT 注入 `USER.md`、SHALL NOT 暴露 `updateProfile`、SHALL NOT 蒸馏——行为 MUST 逐字节等于本能力引入前。画像配置块 MUST 全部可选、默认安全（缺块 → 启用、默认路径/上限、蒸馏关）。

#### Scenario: 禁用即今日行为
- **WHEN** `user-profile.enabled=false`
- **THEN** 无画像注入、无 `updateProfile` 工具、无蒸馏（与引入前一致）

#### Scenario: 缺配置块用默认
- **WHEN** 配置无 `user-profile` 块
- **THEN** 画像启用、路径默认 `USER.md`、注入上限默认值、蒸馏默认关

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

