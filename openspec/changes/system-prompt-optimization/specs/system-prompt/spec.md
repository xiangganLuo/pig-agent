## ADDED Requirements

### Requirement: 播种一份详尽、结构化、英文的生产级默认系统提示词

系统 SHALL 令 `WorkspaceManager.defaultAgentMd()` 返回一份**详尽、结构化、英文**的生产级 agent 系统提示词（新工作区 `initialize()` 时经 `createIfAbsent` 写入 `workspace/AGENT.md`）。该提示词 MUST 非空、以英文书写，并 MUST 分节覆盖以下方面：**身份/角色**（PigAgent 是运行在终端的编码/自动化 agent）、**操作原则**（先想后做、选对工具、简洁、如实汇报不谎报未验证的成功、真歧义才问否则合理默认）、**工具纪律**、**技能（skills）**、**安全与权限**、**规划**、**输出规范**、**错误处理**。

提示词的措辞 MUST 准确映射系统的最终能力集，不得罗列不存在（幻觉）的工具：
- 文件操作 MUST 点名 `readFile`、`writeFile`、`listDirectory`（原生绝对路径），并 MUST 指明创建/读取/编辑文件用这些工具而非 shell；
- 运行程序 MUST 点名 `executeCommand`（仅用于跑程序/命令，不做文件 CRUD）；
- Web 访问 MUST 提及 `fetchUrl` 与 `webSearch`，且 `webSearch` MUST 标注需搜索密钥（`BRAVE_API_KEY`）且可能不可用；
- MUST 提及任务/清单类工具与技能加载工具 `loadSkill`（及 `listSkills`），并 MUST 含一个「技能（skills）」分节把内置技能描述为按需查阅的能力包；
- 安全分节 MUST 说明其在「人在回路」的权限模型下运行（读只读放行、可变/执行/网络/MCP 管理类可能需确认或被拒），MUST 指明凭据/密钥文件禁区、MUST 要求永不回显密钥，并 MUST 要求在工具被拒/不可用时优雅降级（不死循环重试、不绕过安全门）；
- 输出规范 MUST 要求**按用户书写的语言应答**（而非固定英文应答）。

提示词内容对同一发行版 MUST 稳定（每次运行 byte-stable，利于前缀缓存），且不含运行期可变值。

#### Scenario: 默认 AGENT.md 非空且为英文
- **WHEN** 新工作区 `initialize()` 后读取 `AGENT.md`
- **THEN** 内容非空、以英文书写，且包含身份标识 `PigAgent`

#### Scenario: 默认 AGENT.md 命中关键能力锚点
- **WHEN** 检视默认 `AGENT.md` 文本
- **THEN** 同时包含文件工具锚点（`readFile`、`writeFile`、`listDirectory`）、技能锚点（`loadSkill` 及一个「Skills」分节标志）、以及安全/权限锚点（如 `permission`/`secret` 等）

#### Scenario: webSearch 标注为可能不可用
- **WHEN** 检视默认 `AGENT.md` 关于 web 访问的描述
- **THEN** 提及 `webSearch` 并说明其需要搜索密钥（`BRAVE_API_KEY`）且可能不可用（可回退），从而避免模型幻觉调用

### Requirement: 仅新工作区播种，现有 AGENT.md 保持不变（可编辑模板）

系统 SHALL 仅在 `AGENT.md` 不存在时播种默认提示词（`createIfAbsent` 幂等）。当工作区已存在 `AGENT.md` 时，`initialize()` MUST NOT 覆盖或修改其内容——用户对提示词的定制 MUST 逐字保留。默认提示词 MUST 保持为**用户可编辑模板**：改进的只是「出厂默认」，不强制或迁移既有内容。

#### Scenario: 现有 AGENT.md 不被覆盖
- **WHEN** 工作区已存在一份自定义 `AGENT.md`，再次调用 `initialize()`
- **THEN** 该 `AGENT.md` 内容原样保留（不被默认提示词覆盖）

#### Scenario: 缺失时播种默认
- **WHEN** 新工作区（无 `AGENT.md`）调用 `initialize()`
- **THEN** 以精修后的默认提示词创建 `AGENT.md`

### Requirement: 固定 toolGuidance 与最终能力集对齐并固化硬约束

系统 SHALL 令 `AgentBootstrap` 追加到系统提示词的固定 `toolGuidance` 块（`sysPrompt = readAgentMd() + readInfoMd() + toolGuidance`）与最终能力集对齐，并以一个包级可见常量（`TOOL_GUIDANCE`）承载以便测试锚定。该固定块 MUST 固化以下「永远成立」的硬约束（即使用户重写了 `AGENT.md` 仍生效）：
- 文件 CRUD MUST 走 `writeFile`/`readFile`/`listDirectory` + 原生绝对路径，`executeCommand` MUST 仅用于运行程序/命令、不用于文件 CRUD；
- MUST 提示部分工具需确认 / 可能被拒 / 可能不可用（如 `webSearch`），此时 MUST 优雅降级（不死循环重试、不绕过安全门）；
- MUST 要求永不回显密钥/令牌，凭据文件禁区。

固定块内容对同一发行版 MUST 稳定（byte-stable），且系统提示词的装配方式与拼接顺序 MUST 保持不变。

#### Scenario: TOOL_GUIDANCE 固化文件操作纪律
- **WHEN** 检视 `AgentBootstrap.TOOL_GUIDANCE`
- **THEN** 包含 `writeFile` 且指明不要用 shell（`executeCommand`）创建/编辑文件

#### Scenario: TOOL_GUIDANCE 固化密钥不回显
- **WHEN** 检视 `AgentBootstrap.TOOL_GUIDANCE`
- **THEN** 包含「永不回显密钥/令牌」意味的约束（如 `secret`/`credential`/`token` 锚点）

#### Scenario: 系统提示词装配顺序不变
- **WHEN** 构建系统提示词
- **THEN** 仍为 `readAgentMd()` + `readInfoMd()` + `TOOL_GUIDANCE` 的拼接（顺序与今天一致），仅内容改进
