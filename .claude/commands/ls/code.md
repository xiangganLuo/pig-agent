---
name: "LS: Code"
description: 编码⇄单测内环——逐 task TDD 自动推进直到收敛（AI 开发流水线第 3 步 · loop engine）
category: Workflow
tags: [workflow, ls-pipeline, code, tdd, loop-engine]
---

编码⇄单测**内环**（loop engine）。`/ls:*` 流水线第 3 步（**自动阶段**）。复用 `/opsx:apply` 的 task 驱动语义，叠加 TDD 循环与构建校验。

**前置**：spec 已就绪并经人工批准（`/ls:spec`）。**Input**: 变更名（缺省从对话/分支推断）。

**Steps**

1. **载入变更上下文**
   - `openspec status --change "<name>" --json` 取 schema、`changeRoot`、tasks 位置。
   - `openspec instructions apply --change "<name>" --json` 取 `contextFiles` 与任务清单；读全 proposal/design/tasks/spec。
   - 若含 **Spike 卡点**且未完成：先做 spike，结论写回 `design.md`；spike 不过则停下报告，不进后续编码。

2. **探测覆盖率工具（一次）**
   ```powershell
   Select-String -Path pom.xml,**/pom.xml -Pattern "jacoco" -List
   ```
   有 jacoco → 覆盖率 ≥80% 为硬门（`testing.md`）；无 → 降级为「单测全绿 + 关键分支有测试」硬门，覆盖率作建议。

3. **内环：逐 task 跑 TDD**（这是 loop engine 的内环，快、离线、不碰真模型）

   对每个未完成 task（`- [ ]`）：
   - **RED**：先写/补单测（JUnit5 + Mockito + AssertJ，AAA 结构，`*Test` 命名以进默认 surefire）。
   - **GREEN**：写最小实现让其通过。遵循 `coding-style.md`（不可变、函数<50 行、文件<800 行）。
   - **跑单测**（PowerShell，`-D` 参数加引号）：
     ```powershell
     & $mvn -pl <module> -am test "-Dtest=<TestClass>"
     ```
     或整模块：`& $mvn -pl <module> -am test`。（`mvn` = 仓库 wrapper 路径。）
   - **REFACTOR**：绿了再清理，保持测试绿。
   - 通过后把该 task `- [ ]` → `- [x]`。
   - 失败：修实现（非改测试，除非测试本身错）；同一 task 连续 3 次修不好 → 停下报告。

4. **每组 task 后：回归 → 编译 → 逐组提交**（P0）
   - **回归**（防止新 task 悄悄打破旧 task，别只跑本组新测试）：跑受影响模块的**整模块单测**：
     ```powershell
     & $mvn -pl <本组及其下游模块> -am test
     ```
     代价可控时跑全量 `& $mvn test`。有失败 → 先修回归再继续。
   - **编译**：
     ```powershell
     & $mvn -pl pig-agent-cli -am compile
     ```
     BUILD SUCCESS 才继续。
   - **逐组提交**（green 后立即，杜绝"漏提交测试文件 / 半成品跨分支"）：
     ```powershell
     git add <本组涉及的模块目录>   # 必须同时覆盖 src/main 与 src/test
     git commit -m "feat: <本组简述>"   # conventional-commit；bug 分支用 fix:
     ```
     提交前自查 `git status`，确认无遗漏的 `src/test` 文件。

5. **内环退出条件**
   - 全部 task `- [x]`，且 `mvn -pl pig-agent-cli -am compile` 通过，且相关模块 `mvn test` 全绿（+ 覆盖率门）。
   - 达成 → 提示进入 `/ls:itest`（外环集成测试）。

**自动 / 暂停策略（半自动）**
- 默认**自动逐 task 推进**，不逐个问用户。
- 仅在这些情况停下：task 语义不清、实现暴露 design 缺陷需回改 spec、错误/阻塞、连续无进展、涉及安全敏感改动（触发 `security.md` 检查）。

**Output（每轮）**
```
## 编码内环：<name>
本轮完成: [x] task A / [x] task B ...
进度: N/M
单测: <module> 绿 ✓ | 编译: BUILD SUCCESS ✓ | 覆盖率: <x% 或 n/a>
下一步: 剩余 K 个 task / 或 全部完成 → /ls:itest
```

**Guardrails**
- 测试先行；改实现不改测试（除非测试错）。
- 集成 `*IT` 测试**不**在此跑（真模型、慢，归 `/ls:itest` 外环）。
- 每完成一个 task 立即勾选 tasks.md。
- **每组绿后立即提交**，`git add` 覆盖 `src/main` 与 `src/test` 两侧（P0，防漏测试文件）。
- **组边界跑整模块回归**而非只新测试，早暴露跨 task 回归（P0）。
- 触碰 auth/输入/文件/外部调用时按 `security.md` 自检。
