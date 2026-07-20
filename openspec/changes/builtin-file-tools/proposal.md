## Why

pig-agent 是编码 agent，但工具层缺了「富文件操作」：编辑只有整文件覆盖的 `writeFile`（无精确串替）、没有内容搜索、没有文件名查找。结果模型被迫 `executeCommand` 出去调 `grep`/`find`/`sed` —— 这在 **PowerShell/Windows 宿主上直接失效**（无 grep/find），且每次搜索都白走一遍命令沙箱 + 权限判定，既慢又噪。把这几件事收回工具层，做成**宿主无关的纯 Java 内置文件工具**，是内核路线图 Wave-1 T1 的目标。

## What Changes

- **`editFile`（新，WRITE）**：对**已存在**文件做定位**串替**（不是整文件覆盖）。默认要求 `oldString` 唯一匹配（0 或多处 → 拒绝、不改盘），`replaceAll` 才替换全部；空编辑（old==new）拒绝。区别于 `writeFile` 的创建/覆盖语义。
- **`searchFiles`（新，READ_ONLY）**：跨目录树对文件**内容**做字面/正则搜索，**完全用 Java 实现**（`java.util.regex` + `Files.walk`），**绝不内部 shell 出去** —— 否则又回到 PowerShell 无 grep 的原点。输出有上界（最大命中/最大文件/单行长度），非法正则返回 `{"error"}`。
- **`findFiles`（新，READ_ONLY）**：跨目录树按 **glob** 匹配文件名（纯 Java `PathMatcher` glob 语法），返回有界的路径列表。
- **`ToolRiskClassifier` 登记新工具名**：`editFile`=WRITE，`searchFiles`/`findFiles`=READ_ONLY；且后两者的 `@Tool` 方法声明 `readOnly=true`，让 plan/EXPLORE 只读模式放行搜索。
- **SPI 自注册**：新增一个 `io.pigagent.tool.spi.ToolProvider` 实现（`FileSearchToolsProvider`，承载 `searchFiles`+`findFiles`）+ 一行 `META-INF/services` 条目，经 `ToolRegistrar.registerAll` 自动拾取，`AgentBootstrap` 装配代码**零改动**；`editFile` 作为方法挂到既有 `FileSystemTools`，随既有 `FileSystemToolsProvider` 自动注册。
- **复用既有安全边界**：三工具复用 `FileSystemTools` 的**凭据文件黑名单**（拒 `models.json`/`mcp.json`(+`.bak`)）+ **real-path 归一**（`toRealPath` 防 `../`/symlink 逃逸）+ **`{"error"}` 返回契约**（`ToolErrors.message`，凭据脱敏）。
- **折入 T6（纯重构，无行为变化）**：`ToolContext` 现有 6 个电话簿式 telescoping 构造器已是坏味道且随每个新工具增长；本 change 触碰 `ToolContext` 使用面，顺带把它重构为 **Builder**（更新全部既有调用点，行为逐字节不变），降低未来扩展摩擦。

无 **BREAKING**：纯新增工具 + 纯重构。既有工具（`readFile`/`writeFile`/`listDirectory`/…）签名、风险分级、权限/可用性/契约门控均不变。

## Capabilities

### New Capabilities
- `builtin-file-tools`：宿主无关的富内置文件工具（精确串替 `editFile`、纯 Java 内容/正则搜索 `searchFiles`、glob 文件名查找 `findFiles`），把「搜索/编辑必须 shell 出去」从工具层收回，在无 POSIX 工具的宿主（PowerShell/Windows）上同样可用，并省去每次 shell 搜索白走沙箱+权限的开销；复用既有凭据黑名单 + real-path 归一 + `{"error"}` 契约，经既有 SPI 自注册。

### Modified Capabilities
<!-- 不修改任何既有能力的 REQUIREMENTS：
     - `tools-core`（pig-agent-tools 只承载高可用零配置核心工具）：新工具正是高可用、零外部配置、纯 Java 无重依赖，落在其既有约束内，是能力内的加法而非要求变更。
     - `tool-autoregister` / `tool-json-contract` / `tool-permissions` / `tool-sandbox`：本 change 复用其现有契约（SPI 自注册、{"error"} 契约、风险分级、凭据黑名单/real-path），未改其任何 SHALL。
     - `ToolContext` builder 重构为纯实现细节（无 spec 级行为变化），落 design + tasks，不作为 spec 要求。 -->

## Impact

- **代码（`pig-agent-tools`）**：
  - 新增包内类：`io.pigagent.tool.filesystem.FileSearchTools`（`searchFiles`+`findFiles`，纯 Java）；抽取 `io.pigagent.tool.filesystem.CredentialFileGuard`（凭据黑名单 + real-path 归一的不可变值对象，`FileSystemTools` 与 `FileSearchTools` 共用，DRY）。
  - 改 `FileSystemTools`：新增 `editFile` 方法（复用抽取后的守卫）。
  - 改 `permission/ToolRiskClassifier`：默认表**新增** `editFile`/`searchFiles`/`findFiles` 三条（仅新增条目，不改查表逻辑）。
  - 新增 `spi/providers/FileSearchToolsProvider` + `META-INF/services/io.pigagent.tool.spi.ToolProvider` 增一行。
  - 改 `spi/ToolContext`：6 个 telescoping 构造器 → Builder（纯重构）。
- **调用点**：`ToolContext` 的构造调用点（`AgentBootstrap` 及各测试）改用 Builder（编译期机械替换，行为不变）。
- **不改**：`AgentBootstrap` 的工具装配流程、既有工具行为、`PigAgentConfig`（无新配置块）、任何 MCP/权限/沙箱语义。
- **测试**（离线）：`editFile` 唯一/多处/未找到/replaceAll/空编辑/凭据文件/`../` 逃逸；`searchFiles` 命中+行号、无外部进程、封顶截断、非法正则、跳过凭据文件/二进制；`findFiles` glob 命中/无匹配/封顶；`ToolRiskClassifier` 认得三个新名 + readOnly；`FileSearchToolsProvider` 经 SPI 被发现；`ToolContext` builder 与旧构造等价。
- **文档**：`CLAUDE.md` 工具段增补一句富文件工具说明。
