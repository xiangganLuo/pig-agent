## 1. Spike：掩码输入承重验证（先做、卡点）

- [x] 1.1 javap 确认 JLine 3.28.0 `org.jline.reader.LineReader.readLine(String, Character)` 存在 → `reader.readLine(prompt,'*')` 掩码可用（真实终端回显抑制留组 7 冒烟）
- [x] 1.2 javap 确认 `java.io.Console.readPassword(String, Object...)` 存在（JDK 保证不回显）；非交互环境 `System.console()` 返回 null → 降级路径可达
- [x] 1.3 结论写回 design.md（D5 + Risks 标记「已实证/承重风险解除」）——Spike 通过，进第 2 组

## 2. 文件工具敏感文件黑名单（tool-sandbox）

- [x] 2.1 `FileSystemToolsTest`（7 测试）：直接命中拒绝、`../` 间接命中拒绝、写拒绝（存在/不存在两态均不改文件）、普通项目文件读写放行、缺省空名单向后兼容、被拒返回 `{"error"}` 不泄内容
- [x] 2.2 `FileSystemTools` 增构造入参 `Set<Path>`；`readFile`/`writeFile` 前规范化比对（同时存 `normalize` 与 `toRealPath` 两种表示，防 `..`/符号链接/短名大小写），命中返回 `ToolErrors.message("access denied: credential file")`
- [x] 2.3 `ToolContext` 增可空 `workspaceRoot` + `webAllowedHosts` accessor（保留 2 参旧构造器）；`FileSystemToolsProvider.create` 据 root 算 models.json/mcp.json/.bak 名单，root 为 null 传空
- [x] 2.4 `FileSystemToolsTest` 7/7 绿

## 3. fetchUrl SSRF 出口防护 + 主机白名单（tool-sandbox）

- [x] 3.1 `SsrfGuardTest`（12 测试，离线字面 IP）：回环 v4/v6、localhost、私网(10/172.16/192.168)、链路本地(169.254)、multicast、非 http(s) scheme、十进制 IP 绕过 各拒；公网字面 IP 放行；allowed-hosts 非空只放行白名单、白名单内私网仍拒（纵深）
- [x] 3.2 `io.pigagent.tool.webfetch.SsrfGuard`（纯函数）：scheme 校验 + host 规范化去括号/末尾点 + allowed-hosts 判定 + `getAllByName` 全地址 loopback/anyLocal/linkLocal/siteLocal/ULA/multicast 判定
- [x] 3.3 `SmartWebFetchTool` 增构造入参 `List<String> allowedHosts`；`fetchUrl` 前调 `SsrfGuard`，被拒返回 `ToolErrors.message("blocked: destination not allowed")`；保持默认 `Redirect.NEVER`
- [x] 3.4 `ToolContext.webAllowedHosts()` accessor；`SmartWebFetchToolProvider.create` 注入
- [x] 3.5 `PigAgentConfig` 增 `tools.web.allowed-hosts`（默认空）；`AgentBootstrap:192` 注入 `workspace.getRootPath()` + `config.getTools().getWeb().getAllowedHosts()`
- [x] 3.6 `SsrfGuardTest` 12/12 绿；`mvn -pl pig-agent-cli -am compile` BUILD SUCCESS（config+wiring 无回归）

## 4. 凭据录入掩码（credential-hardening）

- [x] 4.1 `ReplCommands`：`/model add`（`:268`）、`/model edit`（`:327`）API key 改 `reader.readLine(prompt, '*')` 掩码
- [x] 4.2 `McpCommand.readKeyVals` 改两步录入（key 可见 → 敏感键 value 掩码、普通可见），抽出 `isSensitiveKey`；移除 M-1 TODO
- [x] 4.3 `OnboardingWizard` 新增 `readSecret`：`System.console().readPassword`；console 为 null 回退可见读取 + 一次告警（`warnedNoMask`）
- [x] 4.4 `McpCommandSensitiveKeyTest`（2 测试）：token/key/authorization/secret/password 命中、REGION/HOST/timeout 不命中
- [x] 4.5 `McpCommandSensitiveKeyTest` 2/2 绿；onboarding+cli 经 `-am` 编译通过（全量回归留组 6）

## 5. 凭据文件 0600 落盘（credential-hardening）

- [x] 5.1 决定：**各 store 内联私有 `restrictToOwner`**（model/mcp 均不依赖 workspace；为 5 行 helper 加跨模块依赖不划算，且与既有内联 `backupCorrupt` 重复模式一致）
- [x] 5.2 两 store 测试各加 `savedCredentialFileWritesSuccessfully_andIsOwnerOnlyOnPosix`：恒断言写入成功+文件存在；POSIX 才断言 `rw-------`（`assumeTrue`，Windows 跳过）
- [x] 5.3 `restrictToOwner`：`Files.setPosixFilePermissions(file,"rw-------")`，捕获 `UnsupportedOperationException`/`IOException` 忽略
- [x] 5.4 `JsonModelStore.persist()` 与 `JsonMcpStore.persist()` writeValue 成功后调用；移除 `JsonMcpStore` M-2 TODO
- [x] 5.5 两 store 各 7/7 绿（POSIX 断言 Windows skip 1）

## 6. 集成与回归

- [x] 6.1 全量测试绿：323 测试 / 0 失败 / 0 错误 / 2 skip（Windows POSIX 断言）。较基线 300 增 23。注：`-T 1C` 并行下 `AgentKernelInterruptTest.chat_normalTermination_clearsTurn`（core，本变更未触碰）偶发时序 flaky，单线程稳定绿——**既有 flaky，非本变更引入**（建议记入批次 C）
- [x] 6.2 `mvn -pl pig-agent-cli -am compile` BUILD SUCCESS（组 3 边界已验，全量 test 再次覆盖）
- [x] 6.3 复查无凭据泄漏：新增失败返回均为 `access denied`/`blocked`（无凭据、无内网 IP）；掩码路径不 log key；`restrictToOwner` 无日志；`readSecret` 告警无 key

## 7. 文档与人工冒烟

- [x] 7.1 `docs/review/mcp-security-followups.md`：M-1/M-2 标记为已解决（指向本变更 + 回归测试），结论同步
- [x] 7.2 README 新增"工具出口边界与凭据保护"小节；`CLAUDE.md` 新增 `tool-sandbox`+`credential-hardening` 段
- [ ] 7.3 人工冒烟（真实终端 + 真模型，留 `/ls:itest`）：`/model add` 掩码不回显、agent `readFile(models.json)` 被拒、agent `fetchUrl(169.254.169.254)` 被拒、`fetchUrl` 公网正常。核心判定逻辑已由 `FileSystemToolsTest`/`SsrfGuardTest`/`McpCommandSensitiveKeyTest` 离线覆盖
