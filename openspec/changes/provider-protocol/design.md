## Context

需求经 `/ls:dev` 澄清门确认（2026-07-03）：把 provider 模块从"按厂商写死"重构为"按协议标准"。现状链路：`OnboardingWizard` / `/model add` 让用户选一个 `AgentOnboardingProvider`（6 个厂商类之一）→ 存 `StoredModel{providerId,...}` 到 `models.json` → `ModelManager.buildModel` 经 `ProviderRegistry.findById(providerId)` 拿到 provider → `provider.createModel(ModelSpec)` 造出 AgentScope `Model`。

关键观察：现有 6 个 provider 里，5 个已是天然的"一协议一类"（各自绑定 `AnthropicChatModel` / `OpenAIChatModel` / `GeminiChatModel` / `OllamaChatModel` / `DashScopeChatModel`），唯有 `MimoProvider` 复用 `OpenAIChatModel` + 写死 baseUrl——它就是"按厂商"设计冗余的活标本。因此重构的实质是：**把 SPI 语义正名为"协议"、删掉唯一的厂商冗余类、精简与厂商 env-var 强耦合的死代码**。

已确认的事实：
- `MimoProvider.createModel` = `OpenAIChatModel.builder().apiKey().baseUrl().modelName()`，与 `OpenAiProvider` 唯一差别是默认 baseUrl（`https://api.xiaomimimo.com/v1/chat/completions`）与默认模型名 → 用 `openai` 协议 + 手填该 baseUrl 完全等价。
- env-fallback 方法 `requiredCredentialKeys()` / `isAvailable()` / `createModelFromEnv()` / `ProviderRegistry.getAvailableProviders()` 在 `PigAgentCli` 主接线里**从未被调用**（grep 确认），是历史残留死代码。
- `ProviderCredentials`（`io.pigagent.core.provider`）仅被自身单测引用，与协议选择链路无关。

约束：AgentScope 1.0.12；领域类型不可变（record + `withXxx`）；本机 Maven 经 wrapper 可编译/测试验证；破坏式变更已获授权（无需迁移代码）。

## Goals / Non-Goals

**Goals:**
- provider 模块按 **5 套协议标准**建模：`openai` / `anthropic` / `gemini` / `ollama` / `dashscope`。
- SPI 正名 `AgentOnboardingProvider` → `ModelProtocol`，标识 `providerId` → `protocolId`，贯穿 `ModelSpec` / `StoredModel` / `models.json` / registry / onboarding / CLI。
- 删除 `MimoProvider`；OpenAI 兼容厂商一律经 `openai` 协议 + baseUrl 接入。
- 选择体验"纯协议 + 手填"：选协议 → baseUrl → key → 模型名。
- 精简 SPI，移除 env-var 强耦合死代码。

**Non-Goals:**
- 厂商预设清单（选 openai 后一键带出 DeepSeek/Kimi 的 baseUrl）——已决策不做（"纯协议 + 手填"）。
- 旧 `models.json` 自动迁移——已决策破坏式。
- 重命名 maven 模块 `pig-agent-providers` 或基础包 `io.pigagent.provider`——过度改动，仅更名类型。
- 改动 `ProviderCredentials` 或 legacy `application.yaml` 的 `model:` 展示块。
- 新增协议（如 bedrock / vertex）——本次仅覆盖现有 5 套。

## Decisions

- **D1 — SPI 正名为协议，保留形态。** 现有 `AgentOnboardingProvider` 的形态（一个方法 `createModel(ModelSpec)` + 若干元信息）本就是协议适配器，只是命名为"provider/厂商"。新接口 `ModelProtocol`（`io.pigagent.core.protocol`）：
  ```java
  public interface ModelProtocol {
      String protocolId();                 // openai|anthropic|gemini|ollama|dashscope
      String displayName();
      String description();
      String defaultModelName();
      Model createModel(ModelSpec spec);
      default boolean requiresApiKey()  { return true; }
      default boolean supportsBaseUrl() { return false; }
  }
  ```
  移除 `requiredCredentialKeys()` / `isAvailable()` / `createModelFromEnv()`（死代码）。

- **D2 — `ModelSpec` / `StoredModel` 字段更名。** `ModelSpec(String protocolId, apiKey, baseUrl, modelName)`（移到 `io.pigagent.core.protocol`）；`StoredModel(id, protocolId, apiKey, baseUrl, modelName)`，`label()` = `protocolId + " / " + modelName`，`withXxx` 不变。`JsonModelStore.Entry` 字段 `providerId` → `protocolId`。

- **D3 — 破坏式，不迁移。** 旧 `models.json` 的 `providerId` 字段在新 `Entry` 上无对应 setter（`@JsonIgnoreProperties(ignoreUnknown=true)` 已存在）→ `protocolId` 反序列化为 null → `ModelManager.isConfigured()`（`registry.findByProtocol(null)` 为空）判否 → 触发 onboarding 重配。零迁移代码，符合决策。不做旧字段兼容读取。

- **D4 — 5 协议实现，删 Mimo。** provider 模块内：
  - `openai/OpenAiProtocol`（`OpenAIChatModel`，`supportsBaseUrl=true`，默认 `gpt-4o`）——**OpenAI 兼容厂商统一入口**。
  - `anthropic/AnthropicProtocol`（`AnthropicChatModel`，`supportsBaseUrl=true`，默认 `claude-sonnet-4-6`）。
  - `gemini/GeminiProtocol`（`GeminiChatModel`，默认 `gemini-2.0-flash`）。
  - `ollama/OllamaProtocol`（`OllamaChatModel`，`requiresApiKey=false`，`supportsBaseUrl=true`，默认 baseUrl `http://localhost:11434`，默认 `llama3.2`）。
  - `dashscope/DashScopeProtocol`（`DashScopeChatModel`，默认 `qwen-max`）——保留原生 SDK。
  - **删除** `mimo/MimoProvider`。

- **D5 — registry 更名。** `ProviderRegistry` → `ProtocolRegistry`：`register(ModelProtocol)` / `findByProtocol(String):Optional<ModelProtocol>` / `getAllProtocols():List<ModelProtocol>`。移除 `getAvailableProviders()`（死代码）。

- **D6 — CLI/onboarding 走协议。** `OnboardingWizard`："Select a protocol" 列 `getAllProtocols()`；`/model add` 同理；`/providers` 命令更名 `/protocols`（列协议类型 + active 标记），`/help` 同步。`ReplContext.registry()` 返回类型改 `ProtocolRegistry`。

- **D7 — 保留模块名与基础包。** 不改 maven 模块 `pig-agent-providers` 与包 `io.pigagent.provider.*`（仅类型更名 `*Provider`→`*Protocol`）；不动 `ProviderCredentials` 与 legacy `model:` 展示块。理由：KISS/YAGNI，把改动锁在语义与冗余消除上，避免整模块改名的巨量 import 抖动。

## Risks / Trade-offs

- **破坏已有用户配置**：升级后现有 `models.json` 失效需重配。缓解：项目仍 SNAPSHOT、已决策接受；`isConfigured()` 判否会自然走 onboarding，不崩（`JsonModelStore` 容错已有）。
- **模块名/包名与"协议"语义不完全一致**（模块仍叫 providers、包仍 `io.pigagent.provider`）：接受此不一致以换取低改动面；类型名（`ModelProtocol` / `*Protocol` / `ProtocolRegistry`）已充分表达语义。
- **删除 env-fallback 死代码**：若未来想恢复"env var 直接起模型"，需重新设计（届时按协议+约定 env 更合理，而非厂商 env）。当前无使用者，删除零风险。
- **承重假设**：本重构不引入新框架语义（`*ChatModel.builder().baseUrl()` 均为现有已用 API），无需 spike。唯一需真机验证的是"openai 协议 + 自定义 baseUrl 能连通对话"——放到 `/ls:itest` 外环用真实 endpoint 验证。

## Migration Plan

破坏式，无自动迁移。发布说明需提示："本版本模型配置格式变更，升级后请重新运行 onboarding 配置模型（旧 `models.json` 将被判为未配置并触发重配）。" 无 DB、无脚本。

## Open Questions

无。四项关键决策（协议范围 / 迁移策略 / 选择体验 / 分支类型）已在澄清门确认。
