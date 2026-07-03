---
name: "LS: Clarify"
description: 需求澄清 + 规模评估拆分（多 spec 可并行）+ 创建特性分支（AI 开发流水线第 1 步）
category: Workflow
tags: [workflow, ls-pipeline, clarify, multi-spec]
---

需求澄清、按规模拆分为一个或多个**可独立上线**的 spec、并为每个 spec 建特性分支。`/ls:*` 流水线第 1 步（**人工阶段**）。

**Input**: `/ls:clarify` 后可跟一句需求描述；缺省时主动发问。

**Steps**

1. **澄清整体需求（AskUserQuestion，逐项问清，不要臆测）**

   若已给描述，先复述理解；然后用 **AskUserQuestion** 把以下问到具体、可验收：
   - **意图/问题**：要解决什么？现状痛点？（拒绝空泛，逼出具体场景）
   - **变更类型**：`feat` / `bug` / `docs` / `opt`
   - **验收标准**：可观测判据（行为、命令、输出、测试）
   - **影响模块**：涉及哪些 `pig-agent-*`
   - **可复用性**：是否已有可复用实现/模式（先查再造）

   **IMPORTANT**：不清楚就继续问，别急着拆分/建分支。

2. **规模评估与拆分（可选多 spec，人工审）**

   评估这个需求应该是 **单 spec** 还是 **拆成多个可独立上线的 spec**。倾向拆分当：
   - 需求足够大，单 spec 的 tasks 会很多、迭代慢；
   - 能切成多个**各自可独立上线、可并行开发**的部分（纵向切功能，而非横向切层）；
   - 各部分之间依赖弱、边界清晰。

   **不拆**当：需求小、或强耦合难以独立上线。

   若判断可拆，产出拆分方案：每个子 spec 给 `name` + 一句范围 + 是否可独立上线 + 与其它子 spec 的依赖（可并行 / 有先后）。用 **AskUserQuestion** 让用户**审阅并调整拆分**（选项如：确认此拆分 / 需要调整 / 不拆做单 spec）。拆分是关键决策，**必须人工审过**。

3. **派生 name 与分支（每个 spec 一条，可并行）**
   - 每个 spec：kebab-case `name` + 分支 `feat/<name>`（type 依该 spec 性质，通常 `feat`）。
   - 多 spec 时各自独立分支，可**并行开发**（不同会话/agent 分别推进）。
   - 注意：`bug/` 分支的提交信息仍用 conventional-commit 的 `fix:`。

4. **建分支**（PowerShell；每个 spec 各建一条）
   ```powershell
   git fetch origin --quiet; git checkout -b <type>/<name> origin/main
   ```
   多 spec 时逐个从 `origin/main` fresh 拉出。工作树有未提交改动或分支已存在 → 停下告知，不强行覆盖。
   （并行开发建议每个 spec 用独立 worktree/会话，避免互相踩工作树。）

5. **人工确认门**
   打印需求摘要 + 拆分结果 + 各分支，等待用户确认。**确认前不进入下一步。**

**Output（单 spec）**
```
## 需求已澄清（单 spec）
**变更**: <name>（类型 <type>）  **分支**: <type>/<name>
**意图** / **验收标准** / **影响模块** / **可复用**: ...
确认后运行 `/ls:spec <name>`。
```

**Output（多 spec）**
```
## 需求已澄清并拆分为 <N> 个可独立上线的 spec

| spec | 分支 | 范围 | 独立上线 | 依赖 |
|------|------|------|:-------:|------|
| <name1> | feat/<name1> | ... | 是 | 无（可并行） |
| <name2> | feat/<name2> | ... | 是 | 依赖 name1 |

并行建议: <哪些可同时开工 / 哪些需等依赖>
逐个推进：在各自分支/会话运行 `/ls:spec <name>` → `/ls:code` → `/ls:itest` → `/ls:archive`。
用 `/ls:status` 查看多 spec 整体进度。
```

**Guardrails**
- 只做澄清 + 拆分 + 建分支，**不写代码、不建 spec**。
- **拆分方案必须人工审**：不擅自决定切几个 spec，给方案让用户确认/调整。
- 每个子 spec 必须**可独立上线**（能单独 /ls:archive 并合并），否则合并回单 spec。
- 分支一律从 `origin/main` fresh 拉出，保持职责单一。
