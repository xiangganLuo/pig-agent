## ADDED Requirements

### Requirement: 默认不改变行为（向后兼容）

当记忆抽取能力未启用（配置 `memory.extraction.enabled=false`，为默认）时，会话层长期记忆的 `record` MUST 与引入本能力前**逐字节一致**——即原始 `FileSystemLongTermMemory.record`（按字符阈值截断的条目 append），MUST NOT 起任何抽取模型调用、MUST NOT 起后台线程。全局层写路径与 `PreReasoning` 注入半部在任何情况下都 MUST NOT 被本能力改变。

#### Scenario: 禁用时走原始 record
- **WHEN** `memory.extraction.enabled=false` 且发生一次 `record`
- **THEN** 消息按原始 `FileSystemLongTermMemory` 行为落盘，不调用抽取器、不触发后台抽取

#### Scenario: 禁用谓词经 Decorator 直接委托
- **WHEN** `ExtractingLongTermMemory` 的 `enabled` 谓词为 false 时调用 `record`
- **THEN** 调用被直接委托给被包裹的原始记忆，产生与不包裹时相同的写入

### Requirement: LLM 抽取分类事实

系统 SHALL 提供 `MemoryExtractor` 策略（接口即可 mock 接缝），从一个完成的回合中抽取**分类事实**：每条事实 MUST 带一个类别（`user-preference` / `project-fact` / `reference`）、一个 [0,1] 的置信度、一句规范化陈述、以及一个用于去重的 subject。LLM 实现 MUST 复用当前模型（一次性 agent），抽取用的 prompt、JSON 解析与分类 MUST 落在实现侧。事实值对象 MUST 不可变，且置信度 MUST 被钳制到 [0,1]。

#### Scenario: 解析模型 JSON 为分类事实
- **WHEN** 抽取器把模型返回的 JSON（含 category/subject/statement/confidence）解析为事实
- **THEN** 得到对应类别与置信度的 `ExtractedFact`；未知类别标签容错回退到默认类别而非报错

#### Scenario: 置信度越界被钳制
- **WHEN** 构造一个 confidence 为 1.5 或 -0.3 的事实
- **THEN** 其置信度被钳制到 [0,1]（1.0 / 0.0）

#### Scenario: 分类枚举容错解析
- **WHEN** 以未知或 null 的类别标签解析
- **THEN** 返回默认类别，不抛异常

### Requirement: 置信度门

只有置信度不低于阈值（默认 0.7，经 `memory.extraction.confidence-threshold` 可配）的事实才 MUST 被持久化；低于阈值的事实 MUST 被丢弃。纠正类事实（见「纠正覆盖」）MUST 被视为高置信度而绕过该门。

#### Scenario: 高置信度保留、低置信度丢弃
- **WHEN** 一批事实中有 confidence=0.9 与 confidence=0.5、阈值为 0.7
- **THEN** 0.9 的被保留、0.5 的被丢弃

#### Scenario: 阈值可配
- **WHEN** 阈值配置为 0.8 且某事实 confidence=0.75
- **THEN** 该事实被丢弃（低于配置阈值）

### Requirement: 纠正覆盖

当用户纠正一个既有事实时，抽取器 SHALL 把该事实标记为 `correction`；纠正事实 MUST 被视为高置信度，并 MUST 按 subject **覆盖/取代**同主题的旧事实（去重替换，而非盲目追加）。合并 MUST 按 subject 去重：新事实取代同 subject 的旧事实。

#### Scenario: 纠正覆盖同主题旧事实
- **WHEN** 已存在 `subject=language` 的旧事实，抽取出一条 `subject=language` 的 correction 事实
- **THEN** 合并后同 subject 只保留新的（纠正）事实，旧的被取代

#### Scenario: 纠正绕过置信度门
- **WHEN** 一条 correction 事实即使原始 confidence 低于阈值
- **THEN** 它仍被保留（被规范化为高置信度）

#### Scenario: 非纠正的普通事实按 subject 去重
- **WHEN** 合并一批与既有同 subject 的普通事实
- **THEN** 结果按 subject 去重，同 subject 不出现重复条目

### Requirement: 噪声过滤

系统 SHALL 提供一个纯 `MemoryNoiseFilter` 谓词/策略，将**工具调用与工具结果**消息、以及**会话瞬时事件**（如「我跑了 mvn test」这类临时状态陈述）判定为噪声并**剥离**，使其 MUST NOT 进入耐久记忆。该过滤 MUST 可独立于抽取器单测。

#### Scenario: 剥离工具调用/结果
- **WHEN** 过滤一个含工具调用块/工具结果消息（TOOL 角色）的回合
- **THEN** 这些消息被剥离，仅保留 user/assistant 的实质文本

#### Scenario: 剥离会话瞬时事件
- **WHEN** 助手消息文本是「我跑了 mvn test」这类瞬时状态
- **THEN** 该文本被判为噪声，不进入抽取输入

#### Scenario: 保留实质耐久内容
- **WHEN** 用户表达一个偏好或项目事实的实质陈述
- **THEN** 该消息被保留为耐久候选

### Requirement: 异步去抖写入

抽取与写入 MUST 发生在回合关键路径**之外**（`record` MUST NOT 阻塞回合等待抽取/写入完成），并 MUST 对快速连续的回合做**去抖合并**（一簇快速 record 合并为一次抽取执行）。调度器 MUST 在关闭时 **flush 未决工作并释放线程（无泄漏）**。

#### Scenario: record 不阻塞回合
- **WHEN** 启用抽取时发生一次 `record`
- **THEN** `record` 立即返回（抽取/写入在后台执行），不阻塞回合

#### Scenario: 去抖合并快速回合
- **WHEN** 在去抖间隔内快速提交多次抽取任务
- **THEN** 只有最近一次在安静期后被执行（合并），执行次数远少于提交次数

#### Scenario: 关闭时 flush 未决工作
- **WHEN** 存在未决抽取任务时关闭调度器
- **THEN** 未决任务被立即执行完毕，且后台线程被释放（无泄漏）

### Requirement: 优雅降级

抽取管线的任何失败（抽取器抛异常、模型不可用、JSON 解析失败、IO 失败）MUST 被记日志并跳过，MUST NOT 传播为异常打断回合，也 MUST NOT 破坏已有记忆内容——沿用压缩谱系的「吞掉并记日志」约定。

#### Scenario: 抽取器抛异常被吞掉
- **WHEN** `MemoryExtractor.extract` 抛异常
- **THEN** 管线记 warn 日志并跳过本次写入，回合不受影响，既有记忆不被破坏

#### Scenario: 模型不可用时降级
- **WHEN** LLM 抽取实现的模型调用失败
- **THEN** 抽取返回空事实集（记日志），不抛异常

### Requirement: 只作用于会话层

抽取结果 MUST 只写会话层临时记忆（`sessions/{id}/temp-memory.md`），与今天的 `record` 一致；MUST NOT 写全局层（`workspace/context/memory.md`）。检索半部（`PreReasoning` 注入）与 `/memory` 全局开关语义 MUST NOT 改变——记忆关闭时 `record` 仍为 no-op、`retrieve` 仍返回空。

#### Scenario: 抽取只落会话层
- **WHEN** 启用抽取并成功抽取事实
- **THEN** 事实写入当前会话的临时记忆文件，全局层文件不被写入

#### Scenario: 记忆关闭时不抽取不记录
- **WHEN** `/memory off`（全局记忆开关关闭）
- **THEN** `record` 为 no-op（不抽取），`retrieve` 返回空——与引入本能力前一致
