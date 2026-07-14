# web-console Specification

## Purpose
TBD - created by archiving change web-console-v2. Update Purpose after archive.
## Requirements
### Requirement: 嵌入式本地控制台

系统 SHALL 提供一个嵌入式、随 `pig-agent-cli` 进程启停的本地 Web server，作为 `AgentKernel` 的 adapter。它 MUST NOT 引入 DB、多租户或独立部署，MUST 只绑定本机回环地址（`127.0.0.1`）、面向单用户，并 SHALL 由配置开关控制、默认关闭。

#### Scenario: 随进程启动的本地控制台
- **WHEN** 启用 Web 控制台（`web.enabled=true`）并启动 pig-agent
- **THEN** 本机可经浏览器访问控制台，且服务仅监听 `127.0.0.1`

#### Scenario: 默认关闭
- **WHEN** 未显式启用 `web.enabled`
- **THEN** 不启动 Web server，CLI 行为与之前一致

#### Scenario: 端口随进程释放
- **WHEN** 进程停止或控制台 `stop()`
- **THEN** 端口被释放，后续连接失败

### Requirement: Web 作为 AgentKernel adapter

Web 控制台 SHALL 只作为 `AgentKernel` 门面之上的 adapter：agent 生命周期 / 对话 / 事件经 `AgentKernel` + `KernelEvent` 事件流。它 MUST NOT 新增业务逻辑、MUST NOT 直连内核内部类型（`AgentRegistry`/`AgentInstanceFactory`/`AgentSpecRepository`/`AgentRunner`），与 CLI 共享同一 `AgentKernel` 与同一批 agent。

#### Scenario: Web 与 CLI 共享内核
- **WHEN** 在 Web 切换当前 agent
- **THEN** CLI 侧看到同样的活动 agent（同一 `AgentKernel` 实例）

### Requirement: Agent 管理 REST

Web 控制台 SHALL 经 `AgentKernel` 提供 agent 管理端点：`GET /api/agents`（列出）、`GET /api/agents/{id}`（取单个）、`POST /api/agents`（创建）、`PUT /api/agents/{id}`（改模型）、`DELETE /api/agents/{id}`（删除）、`POST /api/agents/{id}/use`（切换活动）。每个端点直接委托对应门面方法，零业务逻辑。

#### Scenario: 列出并切换 agent
- **WHEN** 用户 `GET /api/agents` 后对某 id `POST /api/agents/{id}/use`
- **THEN** 返回该 agent 且门面的活动 agent 切为该 id

#### Scenario: 创建重复 id
- **WHEN** 创建一个已存在 id 的 agent
- **THEN** 返回 409 冲突

#### Scenario: 切换未知 agent
- **WHEN** 对不存在的 id `POST /api/agents/{id}/use`
- **THEN** 返回 404

### Requirement: 流式对话

Web 控制台 SHALL 提供 `POST /api/chat`（请求体 `{message, agentId?}`），经 `kernel.chat(agentId, msg)` 以 SSE（`text/event-stream`）流式返回一次对话的推理 / 工具 / 最终答案帧，并在结束时发送 `done` 帧。缺省 `agentId` 时使用当前活动 agent。

#### Scenario: 对话流式返回
- **WHEN** 用户 `POST /api/chat` 发送一条消息
- **THEN** 页面按 `data:` 帧实时增量收到 `reasoning`/`tool`/`answer` 帧，结束时收到 `done` 帧

#### Scenario: 缺少消息
- **WHEN** 请求体缺少 `message`
- **THEN** 返回 400

### Requirement: 实时事件推送

系统 SHALL 提供 `GET /api/events`，订阅 `AgentKernel.subscribeEvents()` 并把每个 `KernelEvent` 以 SSE `data:` 帧（JSON：type/agentId/message）推给前端，且带 `retry:` 提示浏览器 `EventSource` 断线重连。无订阅者时门面零成本。

#### Scenario: 事件实时推送
- **WHEN** 内核发生 agent 切换 / 对话开始 / 运行等生命周期事件
- **THEN** 已连接的 `/api/events` 客户端实时收到对应 `data:` 帧

### Requirement: 极简前端 MVP

Web MVP SHALL 提供随 jar 打包的纯静态前端（HTML/CSS/JS，无构建步骤、无重框架）：一个聊天框（发送并流式显示答案）与一个 agent 列表（可切换当前 agent）。`GET /` 返回该页面。

#### Scenario: 打开控制台
- **WHEN** 用户浏览器访问 `GET /`
- **THEN** 返回控制台页面，含聊天框与 agent 列表

### Requirement: 凭据不外泄

Web 控制台的任何响应 MUST NOT 回传敏感凭据（apiKey / token / env / headers）。领域投影（`WebJson`）SHALL 只输出非敏感字段。

#### Scenario: 响应不含凭据
- **WHEN** 列出 agent 或推送事件
- **THEN** 响应不包含任何 apiKey / token / env / headers 字段

