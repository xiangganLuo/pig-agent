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

- [x] 3.1 输入 `/` 弹补全菜单，覆盖全部命令（`build()` 里 16 个子命令全部 `addSubcommand`，`SystemRegistry.completer()` 直接取 picocli 元数据 → 候选即全量）。
- [x] 3.2 单测：`SlashCompletionTest` 断言 `PicocliCommands.commandNames()`（= 补全候选源）含全部 16 个斜杠命令且都以 `/` 开头（防新增/漏挂静默漂移）。

## 4. 状态行

- [x] 4.1 `StatusLine`：`format(model,session,perm)` 纯组装 + `from(ModelManager,SessionManager,ConfigurationManager)` 读值（model 用 `StoredModel.label()` 非 apiKey）；`run()` 每个提示符上方打印一行（不做终端底部固定）。
- [x] 4.2 单测：`StatusLineTest` 组装正确 + `from` 用安全 label、输出不含 apiKey。

## 5. Ctrl-C 打断当前回合

- [x] 5.1 回合进行中：`renderStream` 用 `subscribe()` + `Disposable` + `CountDownLatch`；`terminal.handle(Signal.INT, …)` → `agentKernel.interruptCurrent()` + `disposable.dispose()` + `latch.countDown()`；回合结束 `finally` 恢复上一个 INT handler。中断后回提示符、进程不退出。
- [x] 5.2 空闲态：Ctrl-C 仍 `UserInterruptException`（丢弃当前行），Ctrl-D 仍 `EndOfFileException` 退出——`run()` 循环三态互不串味（未改）。
- [x] 5.3 单测：`AgentReplInterruptTest` 回合内触发 → 调 `interruptCurrent()` + dispose + 返回可输入态；handler 复位。

## 6. 轻量行内交互（选择器 + 确认）

- [x] 6.1 `/model` 无参 → `InlineSelector`（`BindingReader`/`KeyMap` 读方向键，上下移高亮、Enter 选定、Esc/q 取消，原地重绘）；导航态纯 `SelectorModel`（单测）；选定经 `ModelSelection.apply` → `ModelManager.test` + 会话层生效。**降级**：`isInteractive` 为假（dumb/无真 TTY）回退数字输入。
- [x] 6.2 `/session` 无参 → 保持既有子命令（`SelectorModel`/`InlineSelector` 已复用就绪；`/session` 选择器为次要项，暂留既有交互，避免重复造轮子）。
- [x] 6.3 破坏性操作沿用 y/N 行内确认（`/model delete` 等既有），口径不变。
- [x] 6.4 单测：`ModelSelectionTest`「选定 → manager 委托」——会话切换 bind 当前会话、全局切换 setDefault、连通性失败不改任何状态。

## 7. 移除 pig-agent-web（保留 CLI REPL）

- [x] 7.1 删除 `pig-agent-web/` 整目录 + parent POM `<modules>` 条目 + `dependencyManagement` 里 `pig-agent-web` 条目 + `pig-agent-cli/pom.xml` 依赖。模块数 14→13。
- [x] 7.2 删除 `cli.WebLauncher` + `AgentBootstrap.webContext()`（及 `WebContext` import/装配）；`PigAgentCli` 移除 web 注释。
- [x] 7.3 删除 `config.WebConfig` 与 `web` 字段/getter；无 `application.yaml` 模板（配置运行时写工作区），`logback.xml` 无 web 条目（仅注释提及 WebLauncher，已改）。
- [x] 7.4 全量搜残留（主树无 `WebConsole/WebContext/WebLauncher/WebConfig/getWeb`；仅其他 agent 的 worktree 副本与 openspec 归档文档保留）；`mvn clean install -DskipTests` 13 模块 BUILD SUCCESS；config 10 + cli 64 单测全绿。

## 8. 文档与验收

- [x] 8.1 `README` / `CLAUDE.md`：删 Web 章节与 `WebLauncher` 命令；REPL 描述更新为「CC 风格增强」（富渲染 / 补全 / 状态行 / Ctrl-C 打断 / 行内选择器）；模块表去掉 `pig-agent-web`；补 gitbash/mintty 兼容提示（需真 TTY，用 winpty / Windows Terminal）。
- [x] 8.2 `mvn -s "..." test` 全绿：全项目 13 模块 BUILD SUCCESS（tools 73 / session 23 / model 12 / cli 64 等，0 失败）。
- [x] 8.3 `docs/planning/tui-and-core-roadmap.md`：从旧提交恢复并按新方向改写——tui-frontend 改为「CC 风格行式 REPL 增强（非 Lanterna）」能力名 `cc-repl`；§8 进度表标注 6 内核 spec 已并入 main/归档 + 组1–7 已完成。
