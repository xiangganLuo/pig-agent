## MODIFIED Requirements

### Requirement: 混合检索开关与向后兼容

系统 SHALL 提供 `memory.search` 配置块，全部字段可选、默认安全。`hybrid-enabled` MUST 为**三态**开关：**未设（默认）= auto**——系统 SHALL 由「是否已解析出可用嵌入器」派生启用（见「配置嵌入模型即默认启用混合检索」）；**显式 `true`** = 强制启用（即使无嵌入器亦注册 pig `memory_search`，此时混合退化为 BM25-only 排序）；**显式 `false`** = 强制关闭（SHALL NOT 注册 pig `memory_search`、SHALL 保留原生纯关键词检索）。当**无可用嵌入器且未显式启用**（`hybrid-enabled` 未设、无嵌入模型）时，系统 SHALL NOT 注册 pig `memory_search`、SHALL 保留原生纯关键词检索，行为 MUST 逐字节等于本能力引入前。系统 MUST 向后兼容既有配置：既有显式 `hybrid-enabled: true`/`false` MUST 原样读回为「强制启用/强制关闭」，语义与本能力引入前一致；仅「缺字段」升级为 auto（派生）。启用时权重（`bm25-weight`/`vector-weight`）、候选倍数、`min-score`、`top-k`、重建节流 MUST 可配。

#### Scenario: 无嵌入器且未显式启用即今日行为
- **WHEN** 未配置任何可用嵌入器，且 `hybrid-enabled` 未设（默认 auto）
- **THEN** 不注册 pig `memory_search`，原生纯关键词检索原封不动（逐字节等于本能力引入前）

#### Scenario: 显式关闭强制原生检索
- **WHEN** `hybrid-enabled` 显式设为 `false`（即便已配置嵌入模型）
- **THEN** 不注册 pig `memory_search`，保留原生纯关键词检索与全部原生记忆工具

#### Scenario: 显式启用即使无嵌入器
- **WHEN** `hybrid-enabled` 显式设为 `true` 且无可用嵌入器
- **THEN** 注册 pig `memory_search`，混合检索以 BM25-only 排序工作（向量层缺失优雅降级）

#### Scenario: 既有显式布尔值原样读回
- **WHEN** 读取一份既有配置，其 `hybrid-enabled` 显式写为 `true` 或 `false`
- **THEN** 读回为「强制启用/强制关闭」，语义与本能力引入前一致（不因升级为三态而改变）

#### Scenario: 缺配置块用默认
- **WHEN** 配置无 `memory.search` 块
- **THEN** `hybrid-enabled` 取 auto（派生）、权重默认 `0.7/0.3`、其余取默认值

#### Scenario: 启用后权重可配
- **WHEN** 混合检索启用并设定 `bm25-weight`/`vector-weight`
- **THEN** 混合排序按配置权重融合 BM25 与向量分

## ADDED Requirements

### Requirement: 配置嵌入模型即默认启用混合检索

当 `hybrid-enabled` 为 auto（未显式设定）时，系统 SHALL 由嵌入器解析（`resolveEmbedder`，解析顺序：config `embedder-model-id` → store 默认嵌入模型 → 无）是否得到一个**可用嵌入器**派生混合检索的启用：**得到嵌入器（即用户已配置嵌入模型）→ SHALL 默认注册 pig `memory_search` 走混合 BM25+向量检索**（无需显式开开关）；**未得到嵌入器（无嵌入模型）→ SHALL 保持 BM25-only / 原生纯关键词检索**（今日行为，零回归）。派生决策 MUST 为纯逻辑、确定性（`effective = 显式覆盖若已设，否则 embedderPresent`），MUST 离线（fake/确定性嵌入器）可验证，MUST NOT 依赖真实网络。嵌入器 MUST 只解析一次并复用于索引构建（不重复解析）。显式 `hybrid-enabled` MUST 覆盖派生结果。翻默认检索路径为本能力（M-B）职责；嵌入器**供给**方（嵌入模型层）MUST NOT 因本要求而改变其「只供给不翻默认」的语义。

#### Scenario: 配置嵌入模型即默认走混合
- **WHEN** `hybrid-enabled` 未设（auto），且嵌入器解析得到一个可用嵌入器
- **THEN** 系统默认注册 pig `memory_search`，记忆检索走混合 BM25+向量（无需显式开开关）

#### Scenario: 无嵌入模型默认 BM25-only 零回归
- **WHEN** `hybrid-enabled` 未设（auto），且无可用嵌入器（`embedder-model-id` 空且无默认嵌入模型）
- **THEN** 不注册 pig `memory_search`，保留原生纯关键词检索，逐字节等于本能力引入前

#### Scenario: 显式开关覆盖派生
- **WHEN** 已配置嵌入模型但 `hybrid-enabled` 显式设为 `false`
- **THEN** 派生结果被覆盖，不注册 pig `memory_search`（走原生检索）

#### Scenario: 派生为纯逻辑离线可证
- **WHEN** 以一个确定性/fake 嵌入器（非空）与「无嵌入器」分别驱动派生决策
- **THEN** 非空 → 混合激活（索引 `vectorEnabled()` 为真且注册工具）、无 → BM25-only，全程无真实网络

#### Scenario: 嵌入器只解析一次
- **WHEN** 在装配阶段决定是否启用混合并随后构建索引
- **THEN** 嵌入器仅解析一次，且被复用于索引构建（不重复解析、不重复告警）

### Requirement: 混合顶替原生记忆工具的取舍默认触发

当混合检索启用时（现包含「已配置嵌入模型」的默认路径），pig 以同名 `memory_search` 取代原生搜索工具，其 `disableMemoryTools()` MUST 同时关闭原生 `memory_get`/`memory_save`/`session_search`（含 `session_list`/`session_history`）——本取舍 SHALL 在「已配置嵌入模型且 `hybrid-enabled` 为 auto」时**默认触发**（不再仅限显式开关）。原生 flush/consolidation **hooks** MUST 保留（`MEMORY.md`/日志层照常写、durable 事实自动落盘），使检索质量提升而记忆写路径不受损。系统 SHALL 提供逃生舱：显式 `hybrid-enabled: false` MUST 恢复原生纯关键词 `memory_search` 与全部原生记忆工具，供需要 `session_search` 等工具的用户选择。

#### Scenario: 默认路径下顶替原生记忆工具
- **WHEN** 已配置嵌入模型、`hybrid-enabled` 为 auto，混合检索默认启用
- **THEN** 模型看到的 `memory_search` 为 pig 混合工具，原生 `memory_get`/`memory_save`/`session_search` 不再暴露

#### Scenario: 记忆写路径不受影响
- **WHEN** 混合默认路径顶替了原生记忆工具
- **THEN** 原生 flush/consolidation hooks 仍把 durable 事实写入 `MEMORY.md`/日志层（写路径不受损）

#### Scenario: 逃生舱恢复原生工具集
- **WHEN** 用户需要 `session_search` 等原生工具，显式设 `hybrid-enabled: false`
- **THEN** 恢复原生纯关键词 `memory_search` 与全部原生记忆工具

### Requirement: 真实嵌入器往返离线覆盖与 live 延后

系统对真实嵌入器（OpenAI 兼容 `/embeddings` HTTP）的**请求构造/响应解析纯逻辑** MUST 离线可测（经可注入 HTTP seam 喂 canned JSON）：MUST 覆盖 (a) 请求体 `{model,input}` 构造与 `/embeddings` URL 拼接（容忍 base URL 尾部斜杠）；(b) 解析 `data[0].embedding` 为向量并 **L2 归一化**，且解析出的**维度**与响应向量长度一致；(c) **错误分类**——无 `data`/空向量/非 2xx 响应 MUST 抛出携带异常类型（不含凭据）的错误，并由检索门面捕获降级为 BM25-only（不崩溃、不抛给调用方）。真实 `/embeddings` **网络往返**与真实嵌入**召回质量** MAY 延后到 live-model 集成测试（`/ls:itest`）；本能力的离线正确性 MUST NOT 依赖真实网络。凭据 MUST NOT 出现在异常、日志或输出中。

#### Scenario: 请求/响应纯逻辑离线可测
- **WHEN** 以 canned `/embeddings` JSON 响应喂给注入了 fake HTTP seam 的嵌入器
- **THEN** 嵌入器正确构造请求体、拼接 `/embeddings` URL、解析出向量并 L2 归一化，维度与响应一致，全程无真实网络

#### Scenario: 错误分类离线可测
- **WHEN** canned 响应无 `data`、或向量为空、或 HTTP 非 2xx
- **THEN** 嵌入器抛出携带异常类型（不含凭据）的错误，检索经门面降级为 BM25-only，不崩溃

#### Scenario: 真往返延后集成测试
- **WHEN** 需要验证真实 `/embeddings` 网络往返与召回质量
- **THEN** 该验证在 live-model 集成测试（`/ls:itest`）进行，不在本能力的离线单测内
