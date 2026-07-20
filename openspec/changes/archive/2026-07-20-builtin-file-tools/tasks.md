## 1. 凭据守卫抽取（纯重构，无行为变化）

- [x] 1.1 抽取 `io.pigagent.tool.filesystem.CredentialFileGuard`（不可变值对象）：构造入参 `Set<Path> credentialFiles`，对每个文件同记 `toAbsolutePath().normalize()` 与 `toRealPath()` 两形态；`boolean isDenied(String path)`/`isDenied(Path)` 把请求路径两形态与黑名单比对。逐字节搬自 `FileSystemTools` 现有 `deniedPaths`/`isDenied`/`realPathOrNull`。
- [x] 1.2 `FileSystemTools` 改用 `CredentialFileGuard`（`readFile`/`writeFile` 行为不变）。
- [x] 1.3 单测：`CredentialFileGuardTest`（命中/未命中/`../` 归一/文件缺失/空守卫）；`FileSystemToolsTest` 既有 7 例不回归。

## 2. editFile（挂到 FileSystemTools）

- [x] 2.1 `FileSystemTools#editFile(path, old_string, new_string, replace_all)`（`@Tool`，默认非只读=WRITE）：不存在→`{"error"}`；空 oldString→error；空编辑（old==new）→error；字面匹配计数（0→未找到 error / >1 且非 replace_all→歧义 error / 唯一或 replace_all→替换，全走 `String.indexOf`/`substring`/`replace` 字面语义，无正则）；改盘前经凭据守卫拒凭据文件；成功返回替换处数摘要；`IOException`→`ToolErrors.message`。
- [x] 2.2 单测（`FileSystemToolsEditTest` 9 例）：不存在→error 不创建；唯一匹配精确替换、余部逐字节保留；多处未 replace_all→error 不改盘；未找到→error 不改盘（幂等安全）；replace_all 全替换；空编辑→error；空 oldString→error；凭据文件→access denied 不改盘；字面（非正则）替换。

## 3. searchFiles + findFiles（新 FileSearchTools，纯 Java）

- [x] 3.1 `io.pigagent.tool.filesystem.FileSearchTools`（构造入参凭据文件集合 → 内建 `CredentialFileGuard`）。命名常量：`MAX_MATCHES=200`/`MAX_MATCHES_PER_FILE=20`/`MAX_LINE_LEN=500`/`MAX_FILE_SIZE=5_000_000`/`MAX_RESULTS=500`。
- [x] 3.2 `searchFiles(pattern, path, file_pattern?, ignore_case?)`（`@Tool(readOnly=true)`）：`Files.walk`+`Pattern.compile` 逐行 `find()`，**无 ProcessBuilder/外部命令**；`file_pattern` 可选 glob 限定文件名；跳过二进制（NUL 字节）/超大文件/凭据文件；命中格式 `<relpath>:<lineNo>: <trimmed>`；多重封顶达上限即停并标注 `… (truncated)`；`PatternSyntaxException`→`{"error"}`。
- [x] 3.3 `findFiles(pattern, path)`（`@Tool(readOnly=true)`）：`FileSystems.getDefault().getPathMatcher("glob:"+pattern)` 对 `Files.walk` 相对路径匹配；空模式/非法 glob→`{"error"}`；无匹配→明确空结果文案；`MAX_RESULTS` 封顶；跳过凭据文件。
- [x] 3.4 单测（`FileSearchToolsTest` 14 例，真实临时目录夹具）：`searchFiles` 命中+行号、`file_pattern` 过滤、`ignore_case`、非法正则→error、非目录→error、封顶截断、跳过二进制/凭据文件；`findFiles` `**`/顶层 glob 命中、无匹配空提示、空模式→error、跳过凭据文件。

## 4. ToolRiskClassifier 登记（仅新增条目）

- [x] 4.1 `ToolRiskClassifier` 默认表新增：`editFile`→`WRITE`、`searchFiles`→`READ_ONLY`、`findFiles`→`READ_ONLY`（不改查表逻辑）。
- [x] 4.2 单测（`ToolRiskClassifierTest#richFileToolsClassified`）：`editFile`==WRITE、`searchFiles`/`findFiles`==READ_ONLY。

## 5. SPI 自注册（FileSearchToolsProvider + META-INF）

- [x] 5.1 `io.pigagent.tool.spi.providers.FileSearchToolsProvider implements ToolProvider`：`create(ctx)` 取 `ctx.workspaceRoot()`，非空→用 `{models.json, mcp.json, *.bak}` 构造 `FileSearchTools`，为空/`ctx==null`→无黑名单（与 `FileSystemToolsProvider` 对称）。
- [x] 5.2 `META-INF/services/io.pigagent.tool.spi.ToolProvider` 追加一行 `io.pigagent.tool.spi.providers.FileSearchToolsProvider`。
- [x] 5.3 单测：`FileSearchToolsProviderTest`（有/无 `workspaceRoot`/`null` 三路径）；`ToolRegistrarTest#registerAll_discoversFullCoreToolSet` 扩充断言，经 SPI 发现 `editFile`（随 `FileSystemTools`）+ `searchFiles`/`findFiles`（随新 provider）。

## 6. ToolContext Builder 重构（T6，纯重构）

- [x] 6.1 `ToolContext` 加 `builder()` + 内部 `Builder`（逐字段 setter，`build()` 委托既有全参构造器 → 保留默认语义：`webAllowedHosts` 空列表非 null、三个 `BooleanSupplier` 默认 `()->false`、其余可空）。
- [x] 6.2 `AgentBootstrap` 的 `ToolContext` 构造点改用 Builder（内部友好度提升点）；既有 6 个便利构造器**保留为委托**（避免跨模块 `pig-agent-plugin`/`pig-agent-plugin-builtin` 测试调用点的无谓改动，零行为变化）——新工具依赖此后走 Builder setter，不再新增 telescoping 重载。
- [x] 6.3 单测（`ToolContextBuilderTest` 2 例）：空 builder 与空构造等价（默认值一致）、全字段 builder 与全参构造等价，锁「零行为变化」。

## 7. 验收

- [x] 7.1 `mvn -pl pig-agent-tools -am test` 全绿：389 tests, 0 failures, 0 errors, 3 skipped（既有 IT）；新增 `CredentialFileGuardTest`(4)/`FileSystemToolsEditTest`(9)/`FileSearchToolsTest`(14)/`FileSearchToolsProviderTest`(3)/`ToolContextBuilderTest`(2)；`FileSystemToolsTest`/`ToolRiskClassifierTest`/`ToolRegistrarTest` 不回归。
- [x] 7.2 `mvn -pl pig-agent-cli -am compile` BUILD SUCCESS（Builder 重构后 `AgentBootstrap` 通过）。
- [x] 7.3 `CLAUDE.md` `pig-agent-tools` 键型段增补 `FileSystemTools`(含 `editFile`+`CredentialFileGuard`) 与 `FileSearchTools`(`searchFiles`/`findFiles`，纯 Java、宿主无关) 说明。
