## Context

`exec-sandbox` P1 交付了 `SandboxPolicy`（配置值对象）+ `CommandGuard`（Strategy），其 `checkDenied` 是**二元**分类：block（灾难性 denylist，返回 `{"error":"blocked: …"}` 不执行）或 pass（照常执行）。当时的设计文档已在 **D12** 明确把中间 **warn** 层标为「延后（follow-up）」，并指出「most-severe-wins 的框架已就位，加 warn 层只需在 `checkDenied` 之外增一个 `classify` 档位」。本变更正是 D12 的落地。

本能力**安全敏感**：核心张力仍是「让模型看见中危风险」与「绝不误伤正常开发命令、绝不改变既有 block/pass 契约」之间的平衡——一切默认值向**保守、纯追加**倾斜。

分层不变（`tool-availability` visibility → `tool-permissions` may-run veto → **`exec-sandbox` constrained-run**）；warn 完全落在 constrained-run 层内部，是其分类结果的第三档。

## Goals / Non-Goals

**Goals:**
- 把 `CommandGuard` 分类器从二元扩为**三级、最严者胜**（block > warn > pass），复用既有两遍结构（整串结构型扫描 + 引号感知子命令拆分）。
- 引入保守内置 warn 集（pip/apt install、sudo/su、非根 chmod 777、`PATH=` 重赋值、npm -g），可经 `exec.warnlist` 追加。
- warn 命中 → 命令照常执行 + 工具结果追加 `⚠️ Warning: <类别>`；**不改 block/pass 契约**。
- 保守默认：正常开发命令保持静默；block 行为与所有既有单测零回归。
- 离线可测：三级分类与 warn 提示格式全部经纯函数断言，无需 spawn 真 shell。

**Non-Goals:**
- **不做 OS 级隔离 / 不追求对抗性边界**——warn 与 denylist 同为 best-effort 正则匹配（base64/别名可绕过）；warn 只是「看得见的风险提示」。
- **不改 block denylist 底线 / 输出上限 / env 脱敏 / 超时 / cwd / `buildInvocation`**。
- **不改权限 veto、可用性门控、`{"error"}` 返回契约**——warn 纯追加于成功/执行后的结果文本。
- 不做 warn 的交互式确认（那是 `tool-permissions` 的职责）；warn 不阻断、不询问，仅提示。
- 不做自主/渠道 agent 的差异化 warn 策略（沿用统一策略）。

## Decisions

- **D12-1 — 三级分类 `classify(command) → CommandClassification`，最严者胜**：新增 `CommandClassification`（记录：`Tier{PASS,WARN,BLOCK}` + `reason`；便捷工厂 `PASS`/`warn(r)`/`block(r)` + `isBlocked/isWarn/isPass`）。`classify` 顺序：① 输入校验（空/超长/NUL → `BLOCK`，复用既有逻辑）；② **block 扫描**（整串结构型 + 用户 denylist + 子命令单命令规则，全部沿用 P1）命中 → `BLOCK`；③ **warn 扫描**（子命令级 warn 规则 + 用户 warnlist）命中 → `WARN`；④ 否则 `PASS`。**最严者胜由顺序天然实现**：block 先判并短路，因此同时命中 block+warn 的命令归 block。子命令只拆分一次（`splitSubCommands`）供 block/warn 复用。理由：single-flight 的纯判定，最省心地表达「block > warn > pass」，且不动既有 block 正则与两遍结构。
- **D12-2 — `checkDenied` 降为 `classify` 的薄封装（契约绝对不变）**：`checkDenied(cmd)` = `classify(cmd)` 后 `isBlocked ? Optional.of(reason) : Optional.empty()`。因此 `checkDenied` **只反映 block**——命中 warn 的命令对它仍返回「未拦」，`ShellToolsTest`/既有 `CommandGuardTest`（全部基于 `checkDenied`）逐字节零回归。理由：明确「block/pass 二元契约」由 `checkDenied` 承载且不动；warn 是 `classify` 才可见的新维度，二者解耦。
- **D12-3 — warn 判定复用两遍结构，落在子命令级（Pass 2 类比）**：内置 warn 规则**逐子命令**匹配（`splitSubCommands` 拆分后对每个 trimmed 子命令 `find`），因此「复合命令中任一 warn 子命令命中 → 整条 warn」自然成立；无操作符时子命令即整串，`^` 锚定仍生效。当前 warn 集无跨操作符模式，故不设 warn 结构型 pass（保留扩展位）。理由：与 block 的 Pass 2 对称，同一 `splitSubCommands` 复用，且避免在引号内参数上误配（见 D12-4 锚定）。
- **D12-4 — 保守内置 warn 集与「防误伤」正则工程**：内置 warn 规则（`CASE_INSENSITIVE`，逐子命令）——
  - `pip install`：`^(?:sudo\s+)?(?:python[0-9.]*\s+-m\s+)?pip[0-9.]*\s+install\b`（含 `pip3`/`python -m pip`/`sudo pip`），锚定子命令首以免命中引号内 `-m "… pip install …"`。
  - `apt`/`apt-get install`：`^(?:sudo\s+)?apt(?:-get)?\s+install\b`。
  - `sudo`/`su`：`^(?:sudo|su)\b`（锚定子命令首；`^su\b` 的词界不误伤 `sudo`/`sublime`/`sum`）。
  - 非根 `chmod 777`：`^(?=.*\bchmod\b)(?=.*\b0*777\b).*$`（多信号前瞻，同 block chmod 风格）。**根/家 777 由 block 先判**（D12-1 最严者胜），故此 warn 只在非根路径生效。
  - `PATH=` 重赋值：`^(?:export\s+|set\s+)?PATH\s*=|\$env:PATH\s*=`（锚定命令首/`export`/`set`/PowerShell `$env:`，避免 `--path=`/`CLASSPATH=`/读取 `$PATH` 误伤）。
  - `npm install -g`：`^(?:sudo\s+)?npm\s+(?:install|i)\b[^\n]*(?:\s-g\b|--global\b)`（`npm run …` 因非 install/i 不命中）。
  拒因为**类别**短语（如 `package install (pip)`/`privilege escalation (sudo)`/`permissive chmod 777`/`PATH reassignment`/`global npm install`），不回显原命令。规则顺序把更**具体**的（apt/pip/npm）排在通用 `sudo/su` 前，使 `sudo apt install` 报更信息量的类别。理由：warn 虽不阻断，仍要「保守优先」——尽量只在真正中危处提示，不在正常命令上刷屏。
- **D12-5 — warn 集是内置默认、配置只追加**：内置 warn 规则编译进 `CommandGuard` 常量（缺省即生效）；`SandboxPolicy.extraWarnPatterns()`（来自 `exec.warnlist`）以 `CASE_INSENSITIVE` 编译**追加**，非法正则**跳过 + warn 日志**（一个坏模式不拖垮其余）。与 denylist 的 D3 完全对称。理由：`warnlist: []` 不应静音内置 warn 集（保守）；用户只能加严不能削弱。
- **D12-6 — `SandboxPolicy` 承载 `extraWarnPatterns`，保持向后兼容**：新增 `List<String> extraWarnPatterns` 字段（`null` → 空、防御性拷贝为不可变）+ `withExtraWarnPatterns(...)`；所有既有 `withXxx` 透传该字段。新增 6 参规范构造器 `(max, timeout, deny, warn, scrub, dir)`；**保留** 5 参构造器（`warn = List.of()` delegate），故 `SandboxPolicyTest` 与 `AgentBootstrap` 旧调用不破。`defaults()` = deny 空 + warn 空。理由：值对象扩字段遵循仓库不可变约定；保留旧构造器把回归面降到零。
- **D12-7 — warn 提示由 `ShellTools` 纯追加，二元契约不变**：`executeCommand` 改用 `guard.classify(command)`：`isBlocked` → 沿用 `ToolErrors.message("blocked: <类别>")`（warn 日志、`auditSummary` 脱敏、不执行）；否则照常 spawn/waitFor/capOutput 得到 `base`（成功/退出码/超时语义**全不变**）；若 `isWarn`，返回 `appendWarnNote(base, reason)` = `base + "\n\n⚠️ Warning: <类别>"`（`base` 为空则仅提示）。`appendWarnNote` 与 `WARN_PREFIX` 抽为**包级静态纯函数**，离线单测（无需 spawn）。warn 命中时 `log.info` 一条（命令经 `auditSummary` 脱敏）。理由：把「是否 warn」的判定收敛在 `classify`，`ShellTools` 只做「执行 + 追加」，提示格式单一可测；成功结果仍是纯输出 + 提示尾注，绝不变为 `{"error"}`。
- **D12-8 — 配置与装配落位**：`ExecSandboxConfig` 新增 `@JsonProperty("warnlist") List<String>`（默认空、`null` setter 容错为空，与 `denylist` 对称）。`AgentBootstrap` 用 6 参构造器把 `execCfg.getWarnlist()` 透传进 `SandboxPolicy`。`ToolContext`/`ShellToolsProvider` 无需改（策略里已带 warn）。无环、无新模块。

## Risks / Trade-offs

- **R1 — warn 误伤正常命令（假阳刷屏）**：过激 warn 正则会在正常命令上追加噪音提示。→ D12-4 保守工程（命令首锚定 + 多信号前瞻 + `PATH=` 负向排除），并以「正常命令保持静默」回归清单（mvn/git/npm run/ls/cat/`chmod -R 755`）作为单测红线。残余边界（如 `git commit -m "... pip install ..."` 引号内命中）为**非阻断的**提示、罕见且无害，接受。
- **R2 — warn 漏网 / 可被绕过**：base64/别名/变量拼接可绕过任何正则（同 denylist）。→ 明确定位为 best-effort「看得见的风险提示」，非安全边界；真正的隔离是后续阶段。warn 不承担阻断职责（那是权限 veto + block 底线）。
- **R3 — warn 改变返回契约的风险**：若 warn 误改成功/错误语义会破坏 `tool-json-contract`。→ D12-2/D12-7 双重保证：`checkDenied` 只反映 block；`appendWarnNote` 仅在**已执行**的 `base` 上追加纯文本尾注，从不把成功变 `{"error"}`、不阻断、不改退出码/超时。既有契约单测回归护航。
- **R4 — `chmod 777 ./localfile` 从「静默 pass」变为「warn」**：这是**有意**的行为收紧（该命令本就中危），且 `checkDenied` 仍为未拦（block/pass 契约不变），既有 `allowsNormalDevCommands`（基于 `checkDenied`）不破。记录为预期变更。
- **R5 — `SandboxPolicy` 构造器新增参数破坏调用方**：→ D12-6 保留 5 参旧构造器（delegate warn 空），旧调用零改动即编译。

## security.md 自检

- **无硬编码密钥**：不引入任何凭据；warn 拒因只报类别，命令经 `auditSummary`/`CredentialSanitizer` 脱敏，绝不回显密钥。
- **输入校验**：warn 规则跑在已规范化命令上；用户 `warnlist` 正则非法即跳过（不崩溃、不放松）。
- **不泄敏**：warn 提示 `⚠️ Warning: <类别>` 不含原命令内容；不改 env 脱敏与既有脱敏路径。
- **fail-safe / fail-closed**：坏 warn 正则→跳过（不影响其余 + 不静音内置集）；内置 warn 集不可被配置削弱（D12-5）；分类顺序保证 block 优先（同时命中→拦，绝不因 warn 降级）。
- **契约不破**：warn 纯追加，block/pass 与 `{"error"}` 返回契约不变（R3）。
- **诚实边界**：明确 warn 非对抗性边界、可被绕过（R2；Non-Goals；CLAUDE.md）。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 三级分类 `classify`（block>warn>pass，最严者胜） | D12-1；task 3.1/3.2；`CommandGuard.classify` + `CommandClassification` | 已实现 |
| `checkDenied` 降薄封装、只反映 block（契约不变） | D12-2；task 3.2；`CommandGuard.checkDenied` | 已实现 |
| warn 复用两遍结构、落子命令级（复合命令生效） | D12-3；task 3.3；`splitSubCommands` 复用 + 复合命令单测 | 已实现 |
| 保守内置 warn 集 + 防误伤正则（锚定/前瞻/负排除） | D12-4；task 3.2/3.4；`WARN` 规则表 + 放行清单单测 | 已实现 |
| warn 集内置默认、`warnlist` 只追加、坏正则跳过 | D12-5；task 1.x/3.2；`extraWarnRules` 编译 + 单测 | 已实现 |
| `SandboxPolicy` 承载 `extraWarnPatterns`（向后兼容旧构造器） | D12-6；task 2.x；`SandboxPolicy` + `SandboxPolicyTest` | 已实现 |
| warn 提示纯追加、二元契约不变（`appendWarnNote`） | D12-7；task 4.x；`ShellTools` + `ShellToolsTest` | 已实现 |
| 配置面 `exec.warnlist` + 装配透传 | D12-8；task 1.x/5.x；`ExecSandboxConfig`/`AgentBootstrap` | 已实现 |
| 正常命令保持静默（无 warn） | R1；task 3.4；静默回归清单单测 | 已实现 |
| block 优先于 warn（同时命中→拦） | D12-1；task 3.3；most-severe-wins 单测 | 已实现 |
| 诚实局限（warn 非对抗边界、可绕过） | Non-Goals/R2；CLAUDE.md | 已实现 |
