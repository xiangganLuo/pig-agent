## Context

pig 模型层现状（`pig-agent-model` + `pig-agent-providers` + `pig-agent-cli`，均在 main）：

- `StoredModel(id, protocolId, apiKey, baseUrl, modelName)`——一个**扁平记录**，无类别/kind 概念；经 `JsonModelStore` 持久化到 `models.json`（`{ defaultModelId, models[] }`，`Entry` 逐字段镜像）。
- `ModelStore`：`findAll`/`findById`/`save`/`deleteById`/`getDefaultId`/`setDefaultId`——**单一集合 + 单一默认指针**。
- `ModelManager`：经 `ModelProtocol.createModel(ModelSpec)` 建 AgentScope **chat** `Model`；`test(StoredModel)` = 建模型 + 一个极小的 chat「ping」探针（`PigAgent` 连通性），25s 有界超时，经 `ModelErrorMessages` 映射成友好 redacted 的 `TestResult(ok,error)`；`runProbe(Callable, timeout)` 为包私有 + 可注入（超时/映射离线可测）。
- `ModelProtocol`：`protocolId`/`displayName`/`description`/`defaultModelName`/`createModel(ModelSpec)`/`requiresApiKey()`/`supportsBaseUrl()`——**只建 chat `Model`**，无嵌入方法。`ModelSpec(protocolId, apiKey, baseUrl, modelName)`——无 path/endpoint kind 概念。
- `/model`（`ReplCommands.ModelCommand`）：list/add/switch/edit/delete + 无参 inline picker；`addModel` = 选 protocol → API key（掩码 `readLine(prompt,'*')`）→ base URL → model name → `test` → `add`。`OnboardingWizard`：选 protocol → key（`Console.readPassword`，无 console 降级可见 + 一次性 warn）→ base URL → model name → `test` → 存为默认；不可跳过，直到一个 chat 模型成功。

嵌入侧现状（`hybrid-memory-search` / `memory-retrieval-injection` 引入，均在 main）：

- `Embedder` seam（`float[] embed(String)`）；`OpenAiCompatibleEmbedder(baseUrl, apiKey, modelName [, HttpPost])`——POST `baseUrl + /embeddings`，`Authorization: Bearer`，请求体 `{model,input}`，解析 `data[0].embedding`，L2-归一化；`HttpPost` 为可注入 seam。**纯请求构建/响应解析（`buildRequestBody`/`parseEmbedding`/`embeddingsUrl`）+ L2-归一化已离线单测**；真 `/embeddings` 往返延后 `/ls:itest`（OD7）。
- `AgentBootstrap.resolveEmbedder(embedderModelId, modelManager)`（约 `AgentBootstrap.java:963`）：`embedderModelId` 空 → `null`（BM25-only）；否则 `modelManager.resolveStoredModel(embedderModelId)`（**一个普通 chat `StoredModel`**）→ `new OpenAiCompatibleEmbedder(m.baseUrl(), m.apiKey(), m.modelName())`。由 `buildMemorySearchIndex`（hybrid）与 `buildMemoryInjection`（M-A）共同消费，二者各自读 `cfg.getEmbedderModelId()`。

**痛点**：嵌入模型不是一等公民——只能借一个 chat `StoredModel` 的凭据凑合；没有类别标记，故无法用 `/embeddings` 探针正确测试、无法在 `/model`/onboarding 中作为独立实体呈现、没有独立的「默认嵌入模型」指针。M-B 要在此之上翻默认检索路径，必须先有干净的嵌入模型层。

## Goals / Non-Goals

**Goals**
- 「嵌入模型」升为模型层一等公民：可存储/选择一个 OpenAI 兼容 `/embeddings` 端点 + 其凭据，与默认 chat 模型并列。
- `/model` 与 onboarding 的嵌入模型 UX（onboarding 可选可跳过）。
- `/embeddings` 连通性测试，与 chat 连通性测试同构（有界、友好 redacted 错误）。
- `resolveEmbedder` 把嵌入 `StoredModel` 解析为 `OpenAiCompatibleEmbedder`（`HttpPost` seam 保留）。
- **默认不配嵌入模型 → BM25-only（今日行为，零回归）**。
- 凭据硬化沿用现有约定（掩码/`0600`/不回显）。

**Non-Goals**
- 翻默认检索路径（BM25-only → 混合）——属 **M-B**，本 spec 只提供嵌入器供给，不改默认。
- 真嵌入器/真 `/embeddings` 往返的离线验证——属 live-model，交 `/ls:itest`（`OpenAiCompatibleEmbedder` 纯逻辑已离线覆盖）。
- 为 anthropic/gemini/ollama/dashscope 各自实现原生嵌入端点——本 spec 限 **OpenAI 兼容 `/embeddings` 契约**（OpenAI 兼容厂商经 openai 协议 + baseUrl 覆盖 Doubao/DeepSeek/… 已足）；其余协议各自 embedder 登记为未来。
- 改 `ModelProtocol.createModel`（建 chat `Model` 路径不变，AgentScope 2.0 无嵌入 Model API）、`OpenAiCompatibleEmbedder` 请求/响应逻辑、`hybrid-memory-search`/`memory-retrieval-injection` 的检索内部。
- 嵌入模型的运行时热切换/多嵌入模型并存的高级编排（YAGNI；单一默认嵌入指针即可满足「选择一个」）。

## Spike（阻塞前置 Task 0 — 全案最关键决策，不过不进编码）

> **承重问题**：`StoredModel`/`ModelSpec`/`ModelProtocol` 的协议抽象能否**干净承载 embeddings 端点**（同一 protocol 的不同 endpoint/path），还是需新增一个 kind？结论必须先钉死，不过不进编码。

**S1 — 协议抽象与 embeddings 端点的关系（读现有代码核验）**
- AgentScope 2.0 **无嵌入 Model API**（OD7 既有结论 + `Embedder`/`OpenAiCompatibleEmbedder` 注释明确）。故嵌入 **不经** `ModelProtocol.createModel(ModelSpec)` 构建——那条路径产出的是 chat `Model`；嵌入由 `OpenAiCompatibleEmbedder` 消费，即对 `baseUrl + /embeddings` 的**原始 HTTP**（OpenAI `/embeddings` 契约）。
- 因此**协议抽象天然能承载 embeddings 端点**：同一个 OpenAI 兼容 protocol（`openai`）+ 同一个 `baseUrl` 可同时服务 chat（vendor SDK 的 completions 端点）与 embeddings（`/embeddings` path）；path 差异已封装在 `OpenAiCompatibleEmbedder.embeddingsUrl(baseUrl)` 内（追加 `/embeddings`），**不是** `ModelProtocol`/`ModelSpec` 的关切 → **无需新增按端点/path 的协议字段或方法**。
- `OpenAiCompatibleEmbedder` 构造只需 `(baseUrl, apiKey, modelName)`——恰好是 `StoredModel` 已有的三字段。故一个 `StoredModel` **在数据形状上已能承载嵌入配置**。

**S2 — 那到底缺什么？（为什么仅复用 chat StoredModel 不够）**
- 系统需要**区分**「这是嵌入配置」与「这是 chat 配置」，用于四处：(a) 连通性测试——chat ping 探针无法验证一个 embeddings-only 端点，必须走 `/embeddings` 探针；(b) `/model` 列表/选择——一等公民要能被单独呈现/选择；(c) 一个**独立的默认嵌入模型指针**（`defaultEmbeddingModelId`），区别于默认 chat 模型；(d) `resolveEmbedder` 的解析目标。
- 今天 `StoredModel` 无任何这样的标记，`resolveEmbedder` 只能盲借一个 chat `StoredModel`。故缺的是一个**最小的类别判别**，而非一整套平行存储/类型。

**Spike 净结论**：走**主路径**——
1. **复用协议 + `StoredModel` 形状承载 embeddings 端点**（同 openai 兼容 protocol + baseUrl；`/embeddings` path 是 `OpenAiCompatibleEmbedder` 细节）；
2. **新增最小 `kind` 判别（`CHAT` 默认 | `EMBEDDING`）到 `StoredModel` + 一个独立 `defaultEmbeddingModelId` 指针到 `ModelStore`/`models.json`**——使嵌入成为一等类别（正确测试 / UX 呈现 / 独立默认 / 解析目标）；
3. **不改** `ModelProtocol.createModel`（嵌入不建 AgentScope Model）。

`OpenAiCompatibleEmbedder` 的请求/响应逻辑离线核验通过（既有 `buildRequestBody`/`parseEmbedding`/`embeddingsUrl` + L2-归一化单测）；唯一 provider 真机相关件（真 `/embeddings` 往返、真嵌入质量）延后 `/ls:itest`——本 spec 的离线正确性（kind/store/UX/probe-mapping/resolve/零回归）与之无关。

**被否决备选**：
- (a) 新建独立 `EmbeddingModel` 类型 + 平行 `EmbeddingModelStore`/`embedding-models.json` —— **否决**（重复造轮子、YAGNI；创建成本高、与 chat 模型层割裂；`StoredModel` 三字段已够）。
- (b) 完全复用 chat `StoredModel`、不加 `kind`（今日 `resolveEmbedder` 做法）—— **否决**（无法区分嵌入/chat：chat probe 测不了 `/embeddings`、UX 无法把嵌入作为一等公民呈现、无独立默认指针）。
- (c) 在 `ModelSpec`/`ModelProtocol` 增按端点/path 的字段 —— **否决**（path 差异是 embedder 细节，非协议关切；徒增协议复杂度）。

## Decisions

- **D1 —（首条，承重）嵌入模型 = 复用 protocol/`StoredModel` 形状 + 新增最小 `kind` 判别 + 独立默认嵌入指针；不新建平行存储/类型；不改 `ModelProtocol.createModel`。** `StoredModel` 增 `kind`（枚举 `CHAT` 默认 | `EMBEDDING`）+ `withKind`；`ModelStore` 增 `getDefaultEmbeddingModelId`/`setDefaultEmbeddingModelId`；`JsonModelStore` 的 `Entry` 增 `kind`、`Data` 增 `defaultEmbeddingModelId`。理由见 Spike 净结论。**向后兼容**：老 `models.json` 无 `kind`/无 `defaultEmbeddingModelId` → 读回 `CHAT`/`null`（沿用 config-resilience 的「缺字段容错」）；聊天模型的 `defaultModelId` 语义不变。
- **D2 — 嵌入走 OpenAI 兼容 `/embeddings` 契约；`ModelProtocol.supportsEmbeddings()` 默认 `false`、`openai` → `true`。** 嵌入模型的 protocol 选择过滤到「advertise 支持嵌入」的协议（`openai`；OpenAI 兼容厂商——Doubao/DeepSeek/Kimi/…——经 `openai` 协议 + 各自 baseUrl，**不新增每厂商类**）。这是对 `ModelProtocol` 的**小而非破坏**扩展（default 方法，其余协议默认 `false`）。**备选**：允许任意协议、仅靠连通性测试验证 —— 记为可放宽路线，但默认按 `supportsEmbeddings` 过滤更诚实（anthropic/gemini/ollama/dashscope 的嵌入端点形状不同，需各自 embedder 实现，超范围）。具体是否落地此方法以编码期最小改动为准（若 UX 直接固定 openai 兼容流程亦可，design 记为首选 D2）。
- **D3 — 连通性测试按 `kind` 分派，复用 `runProbe` 的有界超时 + 友好 redacted 映射。** `ModelManager.test(StoredModel)`：`CHAT` → 今日 chat「ping」探针（不变）；`EMBEDDING` → 构建 `OpenAiCompatibleEmbedder`（用该 `StoredModel` 的 `baseUrl`/`apiKey`/`modelName`）+ `embed("ping")` 探针，经同一 `runProbe(Callable, PROBE_TIMEOUT_SECONDS)` 得 `TestResult`，失败经 `ModelErrorMessages.friendly` redacted（key 绝不回显）。**离线可测**：`runProbe` 已包私有 + 可注入，嵌入探针经注入 fake `Embedder`/`HttpPost` 覆盖成功/失败/超时映射；真 `/embeddings` 往返延后 IT。**备选**：嵌入模型不测、存了再说 —— 否决（与 chat「测过再存」不一致、体验差、错配到发现太晚）。
- **D4 — `resolveEmbedder` 解析顺序：显式 config `embedder-model-id` → `ModelStore` 默认嵌入模型 → `null`（BM25-only）。** `AgentBootstrap.resolveEmbedder`：(1) `embedder-model-id` 非空且可 `resolveStoredModel` → 用其凭据建 `OpenAiCompatibleEmbedder`；(2) 否则若 store 有 `defaultEmbeddingModelId` 且可解析 → 用之；(3) 否则 `null` → BM25-only。**保留** `OpenAiCompatibleEmbedder` 的 `HttpPost` seam。不可解析/异常 → `null`（容错、不抛、warn 日志、key 不入日志）。**放宽兼容（见 R5）**：`resolveEmbedder` 用 `StoredModel` 的凭据构建 embedder **不硬性要求 `kind==EMBEDDING`**——今天有人把 `embedder-model-id` 指向一个 chat 模型的历史用法仍工作（凭据形状相同）；`kind` 用于「测试分派 / `/model` 呈现 / 默认嵌入选择」，`resolveEmbedder` 宽松。**备选**：解析硬要求 `kind==EMBEDDING`，否则报错 —— 否决（破坏今日已配 `embedder-model-id=chat 模型` 的向后兼容）。
- **D5 — 凭据硬化沿用现有约定，不新造。** 嵌入模型 API key 输入：REPL `/model add|edit` 走 JLine `readLine(prompt,'*')` 掩码，`OnboardingWizard` 走 `Console.readPassword`（无 console 降级 + 一次性 warn）；`JsonModelStore` 落盘 `restrictToOwner`（POSIX `0600`）；`ModelManager.test` 失败经 `ModelErrorMessages` redacted、日志/UX 绝不回显 key（对齐 `credential-hardening` / `SecretRedactor` 约定）。
- **D6 — 默认零回归。** 无嵌入模型（无 `kind=EMBEDDING` 条目、`defaultEmbeddingModelId` 为 `null`、config `embedder-model-id` 空）→ `resolveEmbedder` 返回 `null` → `buildMemorySearchIndex`/`buildMemoryInjection` 得 BM25-only，逐字节等于本 spec 引入前；`OnboardingWizard` 的嵌入步为**可选可跳过**，跳过 = 今日（只配 chat 模型）；`/model` 既有 chat 流（add/list/switch/edit/delete + picker）不变。
- **D7 — 交叉依赖 / 边界显式登记（见 Risks R3/R4）。** E0 是 M-B 前置，仅供给解析出的 `Embedder`、不翻默认；与工具体系 `ToolRiskClassifier` 无关（模型层非工具）。

## Architecture

```
models.json  { defaultModelId, defaultEmbeddingModelId, models:[{id,kind,protocolId,apiKey,baseUrl,modelName}] }
        │  JsonModelStore（缺字段容错：kind→CHAT、defaultEmbeddingModelId→null）
        ▼
StoredModel(id, protocolId, apiKey, baseUrl, modelName, kind)      ← pig-agent-model
   kind ∈ { CHAT(默认), EMBEDDING }

ModelManager.test(m):
   m.kind==CHAT      → chat "ping" 探针（今日）  ┐ runProbe(callable, timeout)
   m.kind==EMBEDDING → OpenAiCompatibleEmbedder  ┘  → TestResult(ok, friendly-redacted-error)
                        .embed("ping") 探针

/model（ReplCommands.ModelCommand）           OnboardingWizard（可选步、可跳过）
   add: choose kind → protocol → key(掩码) → baseUrl → name → test → add
   list: 标注 kind；set-default-embedding
        │
        ▼
AgentBootstrap.resolveEmbedder(embedderModelId, modelManager):     ← pig-agent-cli
   1) config embedder-model-id 可解析 → OpenAiCompatibleEmbedder(creds)   (HttpPost seam 保留)
   2) 否则 store.defaultEmbeddingModelId 可解析 → 同上
   3) 否则 null  → BM25-only（今日行为，零回归）
        │
        ▼
   buildMemorySearchIndex / buildMemoryInjection（消费方不变；默认 null → BM25-only）
   （翻默认路径为混合 = M-B，本 spec 不做）
```

## Risks / Trade-offs

- **R1 —（承重）「协议抽象能承载 embeddings 端点」若不成立则要另起平行类型。** → Spike Task 0 已钉死（读现有代码核验：AgentScope 无嵌入 Model API、嵌入走 `OpenAiCompatibleEmbedder` 原始 HTTP、chat/embeddings 同 baseUrl 不同 path、`StoredModel` 三字段已够）；结论走「复用形状 + 最小 kind」，不过不进编码。
- **R2 — 真嵌入质量 / 真 `/embeddings` 往返未离线验。** → 延后 `/ls:itest`；离线全覆盖 `kind`/store 向后兼容、嵌入探针的成功/失败/超时映射（注入 fake `Embedder`/`HttpPost`）、`resolveEmbedder` 解析顺序与容错、默认 null → BM25-only 字节等价。
- **R3 —（交叉依赖，供给关系）E0 是 M-B 的前置。** M-B（真嵌入 + 混合默认路径）**消费** E0 解析出的真 `Embedder`，把 `hybrid-memory-search` 默认检索路径从 BM25-only 升级为混合；**E0 本身不翻默认**（`resolveEmbedder` 默认仍可能返回 null → BM25-only）。供给面清晰：E0 提供「嵌入模型类型 / 存储 / UX / 测试 / 解析成 `Embedder`」，M-B 在其上翻默认并做混合质量的 IT。**若同期落地，E0 先合**，M-B rebase 到 E0 后的 `resolveEmbedder`/store。
- **R4 —（交叉边界）与工具体系无关。** 嵌入模型是**模型层**实体，不是工具——**不进** `ToolRiskClassifier`，不受工具权限/可用性/沙箱门控。凭据硬化沿用 pig 现有 `credential-hardening` 约定，不新造机制。
- **R5 — 历史用法：`embedder-model-id` 指向 chat 模型。** 今日 `resolveEmbedder` 就是拿一个 chat `StoredModel` 的凭据建 embedder。→ D4 放宽：`resolveEmbedder` 按凭据构建 embedder **不硬要求** `kind==EMBEDDING`，故已配 `embedder-model-id=chat 模型` 的用户行为**不变**（向后兼容）；推荐但不强制指向 `kind=EMBEDDING`。
- **R6 — 非 openai 协议的嵌入端点形状不同。** anthropic/gemini/ollama/dashscope 的嵌入 API 未必是 OpenAI `/embeddings` 契约。→ 本 spec 限 OpenAI 兼容 `/embeddings`（D2 `supportsEmbeddings` 门控或 UX 固定 openai 兼容流程）；其余协议各自 embedder 实现登记为未来（超范围）。
- **R7 — 删除/切换嵌入模型的边界。** 删除当前默认嵌入模型应把 `defaultEmbeddingModelId` 置空（回落 BM25-only），而非崩溃——沿用 `JsonModelStore.deleteById` 对 `defaultModelId` 的既有容错模式（删除即清指针）。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 嵌入模型非一等公民、只借 chat StoredModel 凑合 | Why；D1（kind + 默认嵌入指针） | 落 tasks 2 |
| 承重 spike：协议抽象能否承载 embeddings 端点 / 需否新 kind | Spike S1/S2 + 净结论；D1 | 落 tasks 1（spike，已定论） |
| `StoredModel` 增 kind（CHAT 默认 \| EMBEDDING）+ 向后兼容 | D1；R7 | 落 tasks 2 |
| `ModelStore`/`models.json` 独立默认嵌入指针 | D1 | 落 tasks 2 |
| `/model` + onboarding 嵌入 UX（onboarding 可选可跳过） | D2/D5/D6 | 落 tasks 4 |
| `/embeddings` 连通性测试（对齐 chat 测试） | D3 | 落 tasks 3 |
| `resolveEmbedder` 解析嵌入模型 → OpenAiCompatibleEmbedder（HttpPost seam 保留） | D4 | 落 tasks 5 |
| 解析顺序 config id → store 默认嵌入 → null | D4 | 落 tasks 5 |
| 默认无嵌入模型 → BM25-only 零回归 | D6；R5 | 落 tasks 5；断言字节等价 tasks 2/5 |
| 凭据硬化沿用现有约定（掩码/0600/不回显） | D5 | 落 tasks 4 |
| 真 `/embeddings` live 往返延后 IT，离线覆盖 probe-mapping | R2；D3 | 落 tasks 6（IT，延后） |
| E0 是 M-B 前置、只供给不翻默认、同期 E0 先合 | D7；R3 | 已登记（供给关系） |
| 与 `ToolRiskClassifier`/工具体系无关 | R4 | 已登记（边界） |
| 限 OpenAI 兼容 `/embeddings`；其余协议 embedder 未来 | D2；R6 | 已登记（范围） |
| 删除默认嵌入模型 → 清指针回落 BM25-only | R7 | 落 tasks 2 |
