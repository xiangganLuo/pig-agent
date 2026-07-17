# autonomous-skills Specification (delta)

## ADDED Requirements

### Requirement: agent 起草技能到暂存区（不直接安装）

系统 SHALL 提供 `proposeSkill(name, description, body, keywords)` 与 `skillManage(action, name)` 两个 `@Tool`（`pig-agent-tools`，**非只读**），让 agent 把「解过的非平凡任务/工作流」蒸馏成一份 `SKILL.md` 草稿并写入**暂存区** `workspace/skills/<staging-dir>/<name>/SKILL.md`（`staging-dir` 默认 `.pending`），MUST NOT 直接安装到 `workspace/skills/<name>/`。

- `proposeSkill` MUST 把 name/description/keywords/body 渲染成带 YAML front-matter 的 `SKILL.md` 文本并写暂存；写前 MUST 跑内容安全扫描（见「提升前安全扫描」），不通过则 MUST 返回 canonical `{"error":"<reason>"}` 且 MUST NOT 暂存。
- `skillManage` MUST 支持 `list`（列暂存草稿）与 `discard`（删一份暂存草稿）；两者 MUST 只作用于暂存区。
- 两个工具 MUST 在 `ToolRiskClassifier` 中登记为 `WRITE`（受权限治理）。
- 两个工具 MUST NOT 具备任何把草稿提升/安装为活跃技能的能力（提升唯一入口是人工门/交互 auto-promote）——即 agent 侧 **fail-closed = 只提案不安装**。
- 暂存草稿 MUST NOT 被 `WorkspaceSkillSource` 当作活跃技能发现（不出现在 `listSkills`/`loadSkill`），直到被提升。

#### Scenario: proposeSkill 写暂存而非安装
- **WHEN** 启用自主技能后，agent 调 `proposeSkill(name, description, body, keywords)` 且内容通过扫描
- **THEN** `workspace/skills/.pending/<name>/SKILL.md` 被写入，`workspace/skills/<name>/` 不存在，`WorkspaceSkillSource.discover()` 不列出该技能

#### Scenario: skillManage 管理暂存草稿
- **WHEN** 已有若干暂存草稿，agent 调 `skillManage("list", null)` 或 `skillManage("discard", name)`
- **THEN** `list` 返回暂存草稿清单；`discard` 删除对应暂存草稿；两者都不安装任何技能

#### Scenario: 工具不能提升技能（fail-closed）
- **WHEN** agent 仅通过 `proposeSkill`/`skillManage` 操作（无人工门介入）
- **THEN** 不存在任何调用序列能让草稿出现在 `workspace/skills/<name>/` 或被 `WorkspaceSkillSource` 发现——提升必须经人工门（`/skill approve`）或交互轨道 auto-promote

### Requirement: 默认人工门审批（approve/reject）

系统 SHALL 默认**人工门**：暂存草稿不自动生效，operator 经 `/skill review|approve|reject <name>` 审批。`/skill review` MUST 展示待装 `SKILL.md` 全文 + 扫描结果 + 去重判定供人眼审；`/skill approve <name>` MUST 触发「安全扫描 + 去重 + 原子提升」，成功后草稿被安装到 `workspace/skills/<name>/`；`/skill reject <name>` MUST 丢弃暂存草稿。人工门 MUST 仅存在于交互 REPL（operator 面），渠道/自主 agent 进程 MUST 无此入口。

#### Scenario: approve 提升暂存草稿并即时可发现
- **WHEN** 有一份通过扫描且无冲突的暂存草稿，operator 执行 `/skill approve <name>`
- **THEN** 草稿被原子提升到 `workspace/skills/<name>/SKILL.md`，随后 `WorkspaceSkillSource.discover()`/`listSkills` 即列出该技能（无需重启）

#### Scenario: reject 丢弃暂存草稿
- **WHEN** 有一份暂存草稿，operator 执行 `/skill reject <name>`
- **THEN** `workspace/skills/.pending/<name>/` 被删除，且该技能未被安装到 `workspace/skills/<name>/`

### Requirement: 提升前内容安全扫描

系统 SHALL 在提升前对暂存草稿跑内容安全扫描（一个纯函数 Strategy，永不抛），任一不通过 MUST 拒绝提升并给出脱敏原因（草稿留在暂存供人工处置）。扫描 MUST 覆盖：

- **名字/路径**：技能名 MUST 是合法目录名（`SkillSecurity`：拒 `../`、路径分隔符、`.`/`..`、点前缀保留名）；暂存/提升路径经 real-path 归一 MUST 落在 `workspace/skills/` 内（遍历/符号链接逃逸拒绝）。
- **尺寸**：`SKILL.md` 字节数 MUST ≤ `SkillLimits` 尺寸上限，超限拒绝。
- **凭据**：正文经 `CredentialSanitizer` 检出疑似密钥/token（`sk-`、`Bearer`、`key=`/`token=` 等）MUST 拒绝；拒绝原因 MUST NOT 回显命中的凭据值。
- **结构**：`SKILL.md` 的 front-matter MUST 可被 `SkillManifestParser` 解析，且 name/description/body 非空。

提升 MUST 原子落盘（temp-file + `Files.move`/`ATOMIC_MOVE`，跨存储回退 copy+delete）并在 POSIX 上 `restrictToOwner`（`0600`；非 POSIX 忽略），兑现 composite-skill 的 D8 立约。

#### Scenario: 遍历/超大草稿被扫描拒绝
- **WHEN** 一份暂存草稿的名字含 `../`（或点前缀保留名），或其 `SKILL.md` 超过尺寸上限，operator 执行 `/skill approve <name>`
- **THEN** 扫描不通过，提升被拒（`REJECTED_SCAN`），草稿未被安装，原因说明缺陷类别而不回显任何凭据值

#### Scenario: 含凭据的草稿被拒
- **WHEN** 一份暂存草稿正文含疑似 API key / token，执行提升
- **THEN** 凭据扫描判定不通过、提升被拒，拒绝原因为「contains credential-like content」类说明（不含密钥明文）

### Requirement: 提升去重（工作区覆盖内置规则）

系统 SHALL 在提升时按名与既有技能去重：目标名若已是**活跃工作区技能** MUST 拒绝（`REJECTED_CONFLICT`，不覆盖用户真技能）；若仅匹配**内置**技能名 MUST 允许并标注「覆盖内置」（沿用 `SkillRegistry` 工作区覆盖内置规则）；描述与既有技能近似 SHALL 给出**非阻断**告警。去重名集来源 MUST 复用既有 `SkillRegistry`（工作区 + 内置源），MUST NOT 另建名集来源。

#### Scenario: 工作区同名拒绝
- **WHEN** 提升的技能名已存在于 `workspace/skills/<name>/`（活跃工作区技能），执行 `/skill approve <name>`
- **THEN** 提升被拒（`REJECTED_CONFLICT`），既有工作区技能不被覆盖，草稿留在暂存

#### Scenario: 仅内置同名允许覆盖
- **WHEN** 提升的技能名只匹配一个内置技能（工作区无同名），执行提升
- **THEN** 提升成功，`overridesBuiltin=true`，`loadSkill(name)` 返回工作区（新提升）版本、shadow 内置版本

### Requirement: fail-closed —— 渠道/自主 agent 只提案不安装

系统 SHALL 保证渠道 / 自主数字员工 agent（无 confirmer）**永不自动提升**技能：`proposeSkill` 只能暂存；提升唯一入口是 operator 人工门（`/skill approve`，仅 REPL）或交互轨道 auto-promote（仅在 operator 显式开启 `auto-promote` 时，且经 REPL turn hook）。渠道/自主轨道 MUST NOT 有任何自动提升路径。

#### Scenario: 渠道 agent 暂存但从不安装
- **WHEN** 渠道/自主 agent 调 `proposeSkill` 起草技能
- **THEN** 草稿仅进入暂存区、不被安装、不被 `WorkspaceSkillSource` 发现；除非 operator 在 REPL 经人工门批准（或显式开启 auto-promote 由交互 REPL 提升），否则永不生效

### Requirement: 自主技能能力可配且默认关闭

系统 SHALL 以 `skills.autonomous` 配置块治理本能力：`enabled`（默认 **false**）、`staging-dir`（默认 `.pending`）、`auto-promote`（默认 **false**）。当 `enabled=false` 时，`proposeSkill`/`skillManage` 工具 MUST 经 `ToolAvailability` 从模型 schema 隐藏（不注册/不可见），系统行为 MUST 与引入本能力前逐字节一致（`WorkspaceSkillSource` 读栈不变、无写路径）。`auto-promote=true` 时交互 REPL MAY 在一轮结束后对暂存草稿自动跑「扫描 + 去重 + 提升」，渠道/自主轨道 MUST NOT 因此自动提升。

#### Scenario: 关闭时无写工具、零行为变更
- **WHEN** `skills.autonomous.enabled=false`（默认）
- **THEN** `proposeSkill`/`skillManage` 不出现在模型工具 schema 中；`/skill` 无可提升对象；`WorkspaceSkillSource`/`SkillsTool` 行为与今天逐字一致

#### Scenario: 开启后写工具可用、暂存生效
- **WHEN** `skills.autonomous.enabled=true`
- **THEN** `proposeSkill`/`skillManage` 进入模型 schema（受 WRITE 权限治理），agent 可起草技能到暂存区，等待人工门/auto-promote
