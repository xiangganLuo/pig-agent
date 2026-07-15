## Context

`plugin-system` 提供了插件运行时（`Plugin.register(ctx)` + `ServiceLoaderPluginSource`/`DirectoryPluginSource` + `PluginRegistry`），但仓库没有任何随发行版内置的插件。本 spec 交付第一批**常用、安全、离线、无凭据**插件，同时作为「怎样写插件」的参考实现。核心要求：用**设计模式**组织，而非面向功能的散装 `register` 代码。

## Goals / Non-Goals

**Goals:**
- 交付一批（约 5–7 个）常用、纯计算、无凭据、离线的插件，覆盖：日期时间 & 时区换算、UUID、Base64、哈希（md5/sha-256）、JSON 美化/校验、epoch↔ISO 互转、随机数/串。
- 用清晰的设计模式给这批插件统一骨架：**模板方法** + **工厂方法** + **SPI 策略** + **注册表**；组合优先于继承。
- 每个插件在 `META-INF/services/io.pigagent.plugin.Plugin` 声明，经既有 `PluginContext` 贡献 `@Tool` 工具，复用既有 `ToolRegistrar` 去重/门控/契约，不另起炉灶。
- 工具皆为 `READ_ONLY`（纯计算），在 `ask` 模式下干净放行。
- 遵循 `tool-json-contract`：失败返回规范 `{"error"}`，不抛异常，凭据安全。
- 模块缺席时零行为变更（新模块，非改造）。

**Non-Goals:**
- 不改 `plugin-system` / `tool-autoregister` / `tool-permissions` / `tool-availability` / `tool-json-contract` 的既有契约。
- 不做任何联网/凭据/文件写/shell 的工具（安全边界：纯计算）。
- 不引入插件热插拔、依赖解析、版本协商（沿用 `plugin-system` 的 Non-Goals）。
- 不贡献 Hook（本批插件只贡献工具；Hook 注册面留给需要它的插件）。

## Decisions

- **D1 — 模板方法 + 工厂方法（`AbstractToolPlugin`）**：抽象基类 `implements Plugin`，把 `register(PluginContext)` 声明为 **final** 骨架——取 `ctx.toolContext()` → 调工厂方法 `createTools(ToolContext)` → `ctx.addTools(...)`。子类只实现 `protected abstract List<Object> createTools(ToolContext)`（工厂方法）并经构造器传入稳定 `id`。理由：装配骨架只写一次、不可被子类改写（模板方法固定不变式），每个插件只关心「我造哪些工具」（工厂方法），消灭重复的 `register` 面条代码。
- **D2 — SPI 策略（每个 `Plugin` 一个可互换策略）**：6 个具体插件各是一个经 `ServiceLoader` 发现的策略，在 services 文件按 FQCN 声明。加一个插件 = 加一个类 + 一条 service 行，不改任何中央 switch/装配。复用既有 `ServiceLoaderPluginSource` → `PluginRegistry` → `ToolRegistrar` 路径。
- **D3 — 注册表/工厂（`PluginCatalog`）**：`PluginCatalog.all()` 返回本模块全部插件实例，作为插件集的**单一事实源**，供测试与程序化自省。services 文件与 `PluginCatalog` 是「运行时发现」与「程序化枚举」两条视角，故意各存一份；一致性由单测守卫（两者 id 集合必须相等），防止「加了类忘了加 service 行」这类漂移。
- **D4 — 组合优先于继承（工具是无状态 POJO）**：`@Tool` 工具（`TimeTool`/`UuidTool`/`Base64Tool`/`HashTool`/`JsonTool`/`RandomTool`）是无状态纯计算类，经组合被插件的 `createTools` 实例化注入，**不建工具继承树**。工具方法内自包含、无共享可变状态，天然线程安全、易单测。
- **D5 — 插件粒度按领域分组**：按能力领域切 6 个插件（time / uuid / base64 / hash / json / random），每个插件一个工具类、类内多个 `@Tool` 方法。既满足「多个插件各自声明」的要求，又让相关能力内聚、类保持小（<800 行/函数<50 行）。
- **D6 — 权限分级登记为 READ_ONLY**：本批工具皆纯计算（无 shell/网络/写盘），在 `pig-agent-tools` 的 `ToolRiskClassifier.DEFAULTS` 增补它们的方法名 → `READ_ONLY`，从而 `PermissionPolicy` 在所有模式下放行（`ask` 无需确认）。这与既有 `webSearch`/`readFile` 等在同表登记的风格一致（字符串键，无编译期反向依赖）。未登记时会 fail-safe 落到 `EXEC`（`ask` 下需确认），功能仍可用只是体验差——登记是为「干净放行」。
- **D7 — 返回契约与凭据安全**：每个工具成功返回正常文本/JSON；失败（非法时区、非法 Base64、非法 JSON、非法范围等）返回 `ToolErrors.message(reason)` 规范 `{"error"}`，绝不抛异常作为模型可见错误路径（分发守卫仍是兜底网，不是主契约）。`jsonValidate` 的「无效」是正常业务输出（`{"valid":false,...}`），非错误。
- **D8 — JSON 用 Jackson，不手搓**：`JsonTool` 用父 POM 已管理的 `jackson-databind`（全仓通用、久经考验）做校验/美化，避免手写易错的 JSON 解析（DRY / 复用胜过重造）。
- **D9 — 模块落位与依赖方向**：`pig-agent-plugin-collection` 依赖 `pig-agent-plugin`（传递带 tools + core + agentscope）+ 显式 `jackson-databind`。`pig-agent-cli` 依赖本模块（仅为把插件放上运行时 classpath 供发现，无编译期直用）。无环。

## Risks / Trade-offs

- **R1 — `ToolRiskClassifier` 与集合工具名的字符串耦合**：把集合工具名登记进 `pig-agent-tools` 的默认表，使 tools 模块「知道」集合工具名（仅字符串，无编译依赖）。→ 与既有 `webSearch`/MCP 工具名同表登记的现状一致，可接受；未登记也仅退化为 `EXEC`（fail-safe，功能不丢）。
- **R2 — Catalog 与 services 文件双份易漂移**：两处各存插件清单可能不同步。→ D3 的一致性单测强制两者 id 集合相等，漂移即红。
- **R3 — 时区/时间解析的边界**：非法/未知时区、歧义 ISO 串。→ 统一走 `try/catch` → `{"error"}`；时区用 `ZoneId.of`（IANA），epoch 显式区分 `seconds|millis`，避免猜测。
- **R4 — 随机数「安全性」误用**：`RandomTool` 是通用随机（`ThreadLocalRandom`），非密码学安全。→ 工具描述明确「非密码学用途」，避免被当作密钥生成器；仍为纯计算 `READ_ONLY`。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 模板方法 + 工厂方法公共骨架 | D1；task 2.x；`AbstractToolPlugin`（`register` final + `createTools` 抽象） | 已实现 |
| SPI 策略：加插件=加类+service 行 | D2；task 2.x/4.x；6 个 `*Plugin` + services 文件 | 已实现 |
| 注册表/工厂单一事实源 + 一致性守卫 | D3；task 2.x/5.x；`PluginCatalog` + 一致性单测 | 已实现 |
| 组合优先于继承（无状态工具 POJO） | D4；task 3.x；6 个 `*Tool` | 已实现 |
| 一批常用离线无凭据工具（≥5–7） | D5；task 3.x；time/uuid/base64/hash/json/random | 已实现 |
| 纯计算工具 READ_ONLY 干净放行 | D6；task 3.5；`ToolRiskClassifier.DEFAULTS` 增补 | 已实现 |
| 失败返回 `{"error"}`、凭据安全 | D7；task 3.x；`ToolErrors.message` | 已实现 |
| JSON 复用 Jackson 不手搓 | D8；task 3.x；`JsonTool` + `jackson-databind` 依赖 | 已实现 |
| 模块缺席零行为变更（新模块叠加） | proposal；task 5.x（发现/注册端到端单测） | 已实现 |
| 装配接入（cli 依赖 + 父 POM 登记） | D9；task 1.x/4.x | 已实现 |
