# builtin-file-tools Specification

## Purpose
TBD - created by archiving change builtin-file-tools. Update Purpose after archive.
## Requirements
### Requirement: 精确串替编辑 editFile

系统 SHALL 提供内置工具 `editFile`，对**已存在**文件做定位串替（**不是**整文件覆盖）。目标文件不存在时 MUST 返回规范化 `{"error"}` 且 MUST NOT 创建或修改任何文件（区别于 `writeFile` 的创建/覆盖语义）。`editFile` MUST 把 `oldString` 按**字面**替换为 `newString`；默认（未 `replaceAll`）MUST 要求 `oldString` 在文件中**恰好唯一匹配**——0 次或多于 1 次匹配 MUST 在改盘前返回 `{"error"}` 且 MUST NOT 修改文件；显式 `replaceAll` 时 MUST 替换全部匹配。空编辑（`oldString` 等于 `newString`）MUST 被拒。`editFile` MUST 被 `ToolRiskClassifier` 分级为 `WRITE`。

#### Scenario: 目标文件不存在则拒绝（不创建）
- **WHEN** 对一个不存在的路径调用 `editFile`
- **THEN** 返回 `{"error"}`，且不创建该文件、不改动任何文件（与 `writeFile` 的创建语义区分）

#### Scenario: 唯一匹配时精确替换
- **WHEN** `oldString` 在已存在文件中恰好出现一次
- **THEN** 该处被替换为 `newString`，文件其余内容逐字节保留

#### Scenario: 多处匹配且未 replaceAll 则拒绝且不改盘
- **WHEN** `oldString` 出现多于一次且未传 `replaceAll`
- **THEN** 返回 `{"error"}`（歧义），且文件未被修改

#### Scenario: 未找到则拒绝且不改盘（幂等安全）
- **WHEN** `oldString` 在文件中不存在（含同一 edit 已被应用过、再次调用）
- **THEN** 返回 `{"error"}`，且文件未被修改（重复应用是安全的非改动错误，绝不二次替换或破坏文件）

#### Scenario: replaceAll 替换全部匹配
- **WHEN** 传 `replaceAll` 且 `oldString` 出现 N 次（N≥1）
- **THEN** 全部 N 处被替换为 `newString`

#### Scenario: 空编辑被拒
- **WHEN** `oldString` 等于 `newString`
- **THEN** 返回 `{"error"}`（无意义编辑），文件未被修改

#### Scenario: editFile 分级为写
- **WHEN** 对工具名 `editFile` 做风险分级
- **THEN** 结果为 `WRITE`

### Requirement: 纯 Java 内容/正则搜索 searchFiles

系统 SHALL 提供内置工具 `searchFiles`：跨目录树对文件**内容**做字面/正则匹配。其实现 MUST **完全用 Java**（`java.util.regex` + Java 文件遍历），MUST NOT 内部 shell 出去或调用任何外部命令（`grep`/`find`/`rg` 等），以保证在无 POSIX 搜索命令的宿主（PowerShell/Windows）上同样可用。输出 MUST 有上界（限制总命中数、单文件命中数、单行长度、被扫文件大小），超界时以截断标记提示而非无界返回。非法正则 MUST 返回规范化 `{"error"}` 而非抛出异常。`searchFiles` 的 `@Tool` 方法 MUST 声明 `readOnly=true` 且 MUST 被分级为 `READ_ONLY`（使 plan/EXPLORE 只读模式放行）。

#### Scenario: 内容命中返回带定位的结果
- **WHEN** 以一个能命中的模式在目录树上调用 `searchFiles`
- **THEN** 返回匹配所在的文件路径与行号及匹配行文本（受输出上界约束）

#### Scenario: 完全纯 Java，无外部进程
- **WHEN** 在无 `grep`/`find` 的宿主上执行 `searchFiles`
- **THEN** 搜索照常完成（不依赖任何外部命令，纯 Java 遍历 + 正则）

#### Scenario: 输出封顶截断
- **WHEN** 命中数或输出量超过编译期上界
- **THEN** 返回被截断的有界结果并附截断标记，不无界返回

#### Scenario: 非法正则返回 error
- **WHEN** 传入语法非法的正则
- **THEN** 返回 `{"error"}`（不抛异常、不中断回合）

#### Scenario: searchFiles 只读且分级为只读
- **WHEN** 对工具名 `searchFiles` 做风险分级
- **THEN** 结果为 `READ_ONLY`（且其 `@Tool` 方法声明 `readOnly=true`）

### Requirement: glob 文件名查找 findFiles

系统 SHALL 提供内置工具 `findFiles`：跨目录树按 **glob** 模式匹配文件名（纯 Java glob 语义，支持 `*`/`**`/`?`/`[...]`/`{a,b}`），返回匹配的文件路径。输出 MUST 有上界。空模式或非法 glob MUST 返回规范化 `{"error"}`；无任何匹配 MUST 返回明确的空结果提示（无匹配是正常结果，而非错误）。`findFiles` 的 `@Tool` 方法 MUST 声明 `readOnly=true` 且 MUST 被分级为 `READ_ONLY`。

#### Scenario: glob 命中返回路径列表
- **WHEN** 以能命中的 glob（如 `**/*.java`）在目录树上调用 `findFiles`
- **THEN** 返回匹配的文件路径列表（受输出上界约束）

#### Scenario: 无匹配返回空结果提示
- **WHEN** glob 语法合法但无任何文件匹配
- **THEN** 返回明确的空结果提示（不返回 `{"error"}`）

#### Scenario: 空/非法 glob 返回 error
- **WHEN** 传入空模式或语法非法的 glob
- **THEN** 返回 `{"error"}`

#### Scenario: findFiles 只读且分级为只读
- **WHEN** 对工具名 `findFiles` 做风险分级
- **THEN** 结果为 `READ_ONLY`（且其 `@Tool` 方法声明 `readOnly=true`）

### Requirement: 复用凭据文件黑名单与路径逃逸防护

三个工具 MUST 复用既有的凭据文件黑名单：`editFile` MUST NOT 编辑工作区凭据文件（`models.json`/`mcp.json` 及其 `.bak` 兄弟）；`searchFiles`/`findFiles` MUST NOT 在结果中返回这些凭据文件的内容、也 MUST NOT 将其列为命中。路径匹配 MUST 归一到解析后的 real-path（`toRealPath`，回退 `toAbsolutePath().normalize()`），以拦截 `../` 遍历与指向凭据文件的 symlink 逃逸。任何被拒访问 MUST 以规范化 `{"error"}` 呈现，MUST NOT 抛出异常，MUST NOT 回显凭据值或内部绝对路径细节。

#### Scenario: editFile 拒编辑凭据文件
- **WHEN** `editFile` 的目标解析到某个凭据文件
- **THEN** 返回 `{"error"}`（access denied），且文件未被修改

#### Scenario: 经 ../ 或 symlink 指向凭据文件仍被拒
- **WHEN** 请求路径经 `../` 或 symlink 解析到某凭据文件
- **THEN** 经 real-path 归一后同样被拒（不因路径形态差异绕过黑名单）

#### Scenario: 搜索/查找不泄露凭据文件
- **WHEN** `searchFiles`/`findFiles` 遍历到黑名单中的凭据文件
- **THEN** 该文件不进入结果（内容不被返回、路径不被列为命中）

### Requirement: 经既有 SPI 自动注册且遵循返回契约

三个工具 MUST 经既有 `ToolProvider` SPI + `META-INF/services` 声明被 `ToolRegistrar.registerAll` 自动发现并注册进 `Toolkit`——新增 `searchFiles`/`findFiles` 由一个新 `ToolProvider` 承载（新增一行服务声明），`editFile` 随承载它的既有文件工具 provider 一并注册——装配代码（`AgentBootstrap`）MUST 零改动。三个工具的返回值 MUST 遵循统一契约：成功返回其正常输出，失败返回规范化 `{"error":"<reason>"}`（经 `ToolErrors.message`，凭据脱敏），MUST NOT 把异常作为模型可见的错误路径。

#### Scenario: 经 SPI 自动注册，装配零改动
- **WHEN** `AgentBootstrap` 经 `ToolRegistrar.registerAll` 装配工具集
- **THEN** `editFile`/`searchFiles`/`findFiles` 经既有 SPI + `META-INF/services` 被注册进 `Toolkit`，无需编辑装配代码

#### Scenario: 失败遵循 {"error"} 契约
- **WHEN** 任一工具遇到错误（不存在/非法模式/被拒/IO 异常）
- **THEN** 返回规范化 `{"error":"<reason>"}`（凭据脱敏），不抛异常中断回合

