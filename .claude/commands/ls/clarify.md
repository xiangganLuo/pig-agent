---
name: "LS: Clarify"
description: 需求澄清 + 创建带类型前缀的特性分支（AI 开发流水线第 1 步）
category: Workflow
tags: [workflow, ls-pipeline, clarify]
---

需求澄清并创建特性分支。这是 `/ls:*` 半自动开发流水线的第 1 步（**人工阶段**）。

**Input**: `/ls:clarify` 后可跟一句需求描述；缺省时主动发问。

**Steps**

1. **澄清需求（AskUserQuestion，逐项问清，不要臆测）**

   若已给描述，先复述你的理解；然后用 **AskUserQuestion** 把以下要素问到具体、可验收：
   - **意图/问题**：要解决什么？现状痛点是什么？（拒绝空泛，逼出具体场景）
   - **变更类型**：`feat`（新功能）/ `bug`（修缺陷）/ `docs`（文档）/ `opt`（优化重构）
   - **验收标准**：完成的可观测判据（行为、命令、输出、测试）
   - **影响模块**：涉及哪些 module（`pig-agent-*`）
   - **可复用性**：是否已有可复用实现/模式（先查再造，遵循 `development-workflow.md` 的「Research & Reuse」）

   **IMPORTANT**：不清楚就继续问，别急着建分支。

2. **派生变更名与分支前缀**
   - 从需求派生 kebab-case 变更名（如「工具权限模式」→ `permission-system`）。
   - 分支名 = `<type>/<name>`，type ∈ {`feat`,`bug`,`docs`,`opt`}。
   - 注意：`bug/` 分支对应的提交信息仍用 conventional-commit 的 `fix:`。

3. **从 main 拉出干净分支**（PowerShell）
   ```powershell
   git fetch origin --quiet; git checkout -b <type>/<name> origin/main
   ```
   若工作树有未提交改动或分支已存在，停下告知用户如何处理，不要强行覆盖。

4. **人工确认门**
   打印需求摘要 + 已建分支名，等待用户确认。**确认前不进入下一步。**

**Output**
```
## 需求已澄清

**变更**: <name>（类型 <type>）
**分支**: <type>/<name>（从 origin/main 拉出）
**意图**: <一句话>
**验收标准**: <bullet>
**影响模块**: <modules>
**可复用**: <发现的既有实现/模式，或"无">

确认无误后运行 `/ls:spec <name>` 设计 spec。
```

**Guardrails**
- 只做澄清 + 建分支，**不写任何代码、不建 spec**。
- 需求含糊时优先追问，不要用默认值糊弄。
- 分支一律从 `origin/main` fresh 拉出，保持职责单一。
