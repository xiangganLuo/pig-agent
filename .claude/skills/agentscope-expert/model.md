# Model Providers

> 源: `D:\Users\admin\Documents\en\docs\building-blocks\model.md`
> 源: `D:\Users\admin\Documents\en\integration\model\{anthropic,openai,gemini,dashscope,ollama}.md`

---

## 架构分层

```
CredentialBase
└── ChatModelBase
    ├── OpenAIChatModel      (io.agentscope.extensions.model.openai)
    ├── AnthropicChatModel   (io.agentscope.extensions.model.anthropic)
    ├── DashScopeChatModel   (io.agentscope.extensions.model.dashscope)
    ├── GeminiChatModel      (io.agentscope.extensions.model.gemini)
    └── OllamaChatModel      (io.agentscope.extensions.model.ollama)
```

`agentscope-core` 只保留公共接口：`Model`、`ChatModelBase`、`Formatter`、`ModelRegistry`、`ModelProvider` SPI。
各 provider 实现在独立扩展模块中，包名前缀均为 `io.agentscope.extensions.model.<provider>.*`。

---

## 五大 Provider — 类名 + Maven artifact

| Provider | 模型类 | Maven artifactId | 环境变量 |
|----------|--------|-----------------|---------|
| OpenAI | `OpenAIChatModel` | `agentscope-extensions-model-openai` | `OPENAI_API_KEY` |
| Anthropic | `AnthropicChatModel` | `agentscope-extensions-model-anthropic` | `ANTHROPIC_API_KEY` |
| DashScope | `DashScopeChatModel` | `agentscope-extensions-model-dashscope` | `DASHSCOPE_API_KEY` |
| Gemini | `GeminiChatModel` | `agentscope-extensions-model-gemini` | `GEMINI_API_KEY` |
| Ollama | `OllamaChatModel` | `agentscope-extensions-model-ollama` | `OLLAMA_BASE_URL`（可选，默认本地端点） |

所有 groupId 均为 `io.agentscope`，版本用 `${agentscope.version}`。

---

## Maven 依赖声明（示例）

```xml
<!-- OpenAI / 兼容端点 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- Anthropic -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-anthropic</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- DashScope -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- Gemini -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-gemini</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- Ollama -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-ollama</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

---

## 创建模型的三种路径

### 1. 字符串 model id（最简洁）

通过 `ModelRegistry` 的 `provider:model` 格式，扩展模块经 Java SPI 自动发现，读取标准环境变量：

```java
ReActAgent agent = ReActAgent.builder()
        .name("assistant")
        .model("dashscope:qwen-plus")    // ModelRegistry.resolve(modelId)
        .build();
```

其他示例：`"openai:gpt-4.1-mini"` / `"anthropic:claude-sonnet-4.5"` / `"gemini:gemini-2.0-flash"` / `"ollama:llama3"` / `"qwen-plus"`（DashScope 短形式）。

### 2. 显式 builder（推荐用于自定义配置）

```java
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.core.model.GenerateOptions;

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .stream(true)
        .formatter(new DashScopeChatFormatter())
        .defaultOptions(GenerateOptions.builder()
                .parallelToolCalls(false)
                .build())
        .build();
```

builder 通用字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `apiKey` | `String` | API 密钥 |
| `modelName` | `String` | 模型标识符 |
| `stream` | `boolean` | 是否流式输出（Gemini 用 `streamEnabled`） |
| `defaultOptions` | `GenerateOptions` | 温度/maxTokens/thinkingBudget/parallelToolCalls 等 |
| `formatter` | `Formatter` | 覆盖默认消息格式化器 |
| `baseUrl` | `String` | 自定义端点（OpenAI 兼容代理） |

### 3. Spring Boot starter

| Starter artifactId | Provider |
|-------------------|----------|
| `agentscope-openai-spring-boot-starter` | OpenAI |
| `agentscope-anthropic-spring-boot-starter` | Anthropic |
| `agentscope-dashscope-spring-boot-starter` | DashScope |
| `agentscope-gemini-spring-boot-starter` | Gemini |
| `agentscope-ollama-spring-boot-starter` | Ollama |

```yaml
agentscope:
  model:
    provider: openai
  openai:
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-4.1-mini
    stream: true
```

每个 starter 暴露对应 customizer bean（如 `OpenAIChatModelBuilderCustomizer`）用于 builder-only 选项微调。

---

## 流式 API：`Model.stream(...)`

`Model` 接口（`io.agentscope.core.model.Model`）统一暴露：

```java
Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options)
```

`ChatResponse` 包含内容块列表（`TextBlock` / `ThinkingBlock` / `ToolUseBlock` / `DataBlock`）以及 `ChatUsage`（token 计数 + 耗时）。

实现入口：`ChatModelBase.doStream(List<Msg>, List<ToolSchema>, GenerateOptions)` — 自定义 provider 继承此方法。

```java
// 直接调用示例
model.stream(
        List.of(new UserMessage("Count from 1 to 5.")),
        List.of(),
        GenerateOptions.builder().build())
    .doOnNext(chunk -> System.out.println(chunk.getContent()))
    .blockLast();
```

---

## OpenAI 兼容端点（DeepSeek、Kimi、Qwen-compat 等）

使用 `OpenAIChatModel` + `baseUrl(...)` 即可，无需额外代码：

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;

OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("...")
        .baseUrl("https://api.moonshot.cn/v1")
        .modelName("moonshot-v1-8k")
        .stream(true)
        // 兼容端点 + 结构化输出 + 工具调用同时存在时，可能需要：
        .nativeStructuredOutputWithTools(false)
        .build();
```

`DeepSeekCredential`、`KimiCredential`、`XAICredential` 等 OpenAI 兼容 Credential 类来自 `agentscope-core`（非扩展模块）。

---

## Formatter

每个 provider 扩展模块提供两种格式化器（均在 `io.agentscope.extensions.model.<provider>.formatter` 包下）：

| Provider | 单代理 | 多代理 |
|----------|--------|--------|
| DashScope | `DashScopeChatFormatter` | `DashScopeMultiAgentFormatter` |
| OpenAI | `OpenAIChatFormatter` | `OpenAIMultiAgentFormatter` |
| Anthropic | `AnthropicChatFormatter` | `AnthropicMultiAgentFormatter` |
| Gemini | `GeminiChatFormatter` | `GeminiMultiAgentFormatter` |
| Ollama | `OllamaChatFormatter` | `OllamaMultiAgentFormatter` |

---

## ModelRegistry 高级用法

```java
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.GenerateOptions;

// 动态创建（多租户/插件场景，不缓存）
ModelCreationContext context = ModelCreationContext.builder()
        .apiKey(tenantApiKey)
        .baseUrl(tenantBaseUrl)
        .stream(false)
        .option("contextWindowSize", 128000)
        .component(GenerateOptions.class, GenerateOptions.builder()
                .parallelToolCalls(false).build())
        .build();

Model model = ModelRegistry.resolve("openai:gpt-4.1-mini", context);

// 注册自定义工厂
ModelRegistry.registerFactory(
        "myprov:.*",
        modelId -> new MyProviderChatModel(
                new MyProviderCredential(System.getenv("MYPROV_API_KEY"), null),
                modelId.substring("myprov:".length())));
```

缓存策略：`DEFAULT`（简单字符串 id 缓存，带 context 不缓存）/ `DISABLED` / `ENABLED`（需提供 `cacheId`）。

---

## Credential 与 ModelCard

```java
import io.agentscope.extensions.model.anthropic.credential.AnthropicCredential;
import io.agentscope.core.credential.ModelCard;

AnthropicCredential cred = new AnthropicCredential(System.getenv("ANTHROPIC_API_KEY"));
List<ModelCard> cards = cred.listModels().block();  // Mono<List<ModelCard>>
// card.modelName() / card.displayName() / card.contextSize()
```

各 provider Credential 类在扩展模块内（如 `io.agentscope.extensions.model.anthropic.credential.AnthropicCredential`）；`DeepSeekCredential` / `KimiCredential` / `XAICredential` 在 `agentscope-core`。

---

## DashScope 推理模型（Thinking）

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-235b-a22b-thinking-2507")
        .stream(true)
        .enableThinking(true)
        .formatter(new DashScopeChatFormatter())
        .defaultOptions(GenerateOptions.builder()
                .thinkingBudget(2048)
                .build())
        .build();
```

注意：DashScope thinking 模式**不支持结构化输出**，框架强制走 fallback 路径。

---

## 关键类速查

| 类/接口 | 包 |
|--------|-----|
| `Model` | `io.agentscope.core.model` |
| `ChatModelBase` | `io.agentscope.core.model` |
| `GenerateOptions` | `io.agentscope.core.model` |
| `ChatResponse` | `io.agentscope.core.model` |
| `ModelRegistry` | `io.agentscope.core.model` |
| `ModelCreationContext` | `io.agentscope.core.model` |
| `CredentialBase` | `io.agentscope.core.credential` |
| `ModelCard` | `io.agentscope.core.credential` |
| `OpenAIChatModel` | `io.agentscope.extensions.model.openai` |
| `AnthropicChatModel` | `io.agentscope.extensions.model.anthropic` |
| `DashScopeChatModel` | `io.agentscope.extensions.model.dashscope` |
| `GeminiChatModel` | `io.agentscope.extensions.model.gemini` |
| `OllamaChatModel` | `io.agentscope.extensions.model.ollama` |
| `AnthropicCredential` | `io.agentscope.extensions.model.anthropic.credential` |
