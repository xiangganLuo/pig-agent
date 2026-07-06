> ⚠️ 前置：本 tasks 依赖 `agent-kernel-facade` 归档后的 `AgentKernel` API / `KernelEvent` 类型最终定型。启动本 spec 编码前，先核对门面签名再微调下列 task。requirements（契约）稳定。

## 1. 技术选型 + 嵌入式 server 骨架（pig-agent-web 新模块）

- [ ] 1.1 定技术栈（倾向 Javalin）；新增 `pig-agent-web` 模块，隔离 web 依赖。
- [ ] 1.2 嵌入式 server 骨架：绑 `127.0.0.1`、随进程启停、装配开关（默认可配）。
- [ ] 1.3 单测/冒烟：server 起停；仅本机监听。

## 2. 门面 adapter：REST + 事件流（pig-agent-web）

- [ ] 2.1 REST 端点委托 `AgentKernel`：list/get/create/update/delete/use agent、runNow。
- [ ] 2.2 `KernelEvent` → SSE/WS 推送（订阅 `subscribeEvents()`）；断线重连。
- [ ] 2.3 单测：端点委托正确；事件推送经门面（可用假 `AgentKernel` 验证）。

## 3. Web MVP 前端（静态资源）

- [ ] 3.1 页面：agent 列表/切换、实时会话流、运行监控、晨报收件箱、「等你决定」审批。
- [ ] 3.2 前端消费 REST + SSE/WS；随 jar 打包静态资源。

## 4. 装配 + 校验 + 文档

- [ ] 4.1 `PigAgentCli` 在启用时装配 Web 控制台（默认开关）；与 CLI 共享同一 `AgentKernel`。
- [ ] 4.2 全模块 `mvn test` BUILD SUCCESS，无回归；内核与 CLI 不受影响。
- [ ] 4.3 文档：`CLAUDE.md` + `README`（Web 控制台启用/访问/安全说明：仅本机、无 DB）。
