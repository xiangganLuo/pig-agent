## Why

`executeCommand`（`ShellTools`）是 agent 权力最大的工具：它经 OS 原生 shell（Windows PowerShell / Unix bash）执行任意命令。今天它**几乎无约束**——硬编码 30s 超时、`readAllBytes()` **无上限**读取全部输出、把父进程**完整环境变量**（含 `ANTHROPIC_API_KEY` 等凭据）原样传给子进程、且对命令内容不做任何过滤。这意味着：一条失控命令可 OOM 拖垮进程；`echo $ANTHROPIC_API_KEY` 能把密钥回显给模型；`rm -rf /`、`curl … | sh`、`mkfs`、fork bomb 等灾难性命令会被照单执行。

`tool-permissions`（may-run：是否允许一个工具运行）与 `tool-availability`（visibility：工具是否可见）已经是两道正交门，但它们都不管「一个**获准**运行的命令，运行时被约束到什么程度」。缺的正是这道**受约束执行**层——沙箱系统的 P1（命令执行）。

关键约束（安全敏感）：**保守优先，绝不误伤正常开发命令**（mvn/git/npm/ls/cat/删项目文件）。denylist 只拦真正灾难性的模式；默认值对交互式编码安全。

## What Changes

- **新能力 `exec-sandbox`**：为 `executeCommand` 引入受约束执行，由一个配置驱动的 `SandboxPolicy`（值对象）+ `CommandGuard`（Strategy）承载，覆盖四项约束：
  - **输出上限**（`exec.max-output-bytes`，默认 200_000）：有界读取子进程输出，超限截断并加清晰标记，杜绝失控命令 OOM。
  - **可配置超时**（`exec.timeout-seconds`，默认 30）：把现在硬编码的 30s 变为配置项（`<=0` 回退默认，始终保留超时）。
  - **保守内置 denylist**（`exec.denylist`）：内置一组**真正灾难性**模式（`rm -rf /`|`~`、`Remove-Item` 递归删根/家、`mkfs`/`format <盘>:`/`diskpart`、`curl|sh`/`wget|bash`/PowerShell `iex` 下载执行、fork bomb、`shutdown`/`reboot`、`dd of=/dev/…`、`chmod -R 777 /`）为**安全底线**（永远生效），用户可经配置**追加**自定义正则。命中即拒（不执行），返回规范 `{"error":"blocked: <类别>"}`。
  - **env 脱敏**（`exec.scrub-env`，默认开）：不把父进程凭据类环境变量传给子进程——按名剔除 `*_KEY`/`*TOKEN*`/`*SECRET*`/`*PASSWORD*` 等，保留 PATH/HOME/LANG 等正常变量，使子命令无法 `echo $ANTHROPIC_API_KEY`。
  - **工作目录**（`exec.working-dir`，可选；默认继承当前）：设置时子进程在此目录运行。
- **`PigAgentConfig` 新增 `sandbox` 块**（含 `exec` 子块）：全部可选、默认安全、向后兼容——缺 `sandbox` 块即等价于「保守内置 denylist + 200KB 上限 + 30s + env 脱敏开 + 不限 cwd」。
- **装配接入**：经 `ToolContext` 把 `SandboxPolicy` 注入 `ShellTools`（新增字段，`AgentBootstrap` 从配置构造），与 `FileSystemTools` 取 `workspaceRoot` 的方式一致。`buildInvocation`（OS shell 选择）保持不变。

无 **BREAKING**：无配置时默认策略即安全保守值；`buildInvocation` 与既有 `ShellToolsTest` 行为不变；正常开发命令一律放行。

## Capabilities

### New Capabilities
- `exec-sandbox`: 命令执行沙箱——`executeCommand` 在一道**受约束执行**层下运行（输出上限、可配置超时、保守灾难性命令 denylist、env 凭据脱敏、可选 cwd），由 `SandboxPolicy`（配置值对象）+ `CommandGuard`（Strategy）承载。与 `tool-permissions`（may-run）、`tool-availability`（visibility）正交叠加：权限决定「能否运行」，沙箱决定「运行时被约束到什么程度」。保守默认，绝不误伤正常开发命令；无配置零行为回归。

### Modified Capabilities
<!-- 无：不改 tool-permissions / tool-availability / tool-json-contract 的契约。executeCommand 仍是 EXEC 风险、仍受权限 veto；本能力只在其获准运行后叠加运行时约束，并复用 {"error"} 返回契约。 -->

## Impact

- **代码**：`pig-agent-tools` 新增 `io.pigagent.tool.sandbox` 包（`SandboxPolicy` 值对象、`CommandGuard` Strategy、`CappedOutput` 记录）；`ShellTools` 组合二者（denylist 前置检查、有界读输出、脱敏 env + cwd 经 ProcessBuilder、可配置超时）；`ToolContext` 新增 `sandboxPolicy` 字段 + `ShellToolsProvider` 读取；`pig-agent-config` 的 `PigAgentConfig` 新增 `SandboxConfig`/`ExecSandboxConfig`；`AgentBootstrap` 从配置构造 `SandboxPolicy` 注入 `ToolContext`。
- **协作/不改**：`buildInvocation`、权限 veto、可用性门控、返回契约 + 分发守卫维度不变，叠加于受约束执行之上。
- **测试**：`CommandGuard` 纯逻辑离线单测——denylist 拦每条灾难性模式 + 放行一组正常开发命令；输出上限截断 + 标记（`ByteArrayInputStream`，不 spawn）；env 脱敏剔除密钥、保留 PATH；cwd/超时经 `ProcessBuilder` 配置断言（不 spawn）。`SandboxPolicy` 默认值/容错单测。既有 `ShellToolsTest` 不回归。
- **文档**：`CLAUDE.md` 增补一段「命令执行沙箱」（新层 + 配置面），说明与权限的分层关系与保守默认。
- **局限（诚实声明）**：本阶段是**进程内 best-effort 边界**，非 OS 级隔离（无 seccomp/命名空间/容器——那是 P3）；denylist 是「防手滑/防误触」的保守拦截，**不是**对抗有意绕过者的安全边界。自主/渠道 agent 收紧策略留待 P4。
