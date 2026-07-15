## 1. 模块骨架与装配登记

- [x] 1.1 新建 `pig-agent-plugin-collection` 模块（依赖 `pig-agent-plugin` 传递带 tools + core + agentscope；显式 `jackson-databind`），登记进父 POM `<modules>` 与 `<dependencyManagement>`。
- [x] 1.2 `pig-agent-cli` 增加对 `pig-agent-plugin-collection` 的依赖（仅为运行时 classpath 发现）。

## 2. 公共骨架（设计模式）

- [x] 2.1 定义 `AbstractToolPlugin implements Plugin`：`register(PluginContext)` **final** 骨架（模板方法）+ 抽象 `createTools(ToolContext)`（工厂方法）+ 构造器传入稳定 `id`。
- [x] 2.2 定义 `PluginCatalog`（注册表/工厂）：`all()` 返回本模块全部插件实例，单一事实源。
- [x] 2.3 单测：`AbstractToolPlugin` 经假 `PluginContext` 把 `createTools` 的工具贡献进去、`id()` 稳定；`PluginCatalog.all()` 非空且 id 唯一。

## 3. 内置工具（纯计算，READ_ONLY，{"error"} 契约）

- [x] 3.1 `TimeTool`：`currentDateTime(timezone?)`、`convertTimezone(datetime,from,to)`、`epochToIso(epoch,unit,timezone?)`、`isoToEpoch(iso)`；非法时区/串 → `{"error"}`。单测：确定性换算 + 错误路径。
- [x] 3.2 `UuidTool`：`generateUuid(count?)` 返回随机 UUID（count 越界 → `{"error"}`）。单测：格式、数量、错误路径。
- [x] 3.3 `Base64Tool`：`base64Encode(text)`、`base64Decode(text)`；非法 Base64 → `{"error"}`。单测：往返 + 错误路径。
- [x] 3.4 `HashTool`：`md5Hash(text)`、`sha256Hash(text)` 返回十六进制摘要。单测：已知向量（空串/"abc"）。
- [x] 3.5 `JsonTool`（Jackson）：`jsonPrettyPrint(json)` 美化、`jsonValidate(json)` 返回 `{"valid":true|false,...}`；美化非法 JSON → `{"error"}`。单测：美化/校验 + 错误路径。
- [x] 3.6 `RandomTool`：`randomNumber(min,max)`、`randomString(length,charset?)`；min>max / length 越界 → `{"error"}`。单测：区间/长度/错误路径。
- [x] 3.7 6 个 `*Plugin` 子类（`TimePlugin`/`UuidPlugin`/`Base64Plugin`/`HashPlugin`/`JsonPlugin`/`RandomPlugin`）：各 `super("collection:<domain>")` + `createTools` 造对应工具。
- [x] 3.8 `pig-agent-tools` 的 `ToolRiskClassifier.DEFAULTS` 增补本批工具方法名 → `READ_ONLY`。单测（tools 模块）：新名字被分类为 READ_ONLY。

## 4. SPI 声明

- [x] 4.1 `META-INF/services/io.pigagent.plugin.Plugin` 声明 6 个具体插件 FQCN。

## 5. 发现/注册端到端 + 验收

- [x] 5.1 单测：经 `ServiceLoaderPluginSource` + `PluginRegistry` + 真实 `Toolkit` 发现集合插件并注册其全部工具（工具名齐全）。
- [x] 5.2 单测：`PluginCatalog.all()` 的 id 集合与 services 文件声明的插件 id 集合一致（防漂移）。
- [x] 5.3 `mvn -q test` 单线程绿（读 surefire XML 计数）；既有 plugin-system/权限/契约单测回归不破。
- [x] 5.4 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 5.5 `CLAUDE.md` 模块表新增 `pig-agent-plugin-collection` 行 + 插件扩展点补「内置插件集合示例」。
