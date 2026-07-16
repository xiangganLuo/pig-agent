# AgentScope 2.0 Harness 能力地图 ↔ pig 裁决（2026-07-16）

> 依据官方 harness 架构文档（architecture/channel/change-log）。目的：**熟悉底座、在其上盖房子、抓遗漏**。
> 一句话结论：**HarnessAgent 原生提供了 pig 手搓的约 80% harness**。pig 应**采用 HarnessAgent**，把自研 harness 大量删除/替换，只保留真正的差异化"房子"，并**补上 pig 漏做、而框架已提供的能力**。

## HarnessAgent = ReActAgent 的薄包装
"能力在推理循环的关键时刻分层挂载，而非重写循环"。三条能力间通道：`RuntimeContext`（会话元数据）/ Workspace（文件系统路由）/ `AgentStateStore`（跨调用状态）。→ pig 的 `PigAgent`/kernel 应改包 `HarnessAgent`（`.fromAgent(ReActAgent)` 有迁移路径）。

## 裁决表（REPLACE=换原生删自研 / KEEP=差异化保留 / ADAPT=保留但接原生 / GAP=漏做→采用）

| 原生 harness 能力 | 入口 | pig 手搓的 | 裁决 |
|---|---|---|---|
| 状态持久化 (userId,sessionId)，跨进程/副本恢复 | `.stateStore` `AgentStateStore`（默认开） | `SessionManager`/`JsonSession` | **REPLACE** |
| 双层长期记忆自动固化→`MEMORY.md`（每日 md + 后台合并 + 注入系统提示） | `.memory` `MemoryConfig` | 两层记忆 + **A4 记忆抽取** | **REPLACE**（port `/memory` UX） |
| 会话压缩 / 溢出强制重试 | `.compaction` `CompactionConfig`（可指定便宜模型） | `CompressionService` + **A5 上下文增强** | **REPLACE**（port `/compress` UX + lineage） |
| **大工具结果驱逐**（>80K 字符卸载到磁盘留占位） | `.toolResultEviction` | ❌ **无** | **GAP → ADOPT** |
| **子agent 编排**（同步/异步 + 完成反向通知 + `expose_to_user`/`SubagentExposedEvent`/`sendToSubagent`） | `.subagent(...)` 或 `workspace/subagents/` | `AgentRegistry`/`AgentInstanceFactory`/`AgentSpec`/multi-agent-kernel | **REPLACE**（用户点名——多agent编排换成原生子agent） |
| 可插拔文件系统（local / 共享KV / 沙箱，切换不改代码） | `.filesystem(...)` | `FileSystemTools` | **ADAPT/ADOPT** |
| **沙箱隔离（Docker，真隔离 + 跨调用恢复 + 多副本）** | `.filesystem(new DockerFilesystemSpec())` | exec-sandbox（**仅进程内**，非真隔离） | **GAP → ADOPT**（拿到我们 P3 想要的真隔离） |
| Plan Mode（只读思考 + HITL 退出） | `.enablePlanMode()` | permission `plan` 模式（部分） | **ADOPT**（替/补） |
| 技能装配（Git/Nacos/MySQL/classpath/workspace 多层） | `.skillRepository(...)` | `SkillSource`(classpath+workspace) + **A3 复合Skill** | **KEEP 薄 + ADOPT 更多源** |
| MCP 声明式 + 工具级白名单 | `workspace/tools.json` | `McpManager` + availability gate | **ADAPT**（转声明式） |
| 渠道路由/每会话并发/多agent路由 + 原生适配器（钉钉/飞书/GitHub/GitLab/企业微信） | `Gateway`/`ChatUiChannel` | `pig-agent-channel` + 飞书/钉钉 spec | **REPLACE** |
| 工作区驱动人格（AGENTS.md/子agent/技能/MCP 白名单 皆为文件） | `.workspace(path)` | `AGENT.md`（部分） | **ADOPT 全量** |
| retry / fallback / interrupt | `.maxRetries`/`.fallbackModel`/`interrupt()` | `RetryingModel`/`InterruptibleModel` | **REPLACE**（保留 `TransientErrorClassifier` 若原生缺分类） |
| 权限 + HITL | `PermissionEngine`/`PermissionMode`/`permissionContext` | `ToolPermissionHook`/`PermissionDeniedTool` | **REPLACE**（保留 `/permission` UX + 风险分类映射） |
| middleware 5 阶段（onAgent/onReasoning/onActing/onModelCall/onSystemPrompt） | `MiddlewareBase` | pig hooks | **REPLACE/port**（`EphemeralMemoryMiddleware` 已 PoC） |
| **主动推送 / 外呼** | ❌ 官方"以回复为主" | **Track B** | **KEEP（差异化，填原生缺口）** |
| CC 风格 REPL / TUI 渲染 | ❌（仅 Channel/ChatUiChannel） | `repl/render/*` | **KEEP（差异化）** |
| 多协议模型管理 / 运行时切换 / 多配置 / 连通性测 | 模型扩展 + 字符串解析 | `ModelManager`/多配置/`/model` | **KEEP（app 层差异化 = 自有大脑）** |
| `/ls` 开发流水线 + `/opsx` | ❌ | `.claude/commands` | **KEEP** |
| 数字员工三段式晨报（我做了/我发现/等你决定） | 子agent 机制（部分） | `AgentRunner`/`FileReportWriter` | **KEEP（UX 差异化，跑在原生子agent 上）** |
| 中文运维命令 `/model /agent /session /mcp /permission /compress /memory /tasks` | ❌ | pig REPL | **KEEP（retarget 到原生机制）** |
| 工具契约 `{"error"}` / 凭据脱敏 / SSRF / 凭据文件黑名单 | 部分（permission） | contract/availability/SSRF | **KEEP（正交加固，补原生不足）** |

## pig 漏做、而框架已提供 → 应采用（"抓遗漏"）
1. **大工具结果驱逐**（>80K→磁盘 + 占位）——pig 无，直接吃上下文膨胀。
2. **Docker 沙箱真隔离**——pig exec-sandbox 只是进程内 best-effort；原生给我们一直想要的 P3 真隔离。
3. **异步子agent + 完成反向通知**——比 pig 同步多agent 强。
4. **工作区即配置**（子agent/技能/MCP 白名单/人格皆文件）——比 pig 仅 `AGENT.md` 更完整。
5. **技能多源**（Git/Nacos/MySQL）——pig 只有 classpath+workspace。
6. **声明式 `workspace/tools.json`**（MCP + 工具级 allow/deny）——比命令式更干净。

## 重构后 pig 的差异化（"房子"——唯一要自研到极致的）
- **人机体验层**：CC 风格 REPL/TUI + 中文 UX + 运维命令 + **主动外呼（原生缺口）** + 数字员工晨报格式。
- **自有大脑管理**：多协议模型管理 / 运行时切换 / 多配置 / 连通性（app 层）。
- **开发流水线**：`/ls` + `/opsx`。
- **编排策略（最核心）**：把原生能力（子agent/plan/compaction/memory/sandbox/gateway/skills）**组合成"解决人的事情"的方案**——这才是 pig 的价值主张，而非重造这些能力。
- **正交加固**：工具契约 / 凭据 / SSRF（补原生）。

## 多agent → 原生子agent（用户点名，本地文档 `docs/harness/subagent.md` 确认）
原生子agent **远超** pig 的 `AgentRegistry`/`AgentInstanceFactory`/`AgentSpec`/multi-agent-kernel：
- **三种声明**：`workspace/subagents/<id>.md`（文件名=agent_id，front-matter: description/model/tools/steps/temperature/`expose_to_user`/`mode`/`hidden`/workspace ISOLATED|SHARED）、程序化 `SubagentDeclaration`、内置 `general-purpose`。→ **pig 的 `AgentSpec` ≈ 这个 md 前置元数据**，几乎一一对应。
- **工具**：`agent_spawn`/`agent_send`/`agent_list`/`task_output`/`task_cancel`/`task_list`（LLM 直接调）。→ pig 的 `/agent` 变成这些的薄 UX。
- **同步/后台 + 自动反向通知**：`timeout_seconds=0` 后台跑，完成后**框架在下一步推理前用 system-reminder 注入结果，无需轮询**（正是本会话我编排子agent 的机制，原生自带）。→ pig **数字员工**可直接建成"后台子agent + 反向通知 + expose"，晨报格式作为 pig UX 盖在上面。
- **expose_to_user**：把子agent 注册进 Gateway，用户经 `SubagentExposedEvent`/`sendToSubagent` **直接对话子agent**（branch-off）。
- **remote 子agent**（Agent Protocol via `url`）、`persistSession`、**权限继承**（父 DENY 传给子，委派不能绕过安全边界）、递归安全（≤3 层）、userId 传播、Plan Mode 继承、**流式转发**（子事件带 `source` 标签汇入父 `streamEvents`）、`distributedStore` 跨副本。
- **裁决**：pig multi-agent-kernel → **REPLACE 为原生子agent**；`/agent` 保留为薄 UX；数字员工建在原生子agent 上（保留三段式晨报格式差异化）。

## 官方全量文档（本地，2026-07-16 用户提供）
`D:\Users\admin\Documents\en\`：`docs/building-blocks/{agent,tool,model,middleware,permission-system,message-and-event,context}.md`、`docs/harness/{architecture,subagent,channel,memory,compaction,sandbox,filesystem,plan-mode,skill,workspace}.md`、`integration/{channel,session,memory,skill,distributed,protocol,model,rag,infrastructure}/*`、`docs/others/going-to-production.md`。**迁移/spike 一律以本地全量文档 + javap 为准**（优于 WebFetch 片段）。

## 对迁移的影响
- **强烈指向采用 `HarnessAgent`**（spike 正用 javap+PoC 验证 API 级兼容）。若确认，Phase 2-4 从"在 ReActAgent 上接原生权限/状态"升级为"改包 `HarnessAgent`，删掉 session/compression/memory/multi-agent/channel 大量自研，port UX + 采用上面 6 个遗漏能力"。
- 自研删除面显著扩大 → 最贴合"复杂的交给框架、专注核心定位"。
- 记入 Phase 3（memory/compaction 原生化）+ 新增 Phase「子agent 化多agent」「Docker 沙箱」「工具结果驱逐」「工作区即配置」。
