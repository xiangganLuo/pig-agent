## ADDED Requirements

### Requirement: 嵌入式本地控制台

系统 SHALL 提供一个嵌入式、随 `pig-agent-cli` 进程启停的本地 Web server，作为 `AgentKernel` 的 adapter。它 MUST NOT 引入 DB、多租户或独立部署，MUST 只绑定本机回环地址（`127.0.0.1`）、面向单用户。

#### Scenario: 随进程启动的本地控制台
- **WHEN** 启用 Web 控制台并启动 pig-agent
- **THEN** 本机可经浏览器访问控制台，且服务仅监听 `127.0.0.1`

#### Scenario: 不对外暴露
- **WHEN** 从非本机地址尝试访问
- **THEN** 无法连接（服务不监听外部地址）

### Requirement: Web 作为门面 adapter

Web 控制台 SHALL 只经 `AgentKernel` 门面 + `KernelEvent` 事件流获取数据与执行操作，MUST NOT 直连内核内部类或新增业务逻辑；与 CLI 共享同一内核与同一批 agent。

#### Scenario: Web 与 CLI 共享内核
- **WHEN** 在 Web 切换当前 agent 或新建 agent
- **THEN** CLI 侧看到同样的变更（同一 `AgentKernel` 实例）

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
