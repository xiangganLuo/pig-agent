## 1. pig-agent-core：ModelProtocol SPI + ModelSpec

- [x] 1.1 新增 `io.pigagent.core.protocol.ModelProtocol` 接口：`protocolId()` / `displayName()` / `description()` / `defaultModelName()` / `createModel(ModelSpec)` + default `requiresApiKey()`(true) / `supportsBaseUrl()`(false)。不含 env-fallback 方法。
- [x] 1.2 新增 `io.pigagent.core.protocol.ModelSpec`：`record ModelSpec(String protocolId, String apiKey, String baseUrl, String modelName)` + `hasApiKey()` / `hasBaseUrl()`。删除旧 `io.pigagent.core.provider.ModelSpec` 与 `AgentOnboardingProvider`。
- [x] 1.3 单测 `ModelSpecTest`（5/5 PASS）：hasApiKey/hasBaseUrl 对 null/blank/有值的判定（AAA）。
- [x] 1.4 `mvn -pl pig-agent-core test` 绿。

## 2. pig-agent-providers：5 协议实现 + ProtocolRegistry（删 Mimo）

- [x] 2.1 5 个协议类实现 `ModelProtocol`（更名 `*Provider`→`*Protocol`，`providerId`→`protocolId`）：`openai/OpenAiProtocol`(gpt-4o,supportsBaseUrl) / `anthropic/AnthropicProtocol`(claude-sonnet-4-6,supportsBaseUrl) / `gemini/GeminiProtocol`(gemini-2.0-flash) / `ollama/OllamaProtocol`(llama3.2,requiresApiKey=false,supportsBaseUrl,默认 baseUrl) / `dashscope/DashScopeProtocol`(qwen-max)。
- [x] 2.2 删除 `mimo/MimoProvider`。
- [x] 2.3 `registry/ProtocolRegistry`（更名 `ProviderRegistry`）：`register(ModelProtocol)` / `findByProtocol(String)` / `getAllProtocols()`；移除 `getAvailableProviders()`。
- [x] 2.4 `mvn -pl pig-agent-providers -am test` 编译通过（build order [3/13] SUCCESS）。

## 3. pig-agent-model：StoredModel / JsonModelStore / ModelManager 走 protocolId

- [x] 3.1 `StoredModel`：`providerId`→`protocolId`（record 字段 + `create` + `label()`），`withXxx` 保持。
- [x] 3.2 `JsonModelStore.Entry`：字段 `providerId`→`protocolId`（`from`/`toModel` 同步）。`@JsonIgnoreProperties(ignoreUnknown=true)` 已在 → 旧字段被忽略（破坏式）。
- [x] 3.3 `ModelManager`：`buildModel`/`isConfigured` 经 `registry.findByProtocol(m.protocolId())`；`ModelSpec` 用 protocolId 构造；import 改 `io.pigagent.core.protocol.*` + `ProtocolRegistry`。
- [x] 3.4 更新 `StoredModelTest`（providerId→protocolId）。`mvn -pl pig-agent-model -am test` 绿（8/8，无回归）。

## 4. pig-agent-onboarding + pig-agent-cli：协议选择 + /protocols

- [x] 4.1 `OnboardingWizard`：`getAllProviders`→`getAllProtocols`、`provider.providerId()`→`protocol.protocolId()`、文案 "Select a protocol"；纯协议+手填流程不变。
- [x] 4.2 `ReplContext.registry()` 返回 `ProtocolRegistry`；`ReplCommands`：`ProvidersCommand`→`ProtocolsCommand`（`/protocols`），`ModelCommand.addModel` 协议选择，`getAllProviders`→`getAllProtocols`、`providerId`→`protocolId`；`/help` 更新（`/providers`→`/protocols`）。`AgentRepl` 字段/构造器同步 `ProtocolRegistry`。
- [x] 4.3 `PigAgentCli`：import 5 个 `*Protocol`（删 Mimo import）、`new ProtocolRegistry()`、register 5 协议。
- [x] 4.4 `ReplCommandsTest` 未引用 /providers（无需改断言）。全模块 `mvn test` BUILD SUCCESS（13/13），全部单测通过无回归。

## 5. 集成测试（外环）与文档

- [x] 5.1 修复各 `*IT` 中 registry/协议引用（`*Provider`→`*Protocol`、`ProviderRegistry`→`ProtocolRegistry`、删 Mimo 注册）：`AnthropicConnectivityIT` / `FullLinkAgentIT` / `PermissionEnforcementIT` / `PermissionVetoSpikeIT`。
- [~] 5.2 外环：跑 `*IT`（真实 anthropic）。**阻塞于上游基础设施**：连续 3 轮均返回 `502 Upstream service temporarily unavailable`（上游代理网关的模型服务临时宕机，非代码缺陷）。`[SMOKE]` 日志证实协议链路端到端正确——新 `protocolId` 格式解析出 `anthropic / claude-sonnet-4-6` + 自定义 baseUrl、经 `AnthropicProtocol` 建模、带认证真实调用抵达端点（502 为 upstream_error 而非 401/403，认证通过）。待上游恢复后重跑即可绿。
- [x] 5.3 全模块 `mvn test` BUILD SUCCESS 无回归（13/13，全部单测通过；IT 源编译通过）。
- [x] 5.4 文档：`CLAUDE.md` provider 模块章节改为协议表述（5 协议、删 Mimo、/protocols、SPI 更名 `ModelProtocol`）；`README`（中文）模型协议章节 + 文件树 + 扩展示例同步。
