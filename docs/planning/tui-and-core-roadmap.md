# 计划：TUI 前端 + Hermes 可借鉴能力 路线图

> 单一计划文件，汇总当前所有待办。分两条轨道：**前端轨**（TUI，adapter 层）与
> **内核能力轨**（借鉴 Hermes，改 `AgentKernel`/model/tool/memory 内核）。
> 每一项都作为独立 spec 走强制 `/ls:*` 流水线（clarify→spec→code⇄itest→archive，三道人工门）。
>
> 状态图例：`[ ]` 未开始 · `[~]` 进行中 · `[x]` 已归档
>
> 创建于 2026-07-07（分支 `feat/tui-frontend`）。

---

## 0. 两条轨道与依赖总览

```
前端轨（adapter 层，不动内核）
  └─ tui-frontend                [进行中]  CC 风格行式 REPL 增强（非 Lanterna 全屏）
                                            在既有 CLI REPL 上增强：流式富渲染 / 斜杠补全 /
                                            状态行 / Ctrl-C 真中断 / 行内选择器；移除 pig-agent-web 模块
                                            （保留并增强 REPL，不删）。能力名：cc-repl

内核能力轨（借鉴 Hermes，各自独立 spec）—— 6 个已合并进 main（归档）
  ① interruptible-run            [x 已归档]  可中断模型调用 —— Ctrl-C 真中断的前提，头号痛点
  ② tool-availability            [x 已归档]  check_fn 式工具门控
  ③ prefix-cache-context         [x 已归档]  记忆/RAG 注入 user 而非 system
  ④ tool-json-contract           [x 已归档]  工具 JSON 错误契约 + 双层兜底
  ⑤ compression-lineage          [x 已归档]  压缩生成子会话 lineage
  ⑥ tool-autoregister            [x 已归档]  导入/SPI 式工具自注册，省 wiring
```

**咬合关系**：`tui-frontend` 强依赖 ①（`AgentKernel.interruptCurrent()` 现已是真实现，Ctrl-C 直接用）
与 KernelEvent（已存在）。①–⑥ 均已合并进 main，`tui-frontend` reset 到 main 后在其之上增强。

---

## 1. Spec 需求清单（逐条详述，按推进顺序）

> 每条即一个独立 spec，走完整 `/ls:*` 流水线。下表是需求层描述；实现层设计在各 spec 的 `/ls:spec` 阶段产出。

### 顺位 1 · `tui-frontend`（前端轨，能力名 cc-repl）
> **方向修正（用户拍板）**：放弃 Lanterna 全屏多面板，改为在既有 CLI REPL 上做 **Claude Code 风格的行式对话前端增强**。**不删 REPL**，是增强它。
- **需求详述**：把现有 `pig-agent-cli` 的逐行 REPL 增强成 CC 风格：① 流式富渲染（markdown→ANSI：
  粗体/行内代码/代码块/列表/标题；`⏺工具名 / └结果摘要` 工具块；`⋯ thinking` spinner；增量出字）；
  ② 斜杠补全菜单（输入 `/` 弹全部命令，picocli 元数据驱动）；③ 状态行（提示符上方 `model · session · perms`）；
  ④ Ctrl-C 真中断（回合走 `AgentKernel.chat` 注册可中断回合，SIGINT → `interruptCurrent()`，回提示符不退出）；
  ⑤ 轻量行内交互（`/model` 无参方向键选择器 + 数字降级，y/N 确认）。同步**移除 `pig-agent-web` 整模块**（收敛前端面）。
- **关键验收**：REPL 流式富渲染并镜像回合（`noteUserMessage`→`maybeCompress`→`kernel.chat`→`saveCurrent`）；
  斜杠补全覆盖全部命令；状态行不含凭据；Ctrl-C 中断当前回合、进程不退；`pig-agent-web` 无残留引用；
  `mvn compile`/`mvn test` 绿；文档与启动命令同步更新；gitbash/mintty 兼容提示写清。
- **依赖**：`interruptible-run` 已合并进 main → `AgentKernel.interruptCurrent()` 为真实现，Ctrl-C 直接用（不再接桩）。
- **落点**：`pig-agent-cli`（增强 `repl` 包：新增 `render/` 富渲染 + `select/` 选择器 + `StatusLine`/`ModelSelection`；
  删 `WebLauncher`）；移除 `pig-agent-web`；`config` 删 `WebConfig`。**不新增 Lanterna 依赖、不新增模块**。

### 顺位 2 · `interruptible-run`（内核轨）
- **需求详述**：让「一次模型回合」可被用户中途取消。借鉴 Hermes：模型调用跑在后台线程，
  主线程同时等待 {响应就绪 / 中断信号 / 超时}；一旦中断，**放弃该线程、不把半截响应写入对话历史**，
  agent 立即回到可接受新输入的状态。对上暴露 `AgentKernel.interruptCurrent()` 供 TUI/前端调用。
  **本 spec 一并启用 model-retry 的真 per-attempt 硬超时**：可中断落地后，把 `model.retry.per-attempt-timeout-seconds`
  从当前默认 0（禁用）改为可安全使用（超时即中断当前 attempt 并重试，不再因慢模型误伤、不再撞 "Agent is still running"）。
- **痛点**：现有 `ReActAgent` 不可中断 —— 超时后底层仍在跑，重订阅即撞 "Agent is still running"；
  正因如此 model-retry 的 `per-attempt-timeout` 被迫默认 0、digital-employee 的超时只能 best-effort。
- **关键验收**：中断后 agent 不再占用、可立即发起新回合；历史不含半截输出；
  与现有 `RetryingModel` 装饰层协作（在 `model.stream` 层包中断，而非 `reactAgent.stream`，沿用下沉思路）；
  解锁 TUI 真·停止键；`per-attempt-timeout-seconds` 可安全启用（>0 时超时中断当前 attempt 并按策略重试）。
- **依赖**：无（可与 TUI 并行）。**是 TUI 停止能力的实现前提**，建议紧随 TUI。
- **落点**：`pig-agent-core`（agent / retry / kernel）。

### 顺位 3 · `tool-availability`（内核轨）
- **需求详述**：工具可声明「可用性判据」（如缺 API key、缺二进制依赖、服务未配置），
  不可用的工具**不进 Toolkit schema**（模型根本看不到），而非执行时才被权限 hook 拦。
  作为 `ToolPermissionHook` veto 之外的**第一道过滤**，两者互补。
- **价值/痛点**：从源头防止模型幻觉调用不可用工具、节省 schema token；TUI 工具/status 面板据此灰显。
- **关键验收**：缺依赖的工具不出现在传给模型的工具列表；判据异常时 fail-safe 视为不可用；
  已有工具行为不回归。
- **依赖**：无。TUI 若做工具可用性展示则弱依赖本 spec。
- **落点**：`pig-agent-tools`（`@Tool` 旁的 check 机制）+ Toolkit 组装处（`AgentWiring`/`AgentBootstrap`）。

### 顺位 4 · `prefix-cache-context`（内核轨）
- **需求详述**：借鉴 Hermes `pre_llm_call` —— 把 `CompositeLongTermMemory.retrieve` 的合并记忆
  注入到**当前回合的 user 消息**（ephemeral，不落库、不改历史），而非拼进 system prompt，
  从而保持 system prompt 跨回合恒定，命中 Anthropic/OpenRouter 的 prefix cache。
- **价值**：多轮对话省 75%+ 输入 token；与已有 `CompressionService` 配套。
- **关键验收**：system prompt 跨回合字节级稳定；记忆内容仍进入模型输入；原始历史 user 消息不被篡改。
- **依赖**：无。
- **落点**：`pig-agent-core`（memory 注入点 + agent 调用前的消息组装）。

### 顺位 5 · `tool-json-contract`（内核轨，可选收尾）
- **需求详述**：统一 `@Tool` 返回契约 —— 成功/错误一律返回规范结构（错误走 `{"error": ...}`），
  在 dispatch 与 handle 两层兜底 try/catch，**禁止异常穿透到模型**。
- **关键验收**：任意工具抛异常时，模型收到的是规范错误结果而非中断；现有工具不回归。
- **依赖**：无。**落点**：`pig-agent-tools`。

### 顺位 6 · `compression-lineage`（内核轨，可选收尾）
- **需求详述**：借鉴 Hermes —— 上下文压缩时派生一个 session 谱系（lineage）ID，压缩产生的
  「子会话」可回溯到压缩前的原始会话；TUI session 面板可展示 lineage 关系。
- **关键验收**：压缩后能查到 parent/child 关系；压缩仍不触碰持久化历史与记忆（现有约束不变）。
- **依赖**：无。**落点**：`pig-agent-core`（compression）+ `pig-agent-session`。

### 顺位 7 · `tool-autoregister`（内核轨，可选收尾）
- **需求详述**：借鉴 Hermes 的导入时自注册 —— 用扫描 / SPI 机制替代 `AgentBootstrap` 里手动的
  `toolkit.registration().tool(new X()).apply()` 串行 wiring，新工具「落文件即被发现」。
- **关键验收**：新增工具类无需改 wiring 即生效；加载失败的可选工具被隔离、不影响其他工具。
- **依赖**：无。**落点**：`pig-agent-tools` + wiring 处。

**推进策略**：**A 方案**（推荐）—— `tui-frontend` 先走完整流水线，设计阶段留好两个桩（见 §2）；
`interruptible-run` 作为紧随其后的独立 spec。顺位 3–7 与 TUI 无强耦合，可按资源穿插推进。

---

## 2. Spec A：tui-frontend / cc-repl（进行中）

- **类型 / 分支**：`feat` / `feat/tui-frontend`（reset 到 `origin/main`，6 内核 spec 已并入）。
- **决策**（用户拍板）：**放弃 Lanterna 全屏**，改为在既有 CLI REPL 上做 **CC 风格行式对话增强**；
  **不删 REPL**；**移除 `pig-agent-web` 模块**。openspec 变更见 `openspec/changes/tui-frontend/`（能力 `cc-repl`）。

### 意图
先把「单一终端前端 + 核心链路」跑通、跑稳。CC 风格 REPL 比全屏 TUI 更贴合本项目（JLine + picocli 既有基座、
Windows 兼容经验、零学习成本），改造成本低、风险小，且 `interruptible-run` 已让 Ctrl-C 真中断可直接落地。

### 组划分与状态（tasks 见 openspec）
- 组1 分支基线 + spec 重写 —— ✓
- 组2 流式富渲染（`render/MarkdownAnsiRenderer`/`StreamingMarkdownPrinter`/`ToolCallFormatter` + spinner）—— ✓
- 组3 斜杠补全（全命令 + drift 守卫测试）—— ✓
- 组4 状态行（`StatusLine`，不含凭据）—— ✓
- 组5 Ctrl-C 真中断（`renderStream` subscribe + SIGINT → `interruptCurrent()`）—— ✓
- 组6 行内选择器（`select/InlineSelector`+`SelectorModel`，`ModelSelection`，数字降级）—— ✓
- 组7 移除 `pig-agent-web`（模块 14→13，保留 REPL）—— ✓
- 组8 文档与验收（README/CLAUDE/本路线图 + gitbash/mintty 兼容提示）—— 进行中

### 影响模块
- 主改：`pig-agent-cli`（增强 `repl` 包 + 新增 `repl/render`、`repl/select`；删 `WebLauncher`）。
- 移除：`pig-agent-web` 整模块（parent POM `<modules>` + `dependencyManagement` + 目录 + `cli` 依赖）。
- `pig-agent-config`：删 `WebConfig` + `web` 字段。
- 只读复用：`AgentKernel` + 各 manager（不改内核）。**不新增 Lanterna、不新增模块。**

### 风险 / 注意
- **终端要求**：JLine 需真控制台。Windows Terminal / PowerShell / cmd 正常；**git-bash/mintty 下 stdin 非真 TTY，交互异常** —— 用 `winpty` 或 Windows Terminal（写入文档）。
- 沿用 JLine jna provider + `jansi(false)`，绝不 `AnsiConsole.systemInstall()`（Windows 会双重包裹 System.out）。
- Web 移除是「后置重建」而非「永久废弃」：生态扩展阶段作为又一 `AgentKernel` adapter 回归（`WebContext`/`WebJson` 投影蓝本仍在 git 历史）。

---

## 3. Spec B①：interruptible-run（待立项，紧随 TUI）

- **借鉴来源**：Hermes `run_agent.py::_interruptible_api_call()`。
- **目标**：模型调用跑后台线程，主线程同时 wait{响应就绪 / 中断事件 / 超时}；被打断即**丢弃线程、
  不把半截响应写进对话历史**。
- **解决的痛点**：架构备注里反复出现的 `ReActAgent` 不可中断 → 超时后底层仍在跑 →
  重订阅撞 "Agent is still running"；model-retry 的 `per-attempt-timeout` 因此被迫默认 0；
  digital-employee 的超时只能 best-effort。
- **解锁**：TUI/Web 的真·停止键；model-retry 的真·per-attempt 硬超时。
- **落点**：`AgentKernel` 增 `interruptCurrent()`；与现有 `RetryingModel` 装饰层协作
  （在 model.stream 层包一层可中断包装，而非 reactAgent.stream —— 沿用 model-retry 的下沉思路）。
- **影响模块**：`pig-agent-core`（agent/retry/kernel）。
- **验收要点**：停止后 agent 不再占用、可立即发起新回合；历史不含半截输出。

---

## 4. Spec B②：tool-availability（待立项）

- **借鉴来源**：Hermes 工具 `check_fn` 门控。
- **目标**：工具可声明可用性判据（如缺 API key / 缺依赖），不可用则**不进 Toolkit schema**
  （模型根本看不到），而非执行时才被权限 hook veto。
- **价值**：从源头防幻觉调用、省 token；TUI 工具面板可据此灰显/隐藏。
- **落点**：`pig-agent-tools`（`@Tool` 旁加 check 机制）+ Toolkit 组装处（`AgentWiring`/`AgentBootstrap`）。
- **与现有权限系统的关系**：作为 `ToolPermissionHook` veto 之外的**第一道过滤**（不可用直接不给看），
  两者互补，不冲突。

---

## 5. Spec B③：prefix-cache-context（待立项）

- **借鉴来源**：Hermes `pre_llm_call` 上下文注入到 **user 消息**而非 system prompt。
- **目标**：`CompositeLongTermMemory.retrieve` 的合并结果注入到当前回合 user 消息（ephemeral，
  不落库、不改历史），保持 system prompt 跨回合恒定 → 命中 Anthropic / OpenRouter prefix cache。
- **价值**：多轮省 75%+ 输入 token；与已有 `CompressionService` 配套。
- **落点**：`pig-agent-core`（memory 注入点 + agent 调用前的消息组装）。

---

## 6. Specs B④⑤⑥：可选收尾优化（低优先，零 TUI 依赖）

- **④ tool-json-contract**：`@Tool` 统一返回规范 + 双层错误兜底，禁止异常穿透到模型。落点 `pig-agent-tools`。
- **⑤ compression-lineage**：压缩时派生 session 谱系 ID，可追溯原始会话；TUI session 面板可展示。落点 `pig-agent-core` + `pig-agent-session`。
- **⑥ tool-autoregister**：用扫描 / SPI 替代 `AgentBootstrap` 手动 `toolkit.registration().tool().apply()`。落点 `pig-agent-tools` + wiring。

---

## 6.5 生态扩展（后置轨，跑通核心后再启动）

本轮刻意收敛前端面以「跑通核心」；以下为核心稳定后的生态扩展方向，均作为又一 `AgentKernel`
adapter / 插件回归，不属于 `tui-frontend` 范围：

- **web-console-v2**：基于收敛后的核心重建 Web 控制台（复用移植保留的 `WebContext`/投影蓝本）。
- **plugin-system**：借鉴 Hermes 的 `register(ctx)` + 三发现源 + hook 体系（尤其 `pre_llm_call`
  上下文注入，与 B③ 呼应），让工具/hook/命令可外挂。
- **channel-expansion**：更多 channel adapter（参照 Hermes 20 平台 gateway）。

> 原则：生态扩展一律「内核稳定 → 加 adapter/插件」，绝不反向污染内核。

---

## 7. 已与 Hermes 同构、无需再做（印证，仅记录）

- 门面解耦：`AgentKernel` + 前端 adapter ≈ Hermes `AIAgent` + 入口适配器（本轮收敛为单 TUI adapter；
  Web 作为又一 adapter 在生态扩展阶段回归）。
- 权限 allowlist：EXEC 首 token 归一化 + 持久化 ≈ Hermes `DANGEROUS_PATTERNS` + `command_allowlist`。
- 协议而非厂商：5 个 `ModelProtocol` SPI ≈ Hermes 3 API 模式 + provider profile。
- 数字员工：`AgentRunner` cron ≈ Hermes first-class agent cron job。

---

## 8. 流水线进度（滚动更新）

| spec（能力名） | clarify | spec | code⇄itest | archive | 分支 |
|------|:---:|:---:|:---:|:---:|------|
| tui-frontend（cc-repl） | ✓ | ✓ | 组1–7 ✓、组8 进行中 | | `feat/tui-frontend` |
| interruptible-run | ✓ | ✓ | ✓ | ✓ 已并入 main | (merged) |
| tool-availability | ✓ | ✓ | ✓ | ✓ 已并入 main | (merged) |
| prefix-cache-context | ✓ | ✓ | ✓ | ✓ 已并入 main | (merged) |
| tool-json-contract | ✓ | ✓ | ✓ | ✓ 已并入 main | (merged) |
| compression-lineage | ✓ | ✓ | ✓ | ✓ 已并入 main | (merged) |
| tool-autoregister | ✓ | ✓ | ✓ | ✓ 已并入 main | (merged) |

**当前状态**：
- `tui-frontend`（能力 `cc-repl`）：方向已从 Lanterna 全屏改为 CC 风格 REPL 增强；组1–7 已完成并全绿
  （cli 64 单测），组8 文档收尾中。`AgentKernel.interruptCurrent()` 因 `interruptible-run` 已并入 main 而为真实现，Ctrl-C 直接用。
- 6 个内核 spec：**已全部合并进 `main` 并归档**（`openspec/changes/archive/`）。`tui-frontend` 已 reset 到 `origin/main` 并在其之上增强。

> 注：本前端不引入 Lanterna、不新增模块；REPL 保留并增强，`pig-agent-web` 模块已移除。
