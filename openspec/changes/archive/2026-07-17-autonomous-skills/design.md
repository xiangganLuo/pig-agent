## Context

pig 的技能栈（`pig-agent-tools`，`io.pigagent.tool.skills`）是**只读**的：`SkillsTool`（`listSkills`/`loadSkill`，均 `readOnly`）经 `SkillRegistry` 组合「内置源 `ClasspathSkillSource` + 工作区源 `WorkspaceSkillSource`」，一个技能 = 一份 `SKILL.md`（composite-skill 已支持 front-matter 元数据 + 支持文件 + 渐进加载 + `SkillSecurity` 加固）。`builtin-skills` 建立了「SPI 发现 + 注册表单一事实源 + `SkillSource` 策略」的范式；composite-skill 的 **D8** 已**立约**：若未来加技能写入，MUST 走 temp-file + `Files.move`（原子）+ `restrictToOwner`（`0600`）。

本 spec 兑现该立约，加一条**写路径**：agent 把「解过的非平凡任务/工作流」蒸馏成 `SKILL.md` 草稿 → 暂存 → **人工门** → 安全扫描 + 去重 → **原子提升** → 既有 `WorkspaceSkillSource` 即时发现。**只读读栈逐字不动**（无第二套读系统、无模型面回归），这是本 spec 的硬约束（OD6-B）。

设计来源：`docs/design/personal-assistant-core-design.md` §7（自主沉淀 skills）；已批准决策 **OD5-A（默认人工门 + fail-closed）** 与 **OD6-B（在 `WorkspaceSkillSource` 上自建写路径）**。

## Goals / Non-Goals

**Goals:**
- `proposeSkill`/`skillManage` @Tool：agent 起草 `SKILL.md`（name/description/keywords/body）→ 写**暂存区**（`workspace/skills/.pending/<name>/`），**不**直接安装。分类 `WRITE`（受权限治理）、availability 门控（关闭即隐藏）。
- **默认人工门**（OD5-A）：`/skill review|approve|reject`；`approve` 才触发扫描 + 去重 + 原子提升；提升后 `WorkspaceSkillSource` 即时发现（无重启）。
- **fail-closed**：渠道/自主 agent（无 confirmer）**永不自动提升**——`proposeSkill` 只能暂存，提升唯一入口是人工门（`/skill approve`）或交互轨道的 `auto-promote`。
- **安全**：提升前 `SkillSecurity`（real-path/遍历/符号链接/保留名）+ `SkillLimits`（尺寸）+ `CredentialSanitizer`（凭据）+ `SkillManifestParser`（结构）。
- **去重**：工作区同名冲突拒绝、仅内置同名允许覆盖（沿用工作区覆盖内置规则）、描述近似告警。
- **复用**：`WorkspaceSkillSource`/composite-skill/`SkillSecurity`/`SkillLimits`/`SkillManifestParser`/`CredentialSanitizer`/`ToolRiskClassifier`/SPI 全部复用，零新增第三方依赖。
- 面向设计模式：值类型（`SkillDraft`/`PendingSkill`/`PromotionResult`/`SkillScanResult`）+ Strategy（`SkillContentScanner`）+ 纯静态守卫（`SkillSecurity`）+ 编排门面（`SkillGate`）+ Repository 风味的暂存区（`SkillStagingArea`）+ Factory/registry SPI（`SkillAuthoringToolProvider`）。

**Non-Goals:**
- 不改 `SkillsTool` 的 `@Tool` 名/签名/未知名与读错兜底语义（无模型面回归）；不改 `SkillProvider` SPI / `ClasspathSkillSource` / `SkillRegistry` 发现与去重契约。
- 不做 curator 式语义 umbrella-merge、usage 统计、老化归档（后续分期，参考原生 `enableSkillCurator`）。
- 不采用 2.0 原生 self-learning loop（`enableSkillManageTool`/`enableSkillPromotionGate`/`enableSkillCurator`）——OD6-B 明确避免与 pig 只读读栈形成双读系统。
- 不引入技能热重载/远程仓库/版本协商（沿用 composite-skill Non-Goals）。
- 不引入 YAML 第三方库（front-matter 渲染/解析复用手写 `FrontMatterManifestParser`）。

## Decisions

### D1 — 暂存区 = `workspace/skills/.pending/<name>/`（`WorkspaceSkillSource` 天然不见 + 点前缀保留双保险）
草稿写到 `workspace/skills/<staging-dir>/<name>/SKILL.md`（`staging-dir` 默认 `.pending`）。**关键**：`WorkspaceSkillSource.discover()` 只列 `skills/` 的**直接子目录**且要求该目录下**直接**有 `SKILL.md`；`.pending/<name>/SKILL.md` 深一层，故暂存草稿**本就不可见**（无需改读栈）。**双保险**：`WorkspaceSkillSource` 加固为**跳过点前缀（`.`）目录**（`.pending`/`.archive` 保留），即便有人在 `.pending/SKILL.md` 直放也不浮现。理由：暂存不能污染 `listSkills`；深一层 + 点前缀保留两层保证暂存与安装的清晰隔离，且**不引入第二套读系统**（OD6-B）——提升 = 把目录移出 `.pending` 到 `skills/<name>/`，`WorkspaceSkillSource` 下一次扫描即见。

### D2 — `SkillDraft` 值类型 + front-matter 渲染
`record SkillDraft(String name, String description, List<String> keywords, String body)`，`null` 归一为空、`keywords` 防御性拷贝。`toSkillMd()` 渲染 `---` fenced front-matter（`name`/`description`/`keywords`/`version`）+ `\n` + body。**往返一致**：`SkillManifestParser.defaults().parse(draft.toSkillMd(), name)` 还原出等价元数据（front-matter 方言与 `FrontMatterManifestParser` 对齐）。理由：值类型天然不可变、易测；渲染集中一处避免手拼字符串漂移；与既有解析器往返闭合。

### D3 — `SkillStagingArea`：暂存 I/O + 原子提升（Repository 风味）
`SkillStagingArea(Path skillsRoot, String stagingDirName, SkillLimits limits)`：
- `stage(SkillDraft)`：校验名字（`SkillSecurity.isValidSkillName`）→ 写 `.pending/<name>/SKILL.md`（temp-file + `ATOMIC_MOVE`，`restrictToOwner`/`0600`）。
- `read(name)` / `list()` / `discard(name)`：读/列/删暂存草稿（`list()` 返回名 + 描述 + 尺寸，描述经头部解析廉价读）。
- `promote(name)`：把 `.pending/<name>/` **原子移动**到 `skills/<name>/`（`Files.move(ATOMIC_MOVE)`，跨存储回退 copy+delete；目标存在则由 `SkillGate` 去重先拦）。
所有路径经 `SkillSecurity` real-path 归一校验（限定在 `skills/` 内）。理由：把暂存/提升的文件系统细节收敛在一个类（Repository 模式），`SkillGate` 只编排策略；原子 + `0600` 兑现 composite-skill D8 立约、镜像 `JsonModelStore.persist`。

### D4 — `SkillContentScanner`（Strategy 纯扫描）→ `SkillScanResult`
`interface`（默认实现 `DefaultSkillContentScanner`）`SkillScanResult scan(String name, String skillMd)`，纯函数、永不抛，返回 `record SkillScanResult(boolean passed, List<String> reasons)`。四道：
1. **名字**：`SkillSecurity.isValidSkillName`（拒 `../`/路径分隔/`.`/`..`/点前缀保留名）。
2. **尺寸**：`skillMd` 字节 ≤ `SkillLimits.maxSkillBytes`（默认 256 KiB）。
3. **凭据**：`CredentialSanitizer.sanitize(skillMd)` 与原文不等 → 含疑似密钥/token → 拒（reason **不回显**命中的密钥值，只说「contains credential-like content」）。
4. **结构**：`SkillManifestParser.defaults().parse(...)` 后 name/description/body 非空（可解析、有内容）。
理由：Strategy + 纯函数离线可测、可替换；复用既有守卫（`SkillSecurity`/`SkillLimits`/`CredentialSanitizer`/`SkillManifestParser`），不重造；扫描 reason 凭据脱敏，与「不外泄凭据」基线一致。

### D5 — `SkillGate`：编排门面（review/approve/reject/auto-promote）+ 去重
`SkillGate(SkillStagingArea staging, SkillContentScanner scanner, SkillRegistry existingSkills, WorkspaceSkillSource workspaceSource)`：
- `List<PendingSkill> listPending()` — 暂存草稿（名/描述/尺寸/扫描是否过）。
- `Optional<String> review(String name)` — 待装 `SKILL.md` 全文（供人工门展示）。
- `PromotionResult promote(String name)` — 读暂存 → `scanner.scan` → **去重** → 通过则 `staging.promote` → 原子安装。
- `boolean discard(String name)` — 删暂存。
- `List<String> autoPromotePending()` — 对所有暂存跑 `promote`，返回成功提升的名（供 `auto-promote` 交互轨道用）。
**去重规则**（保守）：目标名若已是**工作区活跃技能**（`workspaceSource.discover()` 名集，已排除 `.pending`）→ `REJECTED_CONFLICT`（不覆盖用户真技能）；若仅匹配**内置**技能名 → 允许，`overridesBuiltin=true`（沿用工作区覆盖内置规则）；否则干净提升。描述与既有技能近似（归一化 token 重叠阈值）→ `warnings` 非阻断告警。`record PromotionResult(Status status, List<String> reasons, List<String> warnings, boolean overridesBuiltin)`，`Status ∈ {PROMOTED, REJECTED_SCAN, REJECTED_CONFLICT, NOT_FOUND, ERROR}`。理由：门面把「扫描 + 去重 + 提升」三步收一处、单测友好；去重经既有 `SkillRegistry`（工作区 + 内置）不重造名集来源。

### D6 — `SkillAuthoringTool`（@Tool，非只读，永不提升 = fail-closed by construction）
两个 `@Tool` 方法（同一类，一个 provider、一个 availability 门）：
- `proposeSkill(name, description, body, keywords)`：组 `SkillDraft` → `SkillContentScanner` 轻校验（不过则返 `{"error"}` 不暂存）→ `staging.stage` → 成功返回「staged，pending review」。**永不提升**。
- `skillManage(action, name)`：`list`（列暂存）/`discard`（删暂存）。**永不提升**。
两者 **不持有** `SkillGate`/promoter/confirmer——**结构上无任何路径能从工具提升技能**，故渠道/自主 agent（只有工具、够不到 `/skill`）**天然 fail-closed = 只提案不安装**。`implements ToolAvailability`：`availabilityToolNames()={proposeSkill, skillManage}`，`checkAvailability()` 读 `autonomousSkillsEnabled`（`skills.autonomous.enabled`），关闭 → 从 schema 移除（`ToolAvailabilityGate`）。返回契约：成功正常输出、失败 `ToolErrors.message(...)`（canonical `{"error"}`、凭据脱敏）。理由：fail-closed 用**能力缺失**（工具无提升入口）实现，比运行期判「我在哪条轨道」更强、更简单；availability 门保证 `enabled=false` 时模型 schema 里根本没有这两个工具（零行为变更）。

### D7 — 人工门 `/skill review|approve|reject`（operator 面，全信任）
`SkillCommand`（`pig-agent-cli`，与既有 `/skills` 复数只读列举**区分**，本命令单数 `/skill` 管写路径）：
- `/skill` / `/skill review`：列暂存草稿（名 + 描述 + 扫描/去重预览）。
- `/skill review <name>`：展示待装 `SKILL.md` 全文 + `SkillContentScanner` 结果 + 去重判定（供人眼审）。
- `/skill approve <name>`：`SkillGate.promote` → 成功报「promoted，WorkspaceSkillSource 即时可见」/ 失败报扫描/冲突原因。
- `/skill reject <name>`：`SkillGate.discard`。
理由：人工门是 operator 命令（REPL 交互面，全信任），与渠道/自主轨道物理隔离——`/skill` 只在 REPL 存在，渠道/自主进程无此入口，fail-closed 再加一层。

### D8 — `auto-promote`（可配开关，默认关；交互轨道兑现，渠道/自主永不）
`skills.autonomous.auto-promote` 默认 **false** = 纯人工门（OD5-A 推荐）。`true` 时：REPL `runTurn` 末对**交互轨道**调 `SkillGate.autoPromotePending()`（**仍跑扫描 + 去重**；不过则留暂存待人工审）。渠道/自主轨道**无** REPL turn hook → 永不自动提升（fail-closed by construction，D6 已从工具侧封死，此处再确认轨道侧）。理由：兑现 OD5-C「可配开关」而不破坏 OD5-A 默认；auto-promote 真实生效（REPL 兑现）、安全（扫描去重照跑、仅交互 operator 自己的进程、默认关）、fail-closed（渠道/自主无此路径）。诚实局限：交互轨道 auto-promote 会提升**当前所有暂存草稿**（含渠道曾暂存的）——但仅在 operator 显式开启时，且每条仍过扫描去重（文档化）。

### D9 — 配置 `skills.autonomous`（默认关、向后兼容）
`PigAgentConfig` 加 `skills.autonomous`：`enabled`（默认 **false**）、`staging-dir`（默认 `.pending`）、`auto-promote`（默认 **false**）。默认关 → `SkillAuthoringToolProvider` 见 `autonomousSkillsEnabled=false` → 工具被 availability 门移除；无写工具、无 `/skill` 效果差异、`WorkspaceSkillSource` 逐字节等价今天。`@JsonIgnoreProperties(ignoreUnknown=true)` 容错沿用（schema 漂移不炸）。理由：自我改写行为是高风险能力，**保守默认关**（对齐 OpenClaw 强制人工审 + pig 安全基线）；`enabled=false` 是零行为变更的硬保证。

### D10 — 依赖方向与新增依赖：零新增第三方
`authoring` 仅用 JDK（`Files`/`Path`）+ 既有 `io.pigagent.tool.skills.*` / `io.pigagent.tool.contract.CredentialSanitizer` / SLF4J。`pig-agent-config` 无新增依赖。`pig-agent-cli` 复用既有装配。无环、零新增第三方库。

## Risks / Trade-offs

- **R1 — 技能草稿质量依赖真模型**：agent 蒸馏得好不好、何时该沉淀 vs 克制（例行/一次性/纯偏好/瞬时失败/含密不沉淀）是模型行为。→ 只有确定性的暂存/扫描/去重/提升/fail-closed 逻辑单测；草稿质量 + 触发时机需真模型 `*IT`（诚实文档化，本轮不做）。
- **R2 — prompt 注入把恶意技能写进未来行为**：→ **默认人工门**（人眼审待装全文）+ 提升前凭据/结构/尺寸/路径四道扫描 + 默认 `enabled=false`。auto-promote 是显式高风险开关（默认关、扫描照跑）。
- **R3 — Windows 符号链接/`0600` 局限**：`ATOMIC_MOVE` 跨存储/某些 FS 不支持 → 回退 copy+delete；`0600` 非 POSIX 忽略（沿用 `JsonModelStore` 现状）。符号链接逃逸单测用 `assumeTrue` 在不可建链接平台跳过；名字校验 + 尺寸 + 遍历（`../`）加固不依赖符号链接、始终受测。
- **R4 — 暂存目录被误当技能**：→ D1 双保险（深一层 + 点前缀保留），`WorkspaceSkillSourceReservedDirTest` 断言 `.pending`/`.archive` 不出现在 `listSkills`。既有工作区技能若名以 `.` 起头会被跳过——点前缀是隐藏目录约定，作技能名极罕见，视为可接受并文档化。
- **R5 — 去重保守（仅按名 + 描述告警）**：语义近似的重复技能仍可能并存 → 保守按名拒绝防误覆盖，描述近似给非阻断告警提示人；curator 式 umbrella-merge 分期。工作区同名拒绝、内置同名允许覆盖与既有「工作区覆盖内置」语义一致、可预期。
- **R6 — auto-promote 提升非当前轮暂存**：→ 默认关；仅 operator 显式开启；每条仍过扫描去重；文档化。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| `proposeSkill`/`skillManage` @Tool 写暂存、不直接安装 | D1/D3/D6；task 4.x | 已实现 |
| `proposeSkill`/`skillManage` 分类 WRITE（受权限治理） | D6；`ToolRiskClassifier`；task 2.x | 已实现 |
| 默认人工门 `/skill review|approve|reject`（OD5-A） | D7；task 6.x | 已实现 |
| 提升前安全扫描（`SkillSecurity` 路径/名字 + `SkillLimits` 尺寸 + `CredentialSanitizer` 凭据 + `SkillManifestParser` 结构） | D4；task 3.x | 已实现 |
| 去重（工作区同名拒绝 / 内置同名允许覆盖 / 描述近似告警） | D5；task 5.x | 已实现 |
| 原子提升到 `workspace/skills/<name>/`，`WorkspaceSkillSource` 即时发现（无重启，OD6-B） | D1/D3/D5；task 3.x/5.x | 已实现 |
| fail-closed：渠道/自主只提案不提升（工具无提升入口 + `/skill` REPL 独占 + 无 turn hook） | D6/D7/D8；task 4.x/5.x | 已实现 |
| 复用 `WorkspaceSkillSource` + composite-skill（不建第二套读系统，只读 `SkillsTool` 逐字不变） | D1/D10；builtin-skills/composite-skill 不改；task 1.x | 已实现 |
| 兑现 composite-skill D8 立约（原子 + `0600`） | D3；task 3.x | 已实现 |
| 暂存/归档目录不污染 `listSkills`（点前缀保留） | D1；`WorkspaceSkillSource`；task 3.x | 已实现 |
| 配置 `skills.autonomous`（`enabled`/`staging-dir`/`auto-promote`），默认关零行为变更 | D9；`PigAgentConfig`；task 2.x/7.x | 已实现 |
| `auto-promote` 可配（默认关，交互轨道兑现，渠道/自主永不）（OD5-C） | D8；task 5.x/6.x | 已实现 |
| availability 门：`enabled=false` → 工具从 schema 隐藏 | D6/D9；`ToolAvailability`；task 4.x | 已实现 |
| 零新增第三方依赖、无环 | D10；task 2.x-6.x | 已实现 |
| 技能草稿质量 + 触发时机需真模型 itest | R1；proposal 诚实局限 | 延后（真模型 `*IT`，本轮不做） |
| curator（语义去重/老化归档/usage 统计） | R5；proposal 诚实局限 | 延后分期 |
| 文档同步（CLAUDE.md builtin-skills 段落） | proposal Impact；task 7.x | 已实现 |
