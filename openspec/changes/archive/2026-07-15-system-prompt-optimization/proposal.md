## Why

`WorkspaceManager.defaultAgentMd()` 播种的默认系统提示词（新工作区首次 `initialize()` 时经 `createIfAbsent` 写入 `workspace/AGENT.md`）目前是一段 ~20 行的骨架：一句身份 + 一个 `## Capabilities` 列表 + 6 条 `## Guidelines`。它虽点名了主要工具，但**不成体系**：没有清晰的角色/操作原则、没有技能（skills）用法、几乎不提权限/沙箱这一「人在回路」的核心约束、没有规划/输出/错误处理规范；而且它罗列的能力早已落后于**最终能力集**——把 `webSearch`/`fetchUrl`/`checklist` 等当成核心工具，未反映它们现在由 `pig-agent-plugin-builtin` 插件贡献，也未提 7 个内置系统技能、MCP 自管、计算类插件工具。

`AgentBootstrap` 里拼装 `sysPrompt = readAgentMd() + readInfoMd() + toolGuidance` 的**固定 `toolGuidance`** 块（用户改了 AGENT.md 也照样追加）今天只覆盖「文件操作走原生工具、别用 shell 建文件」一条，未把「工具可能需确认/被拒/不可用要优雅降级」「永不回显密钥」这些**永远成立**的硬约束固化进去。

对一个生产级编码/终端 agent，这份「出厂说明书」应当是**详尽、结构化、英文**的一等公民产物。本次将其重写为生产级系统提示词，并把固定 `toolGuidance` 对齐到最终能力集，二者措辞准确映射真实的工具/插件/技能与权限模型。

## What Changes

- **重写 `WorkspaceManager.defaultAgentMd()`**：从骨架升级为**详尽、结构化、英文**的生产级 agent 系统提示词。分节覆盖：Identity & role、Operating principles、Tools（按 file-ops / running programs / web / tasks & checklists / utility-compute / MCP self-management 分类，逐字对齐真实工具名）、Skills（`listSkills`/`loadSkill` + 7 个内置技能作为可按需查阅的能力包）、Planning、Safety/permissions & sandboxing（plan/ask/auto/bypass 风险分级 + 凭据文件禁区 + 永不回显密钥 + 被拒/不可用优雅降级）、Output conventions（用**用户的语言**回复、围栏代码块、按路径引用文件）、Error handling。
- **把 `AgentBootstrap` 的固定 `toolGuidance` 提取为可测常量 `TOOL_GUIDANCE`** 并对齐最终能力集：保留「文件 CRUD 走 `writeFile`/`readFile`/`listDirectory` + 原生绝对路径、`executeCommand` 只用于跑程序」，新增「部分工具需确认/可能被拒/可能不可用（如 `webSearch`）→ 不要死循环重试或绕过、优雅降级或询问」「永不回显密钥、凭据文件禁区」。固定块随发行版稳定（每次运行 byte-stable，利于前缀缓存）。
- **模板语义不变**：默认提示词仍经 `createIfAbsent` 播种——**只有新工作区**得到改进后的默认；**现有用户的 `AGENT.md` 保持原样、绝不覆盖**。文件仍是用户可编辑模板。

无 **BREAKING**：`AGENT.md`/`INFO.md`/`toolGuidance` 的**装配方式与拼接顺序不变**，只改内容；`createIfAbsent` 幂等语义不变；工具/插件/技能的注册与契约不变。

## Capabilities

### New Capabilities
- `system-prompt`: 系统为新工作区播种一份**详尽、结构化、英文**的生产级默认系统提示词（`WorkspaceManager.defaultAgentMd()`），准确映射最终能力集的真实工具/插件/技能与「人在回路」权限沙箱模型；`AgentBootstrap` 追加的固定 `toolGuidance` 与之对齐并固化永远成立的硬约束。默认经 `createIfAbsent` 播种（仅新工作区，现有 `AGENT.md` 不动），仍是用户可编辑模板，每次运行 byte-stable。

<!-- 不改：AGENT.md/INFO.md/toolGuidance 的装配（readAgentMd + readInfoMd + toolGuidance）与拼接顺序；工具/插件/技能的注册、可用性门、权限 veto、返回契约。仅改提示词内容 + toolGuidance 内容 + 抽取为可测常量。 -->

## Impact

- **代码（`pig-agent-workspace`）**：`WorkspaceManager.defaultAgentMd()` 内容重写（同签名、私有方法）。
- **代码（`pig-agent-cli`）**：`AgentBootstrap` 内联的 `toolGuidance` 局部变量抽取为包级可见常量 `TOOL_GUIDANCE` 并重写内容；`sysPrompt` 拼接处引用该常量（装配逻辑不变）。
- **协作/不改**：`readAgentMd()`/`readInfoMd()`、`AgentFactory`/`AgentInstanceFactory`/channel agent 的 sysPrompt 消费、`createIfAbsent` 幂等、`ToolRegistrar`/`PluginRegistry`/`SkillsTool`/权限与可用性门，全部不变。
- **测试**：`WorkspaceManagerTest` 扩充——断言默认 `AGENT.md` 非空、英文、含关键能力锚点（`readFile`/`writeFile`/`listDirectory`、`loadSkill` + Skills 分节、安全/权限提示等），且不做脆弱的全文断言；新增 `AgentBootstrapToolGuidanceTest`（`pig-agent-cli`）断言 `TOOL_GUIDANCE` 含 `writeFile`/不用 shell 建文件/密钥不回显等锚点。
- **文档**：`CLAUDE.md` 若描述系统提示词装配，补一句默认提示词已精修 + `toolGuidance` 对齐最终能力集。
