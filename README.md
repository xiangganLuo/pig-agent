<div align="center">

<img src="assets/logo.svg" alt="Pig Agent Logo" width="150"/>

# Pig Agent

**一个基于 AgentScope Java 构建的终端 AI Agent 框架**

*架构简单 · 开发者易学习 · 功能完整 · 方便扩展*

</div>

## 项目背景

Pig Agent 的设计灵感来源于 OpenCLAW、Hermes Agent 等终端智能体产品。目标是为 Java 开发者提供一个：

- **开箱即用**的终端 AI Agent
- **模块化架构**，各功能独立、易于替换
- **标准化接口**，基于 AgentScope 的 `@Tool`、`Hook`、`Memory` 等抽象
- **MCP 原生支持**，可连接任意 MCP Server 扩展能力

### 技术栈


| 组件       | 技术选型                   |
| ---------- | -------------------------- |
| Agent 框架 | AgentScope Java 1.0.10     |
| 终端 REPL  | JLine3 3.28.0              |
| 配置管理   | Jackson YAML 2.18.3        |
| 工具发现   | Apache Lucene 10.1.0       |
| MCP 协议   | AgentScope 内置 MCP Client |
| 构建工具   | Maven (Java 17)            |

## 核心架构

```
┌─────────────────────────────────────────────────────────┐
│                      CLI (JLine3 REPL)                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │ /help    │  │ /tasks   │  │ /skills  │  │ /config │ │
│  └──────────┘  └──────────┘  └──────────┘  └─────────┘ │
├─────────────────────────────────────────────────────────┤
│                    PigAgent (ReActAgent)                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │  Hooks      │  │  Memory     │  │  Model          │ │
│  │  - Logging  │  │  - InMemory │  │  (Provider)     │ │
│  │  - ToolCall │  │  - File     │  │                 │ │
│  └─────────────┘  └─────────────┘  └─────────────────┘ │
├─────────────────────────────────────────────────────────┤
│                      Toolkit                             │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────┐ │
│  │ Shell  │ │  File  │ │  Web   │ │  Task  │ │ MCP  │ │
│  │ System │ │ System │ │ Fetch  │ │  Tool  │ │Tools │ │
│  └────────┘ └────────┘ └────────┘ └────────┘ └──────┘ │
│  ┌────────┐ ┌────────┐ ┌────────┐                     │
│  │ Search │ │CheckList│ │ Skills │                     │
│  └────────┘ └────────┘ └────────┘                     │
├─────────────────────────────────────────────────────────┤
│              Provider / MCP / Task / Workspace            │
└─────────────────────────────────────────────────────────┘
```

### 请求处理流程

```
用户输入 → JLine3 REPL
    ↓
构建 Msg(USER)
    ↓
PigAgent.stream(msg)
    ↓
ReActAgent 推理循环:
    ├── PreReasoningEvent  → LoggingHook
    ├── LLM 调用 (Model.chat)
    ├── PostReasoningEvent → LoggingHook
    ├── PreActingEvent     → ToolCallLoggingHook
    ├── 工具执行 (@Tool 方法)
    └── PostActingEvent    → ToolCallLoggingHook
    ↓
```

## 系统模块

项目采用 Maven 多模块结构，共 11 个模块：

### pig-agent-core — Agent 核心


| 类                        | 职责                                                                       |
| ------------------------- | -------------------------------------------------------------------------- |
| `PigAgent`                | 核心 Agent，封装 AgentScope 的 ReActAgent，提供`call()` 和 `stream()` 接口 |
| `LoggingHook`             | 生命周期 Hook，打印 Pre/PostReasoning、Pre/PostActing 事件                 |
| `ToolCallLoggingHook`     | 工具调用 Hook，打印工具名称和执行状态                                      |
| `FileMemory`              | 文件持久化内存，会话历史保存到磁盘                                         |
| `ModelProtocol`           | 模型协议标准接口（`io.pigagent.core.protocol`），定义 `protocolId`、模型创建等标准方法 |
| `ProviderCredentials`     | 不可变凭证容器，copy-on-write 语义                                         |

### pig-agent-providers — 模型协议

按**协议标准**（而非厂商）组织，共 5 套协议。具体模型由用户经"选协议 + 填 baseUrl / API key / 模型名"配置；任何 OpenAI 兼容厂商（小米 mimo、DeepSeek、Kimi、通义-compat 等）都走 `openai` 协议 + 各自 baseUrl，无需新增代码。


| 协议        | 底层 Model            | 默认模型          | 说明                                            |
| ----------- | --------------------- | ----------------- | ----------------------------------------------- |
| openai      | `OpenAIChatModel`     | gpt-4o            | OpenAI 兼容协议，吃掉 mimo/DeepSeek/Kimi 等厂商 |
| anthropic   | `AnthropicChatModel`  | claude-sonnet-4-6 | Anthropic messages 协议                         |
| gemini      | `GeminiChatModel`     | gemini-2.0-flash  | Google Gemini 协议                              |
| ollama      | `OllamaChatModel`     | llama3.2          | 本地自托管，无需 API key                        |
| dashscope   | `DashScopeChatModel`  | qwen-max          | 阿里云 DashScope 原生协议（通义千问）           |

### pig-agent-tools — 内置工具


| 工具                 | @Tool 方法                                         | 功能                              |
| -------------------- | -------------------------------------------------- | --------------------------------- |
| `ShellTools`         | `executeCommand`                                   | 执行 Shell 命令，30 秒超时        |
| `FileSystemTools`    | `readFile`, `writeFile`, `listDirectory`           | 文件读写和目录列表                |
| `SmartWebFetchTool`  | `fetchUrl`                                         | 抓取网页内容，自动截断至 10K 字符 |
| `BraveWebSearchTool` | `webSearch`                                        | Brave Search API 网页搜索         |
| `TaskTool`           | `createTask`, `listTasks`, `updateTaskStatus`      | 任务管理                          |
| `CheckListTool`      | `createChecklist`, `completeItem`, `showChecklist` | 清单管理                          |
| `SkillsTool`         | `listSkills`, `loadSkill`                          | 从 workspace/skills/ 加载技能     |
| `ToolDiscovery`      | —                                                 | 基于 Lucene 的工具全文搜索        |

### pig-agent-task — 任务管理


| 类                         | 职责                                                                     |
| -------------------------- | ------------------------------------------------------------------------ |
| `Task`                     | 不可变记录，支持`withStatus()`、`withSchedule()` 生成新实例              |
| `TaskSchedule`             | 调度配置：ONCE（一次）、CRON（定时）、DELAYED（延迟）                    |
| `TaskStatus`               | 状态枚举：TODO、IN_PROGRESS、COMPLETED、AWAITING_HUMAN_INPUT             |
| `TaskManager`              | 任务 CRUD 操作                                                           |
| `TaskScheduler`            | 后台调度器，使用`ScheduledExecutorService` 执行延迟/定时任务             |
| `FileSystemTaskRepository` | 文件系统存储，任务以 Markdown 格式保存在`workspace/tasks/{date}/{id}.md` |

### pig-agent-mcp — MCP 集成


| 类             | 职责                                                       |
| -------------- | ---------------------------------------------------------- |
| `McpManager`   | MCP 服务器运行时增删改查、启停、连通测试，实时注册/注销工具 |
| `McpServerSpec`| 不可变服务器配置（record，`withXxx` 拷贝）                  |
| `McpStore`     | 持久化接口                                                 |
| `JsonMcpStore` | `workspace/mcp.json` 实现（按名作键，坏文件备份后从空开始） |

支持的 MCP 传输方式：

- **stdio**: 启动本地进程通信（如 `npx @modelcontextprotocol/server-filesystem`）
- **SSE**: Server-Sent Events 长连接
- **Streamable HTTP**: HTTP 流式传输

### pig-agent-workspace — 工作区管理


| 类                 | 职责                                   |
| ------------------ | -------------------------------------- |
| `WorkspaceManager` | 管理`~/.pig-agent/workspace/` 目录结构 |

工作区结构：

```
~/.pig-agent/workspace/
├── AGENT.md            # 系统提示词（可自定义）
├── INFO.md             # 环境信息
├── application.yaml    # 配置文件
├── context/            # 上下文文件
├── skills/             # 技能目录
└── tasks/              # 任务目录
    ├── recurring/      # 周期任务
    └── 2026-05-14/     # 按日期组织
        └── ab12cd34.md # 单个任务文件
```

### pig-agent-config — 配置管理


| 类                          | 职责                                                        |
| --------------------------- | ----------------------------------------------------------- |
| `PigAgentConfig`            | Jackson 注解的配置类，支持 model、agent、channels、mcp 配置 |
| `ConfigurationManager`      | YAML 持久化，支持变更监听器模式                             |
| `ConfigurationChangedEvent` | 配置变更事件，携带 oldConfig 和 newConfig                   |

### pig-agent-channel — 通道抽象


| 类                   | 职责                                                 |
| -------------------- | ---------------------------------------------------- |
| `Channel`            | 通道接口：start、sendMessage、stop、isRunning        |
| `ChannelAgentBridge` | 通道-Agent 桥接器，将通道消息路由到 Agent 并回传响应 |
| `ChatChannel`        | 终端通道实现                                         |
| `TelegramChannel`    | Telegram 通道（存根）                                |
| `DiscordChannel`     | Discord 通道（存根）                                 |
| `ChannelRegistry`    | 通道注册中心                                         |

### pig-agent-onboarding — 引导向导


| 类                 | 职责                                         |
| ------------------ | -------------------------------------------- |
| `OnboardingWizard` | 首次运行引导，选择提供商、配置凭证、更新配置 |

### pig-agent-cli — CLI 入口


| 类            | 职责                                    |
| ------------- | --------------------------------------- |
| `PigAgentCli` | 主入口，JLine3 REPL，流式输出，优雅关闭 |

REPL 命令：


| 命令      | 功能         |
| --------- | ------------ |
| `/help`   | 显示帮助     |
| `/tasks`  | 列出任务     |
| `/skills` | 列出技能     |
| `/config` | 显示当前配置 |
| `/quit`   | 退出         |

## 代码结构

```
pig-agent/
├── pom.xml                          # 父 POM，依赖管理
├── pig-agent-core/                  # Agent 核心
│   └── src/main/java/io/pigagent/core/
│       ├── agent/PigAgent.java
│       ├── hook/
│       │   ├── LoggingHook.java
│       │   └── ToolCallLoggingHook.java
│       ├── memory/FileMemory.java
│       ├── protocol/
│       │   ├── ModelProtocol.java
│       │   └── ModelSpec.java
│       └── provider/ProviderCredentials.java
├── pig-agent-providers/             # 模型协议
│   └── src/main/java/io/pigagent/provider/
│       ├── openai/OpenAiProtocol.java
│       ├── anthropic/AnthropicProtocol.java
│       ├── gemini/GeminiProtocol.java
│       ├── ollama/OllamaProtocol.java
│       ├── dashscope/DashScopeProtocol.java
│       └── registry/ProtocolRegistry.java
├── pig-agent-tools/                 # 内置工具
│   └── src/main/java/io/pigagent/tool/
│       ├── shell/ShellTools.java
│       ├── filesystem/FileSystemTools.java
│       ├── webfetch/SmartWebFetchTool.java
│       ├── websearch/BraveWebSearchTool.java
│       ├── task/TaskTool.java
│       ├── checklist/CheckListTool.java
│       ├── skills/SkillsTool.java
│       └── discovery/ToolDiscovery.java
├── pig-agent-task/                  # 任务管理
│   └── src/main/java/io/pigagent/task/
│       ├── Task.java
│       ├── TaskStatus.java
│       ├── TaskSchedule.java
│       ├── TaskManager.java
│       ├── TaskScheduler.java
│       ├── TaskRepository.java
│       └── FileSystemTaskRepository.java
├── pig-agent-mcp/                   # MCP 集成
│   └── src/main/java/io/pigagent/mcp/
│       └── McpManager.java
├── pig-agent-workspace/             # 工作区管理
│   └── src/main/java/io/pigagent/workspace/
│       └── WorkspaceManager.java
├── pig-agent-config/                # 配置管理
│   └── src/main/java/io/pigagent/config/
│       ├── PigAgentConfig.java
│       ├── ConfigurationManager.java
│       └── ConfigurationChangedEvent.java
├── pig-agent-channel/               # 通道抽象
│   └── src/main/java/io/pigagent/channel/
│       ├── Channel.java
│       ├── ChannelRegistry.java
│       ├── chat/ChatChannel.java
│       ├── telegram/TelegramChannel.java
│       └── discord/DiscordChannel.java
├── pig-agent-onboarding/            # 引导向导
│   └── src/main/java/io/pigagent/onboarding/
│       └── OnboardingWizard.java
└── pig-agent-cli/                   # CLI 入口
    └── src/main/java/io/pigagent/cli/
        └── PigAgentCli.java
```

## 快速开始

### 前置条件

- Java 17+
- Maven 3.9+
- 至少一个 LLM 提供商的 API Key

### 编译

```bash
mvn compile -s 
```

### 运行

```bash
# 设置 API Key（选择一个提供商）
export ANTHROPIC_API_KEY=your_key
# 或
export OPENAI_API_KEY=your_key
# 或使用本地 Ollama（无需 Key）

# 启动
mvn exec:java -s  -pl pig-agent-cli
```

首次运行会自动：

1. 创建 `~/.pig-agent/workspace/` 工作区
2. 生成 `AGENT.md`、`INFO.md`、`application.yaml`
3. 如果 API Key 未配置，启动引导向导

### 配置示例

编辑 `~/.pig-agent/workspace/application.yaml`：

```yaml
model:
  provider: anthropic
  model-name: mimo-v2.5-pro
  # 模型调用重试（瞬时错误 5xx/网络 自愈）——缺省即启用，可全部省略
  retry:
    enabled: true                    # false 完全关闭（恢复旧行为）
    max-retries: 10                  # 最多重试次数
    per-attempt-timeout-seconds: 0   # 0=关闭（默认）。客户端超时与不可中断 agent 不兼容，会误伤慢模型，勿轻易开启
    first-backoff-ms: 500            # 首次退避
    max-backoff-ms: 8000             # 退避封顶（指数退避）

agent:
  name: PigAgent
  max-iters: 10

mcp:
  servers:
    # stdio 传输 - 本地文件系统
    filesystem:
      command: npx
      args: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"]

    # SSE 传输 - 远程服务
    remote:
      url: http://localhost:3000/sse
      headers:
        Authorization: Bearer token
```

## 核心扩展

### 1. 自定义工具

使用 AgentScope 的 `@Tool` 注解即可创建新工具：

```java
package io.pigagent.tool.custom;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public final class MyCustomTool {

    @Tool(description = "描述工具的功能")
    public String myMethod(
            @ToolParam(name = "param1", description = "参数说明") String param1
    ) {
        // 工具逻辑
        return "结果";
    }
}
```

在 `PigAgentCli.java` 中注册：

```java
toolkit.registration().tool(new MyCustomTool()).apply();
```

### 2. 自定义 Hook

实现 `io.agentscope.core.hook.Hook` 接口：

```java
public final class MyHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent) {
            // 推理前的逻辑
        } else if (event instanceof PostActingEvent) {
            // 工具执行后的逻辑
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 70; // 数值越小优先级越高
    }
}
```

### 3. 自定义模型协议

> 接入某个**已有协议**的新厂商（例如又一个 OpenAI 兼容端点）**无需写代码**——用户在 onboarding / `/model add` 里选协议并填 baseUrl 即可。只有引入一套**全新协议**时才需实现下面的接口。

实现 `ModelProtocol` 接口（`io.pigagent.core.protocol`）：

```java
public final class MyProtocol implements ModelProtocol {

    @Override
    public String protocolId() { return "my-protocol"; }

    @Override
    public String displayName() { return "My Protocol"; }

    @Override
    public String description() { return "My model protocol"; }

    @Override
    public String defaultModelName() { return "my-model"; }

    @Override
    public boolean supportsBaseUrl() { return true; }

    @Override
    public Model createModel(ModelSpec spec) {
        return MyChatModel.builder()
                .apiKey(spec.apiKey())
                .baseUrl(spec.baseUrl())
                .modelName(spec.modelName())
                .build();
    }
}
```

在 `PigAgentCli.java` 中注册：

```java
registry.register(new MyProtocol());
```

### 4. 自定义通道

实现 `Channel` 接口：

```java
public final class MyChannel implements Channel {

    @Override
    public String channelId() { return "my-channel"; }

    @Override
    public void start(Consumer<ChannelMessageReceivedEvent> onMessage) {
        // 启动消息监听
    }

    @Override
    public void sendMessage(String userId, String content) {
        // 发送消息
    }

    @Override
    public void stop() {
        // 停止通道
    }
}
```

### 5. MCP Server 扩展（动态增删改查）

MCP 服务器**在运行时管理**，配置持久化到 `workspace/mcp.json`（按名作键，为唯一真源）。
首次启动时会把旧的 `application.yaml` 中 `mcp.servers` **一次性导入** `mcp.json`，此后以 `mcp.json` 为准。

**运维者通过 `/mcp` 命令实时管理**（完全信任，可添加 stdio 服务器）：

```text
/mcp list                    列出所有服务器及实时健康（已连接 + 工具数 / 未连接 / 已停用）
/mcp add                     交互式添加（stdio | sse | http），先连通测试再生效
/mcp remove <名称|序号>      移除（先确认）
/mcp edit <名称|序号>        重新录入配置（先测试新配置再应用，失败保持旧的）
/mcp enable  <名称|序号>     启用并连接
/mcp disable <名称|序号>     停用（注销工具，保留配置）
/mcp test <名称|序号>        仅测试连通性，不改动任何状态
```

新增/删除即时生效（热注册/注销工具），无需重启。工具命名空间扁平，**新增服务器若工具名与已注册工具冲突即拒绝**。

**Agent 自助管理（受 D-SEC 安全门控制）**：Agent 可经 `McpTool` 调用 `listMcpServers`/`testMcpServer`/`addMcpServer`/`removeMcpServer`，但受 `application.yaml` 的 `mcp.agent-management` 门控：

```yaml
mcp:
  agent-management:
    allow-add: false        # 默认关：禁止 agent 添加
    allow-remove: false     # 默认关：禁止 agent 移除
    allowed-hosts: []       # 开启 add 后，仅允许这些 host 的 URL 服务器
```

- `list`/`test` 始终允许；`add`/`remove` 默认全部关闭。
- 开启 `allow-add` 后，agent **只能添加 URL 类型**（拒绝 stdio/command），且 host 必须在 `allowed-hosts` 白名单内，并经**人工确认**方可添加。
- 工具返回值对 `env`/`headers` **脱敏**（只显示键名，值为 `***`），避免凭据回显。

> 旧的 `application.yaml` `mcp.servers` 配置仍兼容（首启导入），但推荐改用 `/mcp` 命令管理。

### 6. 通道-Agent 打通

通过 `ChannelAgentBridge` 将外部通道（Telegram、Discord 等）连接到 Agent，实现消息自动路由：

```
外部通道 (Telegram/Discord)
    ↓ 用户消息
ChannelAgentBridge
    ↓ Msg(USER)
PigAgent.stream(msg)
    ↓ Agent 响应
Channel.sendMessage(response)
    ↓
外部通道回复用户
```

配置示例（`application.yaml`）：

```yaml
channels:
  telegram:
    enabled: true
    token: "your-telegram-bot-token"
  discord:
    enabled: true
    token: "your-discord-bot-token"
```

启动后自动连接所有 `enabled: true` 的通道，无需手动干预。

## 工具权限体系

对标 Claude Code / opencode / hermes 的权限级别，为工具执行加一道统一安全门（`ToolPermissionHook`，在 `PreActingEvent` 逐次判定）。四种全局**模式**：

| 模式 | 行为 |
|------|------|
| `plan` | 只读：否决所有可变工具，agent 只产出计划 |
| `ask`（默认） | 逐次确认可变工具（`y` 本次 / `a` 始终允许 / `N` 拒绝） |
| `auto` | 自动放行写文件/网络，仍确认执行 shell / MCP 管理 |
| `bypass` | 全部放行、无提示（完全信任 / 恢复旧行为） |

工具按风险分级（只读 / 写 / 执行 / 网络 / MCP 管理；未知工具按最严处理）。`a`（始终允许）会持久化到 `permissions.allowlist`（工具名或命令首 token）。运维命令：

```
/permission status                     查看当前模式与 allowlist
/permission mode <plan|ask|auto|bypass>  切换模式
/permission allow <命令键>              加入 allowlist（--tool 加工具名）
/permission revoke <名称> | reset       移除 / 清空
```

配置（`application.yaml`）：

```yaml
permissions:
  mode: ask                 # 缺省 ask；升级后危险工具开始要确认，可设 bypass 恢复旧行为
  channel-mode: auto
  tool-overrides:           # 可选：重分类某工具
    fetchUrl: high
  allowlist:
    tools: []
    commands: []
```

> 说明：默认 `ask` 会改变旧行为（此前等同 bypass）。MCP 自助接入的 D-SEC 门保持不变（不重复弹窗）。渠道回合共享交互门（fail-safe，不会自动执行需确认的工具）；完整 per-origin `channel-mode` 为后续跟进。

## 24 小时不间断运行

Pig Agent 支持作为后台服务 24 小时运行，持续接收外部通道消息和执行定时任务。

### 运行方式

```bash
# 前台运行
mvn exec:java -s  -pl pig-agent-cli

# 后台运行（nohup）
nohup mvn exec:java -s  -pl pig-agent-cli > pig-agent.log 2>&1 &

# 使用 systemd（推荐生产环境）
# 创建 /etc/systemd/system/pig-agent.service
```

### systemd 服务配置示例

```ini
[Unit]
Description=Pig Agent - AI Agent Service
After=network.target

[Service]
Type=simple
User=pigagent
WorkingDirectory=/opt/pig-agent
ExecStart=/usr/bin/mvn exec:java -s -pl pig-agent-cli
Environment=MIMO_API_KEY=your_key
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### 持续运行能力


| 能力       | 说明                                              |
| ---------- | ------------------------------------------------- |
| 通道常驻   | Telegram/Discord 通道持续监听消息，收到即响应     |
| 定时任务   | TaskScheduler 后台执行 CRON/DELAYED 任务          |
| MCP 长连接 | MCP Server 连接保持，工具随时可用                 |
| 优雅关闭   | 收到 SIGTERM 时依次关闭通道、任务调度器、MCP 连接 |
| 自动重启   | 配合 systemd`Restart=always` 实现故障自愈         |

## 设计原则

- **不可变数据** — `Task`、`ProviderCredentials` 等核心类型均为 record，通过 `withXxx()` 生成新实例
- **接口抽象** — `ModelProtocol`、`Channel`、`TaskRepository` 等均为接口，方便替换实现
- **Hook 扩展** — 通过 `Hook` 接口拦截 Agent 生命周期事件，无需修改核心代码
- **配置驱动** — YAML 配置文件 + 变更监听器，支持运行时动态调整

## License

Apache License 2.0
