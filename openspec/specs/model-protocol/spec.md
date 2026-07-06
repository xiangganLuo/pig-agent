# model-protocol Specification

## Purpose
按**协议标准**（而非具体厂商）建模 LLM 接入：模块定义 `openai` / `anthropic` / `gemini` / `ollama` / `dashscope` 五套协议，用户经"选协议 + 填 baseUrl / API key / 模型名"配置任意兼容厂商的模型，接入 OpenAI 兼容厂商无需新增代码。
## Requirements
### Requirement: 按协议标准定义模型接入
系统 SHALL 以**协议标准**（而非具体厂商）建模 LLM 接入，提供 `openai` / `anthropic` / `gemini` / `ollama` / `dashscope` 五套协议。每套协议 MUST 由一个 `ModelProtocol` 实现表达，负责从 `ModelSpec`（protocolId + 可选 apiKey + 可选 baseUrl + 模型名）构造对应的 AgentScope `Model`。系统 MUST NOT 为具体厂商单独建类；OpenAI 兼容厂商（如小米 mimo、DeepSeek、Kimi、通义-compat）MUST 经 `openai` 协议 + 各自 baseUrl 接入。

#### Scenario: 五套协议可选
- **WHEN** 用户在 onboarding 或 `/model add` 中查看可选项
- **THEN** 列出的是 `openai` / `anthropic` / `gemini` / `ollama` / `dashscope` 五个协议，而非厂商名清单

#### Scenario: OpenAI 兼容厂商经协议 + baseUrl 接入
- **WHEN** 用户选 `openai` 协议、填入小米 mimo 的 baseUrl 与模型名 `mimo-v2.5-pro`
- **THEN** 系统构造出经该 baseUrl 通信的 OpenAI 兼容模型，效果等价于此前的专用 Mimo 厂商类，且代码中不存在任何厂商专用类

### Requirement: 协议标识贯穿配置与持久化
模型配置的核心标识 SHALL 为 `protocolId`（取值为五套协议之一）。`ModelSpec` 与 `StoredModel` MUST 以 `protocolId` 字段承载该标识；持久化文件 `models.json` MUST 以 `protocolId` 键存储。`ModelManager` 构造模型时 MUST 经协议注册表按 `protocolId` 查找对应 `ModelProtocol`。

#### Scenario: 保存的模型按协议解析
- **WHEN** 一个 `StoredModel{protocolId=openai, baseUrl=..., modelName=...}` 被激活
- **THEN** `ModelManager` 经注册表按 `openai` 找到 `ModelProtocol` 并用其 `createModel` 造出模型

#### Scenario: 未知协议判为未配置
- **WHEN** `models.json` 中某条目的 `protocolId` 为 null 或不在五套协议内
- **THEN** 该配置被判为不可解析，`isConfigured()` 返回否，从而触发 onboarding 重配（不崩溃）

### Requirement: 纯协议选择体验
onboarding 与 `/model add` 的交互 SHALL 为"纯协议 + 手填"：先选协议，再按该协议是否 `supportsBaseUrl` / `requiresApiKey` 依次提示填写 baseUrl、API key、模型名。系统 MUST NOT 强制提供厂商预设清单。对 `requiresApiKey=false` 的协议（ollama）MUST NOT 要求 API key。

#### Scenario: 选协议后按需提示
- **WHEN** 用户选择 `ollama` 协议
- **THEN** 系统不索要 API key，提示可填 baseUrl（缺省 `http://localhost:11434`）与模型名

#### Scenario: openai 协议索要 baseUrl 与 key
- **WHEN** 用户选择 `openai` 协议
- **THEN** 系统提示填 API key 与可选 baseUrl（留空则用协议默认 endpoint）与模型名

### Requirement: 协议列表命令
系统 SHALL 提供 `/protocols` 命令列出全部协议类型及其展示名，并对当前激活模型所用协议标注 active。

#### Scenario: 列出协议并标注 active
- **WHEN** 用户执行 `/protocols`
- **THEN** 打印五套协议的 `protocolId` 与显示名，当前模型所用协议一行标注 `(active)`

