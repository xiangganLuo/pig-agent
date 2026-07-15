## 1. 校准最终能力集（只读，先行）

- [x] 1.1 核对真实工具名：`pig-agent-tools` 的 `@Tool`（`readFile`/`writeFile`/`listDirectory`/`executeCommand`/`createTask`/`listTasks`/`updateTaskStatus`/`listSkills`/`loadSkill`/`listMcpServers`/`testMcpServer`/`addMcpServer`/`removeMcpServer`）。
- [x] 1.2 核对插件工具名：`pig-agent-plugin-builtin`（`webSearch`/`fetchUrl`/`createChecklist`/`completeItem`/`showChecklist` + 计算类 `currentDateTime`/`convertTimezone`/`epochToIso`/`isoToEpoch`/`generateUuid`/`randomNumber`/`randomString`/`base64Encode`/`base64Decode`/`md5Hash`/`sha256Hash`/`jsonPrettyPrint`/`jsonValidate`）。
- [x] 1.3 核对内置技能：`pig-agent-skills-builtin` 的 7 个（`code-review`/`systematic-debugging`/`tdd`/`refactoring`/`git-commit`/`security-review`/`planning`）。
- [x] 1.4 核对权限/沙箱模型：`tool.permission` 模式（plan/ask/auto/bypass）+ 风险分级；`tool-sandbox`（凭据文件黑名单、SSRF）。

## 2. 重写 defaultAgentMd()（pig-agent-workspace）

- [x] 2.1 重写 `WorkspaceManager.defaultAgentMd()` 为详尽、结构化、英文的生产级提示词，8 分节（身份/原则/工具/技能/规划/安全权限/输出/错误处理），逐字对齐 1.x 校准的真实工具/技能/模式，`webSearch` 标注可能不可用，Output 明确「按用户的语言应答」。
- [x] 2.2 确认 Java 文本块转义正确（Windows 路径 `\\`、无裸 `"""`），`mvn -q -pl pig-agent-workspace compile` 绿。

## 3. 单测：默认 AGENT.md 锚点（pig-agent-workspace，TDD）

- [x] 3.1 扩充 `WorkspaceManagerTest`（RED→GREEN）：默认 `AGENT.md` 非空、含 `PigAgent`；含 `readFile`/`writeFile`/`listDirectory`；含 `loadSkill` 与「Skills」分节；含安全/权限锚点（`permission`）；英文启发式（关键英文锚点存在）。仅断言锚点、不锁全文。
- [x] 3.2 保留/修正既有 `readAgentMdReturnsContent`（`contains("PigAgent")`）——确认不被新文案破坏。
- [x] 3.3 `mvn -q -pl pig-agent-workspace test` 绿。

## 4. 对齐 toolGuidance + 单测（pig-agent-cli，TDD）

- [x] 4.1 把 `AgentBootstrap.build` 内联的 `toolGuidance` 抽取为包级 `static final String TOOL_GUIDANCE`；`sysPrompt` 引用之（装配 byte-for-byte 等价）。
- [x] 4.2 重写 `TOOL_GUIDANCE` 内容对齐最终能力集：文件 CRUD 走原生工具、`executeCommand` 只跑程序；工具需确认/可能被拒/不可用 → 优雅降级不绕过；永不回显密钥、凭据文件禁区。
- [x] 4.3 新增 `AgentBootstrapToolGuidanceTest`：断言 `TOOL_GUIDANCE` 含 `writeFile`、含「不用 shell 建文件」锚点、含密钥不回显锚点（`secret`/`credential`/`token`）。
- [x] 4.4 `mvn -q -pl pig-agent-cli -am compile` 绿。

## 5. 文档 + 全量验收

- [x] 5.1 `CLAUDE.md`：在系统提示词装配处补一句——默认提示词已精修为详尽英文生产级提示词，`toolGuidance` 已对齐最终能力集（抽为 `TOOL_GUIDANCE`）。
- [x] 5.2 `mvn -q test`（单线程 `-DforkCount=1 -Dsurefire.rerunFailingTestsCount=0`）绿，读 surefire XML 计数确认。
- [x] 5.3 `mvn -q -pl pig-agent-cli -am compile` 绿（装配整体打通）。
- [x] 5.4 `openspec validate system-prompt-optimization --strict` 通过。
