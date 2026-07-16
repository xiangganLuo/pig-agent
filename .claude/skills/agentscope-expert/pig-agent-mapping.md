# pig-agent ↔ AgentScope 2.0 Native Mapping

> 参考来源：
> - `docs/planning/harness-capability-map-2026-07-16.md`（harness 能力地图 + 裁决表）
> - `av2/20260716-foundation-main:docs/planning/agentscope-v2-migration.md`（迁移计划 + javap 实测）

---

## 1. 裁决表（REPLACE / KEEP / ADAPT / GAP）

| pig 自研类型 | AgentScope 2.0 原生 | 裁决 | 理由（一行） |
|---|---|---|---|
| `PigAgent` / `AgentHolder` | `HarnessAgent` (`.fromAgent(ReActAgent)`) | **REPLACE**（适配） | 2.0 提供 `HarnessAgent` 薄包装 ReActAgent，pig 应改包它；`AgentHolder` 继续作可变指针 |
| `SessionManager` / `JsonSession` | `AgentStateStore` (`JsonFileAgentStateStore`)，keyed by `(userId,sessionId)` | **REPLACE** | `io.agentscope.core.session.Session` 在 2.0 已删除；状态持久化改为 `AgentStateStore` |
| `CompositeLongTermMemory` / `FileSystemLongTermMemory` / `CachingLongTermMemory` | `MemoryConfig`（harness 层，每日 MEMORY.md + 后台合并 + 注入系统提示） | **REPLACE**（port `/memory` UX） | harness 原生双层记忆；`LongTermMemory` 接口在 2.0 标注 `@Deprecated`，"v2 rewrite in progress" |
| `CompressionService` | `CompactionConfig`（可指定便宜模型） | **REPLACE**（port `/compress` UX + compression-lineage） | 原生会话压缩 + 溢出强制重试；`ConversationMemory`（av2 新增适配器）是临时桥接 |
| `RetryingModel` (`core.retry`) | `.maxRetries(int)` / `.fallbackModel(Model)` on `ReActAgent.Builder` | **REPLACE** | native retry 已在 builder；保留 `TransientErrorClassifier`（原生暂无瞬态/永久分类） |
| `InterruptibleModel` (`core.interrupt`) | `ReActAgent.interrupt()` / `interrupt(RuntimeContext)` | **REPLACE** | native interrupt 已验证；前端 `TurnHandle` 继续作 UX 抽象，内部驱动原生 |
| `ToolPermissionHook` + `PermissionDeniedTool` | `PermissionEngine` / `PermissionMode` / `PermissionContextState` + `ToolResultState.DENIED` | **REPLACE** | Risk-2 PoC 已证明原生门控等价（tool never runs on DENY）；保留 `/permission` UX + `ToolRiskClassifier` 映射 |
| `EphemeralMemoryContextHook`（注入半部分） | `EphemeralMemoryMiddleware.onReasoning`（av2 新增，已 PoC 绿） | **REPLACE**（legacy hook 作桥接） | `PreReasoningEvent` 仍存（deprecated, via `LegacyHookDispatcher`），零风险桥接；middleware 是前向路径 |
| `AgentRegistry` / `AgentInstanceFactory` / `AgentSpec`（multi-agent 编排） | HarnessAgent 原生子 agent（`workspace/subagents/<id>.md`，`agent_spawn`/`agent_send`/`expose_to_user`） | **REPLACE** | 原生子 agent 远超 pig 同步多 agent：后台+反向通知+`expose`+权限继承+流式转发；`AgentSpec` ≈ md 前置元数据 |
| pig hooks（`LoggingHook` / `ToolCallLoggingHook` / `LoopDetectionHook`） | `MiddlewareBase` 5 阶段（`onAgent/onReasoning/onActing/onModelCall/onSystemPrompt`）+ deprecated `LegacyHookDispatcher` | **REPLACE/port**（现有 hook 零风险桥接） | `LoopDetectionHook` 当前继续用 legacy hook bridge；长期迁移到 middleware |
| `ChannelAgentBridge` + `pig-agent-channel`（飞书/钉钉/Slack/Webhook 等） | `Gateway` / `ChatUiChannel`（原生钉钉/飞书/GitHub/GitLab/企业微信适配器） | **REPLACE** | harness 原生渠道路由 + 每会话并发；pig 渠道实现量大，优先采用原生适配器 |
| exec-sandbox（`SandboxPolicy` / `CommandGuard`，进程内 best-effort） | harness `.filesystem(new DockerFilesystemSpec())`（真 Docker 隔离 + 跨调用恢复） | **GAP → ADOPT**（P3） | pig exec-sandbox 是"抗误操作"，非真隔离；原生给出一直想要的 P3 真隔离 |
| `McpManager` + `JsonMcpStore`（动态 CRUD） | 声明式 `workspace/tools.json`（工具级 allow/deny + MCP 白名单） | **ADAPT** | `Toolkit.registerMcpClient/removeMcpClient` 已保留；逐步转声明式；动态 CRUD `/mcp` UX 保留 |
| `SkillSource` / `SkillRegistry`（classpath + workspace） | `AgentSkillRepository`（Git/Nacos/MySQL/classpath/workspace 多源，`.skillRepository(...)`） | **ADAPT/ADOPT** | pig 两源够用；可选采用更多原生源；`SkillProvider` SPI 保留 |
| 大工具结果处理（❌ pig 无） | `.toolResultEviction`（>80K 字符卸载磁盘 + 占位） | **GAP → ADOPT** | pig 目前直接吃上下文膨胀，采用后开箱即用 |
| `AgentRunner` / `FileReportWriter` / `DeniedActionRecorder`（数字员工晨报） | 原生后台子 agent + `SubagentExposedEvent`（机制部分重叠） | **KEEP**（UX 差异化，跑在原生子 agent 上） | 三段式晨报"我做了/我发现/等你决定"是 pig 差异化 UX，`DeniedActionRecorder` 重新挂原生 DENY 事件 |
| `ModelManager` / `ProtocolRegistry` / 多配置 / 运行时切换 / 连通性测 | 模型扩展 builder（`apiKey/modelName/baseUrl`）+ 字符串解析 | **KEEP**（app 层自有大脑） | 多协议管理 + `/model` 命令 + 运行时切换是 pig 核心差异化，原生无等价 |
| CC 风格 REPL（`repl/render/*` / `StatusLine` / `MarkdownAnsiRenderer` / slash 补全） | ❌ 原生仅 Channel/ChatUiChannel | **KEEP**（差异化） | 原生无 TUI 渲染；保留全部 render 层，仅 `renderStream` 需从 `Event/EventType` 适配到 `Flux<AgentEvent>` |
| 工具契约（`ToolContractGuard` / `GuardedAgentTool` / `ToolErrors.message` / `CredentialSanitizer`）+ SSRF（`SsrfGuard`）+ 凭据文件黑名单 | 原生部分（permission），但无系统化 `{"error"}` 合约 + SSRF | **KEEP**（正交加固，补原生不足） | 安全加固层独立于 permission；保留并 port 到原生 `Toolkit` |
| `/ls:*` AI 开发流水线 + `/opsx:*` | ❌ | **KEEP** | 纯 pig tooling，无 AgentScope 耦合 |

> 源: `docs/planning/harness-capability-map-2026-07-16.md`
> 源: `av2/20260716-foundation-main:docs/planning/agentscope-v2-migration.md`

---

## 2. 迁移策略与分支方案

### 分支格局

- **`main`** = v1 稳定线（当前可工作，6 deerflow 改进已合入，1015 测试全绿）
- **`av2/20260716-foundation-main`** = v2 迁移线（off `main @ 86f2323`，Phase 0-redux + Phase 1 已完成）
- 旧备份 `main-v1-backup` + tag 保留；旧迁移线 `av2/20260715-foundation` 已被 redux 取代

### 各阶段现状

| 阶段 | 模块 | 状态 |
|---|---|---|
| **Phase 0-redux**（POM→2.0 + core/providers） | `pig-agent-core`（234 测试）+ `pig-agent-providers` | ✅ 绿 |
| **Phase 1**（独立叶模块） | `pig-agent-config`（36 测试）/ `pig-agent-workspace` / `pig-agent-task` | ✅ 绿 |
| **Phase 1 BLOCKED** | `pig-agent-model` / `pig-agent-onboarding` | ⛔ 被 `pig-agent-session` 阻塞 |
| **Phase 2**（tools 框架 + 权限重构） | `pig-agent-tools` → `ToolPermissionHook` 换原生 `PermissionEngine` | 待做（L，需安全评审） |
| **Phase 3**（Session 最大重写） | `pig-agent-session`：`JsonSession` → `AgentStateStore` | 待做（L，proven boundary；阻塞 model/onboarding） |
| **Phase 4**（前端 event-model 切换） | `pig-agent-cli` `renderStream` 适配 `Flux<AgentEvent>`；`pig-agent-web` / channel | 待做（Med-High） |
| **Phase 5**（清理）| 删 decorators/hooks；采用原生 compaction/skills；移除 1.x dep | 待做（S） |

> **快速解锁 model/onboarding 的捷径**：把 `AgentModelSwitcher` 接口（仅 1 方法，零 AgentScope 耦合）从 `pig-agent-session` 迁到 `pig-agent-core`，断开 `model → session` 依赖边，model + onboarding 即可独立推进 2.0。

---

## 3. 原生替代手搓 harness 的核心收益（"删什么"）

1. `RetryingModel` + `InterruptibleModel` → native `.maxRetries` / `ReActAgent.interrupt()`，彻底删除两个 Model 装饰器
2. `ToolPermissionHook` + `PermissionDeniedTool` → native `PermissionEngine` + `ToolResultState.DENIED`（Risk-2 PoC 已证明等价）
3. `SessionManager` / `JsonSession`（约 400 行 + 数据格式）→ `AgentStateStore`（原生自动化状态持久化）
4. `CompressionService` + 两层 `LongTermMemory`（数百行）→ `CompactionConfig` + `MemoryConfig`（可指定便宜压缩模型）
5. `AgentRegistry` / `AgentInstanceFactory` / multi-agent-kernel → 原生子 agent（功能更强：后台+通知+流式转发+权限继承）

---

## 4. pig 保留的差异化（原生缺口 / 真实价值主张）

1. **CC 风格 TUI/REPL**：`MarkdownAnsiRenderer` / `StreamingMarkdownPrinter` / `ToolCallFormatter` / `StatusLine` / slash 边打边弹补全——原生无等价
2. **多协议自有大脑管理**：`ModelManager` / `ProtocolRegistry` / 运行时模型切换 / `/model` 命令——原生无等价
3. **主动外呼（Track B）**：pig 的主动推送渠道——官方明确"以回复为主"，原生缺口
4. **工具安全加固正交层**：`ToolContractGuard` / `SsrfGuard` / 凭据文件黑名单——原生 permission 不覆盖
5. **数字员工晨报 UX**："我做了/我发现/等你决定"格式 + `DeniedActionRecorder`——跑在原生子 agent 上的 pig UX
6. **`/ls:*` AI 开发流水线**：纯 pig tooling
7. **中文运维命令**：`/model /agent /session /mcp /permission /compress /memory /tasks`

---

## 5. 原生提供但 pig 漏做（→ 应采用）

1. **大工具结果驱逐**（`.toolResultEviction`）——pig 无，直接吃上下文膨胀
2. **Docker 真隔离沙箱**（`.filesystem(DockerFilesystemSpec)`）——pig exec-sandbox 仅进程内 best-effort
3. **异步子 agent + 反向通知**——比 pig 同步多 agent 更强（完成后 system-reminder 自动注入，无需轮询）
4. **工作区即配置**（`workspace/subagents/`/`workspace/tools.json`/`workspace/skills/`）——比 pig 仅 `AGENT.md` 更完整
5. **技能多源**（Git/Nacos/MySQL）——pig 只有 classpath + workspace 两源

---

## 附：类型验证说明

以下 pig 类型均通过 `git -C D:\work\pig-agent grep -l <ClassName>` 在 `main` 分支确认存在：
`PigAgent` / `AgentKernel` / `CompositeLongTermMemory` / `CompressionService` / `McpManager` / `ChannelAgentBridge` / `ToolPermissionHook` / `SandboxPolicy` / `RetryingModel` / `InterruptibleModel` / `AgentSpec` / `AgentRegistry` / `AgentInstanceFactory` / `EphemeralMemoryContextHook` / `PermissionDeniedTool` / `SessionManager` / `ModelManager` / `AgentHolder` / `ToolContractGuard` / `AgentRunner` / `FileReportWriter` / `DeniedActionRecorder` / `TransientErrorClassifier` / `SkillSource` / `SkillRegistry` / `ProtocolRegistry` / `GuardedAgentTool` / `FileSystemLongTermMemory` / `LoopDetectionHook`

**`ConversationMemory`**：在 `main` 未找到——它是 `av2/20260716-foundation-main` Phase 0-redux 新增的 `AgentState` 适配器，仅存于迁移线。
