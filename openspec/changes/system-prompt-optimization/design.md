## Context

`WorkspaceManager.defaultAgentMd()` 是 agent 的「出厂系统提示词」——新工作区 `initialize()` 时经 `createIfAbsent` 写入 `AGENT.md`，随后 `AgentBootstrap` 以 `sysPrompt = readAgentMd() + "\n\n" + readInfoMd() + toolGuidance` 拼装，喂给交互 / channel / 自治各路 agent。今天这段提示词是 ~20 行骨架，且能力清单落后于最终能力集（web/checklist 已插件化、7 个内置技能、MCP 自管、计算类插件工具、权限沙箱），固定 `toolGuidance` 也只固化了一条文件操作约束。

本 spec **只改内容不改结构**：把默认提示词重写为详尽、结构化、英文的生产级提示词，把 `toolGuidance` 对齐最终能力集并抽取为可测常量。装配方式、拼接顺序、`createIfAbsent` 幂等、工具/技能注册与契约一律不动。

## Goals / Non-Goals

**Goals:**
- 默认 `AGENT.md` = 详尽、结构化、**英文**的生产级系统提示词，分节覆盖身份/原则/工具纪律/技能/安全权限/规划/输出/错误处理。
- 措辞**准确映射最终能力集**：真实工具名（`readFile`/`writeFile`/`listDirectory`/`executeCommand`/`fetchUrl`/`webSearch`/`createTask`…/`createChecklist`…/MCP 自管/计算类）、7 个内置技能、权限模型（plan/ask/auto/bypass + 风险分级）、沙箱（凭据文件禁区、SSRF、密钥不回显）。
- 固定 `toolGuidance` 与提示词对齐，固化「永远成立」的硬约束（即使用户重写 AGENT.md 仍生效）。
- 仍是**用户可编辑模板**，仅新工作区播种（`createIfAbsent`），现有 `AGENT.md` 不动；每次运行 byte-stable（前缀缓存友好）。

**Non-Goals:**
- 不改 sysPrompt 的装配/拼接（`readAgentMd + readInfoMd + toolGuidance`）与顺序。
- 不改 `INFO.md`（含 os/java/workspace，本就每机器异、每次运行稳定）。
- 不改任何工具/插件/技能的注册、可用性门、权限 veto、返回契约。
- 不引入运行期动态提示词生成、按 provider 分支的提示词、i18n 的多语言默认提示词（提示词英文，运行时按「用户的语言」应答由提示词内约束驱动）。

## Decisions

### D1 — 默认提示词：详尽、结构化、英文，8 个固定分节
`defaultAgentMd()` 返回一段 Java 文本块，结构固定为：
1. **Identity & role** — PigAgent 是运行在终端的编码/自动化 agent，有真实工具、优先动手而非空谈。
2. **Operating principles** — 先想后做、选对工具、简洁、如实汇报（不谎报未验证的成功）、真歧义才问否则合理默认、不越范围。
3. **Tools** — 按类分组，逐字对齐真实工具名：file-ops（`readFile`/`writeFile`/`listDirectory`，原生绝对路径，别用 shell）、running programs（`executeCommand` 只跑程序）、web（`fetchUrl` SSRF 守护 / `webSearch` 需 key 且可能不可用）、tasks & checklists、utility/compute（时间/UUID/base64/hash/json/random）、MCP self-management（`listMcpServers`/`testMcpServer` 恒可用；`addMcpServer`/`removeMcpServer` 受策略与人工确认约束）。
4. **Skills** — `listSkills`/`loadSkill`；7 个内置技能作为按需查阅的能力包，给出「何时加载哪个」的示例。
5. **Planning** — 多步先给短计划，用 checklist/tasks 跟踪。
6. **Safety, permissions & sandboxing** — 人在回路；风险分级；被拒/不可用别死循环重试、别绕过；凭据文件禁区；永不回显密钥；破坏性操作谨慎。
7. **Output conventions** — 用**用户的语言**应答、结构化、围栏代码块、按路径引用文件。
8. **Error handling** — 带上下文暴露错误（脱敏）、不静默吞错、读错误后纠偏而非重复失败调用。

理由：编码 agent 的系统提示词是行为的主锚点；分节化 + 具体可执行（点名工具/技能/模式）远胜泛泛「be helpful」。英文贴合模型上下文常态且与工具描述（英文）一致。

### D2 — 措辞对齐「最终能力集」，不写幻觉工具
所有点名的工具/技能/模式都以仓库真实存在为准（核对 `pig-agent-tools` 的 `@Tool`、`pig-agent-plugin-builtin` 的插件工具名、`pig-agent-skills-builtin` 的 7 个 `SKILL.md`、`tool.permission` 的模式与风险类）。web/checklist/compute 描述为「内置能力」而非承诺「一定可用」——`webSearch` 明确标注「需 `BRAVE_API_KEY`、可能不可用」，与可用性门（未配 key 则该工具对模型隐藏）一致，避免模型产生幻觉调用。
- **不点名的**：不逐一枚举每个 compute 工具的签名（提示词给类别 + 代表例即可，避免与工具 schema 重复、避免膨胀），但保证类别与代表工具名真实。

### D3 — 「用户的语言」应答
提示词提示英文，但 **Output conventions 明确要求「按用户书写的语言应答」**。理由：默认提示词英文利于与模型/工具生态对齐且 byte-stable，而实际对话语言应随用户（本仓用户多用中文）。二者不矛盾：系统提示词的语言 ≠ 应答语言，后者由提示词内的显式指令决定。

### D4 — 模板语义：仅新工作区播种，现有 AGENT.md 不动
沿用现有 `createIfAbsent`：`initialize()` 仅当 `AGENT.md` 不存在时写默认内容。故**只有新工作区**得到精修默认；**已有用户的 `AGENT.md` 逐字保留**（他们的定制不被覆盖）。文件仍是用户可编辑模板——改进的是「出厂默认」，非强制内容。不新增迁移/覆盖逻辑（避免破坏用户定制）。

### D5 — `toolGuidance` 抽取为可测常量 `TOOL_GUIDANCE` 并对齐
`AgentBootstrap.build` 内联的 `toolGuidance` 局部变量提为**包级可见 `static final String TOOL_GUIDANCE`**，`sysPrompt` 拼接处引用之（装配逻辑 byte-for-byte 等价）。内容对齐最终能力集，固化三条永远成立的硬约束：
- 文件 CRUD 走 `writeFile`/`readFile`/`listDirectory` + 原生绝对路径，`executeCommand` 只跑程序（保留今天这条）。
- 部分工具需确认 / 可能被拒 / 可能不可用（如 `webSearch`）→ 不要死循环重试或绕过安全门，优雅降级或询问。
- 永不回显密钥/令牌；凭据文件禁区。

抽为常量的理由：使「固定块随发行版稳定且措辞准确」可被单测锚定（`AgentBootstrapToolGuidanceTest`），而不必实例化整个 `build()`；与 `defaultAgentMd()` 的可测性对称。

### D6 — 测试用锚点断言，杜绝脆弱全文断言
`WorkspaceManagerTest` 断言默认 `AGENT.md`：非空、含 `PigAgent`（兼容旧断言）、含 file 工具锚点（`readFile`/`writeFile`/`listDirectory`）、含 `loadSkill` 与 Skills 分节标志、含安全/权限锚点（如 `permission`）、英文（ASCII 主导的启发式，不强行禁中文字符——只断言关键英文锚点存在）。`AgentBootstrapToolGuidanceTest` 断言 `TOOL_GUIDANCE` 含 `writeFile`、含「不用 shell 建文件」意味的锚点、含密钥不回显锚点。**只断言锚点存在**，不锁定整段文案（提示词可继续演进而不碎测）。

## Risks / Trade-offs

- **R1 — 提示词变长增加每轮 token**：详尽 ⇒ 更多 token。→ 它是 byte-stable 前缀（system 段），命中前缀缓存后边际成本低；且质量收益（少幻觉、更规范）远超成本。仍克制：给类别 + 代表工具而非枚举全部签名。
- **R2 — 措辞漂移出能力集**：日后新增/改名工具，提示词可能过时。→ 提示词点名的是稳定的核心工具/技能类别；`webSearch` 的「可能不可用」等表述本就容错；测试锚点会在关键名消失时提示。文档（CLAUDE.md）指向本 spec 作为「保持准确」的约定。
- **R3 — 仅新工作区受益**：老用户 `AGENT.md` 不更新。→ 有意为之（不覆盖用户定制，D4）；老用户可删除自己的 `AGENT.md` 以重新播种，或手动借鉴。
- **R4 — 「英文提示词 vs 用户中文应答」被模型误读**：模型可能整体用英文答。→ Output conventions 用一条显式指令消歧（D3）；这是行业常见模式（英文系统提示 + 「reply in the user's language」）。

## 落实追踪表

| 评审/发现项 | 落点 | 状态 |
|---|---|---|
| 详尽、结构化、英文的生产级默认提示词（8 分节） | D1；system-prompt R1；task 2.x | 已实现 |
| 措辞对齐最终能力集、不写幻觉工具（含 webSearch 可能不可用） | D2；system-prompt R1；task 2.x | 已实现 |
| 「按用户的语言应答」显式约束 | D3；system-prompt R1（Output conventions）；task 2.x | 已实现 |
| 模板语义：仅新工作区播种、现有 AGENT.md 不动 | D4；system-prompt R2；task 2.x/3.x | 已实现 |
| `toolGuidance` 抽取为可测常量并对齐最终能力集 | D5；system-prompt R3；task 4.x | 已实现 |
| byte-stable（每次运行稳定，前缀缓存友好） | D1/D5；system-prompt R2/R3；task（常量本身即稳定） | 已实现 |
| 锚点断言、无脆弱全文断言；修复钉死旧文案的测试 | D6；system-prompt R1/R3；task 3.x/4.x | 已实现 |
| 文档同步（CLAUDE.md） | proposal Impact；task 5.x | 已实现 |
