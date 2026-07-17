## Why

pig 的技能栈今天是**只读**的：`SkillsTool`（`listSkills`/`loadSkill`）+ `SkillSource`/`SkillRegistry`/`WorkspaceSkillSource` 让 agent 能**用**技能，却不能**沉淀**技能。个人助理北极星要求 pig「越用越会」——把「解过的一个非平凡任务/工作流」蒸馏成一份可复用的 `SKILL.md`，下次遇到同类任务直接 `loadSkill` 复用（Hermes / OpenClaw 的 skill-from-experience）。

直接放开 agent 写自己的行为文档风险极高（skill = 改变未来行为的可执行文档，prompt 注入可借此把恶意指令写进未来行为）。两家蓝本处置相反——Hermes `write_approval` 默认关（自由写），OpenClaw **始终强制人工审**。pig 的安全基线（权限 fail-closed、凭据不外泄、外部输入边界加固）与 OpenClaw 一致：**默认人工门**（OD5-A）。

机制上不另起炉灶：**在 pig 已投资的 `WorkspaceSkillSource` + composite-skill 读栈之上加一条写路径**（OD6-B），而非引入第二套读系统——技能一旦提升到 `workspace/skills/<name>/`，既有 `WorkspaceSkillSource` 下一次 `listSkills`/`loadSkill` 即发现（无需重启，已验证 seam），**只读 `SkillsTool` 的 `@Tool` 名/签名逐字不变**（无模型面回归）。

本能力默认 **关闭**（`skills.autonomous.enabled=false`）——自我改写行为是高风险能力，须显式开启；关闭时不注册任何写工具、`WorkspaceSkillSource` 读栈逐字节等价于今天（零行为变更）。

## What Changes

- **`pig-agent-tools`（新增 `io.pigagent.tool.skills.authoring`）：**
  - `SkillDraft`（record 值类型）：`name`/`description`/`keywords`/`body`，`toSkillMd()` 渲染成带 YAML front-matter 的 `SKILL.md` 文本——不可变。
  - `SkillStagingArea`：**暂存区** I/O——把草稿写到 `workspace/skills/.pending/<name>/SKILL.md`（**不**直接安装）、列举/读取/丢弃暂存草稿、**原子提升**（`Files.move` 到 `workspace/skills/<name>/`）。写盘走 temp-file + `ATOMIC_MOVE` + POSIX `0600`（镜像 `JsonModelStore.persist`，兑现 composite-skill 的 D8 立约）。
  - `SkillContentScanner`（**Strategy 纯扫描**）+ `SkillScanResult`（record）：提升前的内容安全扫描——`SkillSecurity` 名字/路径校验（拒绝 `../`/符号链接/保留名）+ `SkillLimits` 尺寸上限 + `CredentialSanitizer` 凭据扫描（正文含密钥/token 即拒）+ `SkillManifestParser` 结构校验（front-matter 可解析、name/description/body 非空）。
  - `SkillGate`：**编排**——`listPending`/`review`/`promote`/`discard`/`autoPromotePending`，把「扫描 + 去重 + 原子提升」收在一处；去重经 `SkillRegistry`（工作区 + 内置）按名判定（工作区同名 → 冲突拒绝；仅内置同名 → 允许并标注「覆盖内置」；描述近似 → 非阻断告警）。`PendingSkill`/`PromotionResult`（record）为其值类型。
  - `SkillAuthoringTool`（`@Tool`，**非只读**）：`proposeSkill(name, description, body, keywords)` → 经 `SkillContentScanner` 轻校验后写暂存（**永不提升**）；`skillManage(action, name)`（`list`/`discard`）管理**暂存草稿**（永不提升）。实现 `ToolAvailability`，`skills.autonomous.enabled=false` 时从 schema 隐藏。
  - `SkillSecurity` 扩：`isValidSkillName(String)`（纯静态，`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`、拒 `.`/`..`/路径分隔/点前缀保留名）。
  - `WorkspaceSkillSource` 加固：扫描时**跳过点前缀（`.`）保留目录**（`.pending`/`.archive` 永不作为技能浮现）——防暂存/归档目录污染 `listSkills`。
  - `ToolRiskClassifier`：`proposeSkill`/`skillManage` 登记为 `WRITE`（受权限治理）。
  - `ToolContext` 扩：`skillStaging`（`SkillStagingArea`，null → 不注册写工具）+ `autonomousSkillsEnabled`（`BooleanSupplier`，availability 实时读配置）。
  - SPI：新增 `SkillAuthoringToolProvider` + `META-INF/services/io.pigagent.tool.spi.ToolProvider` 一行（自动注册，无 `AgentBootstrap` 装配改动）。
- **`pig-agent-config`**：`PigAgentConfig` 加 `skills.autonomous` 块（`SkillsConfig` → `AutonomousSkillsConfig`）：`enabled`（默认 **false**）、`staging-dir`（默认 `.pending`）、`auto-promote`（默认 **false**）。`@JsonIgnoreProperties` 容错沿用。
- **`pig-agent-cli`**：
  - `AgentBootstrap`：构建 `SkillStagingArea`/`SkillContentScanner`/`SkillGate`，注入 `ToolContext`（写工具 availability）+ `Services`（`/skill` 命令 + REPL auto-promote）。
  - `SkillCommand`（`/skill`，operator 面**人工门**）：`review`（列暂存 + 展示待装 `SKILL.md` + 扫描/去重预览）、`approve <name>`（扫描 + 去重 + 原子提升 → `WorkspaceSkillSource` 即时发现）、`reject <name>`（丢弃暂存）。注册进 `ReplCommands`/`ReplContext`/`AgentRepl`。
  - REPL `runTurn` 末：`auto-promote=true` 时对交互轨道调 `SkillGate.autoPromotePending()`（仍跑扫描 + 去重）；渠道/自主轨道无此 hook → **永不自动提升**（fail-closed by construction）。

## Capabilities

### New Capabilities
- `autonomous-skills`: agent 把「解过的非平凡任务/工作流」蒸馏成 `SKILL.md` 草稿（`proposeSkill`/`skillManage` @Tool，WRITE 分级、availability 门控）→ 写**暂存区** `workspace/skills/.pending/<name>/`（**不**直接安装）→ **默认人工门**（`/skill review|approve|reject`）→ 提升前**安全扫描**（`SkillSecurity` 路径/名字加固 + `SkillLimits` 尺寸 + `CredentialSanitizer` 凭据 + `SkillManifestParser` 结构）+ **去重**（工作区同名拒绝、内置同名允许覆盖、描述近似告警）→ **原子提升**（temp + `ATOMIC_MOVE` + `0600`）到 `workspace/skills/<name>/`，由既有 `WorkspaceSkillSource` 即时发现（无重启）。渠道/自主 agent（无 confirmer）**fail-closed = 只提案不提升**；默认 `enabled=false` → 无写工具、零行为变更。

## Impact

- **代码（`pig-agent-tools`）**：新增 `skills/authoring/{SkillDraft, SkillStagingArea, SkillContentScanner, SkillScanResult, SkillGate, PendingSkill, PromotionResult, SkillAuthoringTool}`、`spi/providers/SkillAuthoringToolProvider`；改 `skills/SkillSecurity`（`isValidSkillName` + 点前缀跳过）、`skills/WorkspaceSkillSource`（跳保留目录）、`permission/ToolRiskClassifier`（+2 WRITE）、`spi/ToolContext`（+2 字段）、`META-INF/services/io.pigagent.tool.spi.ToolProvider`（+1 行）。只读 `SkillsTool`/`SkillSource`/`SkillRegistry`/composite-skill 契约不变。
- **代码（`pig-agent-config`）**：`PigAgentConfig` 加 `skills.autonomous`。
- **代码（`pig-agent-cli`）**：`AgentBootstrap` 接线、`SkillCommand` 新增、`ReplCommands`/`ReplContext`/`AgentRepl`/`PigAgentCli` 线程 `SkillGate`。
- **协作/不改**：`ToolRegistrar` 自动注册、`ToolAvailabilityGate`、原生 `PermissionEngine`、返回契约 + 分发守卫、`ToolContractGuard` 全部不变（写工具照常被 guard/gate）。
- **测试**：`pig-agent-tools` 新增 `SkillDraftTest`/`SkillStagingAreaTest`/`SkillContentScannerTest`/`SkillGateTest`/`SkillAuthoringToolTest`/`SkillSecurityAuthoringTest`/`WorkspaceSkillSourceReservedDirTest`；`pig-agent-config` 扩 `PigAgentConfigTest`；`pig-agent-cli` 扩（`ToolContext` availability + `SkillCommand`）。离线，mock 处 mock，无真模型。
- **文档**：`CLAUDE.md` builtin-skills 段落补自主沉淀 skills（propose→gate→scan→promote + 人工门 + fail-closed + 安全 + 去重 + 配置默认）。

## 诚实局限

- **技能草稿质量**依赖真模型（agent 蒸馏得好不好、何时该沉淀/克制）——只有确定性的暂存/扫描/去重/提升/fail-closed 逻辑经离线单测；草稿质量、触发时机需真模型 `*IT` 验证。
- 去重是保守的**按名冲突拒绝 + 描述近似告警**；curator 式语义 umbrella-merge、老化清理（usage 统计 → stale 归档）后续分期（对齐原生 `enableSkillCurator` 思路）。
- 暂存/提升是最佳努力的进程内边界（原子移动 + `0600`），非 OS 级隔离；外部 jar/技能仍是「只装可信内容」。
