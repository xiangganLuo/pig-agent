## Context

`executeCommand`（`ShellTools`，`pig-agent-tools`）经 OS 原生 shell 执行任意命令，是 agent 权力最大的工具。现状几乎无约束：硬编码 30s、`readAllBytes()` 无上限、完整父进程 env（含凭据）传子进程、命令内容零过滤。沙箱系统 P1 补齐**受约束执行**层。本能力**安全敏感**：核心张力是「拦住灾难性命令 / 防凭据泄漏 / 防 OOM」与「绝不误伤正常开发命令」之间的平衡——一切默认值向**保守、交互式安全**倾斜。

与既有两道门的分层（显式）：
- `tool-availability`（visibility）：工具是否进入模型 schema。
- `tool-permissions`（may-run）：一个**可见**工具是否获准运行（`executeCommand` 是 `EXEC` 风险，`ask`/`plan` 下需确认/被否）。
- `exec-sandbox`（constrained-run，本能力）：一个**获准**运行的命令，运行时被约束到什么程度。

三者正交、叠加、次序固定：availability → permission veto → **sandbox 约束**。沙箱不替代权限（一条被权限放行的 `rm -rf /` 仍会被沙箱 denylist 拦住；一条正常命令则只受输出/超时/env/cwd 约束）。

## Goals / Non-Goals

**Goals:**
- 为 `executeCommand` 加四项运行时约束：输出上限（防 OOM）、可配置超时、保守灾难性命令 denylist、env 凭据脱敏；外加可选 cwd。
- 用设计模式承载：`SandboxPolicy`（配置驱动的不可变值对象）+ `CommandGuard`（Strategy，denylist/cap/env 逻辑单一可测之处），**不**把这些逻辑散在 `executeCommand` 里。
- 默认保守、交互式安全：无配置即安全默认；denylist 绝不误伤正常开发命令（mvn/git/npm/ls/cat/删项目文件）。
- 向后兼容：缺 `sandbox` 配置块零行为回归；`buildInvocation` 与 `ShellToolsTest` 不变。
- 离线可测：全部约束逻辑经 `CommandGuard` 纯函数/`ProcessBuilder` 配置断言测试，**无需 spawn 真 shell**。

**Non-Goals:**
- **不做 OS 级隔离**（seccomp / Linux namespaces / cgroups / 容器 / Windows Job Object）——那是 P3。本阶段是进程内 best-effort。
- **denylist 不追求对抗有意绕过者**——它是「防手滑/防误触/防提示注入下的显式灾难命令」的保守拦截，不是安全边界。
- 不改 `tool-permissions` / `tool-availability` / `tool-json-contract` 契约；不改 `buildInvocation` 的 OS shell 选择。
- 不做自主/渠道 agent 的差异化收紧（留 P4）；本 spec 只交付交互式安全默认。
- 不做命令白名单（allowlist-only 执行）——会破坏编码 agent 的通用性；本阶段用「保守 denylist + 运行时约束」。

## Decisions

- **D1 — `SandboxPolicy`：配置驱动的不可变值对象**：承载 `maxOutputBytes` / `timeoutSeconds` / `extraDenyPatterns`（用户追加）/ `scrubEnv` / `workingDir`（可空）。`final` 类 + `withXxx()` 拷贝方法（遵循仓库不可变约定）。构造器**容错钳制**非法值：`maxOutputBytes<=0` 或 `timeoutSeconds<=0` 回退默认，`extraDenyPatterns=null` 视为空——坏配置降级为安全默认而非崩溃。`SandboxPolicy.defaults()` = 保守内置默认（200_000 / 30 / 无追加 / scrub 开 / 不限 cwd）。理由：配置解析产物与运行时使用解耦，值对象天然线程安全、易测；与 `FileSystemTools` 从 `ToolContext` 取 `workspaceRoot`、`webAllowedHosts` 的既有装配风格一致。
- **D2 — `CommandGuard`：Strategy，约束逻辑单一事实源**：由 `SandboxPolicy` 构造，暴露三项纯能力——`checkDenied(command) → Optional<reason>`（denylist）、`capOutput(InputStream) → CappedOutput`（有界读输出）、`buildEnv(parentEnv) → Map`（env 脱敏）；外加 `applyTo(ProcessBuilder)`（把脱敏 env + cwd 落到 `ProcessBuilder`，内部复用 `buildEnv`，便于「不 spawn」地断言配置）。`ShellTools` 只做**组合**：denylist 前置检查 → `applyTo(pb)` → `waitFor(policy.timeoutSeconds())` → `capOutput(stream)`。理由：denylist/cap/env 是本能力的核心风险面，必须集中在一处便于审计与穷举测试，而非散落在 `executeCommand`。
- **D3 — 内置 denylist 是安全底线（永远生效），配置只能追加不能删**：灾难性模式内置在 `CommandGuard` 常量里（编译进制品），配置 `exec.denylist` 只**追加**用户正则。理由（安全）：用户不能因把 `denylist` 配成空数组而**意外关掉**灾难命令防护——安全底线不可被配置削弱，只能加严。用户正则以 `CASE_INSENSITIVE` 编译；非法正则**跳过并 warn**（容错，一个坏模式不拖垮其余）。
- **D4 — denylist「保守优先」的匹配工程**：对**规范化命令**（`\s+`→单空格、`CASE_INSENSITIVE`）做正则 `find()`。多信号灾难模式（`rm`/`Remove-Item`/`chmod`）用**零宽前瞻**要求「命令名 ∧ 递归/危险标志 ∧ 根/家目标」**同时**出现，才判定命中——从而 `rm -rf target`、`rm -rf ./node_modules`、`rm -f file`、`chmod -R 755 ./dir`、`Remove-Item -Recurse .\target` 等正常命令**不命中**，而 `rm -rf /`|`~`、`chmod -R 777 /`、`Remove-Item -Recurse -Force C:\` 命中。`shutdown`/`reboot` 锚定到**命令起始/分隔符/sudo 后**（避免 `echo "shutdown"` 误伤）。`format` 要求 `format <盘符>:`（避免 `npm run format`/`git format-patch` 误伤）。命中理由只报**类别**（如 "recursive delete of root/home"），不回显原命令。见 tasks 的正常命令放行清单（作为回归护栏）。
- **D5 — 输出上限用「有界流读取」而非「读全再截断」**：`capOutput(InputStream)` 边读边计数，达上限后**继续 drain 丢弃**剩余（避免管道写满导致子进程阻塞），只保留前 `maxOutputBytes` 字节，超限时追加 `[output truncated: exceeded N bytes]` 标记并置 `truncated=true`（`CappedOutput` 记录）。理由：`readAllBytes()` 的 OOM 风险必须在**读取处**根除——「读全再截断」仍会 OOM。命名 `capOutput` 呼应指令的「cap output」，但实现为流式有界读取（更强）。
- **D6 — env 脱敏用「按名剔除凭据类」denylist 而非「安全变量 allowlist」**：`scrubEnv=true` 时剔除名字（大写后）含 `TOKEN`/`SECRET`/`PASSWORD`/`PASSWD`/`CREDENTIAL`/`APIKEY`/`_KEY` 或以 `KEY` 结尾的变量，其余（PATH/HOME/LANG/JAVA_HOME/…）保留。理由（保守优先）：编码 agent 的子命令合法地依赖大量环境（`JAVA_HOME`/`MAVEN_OPTS`/`PATH`/`TERM`…），allowlist-only 会频繁误伤正常构建；按名剔除凭据既堵住既定威胁（`echo $ANTHROPIC_API_KEY`），又不破坏正常命令。`scrubEnv=false` 原样透传（旧行为）。剔除产出**新** map（不改父 env）。
- **D7 — 超时/cwd 经 `ProcessBuilder`，`buildInvocation` 不变**：超时是 `waitFor(seconds)` 参数（读 `policy.timeoutSeconds()`）；cwd 经 `pb.directory(...)`（`applyTo` 内设置，仅当配置非空）。OS shell 选择 `buildInvocation` 原样保留（PowerShell/bash 分支既有单测继续护航）。`applyTo(ProcessBuilder)` 可在**不 spawn** 下断言 `pb.directory()` 与 `pb.environment()`。
- **D8 — 返回契约复用 `tool-json-contract`**：被 denylist 拦 → 返回 `ToolErrors.message("blocked: <类别>")`（规范 `{"error"}`，凭据安全），**不执行、不抛异常**；模型读到可读拒因后继续。其余失败路径（`IOException` 等）沿用既有 `ToolErrors.message`。分发层 `GuardedAgentTool` 仍是兜底网。
- **D9 — 装配落位与依赖方向**：`SandboxPolicy`/`CommandGuard`/`CappedOutput` 落 `pig-agent-tools` 的新包 `io.pigagent.tool.sandbox`（与 `ShellTools` 同模块，无新增模块依赖）。`ToolContext` 新增可空 `sandboxPolicy` 字段 + 访问器（保留既有构造器，新增一个带 `sandboxPolicy` 的重载，向后兼容）。`ShellToolsProvider` 从 `ToolContext` 取策略：为空→`new ShellTools()`（默认策略），否则→`new ShellTools(policy)`。`PigAgentConfig` 新增 `SandboxConfig`（`exec` 子块）。`AgentBootstrap` 从 `config.getSandbox().getExec()` 构造 `SandboxPolicy` 注入 `ToolContext`（含手动装配 fallback 分支的 `new ShellTools(policy)`）。无环、无新模块。
- **D10 — 两遍、最严者胜分类（防操作符绕过）**：单遍整串匹配存在 `echo ok && rm -rf /` 之类绕过（危险命令藏在操作符后）。`checkDenied` 改为两遍：**Pass 1** 对整条命令扫描**结构型**规则（fork bomb、`while true … & done`、下载管道入 shell、`… | iex`——这些天然跨操作符，MUST 整串匹配）+ 用户追加正则；**Pass 2** 按 shell 操作符（`;`/`&&`/`||`/`|`/`&`）做**引号感知**拆分（`splitSubCommands`：跟踪单/双引号，不切分引号内的操作符），对每个子命令独立匹配**单命令**规则（rm/Remove-Item 根删、mkfs/format/diskpart、shutdown/reboot、dd、chmod-777）。任一命中即拦（block 胜 allow）。**关键**：单命令规则**只**跑在拆分后的子命令上（不跑整串），否则 `.*` 前瞻会跨子命令误配（`echo / && rm -rf .` 会假阳）；引号感知拆分同时避免 `git commit -m "a && b"` 被误切分。
- **D11 — 廉价输入校验 + 审计日志脱敏**：正则前先做 O(1) 校验——空/空白（"empty command"）、超长（`>10_000` 字符，"command too long"）、含 NUL（"null byte in command"）一律拒绝并返回规范错误（不 spawn）。被拦命令写审计日志时先经 `CredentialSanitizer.sanitize` 脱敏 + 截断到 200 字符（`ShellTools.auditSummary`），绝不明文回显密钥（与「拒因只报类别」叠加，双保险不泄敏）。
- **D12（延后）— 中间「warn」层留作后续**：pip/sudo/`chmod 777 <非根>` 一类「可疑但不灾难」命令的「运行但在结果追加 ⚠️ 提示」中间层本期**不做**（保持 `{"error"}`/正常输出二元返回契约不变），留作 follow-up。当前为 block/allow 二元；most-severe-wins 的框架已就位，加 warn 层只需在 `checkDenied` 之外增一个 `classify` 档位。

## Risks / Trade-offs

- **R1 — denylist 误伤正常命令（假阳）**：过激正则会拦正常开发命令，破坏体验。→ D4 保守工程（多信号前瞻 + 锚定 + 盘符/根目标限定）+ tasks 里一张「正常命令放行」回归清单（mvn/git/npm/ls/cat/`rm -rf target`/`chmod -R 755 ./dir`/…）作为红线单测。
- **R2 — denylist 漏网（假阴）/ 可被绕过**：base64/变量拼接/别名可绕过任何正则 denylist。→ 明确定位为 best-effort「防手滑」，非安全边界（proposal/Non-Goals/CLAUDE.md 均声明）；真正的隔离是 P3。权限 veto 仍是主闸（`ask` 下 EXEC 命令需人工确认）。
- **R3 — env 脱敏漏掉某个非常规命名的凭据**：denylist 命名法可能漏掉不含关键词的密钥变量。→ 覆盖 `*_KEY`/`*TOKEN*`/`*SECRET*`/`*PASSWORD*`/`*CREDENTIAL*`/`APIKEY` 等主流命名，覆盖仓库已知的 `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`/`DASHSCOPE_API_KEY`/`GEMINI_API_KEY`/`MIMO_API_KEY`/`BRAVE_API_KEY`；残余风险由「用户可显式扩展/或关 scrub 自担」承接。allowlist-only 更严但破坏功能，权衡后取 denylist（D6）。
- **R4 — 输出上限截断丢信息**：截断可能切掉命令尾部有用输出。→ 默认 200KB 足够容纳绝大多数命令；截断有清晰标记，用户可调大 `max-output-bytes`；有界 drain 避免子进程阻塞。
- **R5 — cwd 配错导致命令失败**：`working-dir` 指向不存在目录 → `ProcessBuilder.start()` 抛 `IOException` → 经 `ToolErrors.message` 返回规范错误（不崩溃）。默认不设 cwd（继承当前），零风险。
- **R6 — 读输出仍在 `waitFor` 之后（与旧代码同序）**：超大且在超时内产出的输出理论上可能在读取前撑满管道——此为既有限制，本阶段不改并发模型；有界 drain 已根除 OOM，超时兜底阻塞。记录为已知限制。

## security.md 自检

- **无硬编码密钥**：不引入任何凭据；env 脱敏正是**移除**凭据外泄面。
- **输入校验**：命令内容经 denylist 校验；配置值（上限/超时）容错钳制；用户正则非法即跳过。
- **不泄敏**：denylist 拒因只报类别不回显命令；错误经 `ToolErrors`→`CredentialSanitizer` 脱敏；env 脱敏阻断 `echo $SECRET`。
- **fail-safe / fail-closed**：非法配置→安全默认；坏正则→跳过（不放松防护）；内置 denylist 不可被配置削弱（D3）。
- **最小暴露**：`buildInvocation` 不变（不新增 shell 面）；无新网络/文件写面。
- **诚实边界**：明确本层非 OS 隔离、denylist 非对抗性边界（Non-Goals + R2 + CLAUDE.md）。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| `SandboxPolicy` 配置值对象（不可变 + 容错钳制 + 默认） | D1；task 2.x；`SandboxPolicy` | 已实现 |
| `CommandGuard` Strategy（denylist/cap/env 单一处 + applyTo） | D2；task 3.x；`CommandGuard` | 已实现 |
| 内置 denylist 为安全底线、配置只追加、坏正则跳过 | D3；task 3.2；`CommandGuard` 常量 + 追加编译 | 已实现 |
| 保守匹配：拦灾难命令、放行正常命令 | D4；task 3.3/3.4；前瞻/锚定正则 + 放行清单单测 | 已实现 |
| 两遍分类：整串结构型 + 操作符拆分（防 `&& rm -rf /` 绕过） | D10；task 3.3a；`splitSubCommands` + 复合命令单测 | 已实现 |
| 引号感知拆分（`"a && b"` 不误切分） | D10；task 3.3a；引号感知单测 | 已实现 |
| 输入校验（空/超长/NUL） | D11；task 3.3b；输入校验单测 | 已实现 |
| 审计日志命令脱敏 + 截断 | D11；task 4.2；`ShellTools.auditSummary` | 已实现 |
| 中间 warn 层（pip/sudo/chmod-777 非根） | D12 | 延后（follow-up） |
| 输出上限：有界流读取 + 截断标记（防 OOM/防阻塞） | D5；task 3.5；`capOutput`/`CappedOutput` | 已实现 |
| env 脱敏：剔除凭据类、保留 PATH（denylist 法） | D6；task 3.6；`buildEnv` | 已实现 |
| 可配置超时 + 可选 cwd 经 ProcessBuilder | D7；task 3.7/4.x；`applyTo` + `waitFor` | 已实现 |
| 返回契约 `{"error":"blocked: …"}`、不执行不抛异常 | D8；task 4.x；`ShellTools.executeCommand` | 已实现 |
| 装配接入（ToolContext/Provider/Config/Bootstrap） | D9；task 2.x/5.x | 已实现 |
| 与权限/可用性分层（may-run vs constrained-run） | 全文 + proposal；CLAUDE.md 段落 | 已实现 |
| 保守默认 + 无配置零回归 + `ShellToolsTest` 不破 | proposal；task 6.x | 已实现 |
| 诚实局限（非 OS 隔离/非对抗边界；P3/P4 留白） | Non-Goals/R2；CLAUDE.md | 已实现 |
