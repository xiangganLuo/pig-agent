## ADDED Requirements

### Requirement: 长期记忆按需检索注入（pinned + query-aware 切分）

启用检索注入时，系统 SHALL 将长期记忆的上下文注入切分为两部分：**pinned 核心**——一个稳定、有界、query-无关的记忆子集——SHALL 常驻注入 system prompt；**query-aware 相关事实**——按当前 user 消息从记忆语料检索出的 top-K——SHALL 以非缓存位（trailing reasoning message，见下）按需注入。系统 MUST NOT 把整份记忆无差别注入 system prompt（启用时）。检索 MUST 复用既有 `MemorySearchIndex` 的稳定公共门面 `search(query, topK)`，MUST 支持仅 BM25（无嵌入依赖）离线工作。

#### Scenario: pinned 核心注入 system prompt
- **WHEN** 检索注入启用且存在 pinned 核心内容
- **THEN** system prompt 包含该 pinned 核心块（有界、按 `max-chars` 截断），且不包含整份记忆全文

#### Scenario: query-aware top-K 相关事实按 query 注入
- **WHEN** 当前 user 消息为一个查询，记忆语料含与之相关的事实
- **THEN** 系统经 `MemorySearchIndex.search(query, topK)` 检索出至多 top-K 条相关事实并注入本回合上下文

#### Scenario: 仅 BM25 离线可用
- **WHEN** 未配置嵌入器（`embedder-model-id` 为空）而检索注入启用
- **THEN** 检索经 BM25-only 工作、返回相关事实，不抛异常、不报错

### Requirement: prefix-cache 稳定性（query-dependent 内容不进缓存前缀）

系统 MUST 保证 **query-dependent 的内容绝不进入被缓存的 system-prompt 前缀**：pinned 核心（进 system prompt）MUST 为 query-无关，对不同 user 查询字节恒等；query-aware 相关事实 MUST 注入到 system prompt **之后**的非前缀位（一条 trailing 的 reasoning 消息），使其随回合变化而不改变 system-prompt 前缀。pinned 核心 SHALL 在一次会话内字节稳定，仅在 pinned 内容本身变化时才改变。

#### Scenario: 不同 query 下 system prompt 字节恒等
- **WHEN** 同一 pinned 内容下，以两个不同的当前 user 查询分别触发 system prompt 组装
- **THEN** 两次产出的 system prompt 字节完全相同（pinned 注入不随 query 变化）

#### Scenario: query-aware 内容只出现在非前缀位
- **WHEN** query-aware 相关事实被注入
- **THEN** 这些事实的内容出现在 system prompt 之后的 trailing 消息中，MUST NOT 出现在 system prompt 前缀内

#### Scenario: pinned 会话内稳定
- **WHEN** 同一会话内连续多个回合、pinned 内容未变化
- **THEN** 每回合的 system prompt（pinned 部分）字节稳定（前缀可命中缓存），仅当 pinned 内容变化时才变一次

### Requirement: query-aware 注入为 ephemeral，不写回持久化历史

query-aware 相关事实的注入 MUST 为 **ephemeral**：系统 SHALL 在每个推理步以一条 trailing user-side 消息追加到一个新的推理输入中，MUST NOT mutate 传入的消息列表，MUST NOT 把该注入写回持久化的会话历史。该注入 SHALL 每推理步按当前查询重建。

#### Scenario: 注入不改动持久化历史
- **WHEN** query-aware 事实被注入到某推理步
- **THEN** 传入的消息列表不被就地修改，注入的消息不进入持久化会话历史（下次读取历史看不到它）

#### Scenario: 空查询或空结果不注入
- **WHEN** 当前无有效 user 查询，或检索返回空
- **THEN** 系统不注入任何 query-aware 消息，推理输入原样传递（恒等）

### Requirement: pinned 核心为确定性、有界、缺失安全降级

pinned 核心的选取 MUST 为**纯逻辑、确定性**（无模型调用），MUST 受 `max-chars` 上限约束（超出截断）。默认 SHALL 从固化层 `MEMORY.md` 的一个可配置 pinned 标记段提取；当该来源缺失时，系统 MUST 降级为**空 pinned**（此时 system prompt 无常驻记忆段、全部事实走 query-aware 检索），MUST NOT 崩溃或注入不确定内容。pinned 与 query-aware 结果 MUST 去重，同一事实 MUST NOT 在 system prompt 与 trailing 消息中双重注入。

#### Scenario: pinned 有界截断
- **WHEN** pinned 来源内容超过 `max-chars`
- **THEN** 注入的 pinned 块被截断到上限内（确定性）

#### Scenario: pinned 来源缺失降级为空
- **WHEN** 配置的 pinned 标记段在 `MEMORY.md` 中不存在
- **THEN** pinned 为空、system prompt 不注入常驻记忆段，检索注入照常工作，不抛异常

#### Scenario: pinned 与检索结果去重
- **WHEN** 某事实既在 pinned 核心中、又被 query-aware 检索命中
- **THEN** 该事实不在 trailing 消息中重复注入（去重）

### Requirement: 检索注入开关与向后兼容

系统 SHALL 提供 `memory.injection` 配置块，全部字段可选、默认安全。`enabled` MUST **默认关闭**——关闭时系统 SHALL 保持今日注入行为：`onSystemPrompt` 注入整份 `MEMORY.md`、无 query-aware ephemeral 注入，行为 MUST 逐字节等于本能力引入前。启用时 `top-k`、pinned 来源与 `max-chars`、可选 `embedder-model-id` MUST 可配；非法/越界配置 MUST 被安全 clamp。本能力 MUST NOT 改变 `pa-memory-native` 的记忆库与 flush/consolidation 写路径。

#### Scenario: 默认关即今日行为
- **WHEN** `memory.injection.enabled=false`（默认）
- **THEN** system prompt 注入整份 `MEMORY.md`、无 query-aware 注入，逐字节等于本能力引入前

#### Scenario: 缺配置块用默认
- **WHEN** 配置无 `memory.injection` 块
- **THEN** 检索注入默认关、其余字段取默认值，行为与引入前一致

#### Scenario: 启用不影响记忆写路径
- **WHEN** `memory.injection.enabled=true`
- **THEN** `pa-memory-native` 的 flush/consolidation 仍照常把事实写入日志层/固化层，仅注入的位置与切分改变
