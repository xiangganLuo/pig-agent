> ⚠️ 前置：本 tasks 依赖 `agent-kernel-facade` 归档后的 `AgentKernel` API / `KernelEvent` 类型最终定型。启动本 spec 编码前，先核对门面签名再微调下列 task。requirements（契约）稳定。
>
> 技术栈定论（Open Q）：选 **JDK 内置 `com.sun.net.httpserver`**（零外部依赖、离线可构建、满足绑本机 + SSE + 静态资源），而非 Javalin/Spring —— 契合「轻、无重框架」约束。

## 1. 技术选型 + 嵌入式 server 骨架（pig-agent-web 新模块）

- [x] 1.1 定技术栈（JDK `com.sun.net.httpserver`，零外部依赖）；新增 `pig-agent-web` 模块，隔离 web 依赖（仅 core+config+jackson）。
- [x] 1.2 嵌入式 server 骨架 `WebConsole`：绑 `127.0.0.1`、随进程启停（`start`/`stop`/`boundPort`）、装配开关（`WebConfig`，默认关）。
- [x] 1.3 单测/冒烟：`WebConsoleTest#bindsToLoopbackOnly`/`stop_releasesPort`（起停 + 仅本机监听 + 端口释放）。

## 2. 门面 adapter：REST + 事件流（pig-agent-web）

- [x] 2.1 REST 端点（`AgentApiHandler`）委托 `AgentKernel`：list/get/create/update/delete/use agent、runNow —— 零业务逻辑（D1）。
- [x] 2.2 `KernelEvent` → SSE 推送（`EventStreamHandler` 订阅 `subscribeEvents()`）；`retry:` 帧驱动浏览器 `EventSource` 断线重连。
- [x] 2.3 单测：`WebConsoleTest`（REST 全生命周期 + 冲突/404）、`EventStreamHandlerTest`（事件经门面推送）、`WebJsonTest`（投影/解析）。

## 3. Web MVP 前端（静态资源）

- [x] 3.1 页面（`index.html`）：agent 列表/切换/新建/删除/运行 + 实时事件流（会话/运行/报告统一事件）+ 连接状态。
- [x] 3.2 前端（`app.js`）消费 REST + `EventSource(/api/events)`；随 jar 打包 `resources/web/`（纯 HTML/CSS/JS，无构建步骤，D5）。

## 4. 装配 + 校验 + 文档

- [x] 4.1 `PigAgentCli` 在 `web.enabled` 时装配 Web 控制台（默认关）；与 CLI 共享同一 `AgentKernel`；shutdown hook 停止。
- [x] 4.2 全模块 `mvn test` BUILD SUCCESS，无回归；内核与 CLI 不受影响（web 模块 11 用例绿）。
- [x] 4.3 文档：`CLAUDE.md`（模块表 + Web 控制台架构 + `web` 配置）+ `README`（启用/访问/安全说明：仅本机、无 DB）。
