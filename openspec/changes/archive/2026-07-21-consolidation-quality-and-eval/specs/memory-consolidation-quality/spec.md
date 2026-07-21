## ADDED Requirements

### Requirement: 可选定制 flush/consolidation prompt（opt-in、含占位校验与安全回退）

系统 SHALL 允许**可选**定制原生记忆的 flush prompt 与 consolidation prompt；未配置（默认）时 MUST 使用原生默认 prompt，行为 MUST 逐字节等于本能力引入前。自定义 **consolidation prompt** MUST 恰含两个 `%d` 占位（消费 token 上限与近似字符上限），且 MUST NOT 含其它未转义的 `%` 格式转换；当自定义 consolidation prompt 校验失败时，系统 MUST **安全回退到原生默认 prompt**（记 warn），MUST NOT 崩溃、MUST NOT 让后台 consolidation 抛异常。自定义 **flush prompt** 为纯 SYSTEM prompt（无占位要求）；空白 MUST 回退默认。

#### Scenario: 未配置用原生默认（字节等价）
- **WHEN** 未配置任何自定义 flush/consolidation prompt（默认）
- **THEN** 记忆使用原生默认 flush/consolidation prompt，flush/consolidation 行为逐字节等于本能力引入前

#### Scenario: 合法自定义 consolidation prompt 被采用
- **WHEN** 配置了一个恰含两个 `%d`、无其它裸 `%` 的自定义 consolidation prompt
- **THEN** 该自定义 prompt 被用于 consolidation（替换默认 prompt）

#### Scenario: 坏占位 consolidation prompt 安全回退
- **WHEN** 配置的自定义 consolidation prompt 占位数不为二（缺/多 `%d`）或含其它裸 `%` 转换
- **THEN** 系统记 warn 并回退原生默认 consolidation prompt，不崩溃、后台 consolidation 不抛异常

#### Scenario: 合法自定义 flush prompt 被采用
- **WHEN** 配置了一个非空的自定义 flush prompt
- **THEN** 该自定义 prompt 被用于 flush 抽取（flush prompt 无占位要求）；配置为空则回退默认

### Requirement: pig 侧语义去重后置 curator（复用检索原语、确定性、可降级）

启用语义去重时，系统 SHALL 在原生 consolidation **之后**运行一个 pig 侧后置 curator：读取固化层 `MEMORY.md`、切分为事实单元、计算两两**语义相似度**、将相似度超过阈值的近重复**丢弃并保留代表条**，再原子重写 `MEMORY.md`。相似度计算 MUST 复用既有检索原语——`io.pigagent.core.search`（BM25）与 `io.pigagent.core.memory.search`（`Embedder`/cosine）；当配置了嵌入模型时 SHALL 用真嵌入器 cosine，未配置时 MUST 降级为 BM25 相似度（零依赖）。去重逻辑 MUST 为确定性、纯逻辑（无模型改写），MUST 离线以确定性嵌入器/BM25 可测。curator MUST 节流（不每回合运行）且 MUST 容错——读/写失败时降级为不改动原文件并记 warn，MUST NOT 崩溃。默认关闭。

#### Scenario: 近重复事实被去重
- **WHEN** `MEMORY.md` 含两条语义相似度超过阈值的近重复事实，且去重启用
- **THEN** curator 丢弃其一、保留代表条，`MEMORY.md` 被去重后原子重写

#### Scenario: 不相似事实保留
- **WHEN** 两条事实语义相似度低于阈值
- **THEN** 两条均保留，不被误删

#### Scenario: 无嵌入器降级 BM25
- **WHEN** 未配置嵌入模型而去重启用
- **THEN** curator 经 BM25 相似度通道确定性去重，不抛异常、不报错

#### Scenario: 读写失败安全降级
- **WHEN** curator 读取或重写 `MEMORY.md` 失败
- **THEN** 原文件保持不变，系统记 warn 并继续，不崩溃

#### Scenario: 默认关不改动记忆
- **WHEN** 语义去重未启用（默认）
- **THEN** curator 不运行，`MEMORY.md` 不被 pig 侧去重改动（逐字节等于引入前）

### Requirement: 记忆质量评测 harness（离线证结构、真质量延后 live）

系统 SHALL 提供一个记忆质量评测 harness：以**固定对话 fixture** 驱动记忆流水线（flush → consolidation → 去重），断言三类质量指标——**关键事实被记住**、**去重干净**（无残留近重复）、**关键信息未丢**（去重未误杀）。harness 的**结构与断言框架** MUST 离线以 fake/确定性驱动可运行（不依赖真模型）；真实 consolidation 质量基线（真模型抽取/去重的度量）SHALL 由 live-model `*IT` 覆盖并延后交付。该 harness SHALL 设计为通用、可供后续分层衰减能力（M-C）复用。

#### Scenario: 离线以确定性驱动跑通断言框架
- **WHEN** 以确定性驱动（fake 抽取 + 真去重）跑一个固定对话 fixture
- **THEN** 断言框架产出「关键事实被记住 / 去重干净 / 关键信息未丢」三项判定，全离线、不依赖真模型

#### Scenario: 真质量基线延后 live IT
- **WHEN** 需要度量真模型抽取/去重的真实质量
- **THEN** 由 live-model `*IT` 用同一 fixture 与断言驱动真 flush/consolidation（延后交付，默认模型此前 403）

#### Scenario: harness 可供 M-C 复用
- **WHEN** 后续分层衰减能力（M-C）需要评测记忆质量
- **THEN** 可复用同一 fixture/断言框架，无需另起评测系统

### Requirement: MEMORY.md 分层格式契约（向后兼容，供 M-C 键入）

系统 SHALL 定义并对齐一个稳定的 `MEMORY.md` **分层/分段格式契约**（供后续分层衰减能力键入）。当启用结构化 consolidation（定制 prompt）时，consolidation 输出 SHALL 遵循该契约的分层结构、语义去重 curator SHALL 保留其分层标记。该契约 MUST **向后兼容**旧的无分层 `MEMORY.md`——缺少分层标记时 MUST 视为单层、MUST NOT 报错。本能力 SHALL 只定义契约并保证兼容读取，MUST NOT 在本能力内实现分层衰减本身（属 M-C）。

#### Scenario: 启用结构化 consolidation 时产出符合契约
- **WHEN** 启用定制的结构化 consolidation prompt
- **THEN** 产出的 `MEMORY.md` 遵循文档化的分层格式契约，去重 curator 保留其分层标记

#### Scenario: 旧无分层 MEMORY.md 向后兼容
- **WHEN** 读取一个不含分层标记的旧 `MEMORY.md`
- **THEN** 系统将其视为单层，正常处理，不报错

### Requirement: 全默认关与向后兼容

系统 SHALL 提供 `memory.consolidation-quality` 配置块，全部字段可选、默认安全。prompt 定制、语义去重、评测介入 MUST 全部**默认关闭/未配置**——此时行为 MUST 逐字节等于本能力引入前（原生默认 prompt、无 pig 侧去重、无评测介入）。非法/越界配置 MUST 被安全 clamp。本能力 MUST NOT 改变 `pa-memory-native` 的两层记忆布局、flush/consolidation 触发语义，以及 `/memory on|off` 的语义。

#### Scenario: 默认关即今日行为
- **WHEN** 未配置 `memory.consolidation-quality`（默认）
- **THEN** 记忆用原生默认 prompt、无 pig 侧去重、无评测介入，行为逐字节等于本能力引入前

#### Scenario: 缺配置块用默认
- **WHEN** 配置中无 `memory.consolidation-quality` 块
- **THEN** 各子项取安全默认（prompt 空、去重关），行为与引入前一致

#### Scenario: 启用不改两层布局与开关语义
- **WHEN** 启用 prompt 定制或语义去重
- **THEN** `pa-memory-native` 的日志层/固化层布局、flush/consolidation 触发、`/memory on|off` 语义均不变，仅叠加更强 prompt 与后置去重
