## ADDED Requirements

### Requirement: 嵌入式本地控制台

系统 SHALL 提供一个嵌入式、随 `pig-agent-cli` 进程启停的本地 Web server，作为 `AgentKernel` 的 adapter。它 MUST NOT 引入 DB、多租户或独立部署，MUST 只绑定本机回环地址（`127.0.0.1`）、面向单用户。

#### Scenario: 随进程启动的本地控制台
- **WHEN** 启用 Web 控制台并启动 pig-agent
- **THEN** 本机可经浏览器访问控制台，且服务仅监听 `127.0.0.1`

#### Scenario: 不对外暴露
- **WHEN** 从非本机地址尝试访问
- **THEN** 无法连接（服务不监听外部地址）

### Requirement: Web 作为 adapter（门面 + 共享 manager）

Web 控制台 SHALL 作为内核之上的 adapter：agent 生命周期 / 对话 / 事件经 `AgentKernel` 门面 + `KernelEvent` 事件流；其余能力（model / session / compression / mcp / task / permission-config / memory）复用 CLI **同一批 manager 实例**（单一真相源）。它 MUST NOT 新增业务逻辑或重复实现——处理器只做 HTTP↔manager 的委托。与 CLI 共享同一内核与同一批 agent、模型、会话。

#### Scenario: Web 与 CLI 共享内核与 manager
- **WHEN** 在 Web 切换当前 agent、切换模型或新建会话
- **THEN** CLI 侧看到同样的变更（同一 `AgentKernel` / `ModelManager` / `SessionManager` 实例）

### Requirement: 全能力对等（CLI parity）

Web 控制台 SHALL 覆盖 CLI 斜杠命令的能力：agent（列/建/删/切/运行）、model（列/加/切/测/删）、session（列/建/fork/切/删/清）、task（列/建/改状态/删）、mcp（列/加/删/启停/测）、permission（查看/改 mode/allow/revoke/reset）、memory（开关 + 内容查看）、compression（状态/立即压缩/开关）、status 汇总。每类经对应 REST 端点委托同一 manager。凭据（apiKey、mcp 的 env/headers）MUST NOT 在响应中回传。

#### Scenario: 经 Web 切换模型立即生效
- **WHEN** 用户在 Web 的 Models 页对某模型点「切换」
- **THEN** 经 `ModelManager` + 会话层重建活动 agent，CLI/Web 后续对话都用新模型

#### Scenario: 凭据不外泄
- **WHEN** 列出模型或 MCP 服务器
- **THEN** 响应不包含 apiKey / env / headers 等敏感字段

### Requirement: 流式对话

Web 控制台 SHALL 提供 `POST /api/chat`，以 SSE（`text/event-stream`）流式返回一次对话的推理 / 工具 / 最终答案事件，并镜像 REPL 回合的会话与压缩钩子（记录用户消息 → 按需压缩 → 流式 → 保存会话）。

#### Scenario: 对话流式返回
- **WHEN** 用户在 Web 的 Chat 页发送一条消息
- **THEN** 页面按 `data:` 帧实时增量显示 agent 的回答，结束时收到 `done` 帧

### Requirement: 实时事件推送

系统 SHALL 订阅 `AgentKernel.subscribeEvents()` 并经 SSE 或 WebSocket 把会话/运行/报告事件实时推给前端。

#### Scenario: 会话流实时更新
- **WHEN** 一次对话在产出流式内容
- **THEN** Web 页面实时显示，无需手动刷新

### Requirement: 控制台 MVP 功能

Web MVP SHALL 覆盖：agent 列表与切换、实时会话流、运行监控、晨报「收件箱」查看、「等你决定」项审批。

#### Scenario: 管理 agent
- **WHEN** 用户在 Web 打开 agent 列表
- **THEN** 可查看各 agent 并切换当前 agent

#### Scenario: 阅读晨报
- **WHEN** 用户打开晨报收件箱
- **THEN** 可查看数字员工产出的晨报（我做了/我发现/等你决定）

#### Scenario: 审批等你决定项
- **WHEN** 用户对晨报中的「等你决定」项点击批准
- **THEN** 该决定经门面回传处理（具体回写依赖数字员工审批能力）
