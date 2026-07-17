# Tasks — autonomous-skills

> 内环 TDD：每个可测单元先写测试（RED）→ 实现（GREEN）→ `mvn test` → 勾选。分组后 `mvn -q -pl pig-agent-cli -am compile`。

## 1. 前置

- [x] 1.1 从 `main` 拉分支 `feat/20260717-autonomous-skills`（已建）。
- [x] 1.2 勘察复用点：`WorkspaceSkillSource`/`SkillRegistry`/composite-skill（`SkillSecurity`/`SkillLimits`/`SkillManifestParser`）/`CredentialSanitizer`/`ToolRiskClassifier`/`ToolContext`/SPI/`AgentBootstrap`/`ReplCommands`。

## 2. 配置 + 分级 + 上下文（`pig-agent-config` / `pig-agent-tools`）

- [x] 2.1 `PigAgentConfig` 加 `skills.autonomous`（`SkillsConfig` → `AutonomousSkillsConfig`：`enabled` 默认 false、`staging-dir` 默认 `.pending`、`auto-promote` 默认 false）。`PigAgentConfigTest` 断言默认值 + 解析。
- [x] 2.2 `ToolRiskClassifier`：`proposeSkill`/`skillManage` → `WRITE`。`ToolRiskClassifierTest` 扩。
- [x] 2.3 `ToolContext` 加 `skillStaging`（`SkillStagingArea`）+ `autonomousSkillsEnabled`（`BooleanSupplier`）字段 + 访问器（null/默认安全）。

## 3. 暂存区 + 扫描 + 安全（`pig-agent-tools`，`io.pigagent.tool.skills.authoring`）

- [x] 3.1 `SkillDraft`（record + `toSkillMd()` front-matter 渲染）。`SkillDraftTest`（RED→GREEN）：渲染往返经 `SkillManifestParser` 还原等价元数据、null 归一。
- [x] 3.2 `SkillSecurity.isValidSkillName(String)`（纯静态）。`WorkspaceSkillSource` 跳点前缀保留目录。`SkillSecurityAuthoringTest` + `WorkspaceSkillSourceReservedDirTest`（RED→GREEN）：合法/非法名、`.pending`/`.archive` 不浮现。
- [x] 3.3 `SkillStagingArea`（`stage`/`read`/`list`/`discard`/`promote`，temp+`ATOMIC_MOVE`+`0600`，路径 real-path 校验）。`SkillStagingAreaTest`（RED→GREEN）：暂存不可见于 `WorkspaceSkillSource`、原子提升后可见、丢弃、非法名拒绝、跨存储回退。
- [x] 3.4 `SkillScanResult`（record）+ `SkillContentScanner`（Strategy 接口 + `DefaultSkillContentScanner`：名字/尺寸/凭据/结构四道）。`SkillContentScannerTest`（RED→GREEN）：干净过、含凭据拒（原因不回显密钥）、超大拒、非法名拒、结构缺失拒。

## 4. agent 写工具（`pig-agent-tools`）

- [x] 4.1 `SkillAuthoringTool`（`@Tool proposeSkill`/`skillManage`，`implements ToolAvailability`，永不提升）。`SkillAuthoringToolProvider` + `META-INF/services` 一行。`SkillAuthoringToolTest`（RED→GREEN）：propose 通过→暂存、含凭据→`{"error"}` 不暂存、`skillManage list`/`discard`、availability（enabled=false→隐藏）、无提升路径（fail-closed）。

## 5. 门面 + 去重 + auto-promote（`pig-agent-tools`）

- [x] 5.1 `PendingSkill`/`PromotionResult`（record，`Status` 枚举）。
- [x] 5.2 `SkillGate`（`listPending`/`review`/`promote`/`discard`/`autoPromotePending`，去重经 `SkillRegistry`+`WorkspaceSkillSource`）。`SkillGateTest`（RED→GREEN）：approve→扫描+去重+提升→`WorkspaceSkillSource` 列出、reject→丢弃、工作区同名冲突拒绝、仅内置同名允许覆盖（`overridesBuiltin`）、描述近似告警、扫描失败→`REJECTED_SCAN`、`autoPromotePending` 提升通过者。

## 6. CLI 接线 + 人工门（`pig-agent-cli`）

- [x] 6.1 `AgentBootstrap`：构建 `SkillStagingArea`/`DefaultSkillContentScanner`/`SkillGate`，注入 `ToolContext`（写工具 availability）+ `Services`（`SkillGate`）。
- [x] 6.2 `SkillCommand`（`/skill review|approve|reject`）+ 注册 `ReplCommands`、加入 `ReplContext`、线程过 `AgentRepl`/`PigAgentCli`（更新既有 `new AgentRepl(...)` 测试调用点）。
- [x] 6.3 REPL `runTurn` 末：`auto-promote=true` 时调 `SkillGate.autoPromotePending()`（交互轨道；渠道/自主无此 hook = fail-closed）。CLI 侧 availability/命令测试。

## 7. 收尾

- [x] 7.1 `mvn -q test`（单线程）全绿，读 surefire XML 计数确认；列新增测试。
- [x] 7.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 7.3 更新 `CLAUDE.md` builtin-skills 段落（自主沉淀 skills：propose→gate→scan→promote + 人工门 + fail-closed + 安全 + 去重 + 配置默认关）。
- [x] 7.4 提交 `feat: 自主沉淀 skills（proposeSkill/skillManage + 人工门 + 安全扫描 + 去重 + 原子提升）`。
- [ ] 7.5 归档：同步主 spec → `openspec/specs/autonomous-skills/`，change 移 `openspec/changes/archive/2026-07-17-autonomous-skills/`，提交归档。
