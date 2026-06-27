## Context

完整设计与评审记录见 `docs/design/mcp-dynamic-crud-design.md`（经 office-hours → CEO 评审 → 工程评审）。当前 `McpManager.connectAll`（`pig-agent-mcp/.../McpManager.java:23`）只在启动期从 `application.yaml` 连接 MCP 服务器并 `toolkit.registerMcpClient(...)`，运行时不可改。约束：AgentScope 1.0.12；领域类型不可变（record + `withXxx`）；`application.yaml` 的 `mcp.servers` 需向后兼容；本机无 Maven，需用户环境编译验证。可复用形态：`JsonModelStore`/`ModelManager`/`ReplCommands` 里 `/model` 的嵌套 `@Command`/`McpManager.connectServer`。

## Goals / Non-Goals

**Goals:**
- 运行时通过 `/mcp` 管理 MCP 服务器（增删改查 + 连通测试 + 启停），尽量热生效。
- `mcp.json` 持久化为唯一真源，向后兼容导入 `application.yaml.mcp.servers`。
- agent 经 `McpTool` 在 **D-SEC 安全门** 下自助接入。
- 连接健康可见、密钥脱敏、并发安全。

**Non-Goals:**
- 断开连接的自动重连（快速跟进，非 v1）。
- 按 client 的工具命名空间/前缀（已决策扁平 D-NS）。
- 精选/已知 MCP 注册表（未来）。
- 抽取 `/mcp`/`/model`/`/session` 之外的其余 REPL 命令（单独 cleanup）。

## Decisions

- **D-NS 扁平命名空间 + 冲突即拒绝。** 工具不按 client 加前缀；`add` 做碰撞预检，冲突则拒绝并指明冲突工具与所属。备选（按 client 前缀隔离）被否，保持与现有工具注册一致、避免改调用名。
- **D-SEC agent 自助接入安全门。** `addMcpServer(command=…)` = LLM 驱动代码执行/外泄，是 prompt injection 面。`mcp.agent-management.allow-add`/`allow-remove` 默认关；开启时 agent add 仅 URL + `allowed-hosts` + 人工确认，拒绝 stdio。备选（默认开放、仅门控 remove）被否——方向反了。
- **E1 细粒度锁。** `connectServer`（I/O）在锁外；短临界区只做 碰撞检查 → `registerMcpClient` → `map.put`；`remove` 同理。备选（整段含连接的粗锁）被否——慢连接会冻结所有 MCP 操作与对话回合。channel 在独立线程并发，故需同步且检查+注册原子（无 TOCTOU）。
- **E2 命令文件拆分。** `/mcp`/`/model`/`/session` 抽为独立命令类，`ReplCommands` 只注册，守住 <800 行规约。备选（继续塞嵌套类）被否——上帝文件恶化。
- **存储形态。** 借用 `JsonModelStore` 结构（同步、容损、备份坏文件），但按 `name` 作键、无默认指针、加 `enabled`。`save` 按 name upsert，`add` 先查重名。

数据流（详见设计文档 §工程评审）：`add` = 校验 → connect(锁外) → synchronized{碰撞检查→register→map.put} → store.save，注册失败回滚关闭 client；agent add 经 `allow-add → URL-only → 白名单 → 人工确认` 门后走同一路径。

## Risks / Trade-offs

- **[承重] `removeMcpClient`/`allowToolDeletion` 行为未验证** → step-0 spike 先确认；不成立则回退 B-lite（remove/edit/disable 改重启生效，存储/CLI/Tool 接口不变）。
- **[安全] agent 接入外部服务器** → D-SEC 门（默认关 + URL-only + 白名单 + 人工确认）；密钥脱敏防回显。
- **[并发] channel 线程与 REPL 并发改 toolkit/map** → E1 细锁，检查+注册原子。
- **[可用性] MCP 进程/连接静默死亡** → `/mcp list`+`/status` 实时健康可见；自动重连作跟进。
- **[兼容] 双配置源（application.yaml 与 mcp.json）困惑** → 首启一次性导入、文档写明 `mcp.json` 为真源、旧块转只读并提示。

## Migration Plan

- 首次运行无 `mcp.json` 时，从 `application.yaml.mcp.servers` 一次性导入；此后 `mcp.json` 为真源，旧块只读。无破坏性变更，无需数据迁移脚本。
- 回滚：删除 `mcp.json` 即回到从 `application.yaml` 读取；功能为纯增量，可整体回退。

## Open Questions

- 是否在 v1 保留 `enable`/`disable`（YAGNI 候选，可用 remove+add 替代）。
- step-0 spike 结论决定 remove/edit/disable 是热生效还是回退重启。
