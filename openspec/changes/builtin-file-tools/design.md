## Context

工具在 `AgentBootstrap.build` 装配：`ToolRegistrar.registerAll` 经 `ServiceLoader<ToolProvider>` + `META-INF/services` 自动发现内置工具，每个 provider 从 `ToolContext` 取依赖并 `new` 出一个带 `@Tool` 方法的实例。`FileSystemTools` 今天提供 `readFile`/`writeFile`/`listDirectory`，并持有一份**凭据文件黑名单** `deniedPaths`（`models.json`/`mcp.json`(+`.bak`)）：`readFile`/`writeFile` 前经 `isDenied(path)` 拦截，匹配在 **real-path 归一**（`toRealPath`，回退 `toAbsolutePath().normalize()`）上做，兼记归一态与 real-path 两种形态以拦 `../`/symlink。失败一律经 `ToolErrors.message(...)` 返回规范化 `{"error":"<reason>"}`（凭据脱敏），绝不抛异常。

本 change 在同一包 `io.pigagent.tool.filesystem` 内新增三个宿主无关工具，复用上述安全边界与返回契约，经既有 SPI 自注册，不动装配流程。

## Goals / Non-Goals

**Goals:**
- 三个纯 Java 富文件工具：精确串替 `editFile`、内容/正则搜索 `searchFiles`、glob 文件名查找 `findFiles`；在 PowerShell/Windows 等无 grep/find 的宿主上同样可用。
- `searchFiles`/`findFiles` **绝不内部 shell 出去**（否则回到「PowerShell 无 grep」原点），且输出**有界**（不撑爆上下文）。
- 复用而非重造安全边界：凭据黑名单 + real-path 归一 + `{"error"}` 契约。
- 设计模式表达：抽取 `CredentialFileGuard` 值对象消除守卫重复（DRY）；经既有 SPI 自注册（无装配改动）。
- 折入 T6：`ToolContext` telescoping 构造器 → Builder，纯重构、零行为变化。

**Non-Goals:**
- 不做基于索引的全文检索 / 相关性排序（`hybrid-memory-search` 是记忆语料的事，这里是工作区文件的**确定性**字面/正则/glob 匹配）。
- 不做整目录批量编辑 / 交互式 diff 预览 / 多文件事务（`editFile` 一次一处/一文件）。
- 不引入新配置块：输出上界先用**编译期命名常量**（YAGNI），日后需要再抬到 `application.yaml`（见 R3）。
- 不改 `ToolRiskClassifier` 的查表逻辑（那是 Tool-OS T4 命名空间感知改造的范畴，见「交叉依赖」）；本 change 只**新增**目录条目。
- 不做工作区沙箱：延续 `FileSystemTools` 的**凭据文件黑名单**取舍——这是编码 agent，任意项目文件仍可读/搜/查，只封凭据文件。

## Decisions

- **D1 — `editFile` 替换语义与幂等。** 签名 `editFile(path, oldString, newString, replaceAll=false)`。
  - **仅编辑已存在文件**：目标不存在 → `{"error":"file not found: <path>"}`，不创建、不改盘。这是与 `writeFile`（创建/覆盖）的关键分工——`editFile` 是「改已有」，`writeFile` 是「写新的/整篇覆盖」。
  - **默认唯一匹配**：`oldString` 在文件中出现次数必须恰为 1，否则改盘前拒绝：0 次 → `{"error":"oldString not found"}`；>1 次 → `{"error":"oldString is ambiguous (N matches); pass replaceAll or a more specific string"}`。唯一性保护避免「本想改一处却误伤多处」，也逼模型给出足够上下文的锚点（对齐 CC `Edit` 的 must-be-unique 语义）。
  - **replaceAll**：显式 `true` 时替换**全部**匹配（N 处），返回替换处数。
  - **空编辑拒绝**：`oldString.equals(newString)` → `{"error":"no-op edit (oldString equals newString)"}`（含空 `oldString`）。
  - **幂等取舍（关键）**：`editFile` 天然**非幂等**——同一 edit 成功后再跑，`oldString` 已不在 → 落到「未找到 → error 且**不改盘**」。即**重复应用是安全的非改动错误**，绝不会二次替换、绝不会破坏文件。这是刻意选择：不做「已应用则视为成功」的猜测（那需要判断 `newString` 是否已在原位，脆弱且易误判），而是让「未找到」明确回报给模型自行决断。字面匹配（非正则）→ `oldString` 里的正则元字符按字面处理，替换用 `Matcher.quoteReplacement` 防 `$`/`\` 误解释。
  - 分级 **WRITE**；经凭据守卫（拒凭据文件）+ real-path 归一；失败 `{"error"}`。

- **D2 — `searchFiles` 纯 Java 实现与输出封顶。** 签名 `searchFiles(pattern, path, filePattern?, ignoreCase?)`。
  - **纯 Java**：`Files.walk(root)` 遍历 + `Pattern.compile(pattern)` 逐行 `find()`；**无任何 `ProcessBuilder`/外部命令**。这是本 change 的承重点——保证在无 grep 宿主可用。`pattern` 为**正则**（字面搜索即无元字符的正则）；`filePattern` 为可选 glob（如 `*.java`）限定被搜文件；`ignoreCase` 默认 false。
  - **非法正则**：`PatternSyntaxException` → `{"error":"invalid regex: <msg>"}`（不抛）。
  - **输出封顶（命名常量，防上下文撑爆 + 防 OOM/卡死）**：`MAX_MATCHES=200`（总命中上限，达上限追加 `… (truncated)` 标记并停止遍历）、`MAX_MATCHES_PER_FILE=20`、`MAX_LINE_LEN=500`（超长行截断）、`MAX_FILE_SIZE=5_000_000`（跳过超大文件）、跳过**二进制**文件（读前若含 NUL 字节则判为二进制、跳过）。输出格式：`<relpath>:<lineNo>: <trimmed line>`，按文件聚合。
  - **不返回凭据文件命中**：遍历中经凭据守卫跳过黑名单文件（与出口一致，绝不让搜索绕过黑名单泄露 `models.json` 内容）。
  - `@Tool(readOnly=true)`；分级 **READ_ONLY**（plan/EXPLORE 放行）；失败 `{"error"}`。

- **D3 — `findFiles` glob 语法。** 签名 `findFiles(pattern, path)`。
  - **glob 语义**：用 JDK 原生 `FileSystems.getDefault().getPathMatcher("glob:" + pattern)`，对 `Files.walk(root)` 的**相对路径**求匹配。支持 `*`（不跨 `/`）、`**`（跨目录）、`?`、`[...]`、`{a,b}`——即 `**/*.java`、`src/**/Test*.java` 这类。选 JDK PathMatcher 而非自造：零依赖、语义标准、跨平台（Windows 上按 `/` 归一相对路径再匹配）。
  - **空/非法模式**：空 `pattern` → `{"error":"empty glob pattern"}`；`PathMatcher` 抛 `PatternSyntaxException`/`IllegalArgumentException` → `{"error":"invalid glob: <msg>"}`。
  - **无匹配**：返回明确的空结果文案（如 `No files match: <pattern>`），**非报错**（无匹配是正常结果）。
  - **输出封顶**：`MAX_RESULTS=500`（达上限追加截断标记）。
  - `@Tool(readOnly=true)`；分级 **READ_ONLY**；失败 `{"error"}`。

- **D4 — 凭据守卫抽取 + 类归属（DRY / 复用而非重造）。**
  - 抽取 `CredentialFileGuard`（不可变值对象）：封装 `Set<Path> deniedPaths`（构造时对每个凭据文件同记 `toAbsolutePath().normalize()` 与 `toRealPath()` 两形态）+ `boolean isDenied(String path)`（把请求路径两形态与黑名单比对）。这是把今天散在 `FileSystemTools` 里的 `deniedPaths`/`isDenied`/`realPathOrNull` **原样搬出**（行为逐字节不变），供两个工具类共用。
  - `editFile` 挂到既有 **`FileSystemTools`**（自然归属：它就是文件读写工具，已持有守卫）→ 随既有 `FileSystemToolsProvider` 自动注册，**无需新 provider**。
  - `searchFiles`+`findFiles` 落新类 **`FileSearchTools`**（内聚："搜索/查找"一组），构造入参同一份凭据文件集合 → 内部建 `CredentialFileGuard` 跳过命中中的凭据文件。
  - 备选（未采纳）：三工具全塞进 `FileSystemTools` —— 该类会变胖且把「读写」与「搜索」两种关注点混在一起；分成 `FileSystemTools`(读写编辑) + `FileSearchTools`(搜索查找) 更内聚（高内聚低耦合，文件都 < 800 行）。

- **D5 — SPI 自注册（装配零改动）。** 新增 `FileSearchToolsProvider implements ToolProvider`：`create(ctx)` 取 `ctx.workspaceRoot()`，非空则用 `{models.json, mcp.json, *.bak}` 构造 `FileSearchTools`（与 `FileSystemToolsProvider` 完全对称），为空则无黑名单；`META-INF/services/io.pigagent.tool.spi.ToolProvider` 追加一行 `io.pigagent.tool.spi.providers.FileSearchToolsProvider`。`editFile` 因挂在 `FileSystemTools` 上，随既有 provider 免费注册。`AgentBootstrap`/`ToolContext` 字段**零改动**（新工具只需已有的 `workspaceRoot()`）。

- **D6 — T6：`ToolContext` telescoping 构造器 → Builder（纯重构，零行为变化）。** 今天 `ToolContext` 有 6 个 public 构造器逐个透传字段（`taskManager`/`skillsDir`/`workspaceRoot`/`webAllowedHosts`/`sandboxPolicy`/`notificationService`/`outreachEnabled`/`skillStaging`/`autonomousSkillsEnabled`/`userProfileFile`/`userProfileEnabled`），每加一个工具依赖就要再叠一层重载——电话簿式坏味道。本 change 触碰 `ToolContext` 使用面（新 provider 读 `workspaceRoot()`），顺带重构为 Builder：`ToolContext.builder().taskManager(t).workspaceRoot(r)....build()`，保留今天的默认语义（`webAllowedHosts` 空列表非 null、三个 `BooleanSupplier` 默认 `() -> false`、其余可空）。全部既有调用点（`AgentBootstrap`、各测试）机械改到 Builder。**这是纯重构**：`ToolContext` 的可观察行为（各 getter 返回值、默认值）逐字节不变，故不作为 spec 要求，只落 design + tasks；用「builder 与旧构造等价」单测锁行为。

## Risks / Trade-offs

- **R1 — `searchFiles` 在大目录树上的开销。** 纯 Java `Files.walk` 逐行正则会扫很多文件。→ 靠 D2 的多重封顶兜底：`MAX_FILE_SIZE` 跳大文件、二进制跳过、`MAX_MATCHES` 达上限即停止遍历（短路），把最坏情形限在可控范围。仍是**同步、best-effort**，超大 monorepo 全量正则可能慢——由模型经 `path`/`filePattern` 缩小范围缓解；未来可加超时/并行（非目标）。
- **R2 — `editFile` 唯一匹配对模型的要求。** 默认唯一匹配意味着模型必须给足够独特的 `oldString`，否则拿到「歧义/未找到」错误。→ 这是刻意的安全取舍（防误伤），错误信息明确指路（给更具体串或 `replaceAll`）；与 CC `Edit` 语义一致，模型已熟悉。
- **R3 — 输出上界是编译期常量，暂不可配。** → YAGNI：先给保守的合理默认，避免过早引入配置面；若实测需要再抬到 `application.yaml`（对齐 `sandbox.exec` 输出上界的既有做法），届时经 `ToolContext`（现已是 Builder，扩展摩擦低）注入。
- **R4 — 凭据黑名单是「凭据文件」而非「工作区沙箱」。** `searchFiles`/`findFiles` 会遍历工作目录下任意项目文件（编码 agent 的预期）。→ 延续 `FileSystemTools` 既定取舍：只封凭据文件，且搜索结果与文件读一致地跳过黑名单，绝不经搜索绕道泄露 `models.json`。SSRF/命令沙箱等其余正交守卫不受影响。
- **R5 — Builder 重构波及既有调用点。** → 纯机械替换 + 「builder 与旧构造等价」单测；`ToolContext` 无行为变化，其它模块只经 getter 读，编译期即可捕获遗漏。

## 交叉依赖

- **`ToolRiskClassifier` 是三线共用的中央 name→risk 目录**（记忆工具 `memory_*`、技能工具 `proposeSkill`/`skillManage`、画像 `updateProfile` 等均落此表，字符串键、与工具物理所在模块解耦）。本 spec **只新增** `editFile`/`searchFiles`/`findFiles` 三条目录条目，**不改**其查表逻辑（overrides → 默认表 → 未知 EXEC 的顺序）。「命名空间感知的分级改造」（如按 `mcp:<server>` 前缀分级）属 **Tool-OS T4** 范畴，不在本 change。
- **`tool-autoregister` / `tool-json-contract` / `tool-permissions`(EXPLORE 读只读) / `tool-sandbox`(凭据黑名单+real-path)** 能力：本 change **复用**其现有契约，未改任何 SHALL——新工具是这些既有能力的消费者，不是修改者。
- **T6 `ToolContext` Builder** 与 T1 同 change 交付（纯重构），为后续 Wave 工具（更多依赖注入）降摩擦；不阻塞、不被阻塞。

## 落实追踪表（需求发现项 → 落点 + 状态）

| 发现项 / 需求 | 落点 | 状态 |
|---|---|---|
| 模型被迫 shell 出去搜/改（PowerShell 无 grep/find） | D1/D2/D3 三个纯 Java 工具；spec Req 1/2/3 | 本 change 实现 |
| `editFile` 串替（非整文件覆盖）+ 幂等安全 | D1 唯一匹配/replaceAll/未找到不改盘；spec Req 1 | 本 change 实现 |
| `searchFiles` 纯 Java、绝不内部 shell、输出封顶 | D2 `Files.walk`+regex + 多重封顶；spec Req 2 | 本 change 实现 |
| `findFiles` glob 文件名匹配 | D3 JDK `PathMatcher` glob；spec Req 3 | 本 change 实现 |
| `ToolRiskClassifier` 登记：editFile=WRITE、searchFiles/findFiles=READ_ONLY | D1/D2/D3 分级 + spec Req 1/2/3；交叉依赖只新增条目 | 本 change 实现 |
| 搜索工具 `readOnly=true`（plan/EXPLORE 放行读） | D2/D3 `@Tool(readOnly=true)`；spec Req 2/3 | 本 change 实现 |
| 新增一个 ToolProvider + META-INF 条目（自注册，AgentBootstrap 零改） | D5 `FileSearchToolsProvider`；spec Req 5 | 本 change 实现 |
| 复用凭据黑名单 + real-path 归一 + {"error"} 契约 | D4 抽取 `CredentialFileGuard` 共用；spec Req 4/5 | 本 change 实现 |
| 折入 T6：ToolContext builder 重构（纯重构无行为变化） | D6；tasks 第 6 组；R5 | 本 change 实现（纯重构，非 spec 要求） |
| 输出上界可配置 | R3 非目标（先用常量） | 延后 |
| 索引/相关性全文检索、批量编辑、超时/并行搜索 | Non-Goals | 延后 |
| 命名空间感知的分级改造 | 交叉依赖：Tool-OS T4 | 延后（不在本 change） |
