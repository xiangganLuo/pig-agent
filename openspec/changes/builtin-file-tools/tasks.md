## 1. 凭据守卫抽取（纯重构，无行为变化）

- [ ] 1.1 抽取 `io.pigagent.tool.filesystem.CredentialFileGuard`（不可变值对象）：构造入参 `Set<Path> credentialFiles`，对每个文件同记 `toAbsolutePath().normalize()` 与 `toRealPath()` 两形态；`boolean isDenied(String path)` 把请求路径两形态与黑名单比对。逐字节搬自 `FileSystemTools` 现有 `deniedPaths`/`isDenied`/`realPathOrNull`。
- [ ] 1.2 `FileSystemTools` 改用 `CredentialFileGuard`（`readFile`/`writeFile` 行为不变）。
- [ ] 1.3 单测：`CredentialFileGuard` 命中/未命中/`../` 归一/symlink（`toRealPath` 可用时）；`FileSystemTools` 既有测试不回归。

## 2. editFile（挂到 FileSystemTools）

- [ ] 2.1 `FileSystemTools#editFile(path, oldString, newString, replaceAll=false)`（`@Tool`，默认非只读=WRITE）：不存在→`{"error}"`；空编辑（old==new）→error；字面匹配计数（0→未找到 error / >1 且非 replaceAll→歧义 error / 唯一或 replaceAll→替换，`Matcher.quoteReplacement` 防替换串元字符）；改盘前经凭据守卫拒凭据文件；成功返回替换处数摘要；`IOException`→`ToolErrors.message`。
- [ ] 2.2 单测：不存在→error 不创建；唯一匹配精确替换、余部逐字节保留；多处未 replaceAll→error 不改盘；未找到→error 不改盘（幂等安全）；replaceAll 全替换；空编辑→error；凭据文件→access denied 不改盘；`../` 指向凭据文件→拒。

## 3. searchFiles + findFiles（新 FileSearchTools，纯 Java）

- [ ] 3.1 `io.pigagent.tool.filesystem.FileSearchTools`（构造入参凭据文件集合 → 内建 `CredentialFileGuard`）。命名常量：`MAX_MATCHES=200`/`MAX_MATCHES_PER_FILE=20`/`MAX_LINE_LEN=500`/`MAX_FILE_SIZE=5_000_000`/`MAX_RESULTS=500`。
- [ ] 3.2 `searchFiles(pattern, path, filePattern?, ignoreCase?)`（`@Tool(readOnly=true)`）：`Files.walk`+`Pattern.compile` 逐行 `find()`，**无 ProcessBuilder/外部命令**；`filePattern` 可选 glob 限定文件；跳过二进制（NUL 字节）/超大文件/凭据文件；命中格式 `<relpath>:<lineNo>: <trimmed>`；多重封顶达上限即停并标注 `… (truncated)`；`PatternSyntaxException`→`{"error"}`。
- [ ] 3.3 `findFiles(pattern, path)`（`@Tool(readOnly=true)`）：`FileSystems.getDefault().getPathMatcher("glob:"+pattern)` 对 `Files.walk` 相对路径匹配；空模式/非法 glob→`{"error"}`；无匹配→明确空结果文案；`MAX_RESULTS` 封顶。
- [ ] 3.4 单测（真实临时目录夹具）：`searchFiles` 命中+行号、`filePattern` 过滤、`ignoreCase`、非法正则→error、封顶截断、跳过二进制/凭据文件、无外部进程（纯 Java 路径）；`findFiles` `*`/`**`/`{a,b}` 命中、无匹配空提示、空/非法 glob→error、封顶。

## 4. ToolRiskClassifier 登记（仅新增条目）

- [ ] 4.1 `ToolRiskClassifier` 默认表新增：`editFile`→`WRITE`、`searchFiles`→`READ_ONLY`、`findFiles`→`READ_ONLY`（不改查表逻辑）。
- [ ] 4.2 单测：`classify("editFile")==WRITE`、`classify("searchFiles")==READ_ONLY`、`classify("findFiles")==READ_ONLY`；overrides 仍可覆盖。

## 5. SPI 自注册（FileSearchToolsProvider + META-INF）

- [ ] 5.1 `io.pigagent.tool.spi.providers.FileSearchToolsProvider implements ToolProvider`：`create(ctx)` 取 `ctx.workspaceRoot()`，非空→用 `{models.json, mcp.json, *.bak}` 构造 `FileSearchTools`，为空→无黑名单（与 `FileSystemToolsProvider` 对称）。
- [ ] 5.2 `META-INF/services/io.pigagent.tool.spi.ToolProvider` 追加一行 `io.pigagent.tool.spi.providers.FileSearchToolsProvider`。
- [ ] 5.3 单测：`FileSearchToolsProvider.create` 有/无 `workspaceRoot` 两路径；`ToolRegistrar` 经 SPI 发现三个新工具名（`editFile` 随 `FileSystemTools`、`searchFiles`/`findFiles` 随新 provider）。

## 6. ToolContext Builder 重构（T6，纯重构）

- [ ] 6.1 `ToolContext` 加 `builder()` + 内部 `Builder`（逐字段 setter，保留默认语义：`webAllowedHosts` 空列表非 null、三个 `BooleanSupplier` 默认 `()->false`、其余可空）；保留或收敛既有构造器（以不破坏编译为准，优先私有化为单一全参构造 + Builder）。
- [ ] 6.2 全部既有调用点改用 Builder（`AgentBootstrap` 及各测试；机械替换）。
- [ ] 6.3 单测：`ToolContext.builder()....build()` 各 getter 与旧全参构造等价（默认值一致），锁「零行为变化」。

## 7. 验收

- [ ] 7.1 `mvn -q -pl pig-agent-tools -am test` 单测全绿（含新增用例）；既有 `FileSystemTools`/`ToolRiskClassifier`/`ToolRegistrar`/`ToolContext` 相关测试不回归。
- [ ] 7.2 `mvn -q -pl pig-agent-cli -am compile` 绿（Builder 重构后调用点全通过）。
- [ ] 7.3 `CLAUDE.md` 工具段增补一句富文件工具（`editFile`/`searchFiles`/`findFiles`，纯 Java、宿主无关）说明。
