## Why

pig 有「聊天模型」的完整模型层（`StoredModel`/`ModelStore`/`ModelManager` + `/model` UX + 连通性测试 + onboarding），但**没有「嵌入模型」这个一等公民**：`StoredModel` 无嵌入类别、`/model` 无嵌入 UX、无 `/embeddings` 连通性测试。今天 `AgentBootstrap.resolveEmbedder` 只能**复用一个普通 chat `StoredModel` 的凭据**去打 `/embeddings`（临时凑合）。

记忆线 **M-B（真嵌入 + 混合默认路径）硬依赖**一个能承载 OpenAI 兼容 `/embeddings` 端点的「嵌入模型」——它要消费 E0 解析出的真嵌入器，把 `hybrid-memory-search` 的默认检索路径从 BM25-only 升级为混合。本 spec 是内核路线图 **Wave-2 的 E0 嵌入模型层**，作为 M-B 的**前置独立 spec**：把「嵌入模型」升为模型层一等公民（可存储/选择/测试/解析），而 M-B 之后才在其上翻默认。

## What Changes

- **`StoredModel` 增「嵌入模型」类别（`kind` 判别：`CHAT` 默认 | `EMBEDDING`）** + `ModelStore`/`models.json` 增一个**独立的默认嵌入模型指针**（`defaultEmbeddingModelId`），使一个 OpenAI 兼容 `/embeddings` 端点 + 其凭据可被存储/选择为一等实体，与默认 chat 模型并列。向后兼容：老 `models.json` 无 `kind` 字段 → 读回为 `CHAT`（config-resilience 模式）。
- **`/model` UX 支持嵌入模型**：`/model add` 可选择新增一个嵌入模型（choose kind → 复用既有 protocol/key/url/name 流程 + 掩码输入），`/model list` 标注 kind，可将某嵌入模型设为默认嵌入模型；**onboarding 可选**新增一步（可跳过）。
- **`/embeddings` 连通性测试**：`ModelManager.test` 按 `kind` 分派——`CHAT` 走今日 chat ping 探针，`EMBEDDING` 走 `/embeddings` 探针（构建 `OpenAiCompatibleEmbedder` + `embed("ping")`），复用同一有界超时 + 友好 redacted 错误映射（`ModelErrorMessages`）+ `TestResult`。
- **`AgentBootstrap.resolveEmbedder` 解析嵌入模型 → `OpenAiCompatibleEmbedder`**：解析顺序＝显式 config `embedder-model-id`（若设且可解析）→ 否则 `ModelStore` 的默认嵌入模型 → 否则 `null`（BM25-only）。**保留** `OpenAiCompatibleEmbedder` 现有的 `HttpPost` 注入 seam；不可解析/异常 → `null`（容错，不抛）。
- **凭据硬化沿用 pig 现有约定**：嵌入模型的 API key 输入走掩码（REPL `readLine(prompt,'*')` / onboarding `Console.readPassword`），`JsonModelStore` 落盘 `0600`，错误/日志/UX **绝不回显** key。
- **默认无嵌入模型 → BM25-only（今日行为，零回归）**：不配嵌入模型（无 `kind=EMBEDDING` 条目、无 `defaultEmbeddingModelId`、config `embedder-model-id` 空）时 `resolveEmbedder` 返回 `null` → 检索层 BM25-only，逐字节等于本 spec 引入前；嵌入模型可选、不配则一切照旧。

**非破坏**：嵌入模型可选、默认不配；`ModelProtocol.createModel`（建 chat `Model`）不变；聊天模型的存储/UX/测试/解析全部不变。

## Capabilities

### New Capabilities
- `embedding-model-layer`: 把「嵌入模型」升为模型层一等公民——`StoredModel` 的 `kind` 判别（`CHAT`|`EMBEDDING`）+ `ModelStore` 的独立默认嵌入模型指针，可存储/选择一个 OpenAI 兼容 `/embeddings` 端点及其凭据；`/model` 与 onboarding 的嵌入模型 UX；`/embeddings` 连通性测试（对齐 chat 连通性测试）；`resolveEmbedder` 把嵌入 `StoredModel` 解析为 `OpenAiCompatibleEmbedder`（`HttpPost` seam 保留）。默认不配嵌入模型 → BM25-only（今日行为，零回归）。凭据硬化沿用现有约定（掩码/`0600`/不回显）。

### Modified Capabilities
<!-- 无。嵌入模型层是模型层之上的独立叠加能力：默认不配嵌入模型时行为逐字节等于今日（resolveEmbedder 返回 null → BM25-only），故不改写既有 chat 模型/hybrid-memory-search/memory-retrieval-injection 主 spec 的 Requirement——本 spec 只提供「嵌入器供给」，翻默认检索路径是后续 M-B 的职责。此策略与已归档的 hybrid-memory-search / memory-retrieval-injection 一致。 -->

## Impact

- **代码**：
  - `pig-agent-model`：`StoredModel` 增 `kind`（枚举）+ `withKind`；`ModelStore` 增 `getDefaultEmbeddingModelId`/`setDefaultEmbeddingModelId`；`JsonModelStore` 持久化 `kind` + `defaultEmbeddingModelId`（缺省容错 → `CHAT`/null）；`ModelManager.test` 按 `kind` 分派嵌入探针（复用 `runProbe`）。
  - `pig-agent-cli`：`ReplCommands.ModelCommand`（add/list/switch/edit 支持 kind + 设默认嵌入模型）；`AgentBootstrap.resolveEmbedder`（解析顺序：config id → store 默认嵌入 → null；仍供 `buildMemorySearchIndex`/`buildMemoryInjection` 消费）。
  - `pig-agent-onboarding`：`OnboardingWizard` 可选、可跳过的嵌入模型步。
  - `pig-agent-core`（可能）：`ModelProtocol.supportsEmbeddings()` 默认 `false`、`openai` → `true`（以 design 为准；OpenAI 兼容厂商经 openai 协议 + baseUrl，不新增每厂商类）。
- **不改**：`ModelProtocol.createModel`（建 chat `Model` 的路径不变，AgentScope 2.0 无嵌入 Model API）；`OpenAiCompatibleEmbedder` 的请求/响应逻辑（复用不改，仅由 `resolveEmbedder` 用嵌入 `StoredModel` 的凭据构造）；`hybrid-memory-search`/`memory-retrieval-injection` 的默认 BM25-only 路径（翻默认属 M-B）；权限/沙箱/渠道语义。
- **交叉依赖**（详见 `design.md`）：**E0 是 M-B（真嵌入 + 混合默认路径）的前置**——M-B 消费 E0 解析出的真嵌入器把 `hybrid-memory-search` 默认路径从 BM25-only 升级为混合，E0 本身**不翻默认**；与工具体系 `ToolRiskClassifier` **无关**（模型层非工具，不进 risk 分类）；凭据硬化沿用 pig 现有约定，不新造。
- **测试**：离线单测——**Group 1 承重 spike**（`StoredModel`/`ModelSpec`/`ModelProtocol` 能否干净承载 embeddings 端点、`OpenAiCompatibleEmbedder` 离线核验，结论落 design）；其后 `kind`/默认嵌入指针的存储与向后兼容、嵌入连通性探针的映射与超时（离线注入 fake embedder/`HttpPost`）、`resolveEmbedder` 解析顺序与容错、默认不配 → BM25-only 逐字节等价。**真 `/embeddings` live 往返 → `*IT`（`/ls:itest`），不在本 spec 跑**。
- **文档**：`CLAUDE.md` 模型层段落新增「嵌入模型（一等公民）」说明 + `models.json`/onboarding/`/model` 段同步。
