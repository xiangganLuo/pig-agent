## 1. Spike（先做，卡点）

- [ ] 1.1 验证 `PreActingEvent` 否决语义：写一个最小 hook，分别试 (a) `setToolUse` 改哨兵、(b) `Mono.error(...)`、(c) 返回原 event 但改参数，观察哪种能"工具不执行"且"拒绝原因回传模型可继续对话"。用 anthropic 模型跑一次真实工具调用验证。产出结论写回 design.md「Open Questions」。
- [ ] 1.2 验证 hook 内阻塞式 `readerRef.readLine`（复用 McpConfirmer）不死锁 reactor 线程；必要时确定 `subscribeOn/publishOn` blocking scheduler 的接法。

## 2. pig-agent-config：PermissionConfig

- [ ] 2.1 `PigAgentConfig` 新增 `permissions` 块：`mode`（默认 `ask`）、`channel-mode`（默认 `auto`）、`tool-overrides:Map<String,String>`、`allowlist`（`tools:List<String>`、`commands:List<String>`）。getter/setter + Jackson 注解，缺省安全。
- [ ] 2.2 `PermissionMode` 枚举（`PLAN`/`ASK`/`AUTO`/`BYPASS`）+ 解析容错（未知值回退默认并告警，不崩）。
- [ ] 2.3 单测：默认值、YAML 往返、未知 mode 容错、`tool-overrides`/`allowlist` 读写。

## 3. pig-agent-core：权限判定 + Hook

- [ ] 3.1 `ToolRiskClassifier`：工具名 → `ToolRisk`（READ_ONLY/WRITE/EXEC/NETWORK/MCP_ADMIN），内置默认表 + `tool-overrides` 覆盖，未知默认 EXEC（fail-safe）。
- [ ] 3.2 `PermissionDecision`（ALLOW/DENY/ASK）+ `PermissionPolicy`：给定 mode + risk + allowlist → decision（纯函数，无 I/O，好测）。含 plan 全否决、auto 放行 WRITE/NETWORK、EXEC/MCP_ADMIN 规则、bypass 全放、allowlist 命中即 ALLOW。
- [ ] 3.3 `ToolPermissionHook implements Hook`：`PreActingEvent` → 取工具名/参数 → classifier → policy → ALLOW 放行 / DENY 否决（step-0 写法）/ ASK 调 confirmer（y/n/a）；`a` 写 allowlist（工具或规范化命令键）经 `ConfigurationManager.updateConfig`。`priority()` 取小值。MCP_ADMIN 不重复弹窗（委托 D-SEC，仅按模式决定是否放行进入）。
- [ ] 3.4 规范化命令键工具（EXEC 逐命令粒度）：从 `ToolUseBlock.getInput()` 取 command → 规范化键（首 token 或整条，依 1.1 结论）→ allowlist.commands 匹配。
- [ ] 3.5 单测（CRITICAL，权限矩阵回归，纯函数无 LLM）：四模式 × 五风险类的 decision 全覆盖；allowlist 命中；未知工具按 EXEC；plan 否决可变工具；命令键匹配。

## 4. pig-agent-cli：/permission 命令 + 接线

- [ ] 4.1 `PermissionConfirmer`（复用/泛化 `McpConfirmer` 的 y/N，扩展第三态 `a`=always）经共享 `readerRef` 读取；无 reader 时 fail-closed。
- [ ] 4.2 `/permission` 命令（`cli/repl/command/PermissionCommand.java`）：`status`（默认）/`mode <plan|ask|auto|bypass>`/`allow <tool|cmd…>`/`revoke <…>`/`reset`/`list`（展示风险表 + allowlist）。`ReplCommands.build()` 注册；更新 `/help`。
- [ ] 4.3 `PigAgentCli` 接线：构造 `ToolPermissionHook`（`permissionSupplier` + confirmer + configManager），加入 `AgentFactory` 的 hooks 列表（置于 Logging hook 之前）。
- [ ] 4.4 `/status` 增加当前权限模式展示；plan 模式回合结束时 REPL 提示"切 `/permission mode ask` 执行"。
- [ ] 4.5 `PermissionCommand` 派发测试（DumbTerminal，`ReplCommandsTest` 新增 mode 切换 + status 渲染）。

## 5. pig-agent-channel：非交互兜底

- [ ] 5.1 渠道回合使用 `channel-mode`（默认 auto）而非交互 mode；`ChannelAgentBridge` 传入无 confirmer 的权限上下文。
- [ ] 5.2 无 confirmer 时 ASK 决策 fail-closed 拒绝并回传清晰说明（除非 `channel-mode=bypass`）。单测覆盖 EXEC 在渠道 auto 下被拒。

## 6. 文档与验证

- [ ] 6.1 README（中文）新增权限章节（四模式、`/permission`、`permissions` 配置块、`channel-mode` 风险说明、升级后默认 `ask` 的行为变更）+ `CLAUDE.md`。
- [ ] 6.2 验证：`mvn -pl pig-agent-cli -am compile` BUILD SUCCESS（全模块）；`mvn -pl pig-agent-config,pig-agent-core test` 权限矩阵单测全绿；`ReplCommandsTest` 派发测试全绿。手动冒烟以 anthropic 模型实跑：plan 模式只读不落地 / ask 弹 y-n-a / `a` 后免问 / auto 放行写文件但拦 shell / bypass 全放 / 渠道 auto 下 shell 被拒。
