## Why

`exec-sandbox`（命令执行沙箱）的 `CommandGuard` 目前是**二元**分类：一条命令要么命中灾难性 denylist 被 `block`（返回 `{"error":"blocked: …"}`、不执行），要么 `pass`（照常执行）。但现实里有一整类命令**合法但中危**——`pip install`、`apt install`、`sudo`/`su`、对非根路径 `chmod 777`、`PATH=` 重赋值、`npm install -g`：拦掉会破坏正常开发体验，完全放过又让模型看不见风险。这正是 exec-sandbox 设计里 **D12（延后）** 标注的中间 **warn** 层。

关键约束（安全敏感、保守优先）：warn 是**纯追加**——绝不改变既有 block/pass 契约（不阻断合法命令、不把成功结果变成错误），且绝不误伤正常开发命令（`mvn`/`git`/`npm run`/`ls` 必须保持静默）。

## What Changes

- **扩展 `exec-sandbox` 分类器为三级、最严者胜**（`block` > `warn` > `pass`），复用既有两遍结构（整串结构型扫描 + 引号感知子命令拆分）：
  - 新增保守内置 **warn 集**：`pip install`、`apt`/`apt-get install`、`sudo`/`su`、对**非根/家路径**的 `chmod 777`/`chmod -R 777`、`PATH=` 重赋值、`npm install -g`。
  - 命中 warn（且不命中 block）→ 命令**照常执行**，工具结果末尾**追加** `⚠️ Warning: <类别>` 提示（拒因只报类别、凭据安全），模型据此感知风险。
  - **block 严格优先于 warn**：同时命中两者的命令归 block（照旧拦、不执行）；干净命令仍 `pass`、静默。
  - `checkDenied`（block/pass）语义**不变**——命中 warn 的命令对 `checkDenied` 仍为「未拦」，既有单测零回归。
- **配置**：`sandbox.exec.warnlist`（`List<String>`，默认空——即只用内置集；用户可**追加**自定义 warn 正则，非法正则跳过并记日志）。承载于 `SandboxPolicy` 值对象（新增 `extraWarnPatterns` 字段 + `withExtraWarnPatterns` 拷贝方法）。
- **装配接入**：`AgentBootstrap` 从 `config.getSandbox().getExec().getWarnlist()` 构造 `SandboxPolicy` 时透传 warn 列表；`ShellTools.executeCommand` 改用 `guard.classify(command)`（三级判定）驱动 block/warn/pass，warn 时追加提示。

无 **BREAKING**：缺 `warnlist` 配置即只用内置 warn 集；block/pass 返回契约与既有 `ShellToolsTest`/`CommandGuardTest`/`SandboxPolicyTest`/`SandboxConfigTest` 行为不变（除 `chmod 777 ./localfile` 这类**本就该提示**的命令新增一条 warn 提示，`checkDenied` 仍为未拦）。

## Capabilities

### New Capabilities
<!-- 无：不新建能力。 -->

### Modified Capabilities
- `exec-sandbox`: 分类器由二元（block/pass）扩展为**三级、最严者胜**（block > warn > pass）。新增保守内置 warn 集与可配置 `exec.warnlist`；命中 warn 的命令照常执行但在工具结果追加 `⚠️ Warning: <类别>` 提示。warn 为**纯追加**，不改变 block/pass 的既有返回契约与正常命令的静默行为。

## Impact

- **代码**：`pig-agent-tools` 的 `CommandGuard` 新增内置 warn 规则表 + `classify(command) → CommandClassification`（三级），`checkDenied` 改为 `classify` 的薄封装（只反映 block，契约不变）；新增 `CommandClassification` 记录（`Tier{PASS,WARN,BLOCK}` + reason）。`SandboxPolicy` 新增 `extraWarnPatterns`（+ `withExtraWarnPatterns`，保留旧 5 参构造器向后兼容）。`ShellTools.executeCommand` 改用 `classify` 并在 warn 时追加提示（`appendWarnNote` 纯函数，离线可测）。`pig-agent-config` 的 `ExecSandboxConfig` 新增 `warnlist`。`AgentBootstrap` 透传 `warnlist`。
- **协作/不改**：block denylist 底线、输出上限、env 脱敏、超时/cwd、`buildInvocation`、权限 veto、可用性门控、`{"error"}` 返回契约 + 分发守卫维度均不变；warn 叠加于其上。
- **测试**：`CommandGuard` 纯逻辑离线单测——warn 集逐条命中 `WARN` 档（且 `checkDenied` 为空）；block 逐条仍 `BLOCK`；同时命中 warn+block → `BLOCK`（最严者胜）；复合命令 warn 子命令 → `WARN`；干净命令（mvn/git/npm/ls）→ `PASS`（无 warn）；`warnlist` 追加/非法正则跳过。`ShellTools.appendWarnNote` 纯函数单测（warn 追加提示、pass 不变）。`SandboxPolicy`/`ExecSandboxConfig` warn 字段单测。既有单测零回归。
- **文档**：`CLAUDE.md` 命令执行沙箱段增补「三级 block/warn/pass + warn 提示 + `warnlist`」。
- **局限（诚实声明）**：warn 与 denylist 同为**正则的 best-effort**——它是「看得见的风险提示」而非对抗性边界；base64/别名/变量拼接可绕过任何正则匹配（同 R2）。warn 只影响工具结果文本，不改变权限 veto/OS 隔离（后者仍是后续阶段）。
