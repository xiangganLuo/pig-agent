## Why

provider 模块目前是**"一个厂商 = 一个类"**的写死结构：6 个类实现 `AgentOnboardingProvider`（anthropic / openai / gemini / ollama / dashscope / mimo）。其中 `MimoProvider` 已经暴露了这套设计的根本冗余——它与 `OpenAiProvider` 用的是**同一个底层** `OpenAIChatModel`，仅仅换了默认 baseUrl 和模型名。照此逻辑，每接入一个 OpenAI 兼容厂商（DeepSeek、Kimi、通义-compat…）都得新写一个类，不可持续。

主流做法（opencode、LiteLLM 等）是**按协议标准建模**：模块只定义少数几套主流协议（OpenAI 兼容、Anthropic、Gemini…），具体用哪个模型由用户**选协议 + 填 baseUrl / API key / 模型名**决定。本变更把 provider 模块从"按厂商"重构为"按协议"，让新厂商接入退化为一次配置而非一次编码。

## What Changes

- **SPI 语义从"厂商"转为"协议"**：`AgentOnboardingProvider` → `ModelProtocol`（`io.pigagent.core.protocol`），核心标识字段 `providerId` → `protocolId`。同步 `ModelSpec.providerId` → `protocolId`。
- **收敛为 5 套协议标准**：`openai`（OpenAI 兼容，吃掉 mimo / deepseek / kimi / 通义-compat 等一切兼容厂商，各自填 baseUrl）、`anthropic`、`gemini`、`ollama`、`dashscope`（原生 SDK）。**删除 `MimoProvider`**（其能力经 `openai` 协议 + 小米 baseUrl 等价获得）。
- **精简 SPI**：移除与厂商环境变量强耦合的死代码 `requiredCredentialKeys()` / `isAvailable()` / `createModelFromEnv()`（主接线从不调用，模型经 `models.json` + onboarding 配置）；保留 `requiresApiKey()` / `supportsBaseUrl()`。
- **持久化字段更名**：`StoredModel.providerId` → `protocolId`；`models.json` 的 Entry 字段 `providerId` → `protocolId`。**破坏式**（已决策）：旧 `models.json` 反序列化后 `protocolId` 为 null → `isConfigured()` 判否 → 触发 onboarding 重配，不写迁移代码。
- **选择体验"纯协议 + 手填"**：onboarding 与 `/model add` 列出的是**协议**（5 个）而非厂商；用户选协议 → 填 baseUrl（openai/ollama 相关）→ 填 API key → 填模型名。
- **CLI 更名**：`/providers` → `/protocols`（列出协议类型 + active 标记）；`ProviderRegistry` → `ProtocolRegistry`；provider 模块内 5 个类更名为 `*Protocol`。

## Capabilities

### New Capabilities
- `model-protocol`: 按协议标准（openai / anthropic / gemini / ollama / dashscope）定义模型接入，用户经"选协议 + baseUrl + key + 模型名"配置任意兼容厂商的模型，无需为新厂商新增代码。

### Modified Capabilities
<!-- 无既有 spec 需修改；本变更取代旧的"按厂商 Provider"隐式约定（此前无独立 spec） -->

## Impact

- **代码**：`pig-agent-core`（`ModelProtocol` + `ModelSpec` 更名/移包）、`pig-agent-providers`（5 协议类更名、删 Mimo、`ProtocolRegistry`）、`pig-agent-model`（`StoredModel` / `JsonModelStore` / `ModelManager` 走 protocolId）、`pig-agent-onboarding`（协议选择文案）、`pig-agent-cli`（`/protocols`、`/model add` 协议选择、`PigAgentCli` 注册 5 协议）。
- **配置/数据**：`models.json` 字段 `providerId`→`protocolId`，**不向后兼容**——升级后需重新 onboarding（本项目仍 `0.1.0-SNAPSHOT`，可接受）。
- **不改动**：`ProviderCredentials`（独立的 env-var 容器，非协议选择链路，保持原样）；legacy `application.yaml` 的 `model:` 展示块（`models.json` 为唯一真源）；`pig-agent-providers` 模块名与 `io.pigagent.provider` 包名（KISS：仅更名类型，不做整模块改名的过度改动）。
- **测试**：`StoredModelTest` / `ReplCommandsTest` 及各 `*IT`（`AnthropicConnectivityIT` / `FullLinkAgentIT` / `PermissionEnforcementIT` / `PermissionVetoSpikeIT`）中构造 `StoredModel` 的 providerId 参数改为 protocolId。
- **文档**：`CLAUDE.md` provider 模块章节 + `README`（中文）模型配置章节。
