## Why

生产就绪度审查（`docs/review/production-readiness-2026-07-14.md`）判定 P0 安全阻断项：agent 的文件工具与网络工具**没有出口边界**——`readFile` 可读到 `~/.pig-agent/workspace/models.json` 的明文 API key 进入模型上下文，`fetchUrl` 可访问 `169.254.169.254` 云 metadata / 内网 / 回环，二者咬合成一条 prompt-injection 驱动的静默外泄链；同时凭据在录入时明文回显终端、在落盘时无文件权限收敛。安全**骨架**（权限 veto、D-SEC 门、脱敏）已验证有效，缺的正是"工具出口边界"与"凭据存取卫生"这一层。这是从「beta 自用」跨越到「可对外分发」的最低门槛。

## What Changes

- **文件工具敏感文件黑名单**：`FileSystemTools` 的 `readFile`/`writeFile`/`listDirectory` 在访问前归一化路径，**拒绝**触碰 `~/.pig-agent/workspace/` 下的凭据文件（`models.json`、`mcp.json`）。策略为**黑名单**（护凭据）而非工作目录沙箱——本项目是终端编码助手，工作目录为任意用户项目（如 `D:\work\pig-agent`），死锁到 workspace 会让文件工具失能。被拒返回规范 `{"error"}`（沿用 `tool-json-contract`），不抛异常。
- **fetchUrl SSRF 出口防护**：`SmartWebFetchTool.fetchUrl` 在请求前用 `InetAddress.getAllByName` 解析目标 host 的**全部** IP，任一命中回环 / anyLocal / 链路本地(含 `169.254.169.254`) / 私网(10/172.16/192.168) / ULA(`fc00::/7`) / multicast 即**拒绝**；保持 `Redirect.NEVER`。判定基于**解析后的 IP** 而非字面 host，从根上防十进制 IP、`[::1]`、DNS 指向内网等绕过。叠加可选 `allowed-hosts` 白名单：非空时仅允许白名单内 host（供严格部署收紧）。
- **凭据录入掩码**（安全遗留 M-1）：三处 API key / 敏感值录入改为掩码读取——REPL 内 `ReplCommands`（`/model add`、`/model edit`）与 `McpCommand`（`/mcp add`、`/mcp edit` 的 `*token*`/`*key*`/`authorization`/`*secret*` 键）用 JLine `reader.readLine(prompt, maskChar)`；`OnboardingWizard`（非 JLine）用 `Console.readPassword()`，无 console 时回退可见读取并告警。
- **凭据文件 0600 落盘**（安全遗留 M-2）：`JsonModelStore` 与 `JsonMcpStore` 写入后收敛文件权限为 `rw-------`（`Files.setPosixFilePermissions`），非 POSIX 平台 try/catch 忽略（既定接受姿态）。

## Capabilities

### New Capabilities
- `tool-sandbox`: agent 工具的出口边界——文件工具对凭据文件的敏感文件黑名单，与 `fetchUrl` 基于解析 IP 的 SSRF 防护（拒私网/回环/元数据 + 可选 host 白名单）。作为 `tool-permissions` veto、`tool-availability` 门控之外的一道正交出口过滤。
- `credential-hardening`: 凭据的存取卫生——录入时终端掩码（不回显、不留 scrollback）与落盘时文件权限收敛（`0600`）。

### Modified Capabilities
<!-- 无：本变更为正交新增层，不改 tool-permissions / tool-availability / mcp-management 的既有 requirement -->

## Impact

- **代码**：`pig-agent-tools`（`FileSystemTools` 黑名单、`SmartWebFetchTool` SSRF 守卫，可能新增 `SsrfGuard`/`SensitivePaths` 小工具类）、`pig-agent-cli`（`ReplCommands`、`repl/command/McpCommand` 掩码读取）、`pig-agent-onboarding`（`OnboardingWizard` 掩码）、`pig-agent-model`（`JsonModelStore` 0600）、`pig-agent-mcp`（`JsonMcpStore` 0600）。
- **配置**：`SmartWebFetchTool` 的 `allowed-hosts` 与 workspace 根需从 `ToolContext`/config 注入（凭据文件定位需 `WorkspaceManager` 根路径）；纯增量，缺省即"仅黑名单 + 全私网拒绝"，向后兼容。
- **依赖/前提**：JLine3 `LineReader.readLine(String, Character)` 掩码语义、`OnboardingWizard` 运行期 `System.console()` 可用性——列为 step-0 spike 快速验证（唯一承重未知点）。
- **安全**：直接消解审查 P0 #1–4 与安全遗留 M-1/M-2；不引入新放行面。凭据仍明文存储（依赖 `0600` + 目录权限），文档需明确"勿在共享主机使用"。
- **不回归**：既有权限系统、工具 JSON 契约、`CredentialSanitizer`/`redact` 约定保持不变；被拒工具走规范错误结果而非中断回合。
- **测试**：离线单测覆盖每一分支（黑名单命中/放行、SSRF 各私网段拒绝 + 十进制IP/`[::1]`/DNS 绕过失效 + 白名单放行、掩码不回显、0600 收敛与非 POSIX 忽略）。
