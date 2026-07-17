## 1. 友好模型错误 + 凭据底线（HIGH F1b）

- [x] 1.1 `ModelErrorMessages.friendly(Throwable)`（`pig-agent-core`，纯函数）：按 HTTP 状态/异常类型映射本地化一行；unwrap cause 链；fallthrough 经 `SecretRedactor` + bare-token 脱敏。
- [x] 1.2 `ModelErrorMessagesTest`：各分支（429/5xx/401/403/400·422/404/timeout/network）+ null 安全 + 脱敏（sk-/bearer/assignment/AIza/ghp_/xox/JWT）+ cause 链 + code 词界。
- [x] 1.3 `AgentRepl` 错误分支：`Ansi.error(ToolCallFormatter.redact(friendly(err)))` + 长度上限（surrogate 安全）。

## 2. 流式顺序（HIGH #3）

- [x] 2.1 `onEvent` 处理 `TextBlockEndEvent` → flush；工具行渲染前先 flush 父打印器。
- [x] 2.2 `AgentReplTurnTest`：中途工具事件先冲出缓冲答案（顺序断言）。

## 3. 工具反馈（MEDIUM #4/#5）

- [x] 3.1 `ToolCallStartEvent`/`ToolResultStartEvent` 立即打印 `⏺ 头`（每 call id 一次），执行期保留 spinner，结果到达补 `└ 体`。
- [x] 3.2 `ToolResultState.ERROR` → 红色 `✗` errorBody，视觉区分成功。
- [x] 3.3 `ToolCallFormatter` 拆 `head`/`body`/`errorBody`；`AgentReplTurnTest` head-on-start + head 只打一次 + ERROR 渲染。

## 4. 重试可见性（MEDIUM #7）

- [x] 4.1 `ThinkingSpinner` 增 `start(baseLabel, retrySwitch)` + `labelFor`：推理 spinner 超 ~6s 翻 `模型繁忙，重试中…`；工具执行相位不翻。
- [x] 4.2 `ThinkingSpinnerTest`：labelFor 阈值 + tick 后重试文案 + 工具相位不翻（既有测试不回归）。

## 5. 凭据脱敏扩展（MEDIUM #6）

- [x] 5.1 `ToolCallFormatter.redact` 增 bare token：`AIza…`/`xox…`/`xapp-…`/`gh[pousr]_…`/JWT；保留既有 sk-/key=/Authorization。
- [x] 5.2 `ToolCallFormatterTest`：逐类脱敏 + `?key=AIza` URL 场景。

## 6. ModelManager.test() 友好 + 超时（MEDIUM #8）

- [x] 6.1 `runProbe(Callable, timeout)` 有界超时 seam；成功/无响应/超时/异常→友好 `TestResult`。
- [x] 6.2 `ModelManagerTestProbeTest`：四路径 + fallthrough 脱敏。

## 7. 健壮性 + 打磨（#9/#10/#11 + LOW）

- [x] 7.1 斜杠命令失败：一行 `Ansi.error` + `log.warn`（栈仅入日志），替换 `systemRegistry.trace`。
- [x] 7.2 `InlineSelector` 行宽截断（ANSI 感知、code-point 安全）；`InlineSelectorTest`。
- [x] 7.3 中文化 chrome：`[已中断]`/`[已达最大推理轮次]`/`[所有工具调用被权限策略拒绝]`（`Error:` 归入 #2）；更新 `AgentReplInterruptTest`/`AgentReplErrorPrintTest`。
- [x] 7.4 LOW：`ToolCallFormatter`/`SubagentEventRenderer` surrogate-safe 截断；`StatusLine`/`ModelSelection` 去控制字符；空回合 `[无输出]`（`printer.hasOutput()` + produced）；SIGINT 先 `interrupted=true` 后 dispose（已满足，核验）。

## 8. 验收

- [x] 8.1 `mvn -pl pig-agent-cli -am test` 全绿；`mvn -pl pig-agent-core -am test` 覆盖 `ModelErrorMessages`。
- [ ] 8.2 `openspec validate repl-render-ux --strict` 通过。
