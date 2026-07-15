## Context

需求源：`docs/review/production-readiness-2026-07-14.md` 的 P0 阻断项 #1–4（+ 安全遗留 M-1/M-2）。经澄清用户拍板三项取向：文件边界=**敏感文件黑名单**（护凭据、不沙箱化工作目录）、SSRF=**解析 IP 判私网 + `allowed-hosts` 白名单**、范围=本变更（A）。

当前实现事实（已读源确认）：
- `FileSystemTools`（`pig-agent-tools/.../filesystem/FileSystemTools.java`）三方法 `readFile`/`writeFile`/`listDirectory` 直接 `Files.*(Path.of(path))`，**无任何边界**；无构造依赖，由 `FileSystemToolsProvider.create(ctx)` 造。
- `SmartWebFetchTool`（`.../webfetch/SmartWebFetchTool.java`）`fetchUrl` 直接 `URI.create(url)` 发请求，`HttpClient` 未设 `followRedirects` → 默认 `Redirect.NEVER`；无构造依赖，由 `SmartWebFetchToolProvider.create(ctx)` 造。
- `ToolContext`（`.../spi/ToolContext.java`）是不可变依赖holder，现有 `taskManager` + `skillsDir` 两字段，注释明言"按需扩 accessor"。
- `WorkspaceManager` 提供 `getRootPath()` / `getModelsFile()`(=root/models.json) / `getMcpFile()`(=root/mcp.json)——凭据文件路径精确可得。
- `JsonModelStore.persist():59-66` 与 `JsonMcpStore.persist():58-68` 写法一致（`MAPPER.writeValue(file, data)`）；后者已带 M-2 TODO。
- `OnboardingWizard` 用 `new BufferedReader(new InputStreamReader(System.in))`（**非 JLine**）读 key（`:90`）；REPL 侧 `ReplCommands`/`McpCommand` 持有 JLine `LineReader`（`reader.readLine(...)`）。

约束：AgentScope 1.0.12；不可变领域类型；沿用 `tool-json-contract`（失败返回 `ToolErrors.message` 规范 `{"error"}`，不抛异常）与 `CredentialSanitizer`/`redact`（不回显凭据）；`application.yaml` 向后兼容；离线单测为主。

## Goals / Non-Goals

**Goals:**
- 文件工具**无法读/写** `~/.pig-agent/workspace/{models.json,mcp.json}`（含 `.bak`），且 `../`、符号链接等间接路径同样被拦。
- `fetchUrl` 基于**解析后 IP** 拒绝回环/anyLocal/链路本地(含 `169.254.169.254`)/私网(10、172.16、192.168)/ULA(`fc00::/7`)/multicast；十进制 IP、`[::1]`、DNS 指向内网等绕过失效；保持 `Redirect.NEVER`。
- 可选 `allowed-hosts` 白名单：非空时仅白名单 host 放行（严格部署收紧入口）。
- 三处凭据录入终端掩码（不回显、不留 scrollback）。
- `models.json`/`mcp.json` 落盘后收敛为 `0600`（POSIX），非 POSIX 忽略。
- 全部走离线单测；被拒路径返回规范错误、不中断回合、不泄凭据。

**Non-Goals:**
- 工作目录沙箱 / allowed-roots 白名单（用户明确否决，编码 agent 需读写任意项目文件）。
- 凭据加密存储 / OS keychain 集成（仍明文 + `0600` + 目录权限；文档提示勿共享主机）。
- 出网内容级过滤、代理、DNS-over-HTTPS、per-hop 重定向重校验（`Redirect.NEVER` 已规避重定向绕过）。
- 扩大 `readFile` 的其它敏感面（本次仅凭据文件；会话 temp-memory 等非凭据不在黑名单）。

## Decisions

- **D1 文件黑名单落在工具体内（而非权限 hook）。** `readFile` 是 READ_ONLY，权限 hook 在所有模式放行——无法在权限层拦，只能在工具执行体内判。`FileSystemTools` 新增构造入参 `Set<Path> deniedPaths`（规范化后的凭据文件真实路径），每次操作前把请求路径解析为真实路径再比对：**目标存在** → `toRealPath()`（解引用符号链接）；**不存在**（writeFile 新建）→ `toAbsolutePath().normalize()`。命中即返回 `ToolErrors.message("access denied: credential file")`，不读不写。`listDirectory` 允许（目录名非机密，仅文件**内容**是），但落在被拒文件本身的 `listDirectory` 也一并拒（防误用）。备选"权限 hook 把凭据文件读归为需确认"被否——READ_ONLY 短路在 hook 之前，改不动且污染权限语义。
- **D2 SSRF 守卫独立类 `SsrfGuard`（纯函数、可单测）。** `SmartWebFetchTool` 新增构造入参 `List<String> allowedHosts`；`fetchUrl` 发请求前调 `SsrfGuard.check(url, allowedHosts)`：① scheme ∈ {http,https} 否则拒；② host 规范化（`toLowerCase(Locale.ROOT)` 去末尾点，复用 permission-system 的 `normalizeHost` 思路）；③ allowedHosts 非空则 host 必须命中，否则拒；④ `InetAddress.getAllByName(host)` 取**全部**解析地址，任一命中即拒：`isLoopbackAddress`/`isAnyLocalAddress`/`isLinkLocalAddress`/`isSiteLocalAddress`/`isMulticastAddress`，外加 IPv6 ULA（`(addr[0] & 0xfe) == 0xfc`，`isSiteLocalAddress` 不覆盖 ULA）。判据基于**解析 IP** 是防绕过的关键（十进制/十六进制 IP、`[::1]`、DNS 指向内网都会解析成被拒地址）。保持 `Redirect.NEVER`（不跟随即无重定向绕过）。拒绝返回 `ToolErrors.message("blocked: destination not allowed")`（只报类别，不回显解析出的内网 IP）。
- **D3 依赖经 `ToolContext` 注入，wiring 在 `AgentBootstrap`。** `ToolContext` 扩两个可空字段：`Path workspaceRoot`（`FileSystemTools` 据此算 `deniedPaths`）、`List<String> webAllowedHosts`（`SmartWebFetchTool` 用）。两 provider 从 ctx 取值构造。`AgentBootstrap` 建 `ToolContext` 时从 `WorkspaceManager` + config 填入。缺省（root 为 null / 白名单空）时：文件黑名单退化为空（不拦，兼容测试直构），SSRF 仍全私网拒绝——**安全默认不依赖配置**。
- **D4 `allowed-hosts` 配置落点。** 新增 `PigAgentConfig` 下轻量 `tools.web.allowed-hosts`（`List<String>`，默认空）。纯增量、向后兼容；空=仅 IP 守卫。
- **D5 掩码输入按终端类型分派。** REPL 两处（`ReplCommands` 的 `/model add`、`/model edit`）与 `McpCommand`（`/mcp add`、`/mcp edit` 对敏感键 `*token*`/`*key*`/`authorization`/`*secret*`）改用 JLine `reader.readLine(prompt, maskChar='*')`（`LineReaderImpl` 既有重载）。`OnboardingWizard` 非 JLine → 用 `System.console().readPassword(prompt)`；`System.console()` 为 null（管道/IDE/`mvn exec`）时回退现有可见读取并打印一次告警（无 console 无法掩码，属可接受降级）。**Spike 组 1 已实证（javap 静态确认）**：JLine 3.28.0 `org.jline.reader.LineReader.readLine(String, Character)` 存在；`java.io.Console.readPassword(String, Object...)` 存在（JDK 保证不回显）；非交互环境 `System.console()` 返回 null → 降级路径可达。承重风险解除。
- **D6 `0600` 收敛用共享小工具。** 新增 `io.pigagent.workspace.SecureFiles.restrictToOwner(Path)`（放 `pig-agent-workspace`，model/mcp 模块均可依赖它——待 Spike/编码期确认依赖方向，否则各 store 内联 5 行私有方法，避免新增跨模块依赖）：`Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))`，捕获 `UnsupportedOperationException`/`IOException` 忽略（非 POSIX / 失败不影响写入成功）。两 store 的 `persist()` 在 `writeValue` 成功后调用之。

## Risks / Trade-offs

- **[承重] 掩码 API 在本项目终端下的真实行为** → ✅ 已由 Spike 组 1 实证（javap 确认 JLine `readLine(String,Character)` 与 `Console.readPassword` 均存在；非交互 `System.console()==null` 降级可达）。承重风险解除；真实终端的回显抑制留组 7 人工冒烟终验。
- **[绕过] SSRF 仍存边缘** → DNS rebinding（校验后连接时 IP 变化的 TOCTOU）本设计未消解（`getAllByName` 校验与 `HttpClient` 实际连接是两次解析）。**缓解**：v1 接受此残留（本产品威胁模型是 prompt-injection 驱动的直连内网，rebinding 需攻击者控 DNS 且时序精准，风险低）；记入 Open Questions，v2 可改"连校验过的 IP + 固定 Host 头"。`Redirect.NEVER` 已堵重定向绕过。
- **[可用性] 黑名单误伤** → 用户确有正当理由读 `models.json`（如手动排查）时会被工具拒。**缓解**：仅拦 agent 工具路径，用户经 `/model`、直接编辑文件不受影响；名单极小（仅两文件 + `.bak`）。
- **[兼容] 掩码降级** → 无 console 环境掩码失效退回可见读取。**缓解**：打印告警提示"当前终端无法掩码，请注意 scrollback"；不阻断流程。
- **[DRY] `0600` 逻辑归属** → 跨 `pig-agent-model`/`pig-agent-mcp` 两模块。**缓解**：优先 `pig-agent-workspace.SecureFiles` 共享；若引入不当依赖则各内联（5 行、低耦合可接受），编码期二选一并在 tasks 标注。

## Migration Plan

- 纯增量、默认安全：无 `tools.web.allowed-hosts` 配置 → 仅 IP 守卫；`ToolContext.workspaceRoot` 由 `AgentBootstrap` 注入 → 黑名单自动生效。旧 `application.yaml` 无需改。
- 行为变更提示：README（中文）新增"工具出口边界与凭据保护"小节；`CLAUDE.md` 的工具/安全段补一句。
- 回滚：黑名单/SSRF 是工具体内判定，无配置开关即恒开（安全增强不提供关闭旁路）；`allowed-hosts` 留空即回到"仅私网拒绝"。掩码/0600 为纯增强，无回滚需求。

## Open Questions

- `0600` 工具类归属 `pig-agent-workspace.SecureFiles` vs 各 store 内联——编码期依模块依赖方向定（Risks[DRY]）。
- SSRF DNS-rebinding（TOCTOU）残留是否在 v2 升级为"连已校验 IP"——本次记录不做。
- `OnboardingWizard` 在 `System.console()==null` 时是否值得引入 JLine 以支持掩码——本次用可见读取+告警降级，不引入。
