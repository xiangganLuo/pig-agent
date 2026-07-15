## ADDED Requirements

### Requirement: token 估算计入全部内容块

上下文压缩的 token 估算 SHALL 覆盖每条消息的全部内容块——文本、tool 调用入参、以及 tool 返回结果载荷——而非仅计文本。估算 MAY 沿用 `~字符数/4` 的近似启发式，但 MUST 作用于消息的完整序列化内容，使压缩触发反映真实上下文规模。

#### Scenario: 大 tool 结果显著抬高估算
- **WHEN** 对一条携带大体量 tool 返回结果的消息估算 token
- **THEN** 其估算值显著高于仅按文本计的估算（tool 结果被计入，不再被漏掉）

#### Scenario: 纯文本估算不受影响
- **WHEN** 对不含 tool 内容的纯文本消息估算 token
- **THEN** 估算与按文本计基本一致

### Requirement: 摘要保留 tool 调用与结果

当压缩摘要较早回合时，喂给摘要模型的文本 SHALL 纳入 tool 调用与 tool 返回结果的内容（可紧凑、按块截断），使摘要能够保留重要的 tool 输出，而非仅摘要纯文本。

#### Scenario: 摘要输入含 tool 结果
- **WHEN** 待摘要的回合中包含携带 tool 返回结果的消息
- **THEN** 交给摘要模型的文本包含该 tool 结果的载荷内容

#### Scenario: 巨型 tool 结果按块截断
- **WHEN** 某条消息的单个 tool 结果块极大
- **THEN** 该块在摘要输入中被截断到上限，避免撑爆摘要器自身的上下文

### Requirement: 回合内记忆检索缓存

系统 SHALL 通过一个包裹 `LongTermMemory` 的缓存装饰器，在一个回合内按 query 缓存 `retrieve` 的结果：对同一 query 的重复检索只访问底层存储一次；当 query 变化（新回合）时缓存未命中并重新检索。`record` MUST 直通到底层（写入语义不变）并使缓存失效；记忆被禁用时经装饰器的行为 MUST 与未加装饰时一致（不注入、`record` no-op）。

#### Scenario: 同 query 只检索一次
- **WHEN** 一个回合内对相同 query 连续多次检索记忆
- **THEN** 底层记忆存储只被读取一次，后续检索复用缓存结果

#### Scenario: 新 query 重新检索
- **WHEN** query 发生变化（进入新回合）
- **THEN** 缓存未命中，触发对底层存储的一次新检索

#### Scenario: record 直通并失效缓存
- **WHEN** 调用 `record` 写入记忆
- **THEN** 写入被委托到底层记忆，且缓存被失效，使随后的检索重新读取底层

#### Scenario: 禁用记忆经装饰器仍 no-op
- **WHEN** 记忆被禁用且经装饰器检索/记录
- **THEN** 检索不返回内容、`record` 不写入，与未加装饰时行为一致
