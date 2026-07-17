## MODIFIED Requirements

### Requirement: 流式富渲染

REPL SHALL 对助手回答做基础 markdown→ANSI 渲染（粗体、行内代码、代码块、列表、标题）并**增量**出字（不整体缓冲到回合结束）；SHALL 将工具调用渲染为缩进块（`⏺ 工具名(参数摘要)` + `└ 结果摘要`）；SHALL 在推理阶段显示**动态**思考指示——一个按 ~80–120ms 定时循环重绘的 spinner 字形（Claude-Code 风格 braille 转圈 `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`）+ `thinking…` 标签 + 已用秒数（如 `⠹ thinking… (3s)`），由一个**单守护线程 `ScheduledExecutorService`** 定时驱动。当首个答案文本 / 工具调用 / 工具结果 / 回合结束 / 需用户确认事件到达时，REPL MUST 停止动画并清除该行（`\r` + 清行），随后干净地打印答案/工具输出，MUST NOT 出现动画与内容交错的乱码。动画 MUST 仅在真实可交互 TTY 上启用（复用 REPL 既有 TTY 判定）；在非 TTY（dumb / 重定向）上 MUST 降级为单条静态思考指示（或无），MUST NOT 输出 `\r` 动画刷屏。动态 spinner SHALL 可经配置 `repl.spinner` 关闭（缺省开；关闭时退化为静态指示）。spinner 文案固定，凭据（apiKey、MCP 的 env/headers）MUST NOT 出现在任何渲染输出或日志中。

#### Scenario: 答案富渲染并增量出字
- **WHEN** 助手回答包含 markdown 标记
- **THEN** 终端按 ANSI 渲染粗体/行内代码/代码块/列表/标题，且内容随事件增量显示

#### Scenario: 工具调用块渲染且脱敏
- **WHEN** 一次回合触发工具调用
- **THEN** 以 `⏺ 工具名(...)` + `└ 结果摘要` 缩进显示，且不展示 apiKey / env / headers 等敏感字段

#### Scenario: 推理阶段动态 spinner
- **WHEN** 模型进入推理阶段（尚无答案文本 / 工具调用）且运行在可交互 TTY
- **THEN** 终端显示一个按定时器循环的 braille spinner 字形 + `thinking…` + 已用秒数，字形随定时器推进而变化

#### Scenario: 首个输出到达即停并清行
- **WHEN** 首个答案文本 / 工具调用 / 工具结果 / 回合结束 / 需用户确认事件到达
- **THEN** spinner 动画停止、其所在行被清除，随后答案/工具输出干净打印，无动画与内容交错的乱码

#### Scenario: 非 TTY 优雅降级
- **WHEN** 运行在非交互终端（dumb / 重定向 stdout）或 `repl.spinner` 关闭
- **THEN** 不输出 `\r` 动画刷屏，降级为单条静态思考指示（或无），其余渲染不受影响
