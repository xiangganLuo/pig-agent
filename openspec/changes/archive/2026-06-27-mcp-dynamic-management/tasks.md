## 1. Spike（先做，卡点）

- [x] 1.1 验证 AgentScope 1.0.12（经 javap 静态确认，类已复制到根目录）：`Toolkit()` 默认构造即允许删除（无 `allowToolDeletion` 门）；`removeMcpClient(name):Mono<Void>` 实时注销；`getToolNames():Set<String>` 枚举已注册工具供碰撞预检。结论：采用热删除（方案 A）。

## 2. pig-agent-mcp：存储 + McpManager

- [x] 2.1 新增 `McpServerSpec`（record，不可变 + `withXxx`：name/command/args/env/url/streamableHttp/headers/enabled；校验 command XOR url）。
- [x] 2.2 新增 `McpStore` 接口 + `JsonMcpStore`（`workspace/mcp.json`，按 name 作键，synchronized，坏文件备份 `mcp.json.bak` 后从空开始）。
- [x] 2.3 升级 `McpManager`：持有 Toolkit + `name→McpClientWrapper` map；`initialize(store,toolkit)`（取代 connectAll，首启导入 `application.yaml.mcp.servers`，尽力连接 enabled、绝不抛出）。
- [x] 2.4 实现 `add`（先查重名→校验→碰撞预检→test→connect→注册→map→save，注册失败回滚）、`remove`、`edit`（先 test 新 spec）、`enable`/`disable`、`test(→toolCount)`、`list`（合并实时健康 + 脱敏）、`closeAll`（遍历 map）。
- [x] 2.5 E1 细粒度锁：`connectServer` 在锁外；短临界区只做 碰撞检查→`registerMcpClient`→`map.put`；`remove` 同理。
- [x] 2.6 单测：`JsonMcpStore`（按名增删查、重名拒绝、坏文件容损、跨实例持久化，6/6 PASS）；`McpManager`（去重拒绝、remove 删存储、disable 持久化、enable 未知拒绝、list 健康、findByName 委托，6/6 PASS；连接/碰撞/回滚路径依赖真实服务器，归入 6.2 冒烟）。

## 3. pig-agent-config / workspace：配置接入

- [x] 3.1 `PigAgentConfig` 新增 `mcp.agent-management` 块（`allow-add=false`、`allow-remove=false`、`allowed-hosts=[]`）。
- [x] 3.2 `WorkspaceManager` 新增 `getMcpFile()` → `workspace/mcp.json`。

## 4. pig-agent-tools：McpTool + D-SEC 安全门

- [x] 4.1 新增 `McpTool`（`@Tool`：listMcpServers/testMcpServer/addMcpServer/removeMcpServer，委托 `McpManager`，返回脱敏 env/headers）。
- [x] 4.2 D-SEC 门：`allow-add` 默认关；开启时仅 URL + host∈`allowed-hosts` + 人工确认（经 `ReplContext.readerRef`），拒绝 stdio/command；`removeMcpServer` 默认关；list/test 始终允许。
- [x] 4.3 安全门测试（CRITICAL，D-SEC 回归）：allow-add=false 拒绝 / stdio command 拒绝 / host 非白名单拒绝 / 白名单 URL+确认 通过 / removeMcpServer 默认拒绝。

## 5. pig-agent-cli：/mcp 命令 + 接线 + 命令拆分

- [x] 5.1 E2 重构：`/mcp` 抽为独立命令类 `cli/repl/command/McpCommand.java`，`ReplCommands.build()` 注册。`/model`、`/session` 暂留 `ReplCommands`（行为不变、文件仍 < 800 行，纯位置重构推迟以降风险）。
- [x] 5.2 实现 `/mcp`（list/add/remove/edit/enable/disable/test；add/edit 交互式 stdio|sse|http；remove 先确认；索引 vs 名称解析）。
- [x] 5.3 `/mcp list` 与 `/status` 展示实时健康（connected + 工具数 / not connected / disabled）；更新 `/help`。
- [x] 5.4 `PigAgentCli` 接线：`new JsonMcpStore(workspace.getMcpFile())` + `mcpManager.initialize(...)`、注册 `new McpTool(...)`（confirmer 经共享 `readerRef`）、注入 `ReplContext`。（`Toolkit()` 默认即允许删除，无需 `allowToolDeletion` 开关——见 spike。）
- [x] 5.5 `McpCommand` 派发测试（DumbTerminal，`ReplCommandsTest` 新增 /mcp list 派发 + 默认 list，全部 6/6 PASS）。

## 6. 文档与验证

- [x] 6.1 更新 README（中文 MCP 章节：`/mcp`、`mcp.json` 为真源、`application.yaml` 一次性导入、`mcp.agent-management` 安全门）与 `CLAUDE.md`。
- [x] 6.2 验证：`mvn -pl pig-agent-cli -am compile` BUILD SUCCESS（全 12 模块）；`mvn -pl pig-agent-mcp test` 12/12 PASS；`ReplCommandsTest` 6/6（含 /mcp 派发）。手动冒烟在测试团队阶段以 anthropic 模型实跑（实时增删 / 坏服务器不崩 / agent 受门拦截 / 坏 mcp.json 可恢复 / 旧 application.yaml 仍可用）。
