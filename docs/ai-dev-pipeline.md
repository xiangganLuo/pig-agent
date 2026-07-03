# Pig Agent AI 循环开发流水线 —— 指南 + 首轮实战复盘

本文分两部分：**Part 1 指南**（`/ls:*` 半自动开发流水线怎么用）与 **Part 2 首轮实战复盘**（用它做出的第一个真实特性——工具权限体系——完整走了一遍，验证了流水线本身）。

---

# Part 1 · 流水线指南

## 是什么

一条**半自动 AI 开发流水线**，把本项目既有能力（openspec、Maven、规约）串成标准流程，用 `.claude/commands/ls/` 下的 `/ls:*` slash 命令驱动。核心原则：**复用而非重造**——`/ls:*` 只补缺失的胶水，spec/编码/归档三段直接委托 `/opsx:propose`、`/opsx:apply`、`/opsx:archive`。

```
需求澄清 → 特性分支(feat/bug/docs/opt) → spec 设计 → [编码⇄单测 内环] → 集成测试 外环 → openspec 归档
```

## 六个命令

| 命令 | 阶段 | 人工门 | 职责 |
|------|------|--------|------|
| `/ls:clarify` | 需求澄清 + 建分支 | ⏸ 人工 | AskUserQuestion 问清需求；从 `origin/main` 拉 `<type>/<name>` 分支 |
| `/ls:spec` | spec 设计 | ⏸ 人工审批 | 委托 `/opsx:propose` 生成 proposal/design/tasks + delta spec，`openspec validate --strict` |
| `/ls:code` | 编码⇄单测（**内环**） | 自动 | 逐 task TDD：测试→实现→`mvn test`→勾选；组后 `mvn compile` |
| `/ls:itest` | 集成测试（**外环**） | 自动 | 跑 `*IT` 真模型测试；失败回喂 `/ls:code`；连续 3 轮无进展升级人工 |
| `/ls:archive` | openspec 归档 | ⏸ 人工确认 | 委托 `/opsx:archive`：同步主 spec + 移到 `changes/archive/` |
| `/ls:dev` | 总控 | 半自动 | 端到端串联五阶段，尊重上述人工门 |

## 两层 Loop Engine

- **内环（`/ls:code`，快、离线）**：每个 task → 写测试(RED) → 实现(GREEN) → `mvn -pl <module> test` → 重构。退出：本 task 单测绿；全部 task `[x]` 且 `mvn -pl pig-agent-cli -am compile` 通过。
- **外环（`/ls:itest`，慢、真模型）**：任务集 → 内环 → 集成测试(`*IT`) → 失败回喂内环修复 → 重跑。退出：`*IT` 全绿。连续 3 轮无进展 → 升级人工（fail-safe，不空转）。

## 半自动人工门

- **自动**：编码内环、单测、集成测试、失败回环。
- **必停（人工门）**：① 需求澄清确认 ② spec 审批 ③ 归档确认。
- **异常停下**：歧义、阻塞、需回改 spec、安全敏感改动、连续 3 轮无进展。

## 约定

- **分支前缀**：`feat` / `bug` / `docs` / `opt`。注意 `bug/` 分支的**提交信息**仍用 conventional-commit 的 `fix:`。
- **承重技术设 spike 卡点**：spec 若依赖未验证的承重假设（如框架语义、外部 API 行为），把 `tasks.md` 第 1 组设为 Spike，不过不进编码。
- **规约基座**：`.claude/rules/common/{development-workflow,testing,git-workflow,code-review,security}.md`（TDD / ≥80% 覆盖 / conventional commit / 评审门）。

## 快速上手

```
/ls:dev 给某工具加一行调试日志        # 总控，端到端半自动
# 或分阶段手动：
/ls:clarify <需求>  →  /ls:spec <name>  →  /ls:code <name>  →  /ls:itest <name>  →  /ls:archive <name>
```

命令定义在 `.claude/commands/ls/`；机制同 `/opsx:*`（子目录 = 命名空间前缀）。

---

# Part 2 · 首轮实战复盘：工具权限体系

用这套流水线做的**第一个真实特性** = 对标 Claude Code 的工具权限体系（`plan`/`ask`/`auto`/`bypass` 四模式）。这既交付了功能，也**验证了流水线本身**。

## 逐阶段走位

| 阶段 | 结果 |
|------|------|
| **需求澄清**（office-hours 诊断） | 定下：四模式全量对标、`plan`=真只读 agent、仅全局配置、非交互渠道兜底 |
| **建分支** | `feat/permission-system`（从 origin/main fresh 拉出） |
| **spec** | `/opsx:propose` → proposal/design/tasks + `tool-permissions` delta spec，`validate --strict` 通过 |
| **Spike（卡点）** | 承重问题：`PreActingEvent` 能否否决工具调用？`PermissionVetoSpikeIT`（真实 anthropic）实证**策略 B**：hook 在 `PreActingEvent` 把待执行 `ToolUseBlock` 改写为只读 deny 哨兵 → `spyExecuted=false`、模型收拒绝后继续。承重解除 |
| **内环逐组** | Task2 config（`PermissionConfig`+`PermissionMode`，5/5）→ Task3 core（`ToolRiskClassifier`/`PermissionPolicy`/`PermissionResolver`/`ToolPermissionHook`，19/19）→ Task4 cli（`/permission` 命令 + `PigAgentCli` 接线，8/8）。每组 TDD + `mvn` 验证 + commit |
| **外环 itest** | `PermissionEnforcementIT`（真实 `ToolPermissionHook` + anthropic）2/2：**plan 否决可变工具 / bypass 放行** |
| **设计枢轴（Task5）** | 发现 REPL 与渠道共享同一 agent → 给渠道单独 agent（`channel=true` hook、confirmer=null、`ModelManager.attachChannel` 随模型切换重建），渠道用 `channel-mode` 且 ASK fail-closed |
| **文档 + 全量验证（Task6）** | CLAUDE.md + README 权限章节；全模块 `mvn test` BUILD SUCCESS 无回归 |
| **归档** | 人工确认门（本轮用户选择先做完 5.2 再归档） |

## 关键决策

- **架构 A（Hook 统一门）** 胜出：`ToolPermissionHook`（`PreActingEvent`，`priority()=0`）单点拦截，**内置 + MCP 工具统一覆盖**。否决 B（逐工具装饰器，覆盖不了 MCP 工具）、C（回合级，做不了逐工具审批）。
- **策略 B 否决**（spike 定）：改写 `toolUse` 指向 deny 哨兵，优于 `Mono.error` 中断（不优雅）。
- **权限逻辑落 `pig-agent-tools` 而非 core**：core 不依赖 config，为不增耦合，权限包放 tools（且 `PermissionDeniedTool` 本就是 @Tool）。判定核心抽到纯函数 `PermissionPolicy`/`PermissionResolver`，confirmer/writer 注入 → 无需 AgentScope 即可单测。
- **渠道专用 agent**：解决共享 agent 无法区分回合来源的约束；副作用（渠道对话与 REPL 分离）反而更合理。
- **D-SEC 组合而非重复**：MCP 自助接入的既有安全门保持不变，权限 hook 对 MCP_ADMIN 不重复弹窗。

## 教训 / 踩坑（沉淀给下一轮）

- **`mvn -pl <m>` 必须带 `-am`**：否则同级模块未装进本地仓库 → 假 BUILD FAILURE（本轮 model 模块中招一次）。
- **`-Dtest=X` 命中不到的上游模块**：加 `-Dsurefire.failIfNoSpecifiedTests=false`，否则上游模块因"无匹配测试"报错中断。
- **PowerShell 5.1 把 native 命令的 stderr 包成 `NativeCommandError`**：git/mvn 的正常 stderr 会被染红，**以 `Tests run:` / `BUILD SUCCESS` 行和退出码为准**，别被红字误导。
- **`git add` 要覆盖 `src/test`**：本轮 Task3 只 add 了 `src/main`，漏了 3 个测试文件（编译/通过但未入库），切分支时才暴露为未跟踪文件 → 补提交。**逐组提交时 `git add <模块目录>` 或显式列全 main+test。**
- **分支职责单一**：工具链（`opt/ai-dev-pipeline`）与功能（`feat/permission-system`）分开、各自建 PR；跨分支重复归档会在合入 main 时冲突（早前 MCP 归档已有教训）。
- **承重先 spike**：把最不确定的框架语义（`PreActingEvent` 否决）用一次真机小实验证掉，再放心铺开实现——比读字节码猜快且确定。

## 数字

- 7 个 commit，6 组任务；单测 config 5 + tools 19 + cli 8 全绿；集成测试 `PermissionVetoSpikeIT`（spike）+ `PermissionEnforcementIT`（plan 否决 / bypass 放行）。
- openspec change：`tool-permissions` spec，7 Requirement / 若干 Scenario，`validate --strict` 通过。
