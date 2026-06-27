# 设计：MCP 服务器动态管理（CLI 增删改查 + 连通测试 + agent 自助接入）

由 /office-hours 于 2026-06-25 生成
分支：main
仓库：xiangganLuo/pig-agent
状态：APPROVED（已通过 CEO 评审 + 工程评审，2026-06-25）
模式：Builder（面向开源框架能力）

## 决策（Decisions）

- **D-NS（工具命名空间）：扁平，不做按 client 隔离。** 所有 MCP 工具共用一个扁平命名空间，不按 client 加前缀。后果：`add` 必须做碰撞预检，并**拒绝**工具名与已注册工具冲突的服务器（这是硬性要求，不是开放问题）。step-0 仍需确认 AgentScope *如何*注册（以便预检读到正确的工具名列表），但设计立场固定：扁平 + 冲突即拒绝。
- **D-SEC（agent 自助接入须安全加固）：保留方案 C，但锁死。** agent 的 `addMcpServer` 属于 LLM 驱动的代码执行（stdio `command`）/ 数据外泄（URL），是 prompt injection 的攻击面。决策（CEO 评审 D1=B）：`mcp.agent-management.allow-add` **默认关闭**；开启时 agent 的 add 仅限 **URL 传输 + `allowed-hosts` 白名单 + 人工确认**，绝不允许 `command`/stdio；`allow-remove` 默认关闭。`list`/`test` 保持开放。人工 `/mcp` CLI 不受限。见 §4。

## 问题陈述

如今 MCP 服务器是**只读配置、仅启动期生效**。启动时 `McpManager.connectAll`（`pig-agent-mcp/.../McpManager.java:23`）读取 `application.yaml` 的 `mcp.servers`，逐个连接（stdio / SSE / streamable-http，经 `McpClientBuilder`），并用 `toolkit.registerMcpClient(client).block()` 把它们的工具注册进 Toolkit；关闭时执行 `closeAll`。

要增、改、删一个服务器，必须**改 YAML 再重启**。没有列表、没有连通测试、没有启用/停用、没有运行时修改。对一个供其他开发者使用的框架来说，这是个尖锐的痛点：不重启、不手改文件，就无法发现、校验或管理 MCP 连接。

## 亮点（"哇"在哪）

agent 能在对话中自助接入能力。"连接位于 X 的文件系统 MCP 服务器"→ agent 调用一个 `@Tool`，完成连接、跑连通测试、把新工具注册进它运行中的 toolkit，并立刻开始使用——不用重启、不用人改 YAML。同时给人类提供干净的 `/mcp` CLI，谁更喜欢直接操作都行。

## 约束

- **AgentScope 1.0.12。** `Toolkit` *预期*支持实时 `registerMcpClient(client)` 与 `removeMcpClient(String name)`（均返回 `Mono<Void>`），删除受 toolkit 的 `allowToolDeletion` 开关控制。**当前代码库里从未调用过 `removeMcpClient`**（只有 `registerMcpClient`，第 32 行）——见"风险"。
- 仓库对领域类型强制**不可变**（record + `withXxx`）；config 类（`PigAgentConfig` 的内嵌 bean）是被认可的可变例外。多小文件（<800 行、函数 <50 行），见 `.claude/rules/common/coding-style.md`。
- 现有 `application.yaml` 的 `mcp.servers` 必须继续可用（**向后兼容**）——一次性导入新存储。
- **开源标准（D1 选择）**：校验输入、`add` 持久化**之前先跑连通测试**、清晰报错、脱敏密钥、文档。
- 作者环境无本机 Maven——代码只能仔细编写，需在用户环境编译验证。

## 风险与回退（承重项——先验证）

下面三条 AgentScope 行为是"实时"承诺的承重点。**在做 remove/edit/disable 之前，先用一次 ~15 分钟的 spike（Next Steps 第 0 步）验证：**

1. **`removeMcpClient(name)` + `allowToolDeletion`** 是否真能注销一个运行中 MCP client 的工具（以及 `allowToolDeletion` 在 `Toolkit` 上的确切开启方式）。未验证；本仓库今天从未用过。
2. **`removeMcpClient` 的生命周期**——它是否也会停掉底层进程/连接，还是只注销工具？无论如何我们都自己调用 `client.close()`。
3. **工具名命名空间**——MCP 工具是扁平注册还是按 client 分隔？若扁平，两个都暴露 `read_file` 的服务器会在实时注册时冲突。

**若第 (1) 条不成立的回退：** 降级为 **方案 B-lite**——`add`/`test`/`list` 仍实时；`remove`/`edit`/`disable` 持久化到 `mcp.json` 并在下次重启生效，明确提示用户。存储、CLI、`@Tool` 接口不变，仅删除的"实时性"改变。

## 前提（Premises）

1. **假设（须验证——见风险）：** 经 `removeMcpClient`/`registerMcpClient` + `allowToolDeletion` 可实时实现完整热增删改，无需重建 agent。若 spike 证否，remove/edit/disable 路径回退为"重启生效"。
2. 复用现有 `/model` 特性的形态：不可变 record + JSON 存储 + manager + `ReplCommands` 里的嵌套 `@Command` + 一个 `test()` 方法。（见一致性说明——它是*形态*复用，不是逐字照搬。）
3. 开源质量标准：校验（command-xor-url、名称唯一）、清晰报错、test-before-persist、**list/工具输出脱敏密钥**、文档。
4. 无新发布渠道——在现有 CLI 上加一个 REPL 命令 + 几个 agent 工具。

## 密钥与并发（横切关注点）

- **密钥。** `mcp.json` 以**明文**保存 `env` 与 `headers`（可能含 Bearer token——见 `WorkspaceManager` 配置示例），与 `models.json`/`apiKey` 同等姿态。`list()` 输出、尤其是 agent `@Tool` 的返回载荷，必须**脱敏** `env`/`headers` 的值（显示键、掩码值），防止 LLM 把 token 回显进对话。
- **并发。** channel（Telegram/Discord 经 `ChannelAgentBridge`）在**独立线程**跑 agent，与 REPL 线程、agent 自己的 `@Tool` 调用并发。旧 `McpManager` 只在启动期修改；运行时增删查是新的共享状态暴露。所有修改型 `McpManager` 方法（`add/remove/edit/enable/disable`）与 `name → McpClientWrapper` map 必须 `synchronized`（存储本身像 `JsonModelStore` 一样已同步）。

## 备选方案

### 方案 A：原地扩展，持久化到 `application.yaml`
经 `ConfigurationManager.updateConfig` 修改 `config.mcp.servers`。文件最少，但会逼着 `McpConfig`/`McpServerConfig` 长出 setter/可变 map（违反 record/不可变规约），且密钥仍混在主 YAML。**否决。**

### 方案 B：独立 `mcp.json` 存储 + `/mcp` 命令（基础）
不可变 spec record + `JsonMcpStore`，`McpManager` 作实时协调者，`/mcp` 命令镜像 `/model`。干净、一致、容损。也是回退的实时性模式（见风险）。

### 方案 C（选定）：B + 把 MCP 增删查暴露为 agent `@Tool`
在 B 之上，加 `@Tool` 方法让 agent 能在对话中增/测/删/列 MCP 服务器，并为"自改工具集"这一面加护栏。

## 选定方案（C）

### 1. 持久化——`pig-agent-mcp` 新增存储
- **`McpServerSpec`**（record，不可变 + `withXxx`）：`name`（唯一键——也是 `Toolkit.removeMcpClient` 用的 MCP client 名）、`command`、`args`（List）、`env`（Map）、`url`、`streamableHttp`（boolean）、`headers`（Map）、`enabled`（boolean）。校验：**`command` / `url` 二者必居其一**（复用 `connectServer` 里已有的校验）。
- **`McpStore`** 接口 + **`JsonMcpStore`** → `workspace/mcp.json`（`{ "servers": [ {…} ] }`）。**按 `name` 作键**（不是随机 id）。它是 `ModelStore` *去掉* `defaultModelId` 指针（没有"默认 MCP 服务器"概念）*加上* `enabled` 标记——所以借用 `JsonModelStore` 的结构（同步、容损：坏文件备份为 `mcp.json.bak` 后从空开始），不借用其默认指针逻辑。`save(spec)` 是按 name upsert；**`add` 必须先 `findByName` 并拒绝重名**（不要静默覆盖一个活动连接）。

### 2. `McpManager` 升级为实时协调者（所有 mutator `synchronized`）
持有共享 `Toolkit` + `name → McpClientWrapper` map。方法：
- `initialize(McpStore, Toolkit)`——取代 `connectAll`；首启（无 `mcp.json`）时把旧 `application.yaml` 的 `mcp.servers` **导入**存储（map key → `name`），再**尽力**连接每个 `enabled` 服务器（逐个 try/catch、记录失败、绝不抛出——保持今天 `connectAll` 的契约）。
- `add(spec)`——拒绝重名 → 校验 → **碰撞预检**（若某工具名已被另一 client 注册则拒绝；硬性，不延后）→ **test** → `connectServer` → `registerMcpClient(client).block()` → 记录 → `store.save`。注册失败：关闭 client，不持久化。
- `remove(name)`——`removeMcpClient(name).block()` → `client.close()` → 移出 map → `store.deleteById(name)`。
- `edit(name, newSpec)`——**先 test 新 spec**；仅成功后才移除旧 client 并加新的；任何失败都保持旧 client/注册不动（无破坏性 edit）。
- `enable(name)` / `disable(name)`——connect+register / removeMcpClient+close；持久化标记。
- `test(spec)`——构造一个临时 client，连接（`buildSync`），数工具，关闭 → `TestResult(ok, error, toolCount)`（在 `ModelManager.TestResult` 基础上加 `toolCount`）。
- `list()`——把存储的 spec 与实时状态（是否连接？工具数？）合并；**脱敏密钥**。
- `closeAll()`——遍历 **map**、逐个关闭、清空（底层字段从旧的 `clients` list 改为 map）。

### 3. CLI——`/mcp` 命令（`ReplCommands.java` 内的嵌套 `@Command` 类，与现有 `/model` 子命令一致；不存在独立的 `ModelCommand` 文件）
`/mcp list | add | remove <name|idx> | edit <name|idx> | enable <name|idx> | disable <name|idx> | test <name|idx>`。`add`/`edit` 交互式（name → 传输 → command+args+env 或 url+headers → test → 实时注册）。`remove` 先确认。**索引 vs 名称解析：** 全数字按 1 起的列表序号；否则按名称（名为 `"1"` 之类会被当序号——文档说明）。更新 `/help` 与 `/status`。`ReplContext` 增加 `mcpManager`。

> 工程评审 E2 决策：`/mcp`、`/model`、`/session` 拆为各自的命令类文件，`ReplCommands` 只做注册（见下方"工程评审"）。

### 4. agent `@Tool`（C 的增量）——`pig-agent-tools` 里的 `McpTool` —— 受安全门控制
`@Tool` 方法：`listMcpServers`、`testMcpServer`、`addMcpServer`、`removeMcpServer` → 委托给 `McpManager`。返回值**脱敏 `env`/`headers`**。在 `PigAgentCli` 用 `mcpManager` 引用注册。

**威胁模型（为什么要门控）。** MCP stdio 服务器经 `command`+`args` 启动本地进程，所以 agent 发起的 `addMcpServer(command=…)` 等于 **LLM 驱动的本地代码执行**；URL 服务器能把 agent 的工具调用重定向到敌对端点（外泄）。结合 prompt injection（agent 读到的不可信内容要求它"连接到这个 MCP 服务器"），不设门的 agent-add 路径是 RCE / 数据外泄通道。**危险的是 `add`，不是 `remove`。**

**护栏（已决策——CEO 评审 D1=B）：**
- 新增 config 块 `mcp.agent-management`（`PigAgentConfig` 内嵌 bean——被认可的可变例外）：`allow-add` **默认 `false`**、`allow-remove` **默认 `false`**、`allowed-hosts`（默认 `[]`）。
- `allow-add` 开启时，agent 的 add **仅限 URL 传输**（绝不 `command`/stdio），且 URL 的 host 必须在 `allowed-hosts` 内，且必须经**人工确认路径**（`ReplContext.readerRef`）再连接——agent 提议，人类批准。
- `listMcpServers` / `testMcpServer` 始终允许（只读、不注册）。
- 所有 agent 路径仍跑校验 + 连通测试 + 碰撞预检，且工具结果明确列出新增了哪些工具。
- 人类的 `/mcp` 命令不受限（操作者可信）；只有 *agent* 路径受门控。碰撞处理与 CLI 路径共用。

### 5. 接线（`PigAgentCli`）
- 构造 `Toolkit` 时**启用 `allowToolDeletion`**（确切 API 在第 0 步确认）。
- `JsonMcpStore mcpStore = new JsonMcpStore(workspace.getMcpFile())`；`mcpManager.initialize(mcpStore, toolkit)`。
- 把 `new McpTool(mcpManager)` 注册进 toolkit。
- 把 `mcpManager` 注入 `ReplContext`。`WorkspaceManager.getMcpFile()` → `workspace/mcp.json`。

### 6. 可观测性与健康（工程评审——HOLD SCOPE 严谨性）
MCP stdio 进程会死、SSE/HTTP 连接会断；一个静默死亡的服务器 = 静默消失的工具，这对宣传的 24h 运行不可接受。纳入范围：
- `McpManager.list()` 与 `/status` 展示每个服务器的**实时健康**（已连接/失败/工具数），不只是存储配置。
- 失败/断开的服务器在 `/mcp list` 里**可见**标记（不静默消失）。
- 碰撞拒绝的报错要**指明是哪个工具冲突、属于哪个已有服务器**。
- *自动重连*（后台重试断开的连接）记为快速跟进项，非 v1。

## 开放问题

（承重的能力检查 → "风险与回退"，第 0 步验证。工具命名空间 → 决策/D-NS。agent-add 安全 → 决策/D-SEC，CEO 评审已决。）
- 是否在 v1 保留 `enable`/`disable`（见范围说明），还是先做 增删查+test。

## 成功标准

- `/mcp add` 一个 stdio 服务器 → 其工具在**同一会话**内不重启即可用，并跨重启持久化。
- 启用 `mcp.agent-management.allow-add=true` 时，agent 能提议一个**白名单 host** 上的 **URL** 服务器，人工批准后新工具同一回合可调用。默认（`allow-add=false`）下，agent 的 `addMcpServer` 被清晰拒绝。
- agent 永远无法添加 `command`/stdio 服务器，`removeMcpServer` 默认关闭。
- `/mcp remove` → 这些工具立即从运行中的 agent 消失 *（若第 0 步 spike 确认热删除；否则按回退在重启时生效）*。
- `/mcp test` 报告 ok/失败 + 工具数，且对坏服务器**绝不让 REPL 崩溃**。
- 添加一个工具名与已有冲突的服务器会被**拒绝**，且报错指明冲突工具 + 所属服务器。
- `list` / 工具输出永不显示 `env`/`headers` 的明文 token。
- 损坏的 `mcp.json` → 备份、从空开始、REPL 仍运行。
- 现有 `application.yaml` 的 `mcp.servers` 仍可用（一次性导入 `mcp.json`）。

## 发布方案

无新产物——现有 `pig-agent-cli` 上的一个 REPL 命令 + 几个 `@Tool`。文档：更新 README（中文）MCP 章节与 `CLAUDE.md`。无新 CI/CD。

## 依赖

- **第 0 步 spike** 确认 `removeMcpClient` 行为、`allowToolDeletion` 启用方式、MCP 工具名命名空间（见风险）。
- 复用的*形态*：`JsonModelStore`（同步 JSON 存储、容损，但无默认指针）、`ModelManager`（增删查 + `test()`）、`ReplCommands` 里 `/model` 的嵌套 `@Command`、`McpManager.connectServer`（private，留在原处）、`ConfigurationManager`、`WorkspaceManager`、`ReplContext`/`ReplCommands`。

## 范围说明

- `enable`/`disable` 略超"增删查 + test"——有机制就便宜，但若要精简首版，它是 YAGNI 候选（可用 `remove`+`add` 替代）。除非想要更小的 v1，否则保留。

## 实施步骤（构建顺序）

0. **Spike（先做）：** 对照 AgentScope 1.0.12 验证三条风险——`removeMcpClient` + `allowToolDeletion` 启用方式 + 工具名命名空间。决定热删除还是回退。
1. `McpServerSpec` + `McpStore` / `JsonMcpStore`（+ 单测：按名增删查、重名拒绝、坏文件容损）——纯 Java，不依赖 AgentScope。
2. 升级 `McpManager`（同步的按名 map、add/remove/edit/enable/disable/test、initialize+import、碰撞预检）；在 `PigAgentCli` 启用 `allowToolDeletion`。
3. `ReplCommands` 里的 `/mcp` 嵌套命令 + `ReplContext` 接线 + `/help`/`/status` + 实时健康展示（§6）。
4. `McpTool`（`@Tool`）+ 注册 + `mcp.agent-management` config 块（`allow-add=false`、`allow-remove=false`、`allowed-hosts=[]`）+ URL-only/白名单/人工确认 门 + 脱敏（§4 / D-SEC）。
5. 向后兼容导入 + 文档（README + CLAUDE.md）。
6. 在用户的 Maven 环境验证编译。

## 我对你思路的观察

- 你选了"开源框架能力，给其他开发者用"而非"内部能力补全"——你把它定位为别人会依赖的能力，这在我开口前就抬高了你自己的标准（校验、test-before-persist）。
- 在 D2 你选了**完整热增删改**而非保守的"重启生效"楔子——更难、更完整的版本，哪怕它当时建立在未确认的注销能力上。
- 在 D4 你选了 **C 而非推荐的 B**——你要 agent 自己接 MCP 服务器，不只是人类。这是更进取的框架思路：agent 扩展自身。

## 评审记录

经 1 轮独立对抗式评审（5 个维度）。修订后从 6 分提升（目标 8）：把 AgentScope 能力从"已确认"降级为"先验证"的风险并给出回退；纠正 `/model` 复用表述（`ModelCommand` 是嵌套 `@Command`、非文件；`ModelStore` 的默认指针不迁移；按 `name` 作键）；补充密钥脱敏、并发同步、尽力启动、非破坏性 `edit`、重名拒绝、强制碰撞预检。

## CEO 评审报告（HOLD SCOPE）

模式：**HOLD SCOPE**——特性已在 office-hours 充分定型；要做的是严谨性 + 安全，而非更大野心。没加范围；一个能力（agent 自助接入）被重新加固，一个严谨性领域（可观测性）被拉进范围。

| # | 发现 | 严重度 | 处置 |
|---|------|--------|------|
| 1 | agent `addMcpServer(command=…)` = LLM 驱动代码执行 / prompt injection RCE；原默认开启，且只门控了*安全*的 `remove` | CRITICAL | D1=B → §4 / D-SEC：agent-add 默认关；开启时 URL-only + `allowed-hosts` + 人工确认；绝不 stdio；`allow-remove` 关 |
| 2 | 方案 C（agent 自助接入）vs 真正的"不改 YAML 不重启"需求——v1 值不值这复杂度 | HIGH（范围） | 保留 C（用户意图）但门控到安全；人类 `/mcp` 是解决核心痛点的无限制路径 |
| 3 | 无健康 / 自动重连——死掉的 MCP 服务器静默丢工具（vs 宣传的 24h 运行） | HIGH | §6 纳入范围：`/mcp list` + `/status` 实时健康；自动重连作快速跟进 |
| 4 | 扁平命名空间的"冲突即拒绝"较粗 | MEDIUM | 保留决策（D-NS）；报错须指明冲突工具 + 所属服务器 |

结论：**APPROVED（HOLD SCOPE）**——按构建顺序推进；第 0 步 spike + D-SEC 门是任何 agent-add 代码落地前的卡点。CODEX / 跨模型：未运行（gstack 工具在本 Windows 机不可用）。

NO UNRESOLVED DECISIONS

## 工程评审（锁定决策 + 产出）

### 锁定决策
- **E1 — 并发：细粒度锁。** `connectServer`（网络/进程 I/O，`buildSync`）在锁**外**运行。一个短的 `synchronized(McpManager)` 临界区只做：碰撞检查（对照实时 toolkit 工具名）→ `registerMcpClient`（内存内）→ `map.put`。`remove` 同理：`synchronized { removeMcpClient(name); map.remove }` 然后在锁外 `client.close()`。理由：channel 线程（`ChannelAgentBridge`）与 REPL 并发跑 agent；若用粗锁在 30s 连接期间持锁，会冻结其他所有 MCP 操作和任何触及 MCP 的回合。检查+注册+登记原子，故无 TOCTOU。
- **E2 — 命令文件拆分。** 把 `/mcp`、`/model`、`/session` 抽到各自的命令类（如 `cli/repl/command/{McpCommand,ModelCommand,SessionCommand}.java`）；`ReplCommands.build()` 只做注册。让每个文件低于 800 行规约。

### 数据流图（嵌入文档 + McpManager javadoc）

```
/mcp add（人类）/ McpManager.add(spec):
  校验(name 唯一, command XOR url)
    └─▶ connectServer(spec)              [I/O —— 锁外]
          └─▶ synchronized(McpManager) {
                 对照实时 toolkit 工具名做碰撞检查
                   冲突? ──是──▶ 关闭 client + 拒绝(指明冲突工具及所属)
                   否 ──▶ registerMcpClient(client).block()   (内存内, 快)
                       ──▶ map.put(name, client)
              }
          └─▶ store.save(spec)           [锁外]
  注册失败 ──▶ 关闭 client, 不持久化(回滚)

remove(name):
  synchronized { removeMcpClient(name).block(); map.remove(name) } ──▶ client.close() ──▶ store.deleteById(name)
edit = 先 test(new) ──▶ 成功: remove(old) + add(new) ; 失败: 保持 old 不动
```

```
agent McpTool.addMcpServer(spec)  [D-SEC 门]:
  allow-add == false ?            ──是──▶ 拒绝 ("agent MCP add 已禁用")
  传输为 stdio/command ?          ──是──▶ 拒绝 ("agent 只能加 URL 服务器")
  host(spec.url) ∉ allowed-hosts ?──是──▶ 拒绝 ("host 不在白名单")
  否则 ──▶ 经 ReplContext.readerRef 人工确认
             未确认 ──▶ 中止
             确认 ──▶ McpManager.add(spec)   (与 /mcp 同路径)
```

### 测试计划（新路径 100% 覆盖；安全测试为 CRITICAL）
- `JsonMcpStore`（单测，★★★）：按名 save/find、**重名拒绝**、delete 删目录、跨实例持久化、**坏 `mcp.json` → 备份 + 从空开始**。
- `McpManager`（需真实/dumb `Toolkit` + 假 `McpClientWrapper`）：add 注册工具；**碰撞 → 拒绝并指明冲突工具**；注册失败 → 回滚（关闭 client、不持久化）；remove → 工具消失 + client 关闭；edit 失败保持旧的不动。
- `McpTool` 安全门（**CRITICAL —— 即 D-SEC 回归测试**）：`allow-add=false` → 拒绝；`allow-add=true` + stdio `command` → 拒绝；URL host 不在 `allowed-hosts` → 拒绝；白名单 URL + 确认 → 添加；`removeMcpServer` 默认关 → 拒绝。
- `McpCommand` 派发（DumbTerminal 模式，类似 `ReplCommandsTest`）：list/add/test/remove 解析 + 索引 vs 名称解析。
- 并发：尽力测试两个并行 `add` → 无工具丢失/重复（注：时序敏感；若不稳定则注明）。

### 失败模式（生产）
| 代码路径 | 失败 | 有测试? | 错误处理 | 用户看到 |
|---|---|---|---|---|
| MCP stdio 进程中途死亡 | 工具静默失效 | **加测试** | §6 健康展示；自动重连=跟进 | `/mcp list` 标记为失败（不静默） |
| `connectServer` 30s 超时 | 慢/死服务器 | 是（`test` 坏 url） | 细锁 → 不冻结；`add` 返回错误 | 清晰"连接失败" |
| `registerMcpClient` 连接后失败 | 半添加 | 是 | 回滚关闭 client、不持久化 | 清晰报错、无孤儿 |
| `mcp.json` 损坏 | 存储不可读 | 是 | 备份 + 从空开始 | 警告、REPL 仍运行 |
| agent prompt-injection add | 敌对服务器 | 是（安全） | D-SEC 门拦截 | 拒绝并给出原因 |

**前提是** §6 的死进程检测进 v1，否则无静默-失败致命缺口残留。

### NOT in scope（延后）
- 断开 MCP 连接的自动重连——快速跟进，非 v1。
- 按服务器的工具命名空间/前缀——已决策扁平（D-NS）；以后再议。
- 抽取 `/mcp`//`/model`//`/session` 之外的*其余* REPL 命令——单独 cleanup TODO。
- 精选/已知 MCP 注册表（dream-state）——未来。

### 并行方案
- Lane A（`pig-agent-mcp`，串行）：`McpServerSpec`+`McpStore`+`JsonMcpStore` → `McpManager` 升级。
- Lane B（`pig-agent-config`，独立）：`mcp.agent-management` config 块。
- Lane C（`pig-agent-cli`，在 A 之后）：命令文件拆分 + `/mcp` 接线 + `allowToolDeletion`。
- Lane D（`pig-agent-tools`，在 A 之后）：`McpTool` + D-SEC 门。
顺序：A + B 并行 → 然后 C + D 并行。

### GSTACK 工程评审报告
| 章节 | 发现 | 处置 |
|---|---|---|
| Step 0 范围 | 复杂度阈值触发（4 个小类） | 接受——镜像 JsonModelStore，范围在 CEO HOLD 锁定 |
| 架构 | 3（并发、缺图、yaml 失效） | E1 细锁；补图；一次性导入提示 |
| 代码质量 | 1（ReplCommands 上帝文件） | E2 拆 /mcp+/model+/session |
| 测试 | 已出覆盖计划；安全测试 CRITICAL | 见上测试计划；D-SEC 回归测试强制 |
| 性能 | 无（I/O 在锁外；无 N+1；`list()` 轻量） | — |

结论（工程）：**CLEARED（HOLD SCOPE）**——架构 + 测试已锁。Outside Voice（Codex）未运行（gstack 工具在 Windows 不可用）。代码前卡点：第 0 步 spike + D-SEC 门。

NO UNRESOLVED DECISIONS
