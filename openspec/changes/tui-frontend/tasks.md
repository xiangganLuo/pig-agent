## 1. 分支基线：rebase + 弃 Lanterna（已完成）

- [x] 1.1 `feat/tui-frontend` reset 到 `origin/main`（6 内核 spec 已并入；`interruptible-run` 使 `AgentKernel.interruptCurrent()` 成为真实现）。
- [x] 1.2 弃用 Lanterna 全屏方案：`pig-agent-tui` 模块 / 14 面板 / `MultiWindowTextGUI` 壳 / `TuiSpike` / `FluxGuiBridge` / `TuiLauncher` / lanterna 依赖随 reset 从分支移除（未并入主干）。可复用逻辑（回合流程、事件映射、interruptCurrent 接线）回归到既有 `cli/repl`。

## 2. 流式富渲染（CC 风格）

- [x] 2.1 `MarkdownAnsiRenderer`（纯函数）：`**粗体**` / `` `行内代码` `` / ```` ``` 代码块 ```` / `- 列表` / `# 标题` → `Ansi` 转义；单测覆盖典型 + 未闭合标记边界。（`render/MarkdownAnsiRendererTest` 12 测）
- [x] 2.2 增量出字：`AGENT_RESULT` 事件到达即写出（`StreamingMarkdownPrinter` 按完成行刷新，fence 状态跨行），不再缓冲到 `doOnComplete`。（`render/StreamingMarkdownPrinterTest` 5 测）
- [x] 2.3 工具调用块：`TOOL_RESULT` 渲染为 `⏺ 工具名` + `└ 结果摘要`，缩进显示；apiKey / bearer / `key=值` 等敏感字段 `redact` 掉。（`render/ToolCallFormatterTest` 7 测）
- [x] 2.4 推理 spinner：`REASONING` 阶段行尾显示 `⋯ thinking`（`\r` 重绘），出答案/工具时清除。
- [x] 2.5 `runTurn` 改走 `agentKernel.chat(agentKernel.activeId(), msg)`（注册可中断回合），保留 `noteUserMessage → maybeCompress → chat → saveCurrent` 顺序；`AgentReplTurnTest` mock kernel 返回假 `Flux<Event>` 验证映射 + 钩子顺序。

## 3. 斜杠命令补全菜单

- [ ] 3.1 输入 `/` 弹补全菜单，覆盖全部命令（`/model /session /mcp /permission /memory /compress /status /agent /tasks /skills /config /protocols /channels /help /clear /quit`）；沿用 `SystemRegistry.completer()`（picocli 元数据），必要时补 `Completer` 使空 `/` 弹全量。
- [ ] 3.2 单测：补全候选集合 = 全部斜杠命令名（防止命令新增/漏挂时静默漂移）。

## 4. 状态行

- [ ] 4.1 `StatusLine`（纯函数）：组装 `model · session · 权限mode`，读 `ModelManager/SessionManager/ConfigurationManager`；每个提示符上方打印一行（简单可靠，不做终端底部固定）。
- [ ] 4.2 单测：状态行内容组装正确，且不含任何凭据字段。

## 5. Ctrl-C 打断当前回合

- [x] 5.1 回合进行中：`renderStream` 用 `subscribe()` + `Disposable` + `CountDownLatch`；`terminal.handle(Signal.INT, …)` → `agentKernel.interruptCurrent()` + `disposable.dispose()` + `latch.countDown()`；回合结束 `finally` 恢复上一个 INT handler。中断后回提示符、进程不退出。
- [x] 5.2 空闲态：Ctrl-C 仍 `UserInterruptException`（丢弃当前行），Ctrl-D 仍 `EndOfFileException` 退出——`run()` 循环三态互不串味（未改）。
- [x] 5.3 单测：`AgentReplInterruptTest` 回合内触发 → 调 `interruptCurrent()` + dispose + 返回可输入态；handler 复位。

## 6. 轻量行内交互（选择器 + 确认）

- [ ] 6.1 `/model` 无参 → 行内选择器：`BindingReader`/`KeyMap` 读方向键，上下移高亮、Enter 选定、Esc 取消，当前行区重绘；选定后经 `ModelManager` + 会话层生效。**降级**：非交互/异常回退既有数字输入。
- [ ] 6.2 `/session` 无参 → 行内选择器（次要；成本高时留最小实现或数字选择）。
- [ ] 6.3 破坏性操作沿用 y/N 行内确认（既有），纳入统一交互口径。
- [ ] 6.4 单测：选择器「选定 → manager 委托」关键路径（参照现有 `ReplCommands` 测法，真 manager + temp dir 或 mock）。

## 7. 移除 pig-agent-web（保留 CLI REPL）

- [ ] 7.1 删除 `pig-agent-web/` 整目录 + parent POM `<modules>` 条目 + `pig-agent-cli/pom.xml` 对 `pig-agent-web` 的依赖。
- [ ] 7.2 删除 `cli.WebLauncher` + `AgentBootstrap.webContext()`（及其 `WebContext` import/装配）。
- [ ] 7.3 删除 `config.WebConfig` 与 `web.*` 读取；`application.yaml` 去除 `web.*`；`logback.xml` 去除 web 相关条目。
- [ ] 7.4 全量搜残留（`WebConsole` / `WebContext` / `WebLauncher` / `WebConfig` / `web.`）确保 `mvn -am compile` 无残留；`PigAgentCli` 注释里的 web 描述一并更新。

## 8. 文档与验收

- [ ] 8.1 `README` / `CLAUDE.md`：删 Web 章节与 `WebLauncher` 命令；REPL 描述更新为「CC 风格增强」（富渲染 / 补全 / 状态行 / Ctrl-C 打断 / 行内选择器）；模块表去掉 `pig-agent-web`。
- [ ] 8.2 `mvn -s "D:\env\apache-maven-3.9.10\conf\settings.xml" compile` / `test` 全绿。
- [ ] 8.3 `docs/planning/tui-and-core-roadmap.md` §8 进度表：能力名由 `tui-console` 更新为 `cc-repl`，勾选对应 spec/code 列。
