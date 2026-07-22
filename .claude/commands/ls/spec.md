---
name: "LS: Spec"
description: spec 设计——委托 openspec propose 生成提案并校验（AI 开发流水线第 2 步）
category: Workflow
tags: [workflow, ls-pipeline, spec, openspec]
---

spec 设计。`/ls:*` 流水线第 2 步（**人工审批阶段**）。复用 `/opsx:propose`，不重造。

**前置**：应已在特性分支上（`/ls:clarify` 建好）。**Input**: 变更名（缺省时从对话或分支名推断）。

**Steps**

1. **确认变更名与分支**
   - 从参数 / 对话 / 当前分支名（`git branch --show-current`，取 `<type>/` 后缀）推断变更名。
   - 打印「Using change: <name>」及覆盖方式。

2. **生成 spec 提案（委托 /opsx:propose）**
   - **先做复用扫描（P1，先查再造）**：生成 design 前，先查有无可复用/可移植的现成实现（既有代码 / 库 / 开源项目 / 框架能力）；把「复用 X / 移植 Y / 决定自研因 Z」写进 `design.md` 的 Decisions（`/ls:clarify` 已问的可复用性在此做实）。
   - 按 `.claude/commands/opsx/propose.md` 的流程：`openspec new change "<name>"` → 逐 artifact 用 `openspec instructions <id> --change "<name>" --json` 生成 `proposal.md` / `design.md` / `tasks.md` + delta spec。
   - 遵循本仓库既有范例风格（中文、`## Why/What Changes/Capabilities/Impact`、design 的 `Decisions/Risks`、tasks 的分组 `- [ ]`）。
   - **给需集成/E2E 验证的 task 打标 `[IT]`（P2）**：在 `tasks.md` 里把需真模型/外部服务端到端验证的任务行标注 `[IT]`，供 `/ls:itest` 决定跑哪些、是否可跳过。

3. **承重技术设 spike 卡点**
   - 若变更依赖未验证的承重假设（如 AgentScope 语义、外部 API 行为），把 `tasks.md` **第 1 组设为 Spike（先做、卡点）**，spike 不过不进编码。

4. **校验**
   ```powershell
   openspec validate <name> --strict
   ```
   不通过则修正 artifact 直至通过。

5. **人工审批门**
   打印 proposal/design/tasks 摘要（尤其 Decisions、Non-Goals、Spike、任务分组），请用户审阅。**批准前不进入编码。**

**Output**
```
## Spec 已就绪：<name>

**Artifacts**: proposal / design / tasks（+ delta spec: <capability>）
**校验**: openspec validate --strict 通过 ✓
**Spike 卡点**: <有/无 —— 若有，一句话说明承重点>
**任务组**: <N 组，简述>

请审阅 design.md 的 Decisions 与 tasks.md。批准后运行 `/ls:code <name>` 进入编码⇄单测内环。
```

**Guardrails**
- 复用 `/opsx:propose` 的机制，别手搓 openspec 结构。
- `context`/`rules` 是给你的约束，**不要**抄进 artifact 文件。
- 关键上下文不明时追问用户，但优先做合理决策保持推进。
- **先复用后自研**：复用扫描结论必须落进 `design.md` 的 Decisions（P1）。
- 本步只产出 spec，**不写产品代码**。
