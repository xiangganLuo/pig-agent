## Why

`plugin-system` 已落地统一的 `Plugin.register(ctx)` 入口 + 多发现源（classpath `ServiceLoader` + `plugins/` 目录 jar），但仓库里**还没有任何随发行版内置的插件**——插件能力目前只是一条空跑道。开箱即用需要一批**常用、安全、离线、无凭据**的小工具（时间/时区换算、UUID、Base64、哈希、JSON 校验/美化、epoch↔ISO、随机数/串），它们恰好是 `Plugin` SPI 的第一批「样板 + 实用」内容：既让用户马上有可用工具，又给第三方插件作者一份「怎么写插件」的范本。

关键约束：这批插件不能是一堆散装 `register` 面条代码。要用**设计模式**给它们一个统一骨架（模板方法 + 工厂方法 + SPI 策略 + 注册表），让「加一个插件 = 加一个类 + 一条 service 行」，而不是到处改装配。

## What Changes

- **新模块 `pig-agent-plugin-collection`**：依赖 `pig-agent-plugin`（SPI）+ `pig-agent-tools`（`@Tool` 注解 / `ToolErrors` 契约）。登记进父 POM `<modules>` 与 `<dependencyManagement>`；`pig-agent-cli` 依赖它，使其插件进入运行时 classpath 供 `ServiceLoaderPluginSource` 发现。
- **一批内置插件（6 个）**，每个都在 `META-INF/services/io.pigagent.plugin.Plugin` 声明，经 `PluginContext` 贡献一个或多个 `@Tool` 工具：
  - `TimePlugin` → 当前日期时间、时区换算、epoch↔ISO 互转
  - `UuidPlugin` → 生成 UUID
  - `Base64Plugin` → Base64 编码/解码
  - `HashPlugin` → MD5 / SHA-256 摘要
  - `JsonPlugin` → JSON 美化 / 校验
  - `RandomPlugin` → 随机整数 / 随机字符串
- **设计模式化的公共骨架**（非面向功能）：抽象基类 `AbstractToolPlugin`（**模板方法**固化 `register` 装配骨架 + **工厂方法** `createTools(ctx)` 交由子类提供工具集）、`PluginCatalog`（**注册表/工厂**，插件集单一事实源，供测试与自省）。工具本身是无状态 `@Tool` POJO，经**组合**注入插件，不建工具继承树；每个 `Plugin` 是经 `ServiceLoader` 发现的可互换**策略**。
- **权限分级**：这批工具皆为纯计算（无 shell/网络/写盘），在 `ToolRiskClassifier` 默认表登记为 `READ_ONLY`，从而在 `ask` 模式下无需确认即放行。
- **返回契约**：遵循 `tool-json-contract`——成功返回正常输出，失败返回规范 `{"error":"<reason>"}`（`ToolErrors.message(...)`，凭据安全），绝不抛异常作为模型可见错误路径。

无 **BREAKING**：模块缺席时行为与今天完全一致；模块在场时其插件被自动发现、工具照常受可用性/权限/契约门控叠加。

## Capabilities

### New Capabilities
- `plugin-collection`: 一组随发行版内置的常用、安全、离线插件（时间/时区/epoch、UUID、Base64、哈希、JSON、随机），经统一 `Plugin` SPI 贡献 `@Tool` 工具；用模板方法 + 工厂方法 + SPI 策略 + 注册表组织，「加插件 = 加类 + service 行」；工具纯计算（`READ_ONLY`）、遵循 `{"error"}` 返回契约；模块缺席时零行为变更。

### Modified Capabilities
<!-- 无：不改 plugin-system / tool-autoregister / tool-permissions / tool-json-contract 的契约；仅新增「内置插件集合」这一叠加内容，并在 ToolRiskClassifier 默认表登记纯计算工具的 READ_ONLY 分级。 -->

## Impact

- **代码**：新增模块 `pig-agent-plugin-collection`（`AbstractToolPlugin`、6 个 `*Plugin`、6 个 `*Tool`、`PluginCatalog`、`META-INF/services/io.pigagent.plugin.Plugin`）；父 POM `<modules>`/`<dependencyManagement>` 登记；`pig-agent-cli` 增加依赖；`pig-agent-tools` 的 `ToolRiskClassifier.DEFAULTS` 增补本批工具的 `READ_ONLY` 分级。
- **协作/不改**：`PluginRegistry` 加载路径、`ToolRegistrar` 去重/覆盖、可用性门控、权限 veto、返回契约 + 分发守卫等维度均不变，叠加于本批插件工具之上。
- **测试**：逐工具确定性输入→输出 + 失败路径返回 `{"error"}`；插件经 `ServiceLoaderPluginSource` 发现并通过 `PluginRegistry` + 真实 `Toolkit` 注册其工具；`PluginCatalog` 与 services 文件一致性；`AbstractToolPlugin` 模板骨架经假 `PluginContext` 贡献工具。
- **文档**：`CLAUDE.md` 模块表新增 `pig-agent-plugin-collection` 行，并在插件扩展点补一句「内置插件集合示例」。
