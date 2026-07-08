## REMOVED Requirements

### Requirement: 嵌入式本地控制台
**Reason**: 收敛前端面以先跑通核心；`pig-agent-web` 模块整体移除。
**Migration**: 终端前端使用增强后的 CLI 行式 REPL（`cc-repl`）；Web 后置到生态扩展阶段以 web-console-v2 重建（可复用移植保留的 `WebContext`/`WebJson` 投影蓝本）。

### Requirement: Web 作为 adapter（门面 + 共享 manager）
**Reason**: Web 模块移除；同一「门面 + 共享 manager」的 adapter 模式改由 `cc-repl` 承载。
**Migration**: CLI REPL 经 `ReplContext` 复用同一批 manager 实例，等价的共享语义在 `cc-repl` 规格中定义。

### Requirement: 全能力对等（CLI parity）
**Reason**: Web 模块移除；能力对等的职责回归增强后的 CLI REPL 自身。
**Migration**: 参见 `cc-repl` 的「增强既有 CLI REPL 为唯一终端前端」需求（既有全部斜杠命令保留）。

### Requirement: 流式对话
**Reason**: Web 模块移除；流式对话改由增强后的 REPL 富渲染提供。
**Migration**: 参见 `cc-repl` 的「流式富渲染」需求。

### Requirement: 实时事件推送
**Reason**: Web 模块移除；`AgentKernel.subscribeEvents()` 仍在，Web 侧 SSE 后置重建。
**Migration**: CLI REPL 直接消费流式 `Event` 渲染；状态行可选订阅 `KernelEvent`。

### Requirement: 控制台 MVP 功能
**Reason**: Web 模块移除；晨报「收件箱」与「等你决定」审批后置到 web-console-v2。
**Migration**: agent 列表/切换在 REPL 的 `/agent` 命令提供；晨报查看（`/agent report`）沿用既有 CLI 能力，审批延后随生态扩展重建。
