## 1. Spike（阻塞前置 — 全案最关键决策，不过不进编码）

> 目标：钉死「`StoredModel`/`ModelSpec`/`ModelProtocol` 能否干净承载 embeddings 端点，还是需新增一个 kind」。结论落 `design.md §Spike`。（读现有代码 + `OpenAiCompatibleEmbedder` 离线核验即可，无 provider 真机。）

- [x] 1.1 核验 AgentScope 2.0 **无嵌入 Model API**（`Embedder`/`OpenAiCompatibleEmbedder` 注释 + OD7 既有结论）→ 嵌入**不经** `ModelProtocol.createModel`（那产出 chat `Model`），而由 `OpenAiCompatibleEmbedder` 对 `baseUrl + /embeddings` 原始 HTTP 消费。
- [x] 1.2 核验协议抽象能承载 embeddings 端点：同一 openai 兼容 protocol + 同一 `baseUrl` 可服务 chat 与 embeddings；`/embeddings` path 已封装在 `OpenAiCompatibleEmbedder.embeddingsUrl(baseUrl)`，非 `ModelProtocol`/`ModelSpec` 关切 → 无需按端点/path 的协议字段。
- [x] 1.3 核验 `OpenAiCompatibleEmbedder` 构造仅需 `(baseUrl, apiKey, modelName)`（＝`StoredModel` 已有三字段）→ `StoredModel` 数据形状已能承载嵌入配置；缺的是**类别判别 + 独立默认指针**（用于测试分派 / UX 呈现 / 解析目标）。
- [x] 1.4 `OpenAiCompatibleEmbedder` 请求/响应逻辑离线核验：`buildRequestBody`/`parseEmbedding`/`embeddingsUrl` + L2-归一化已有离线单测；真 `/embeddings` 往返延后 `/ls:itest`。
- [x] 1.5 结论 + 理由（含被否决备选：新平行 `EmbeddingModel`+store / 无 kind 复用 chat / 在 ModelSpec 加 path 字段）记入 `design.md §Spike` 与 §Decisions D1。**净结论：复用 protocol+StoredModel 形状 + 新增最小 `kind`（CHAT|EMBEDDING）+ 独立 `defaultEmbeddingModelId`，不改 `createModel`。**

## 2. `StoredModel` kind + store（`pig-agent-model`）

- [ ] 2.1 `StoredModel` 增 `kind` 字段（枚举 `ModelKind{CHAT, EMBEDDING}`，默认 `CHAT`）+ `withKind`；`create(...)` 兼容重载（默认 `CHAT`），`label()` 可选标注类别。保持 record 不可变。
- [ ] 2.2 `ModelStore` 增 `getDefaultEmbeddingModelId()`/`setDefaultEmbeddingModelId(String)`；`deleteById` 删到默认嵌入模型时清空该指针（沿用 `defaultModelId` 的既有容错模式）。
- [ ] 2.3 `JsonModelStore`：`Entry` 增 `kind`（缺省 → `CHAT`）、`Data` 增 `defaultEmbeddingModelId`（缺省 → null）；`@JsonIgnoreProperties(ignoreUnknown=true)` 已有 → 老文件容错读回；落盘仍 `restrictToOwner`（`0600`）。
- [ ] 2.4 单测：`StoredModelTest`（kind 默认/withKind/不可变）；`JsonModelStoreTest`——保存/读回嵌入条目、默认嵌入指针；**老 `models.json` 无 kind → 读回 CHAT**、无默认嵌入指针 → null；删除默认嵌入模型清指针；聊天路径逐字节等价。

## 3. `/embeddings` 连通性测试（`pig-agent-model` `ModelManager`）

- [ ] 3.1 `ModelManager.test(StoredModel)` 按 `kind` 分派：`CHAT` → 今日 chat「ping」探针（不变）；`EMBEDDING` → 构建 `OpenAiCompatibleEmbedder`（该模型 `baseUrl`/`apiKey`/`modelName`）+ `embed("ping")` 探针，经同一 `runProbe(callable, PROBE_TIMEOUT_SECONDS)` → `TestResult`；失败经 `ModelErrorMessages.friendly` redacted（key 不回显）。
- [ ] 3.2 为离线可测保留/引入嵌入探针的可注入 seam（复用 `OpenAiCompatibleEmbedder` 的 `HttpPost`，或包私有可注入 `Embedder`）；`runProbe` 已包私有可注入。
- [ ] 3.3 单测：`ModelManagerTest`——嵌入探针成功 → `TestResult.success`；失败 → 友好 redacted；超时 → 清晰超时原因（注入 fake `Embedder`/`HttpPost`，无网络）；chat 分派回归不变。

## 4. `/model` + onboarding UX（`pig-agent-cli` + `pig-agent-onboarding`）

- [ ] 4.1 `ReplCommands.ModelCommand.addModel`：先选类别（chat/embedding）→ 复用协议/凭据（掩码 `readLine(prompt,'*')`）/base URL/模型名流程 → `test`（按 kind 分派）→ `add`（写正确 kind）；嵌入模型限 `supportsEmbeddings` 协议（或固定 openai 兼容流程，以 design D2 为准）。
- [ ] 4.2 `/model list` 标注每模型类别（chat/embedding，默认嵌入模型标 `*emb`/`[default-emb]` 之类）；新增设默认嵌入模型的子动作（如 `/model set-embedding <id|index>`）。
- [ ] 4.3 `OnboardingWizard`：配好 chat 模型后新增一步「是否配置嵌入模型？（可选，回车跳过）」——是 → 复用协议/`Console.readPassword`/base URL/模型名 + `/embeddings` 测试 + 存为默认嵌入模型；否 → 跳过（不存嵌入、行为等于今日）。chat 步仍不可跳过。
- [ ] 4.4 单测：`ModelSelection`/`ModelCommand` 相关——嵌入 add 走嵌入探针后保存 kind=EMBEDDING、设默认嵌入指针；`OnboardingWizard`（可注入 reader/console）——跳过嵌入步 = 只存 chat、行为等于今日；掩码/不回显路径断言。

## 5. 接线：`resolveEmbedder`（`pig-agent-cli` `AgentBootstrap`）

- [ ] 5.1 `AgentBootstrap.resolveEmbedder(embedderModelId, modelManager)` 解析顺序：(1) `embedder-model-id` 非空且 `resolveStoredModel` 命中 → `OpenAiCompatibleEmbedder(creds)`；(2) 否则 `modelStore.getDefaultEmbeddingModelId()` 可解析 → 同上；(3) 否则 `null`。保留 `HttpPost` seam；不可解析/异常 → `null` + warn（key 不入日志）；**不硬要求 `kind==EMBEDDING`**（R5 向后兼容）。
- [ ] 5.2 消费方不变：`buildMemorySearchIndex`/`buildMemoryInjection` 仍调 `resolveEmbedder`；默认（无嵌入模型）→ `null` → BM25-only；本 spec **不翻默认检索路径**（属 M-B）。
- [ ] 5.3 接线单测：`resolveEmbedder`——config id 命中 → 非 null embedder；config 空但有默认嵌入模型 → 命中默认；两者皆空/不可解析 → null（BM25-only）；既有指向 chat 模型的 `embedder-model-id` 仍构建 embedder（R5）。既有 `MemoryInjectionWiringTest`/`AgentFactoryTest` 等仍绿（默认路径字节等价）。

## 6. 集成测试（真模型 `*IT`，外环，延后 `/ls:itest`）

- [ ] 6.1 `EmbeddingModelIT`（真嵌入端点，可选凭据）：对一个真实 OpenAI 兼容 `/embeddings` 端点——(a) `ModelManager.test(embeddingModel)` 真往返成功/失败映射；(b) `resolveEmbedder` 解析出的 `OpenAiCompatibleEmbedder.embed(...)` 返回合理维度向量。真嵌入质量/召回属 M-B 的 IT，不在本 spec。

## 7. 验收 + 文档

- [ ] 7.1 `mvn -q test`（`pig-agent-model`/`pig-agent-cli`/`pig-agent-onboarding` 及全反应堆）全绿。
- [ ] 7.2 `mvn -pl pig-agent-cli -am compile` 绿。
- [ ] 7.3 `CLAUDE.md` 模型层段落新增「嵌入模型（一等公民：kind + 默认嵌入指针 + `/embeddings` 测试 + `resolveEmbedder`）」说明；`models.json`/onboarding/`/model` 段同步；显式记「默认无嵌入模型 → BM25-only、翻默认属 M-B」。
- [ ] 7.4 归档时（`/ls:archive`）同步主 spec → `openspec/specs/embedding-model-layer/`（本 change 的 `openspec validate --strict` 已在 spec 阶段通过）。
