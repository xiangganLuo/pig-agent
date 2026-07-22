---
name: "LS: Itest"
description: 集成测试外环——跑 *IT 真模型测试，失败回喂修复重跑（AI 开发流水线第 4 步 · loop engine 外环）
category: Workflow
tags: [workflow, ls-pipeline, integration-test, loop-engine]
---

集成测试**外环**（loop engine 外环）。`/ls:*` 流水线第 4 步（**自动阶段**）。跑真实 anthropic 模型的 `*IT` 集成测试，失败则回喂 `/ls:code` 修复并重跑。

**前置**：内环已收敛（`/ls:code` 单测+编译绿）。**Input**: 变更名（缺省从对话/分支推断）。

**Steps**

1. **确认有集成测试**
   - 该变更是否涉及需真模型验证的端到端行为（agent 流、工具执行、渠道等）？
   - 有 `*IT` 测试（如 `AnthropicConnectivityIT`、`FullLinkAgentIT`）则跑；纯离线变更无 `*IT` → 跳过并说明，直接提示 `/ls:archive`。
   - 确认真模型可用：`~/.pig-agent/workspace/models.json` 或 env（`ANTHROPIC_API_KEY` 等）已配。

2. **跑集成测试**（PowerShell，`-D` 参数须引号；`*IT` 默认被 surefire 排除，需显式指定）
   ```powershell
   & $mvn -pl pig-agent-cli -am test "-Dtest=*IT" "-Dsurefire.failIfNoSpecifiedTests=false"
   ```
   （`$mvn` = 仓库 Maven wrapper 路径。）用 `Select-String` 过滤 `BUILD SUCCESS|BUILD FAILURE|Tests run` 看结果；注意 PowerShell 5.1 会把测试 stderr 包成 NativeCommandError，非真失败，以 `Tests run`/`BUILD` 行与退出码为准。

3. **外环：失败回喂修复 + 结构化尝试日志**（loop engine 外环；P0：无进展改为可判定 + 跨会话可续）
   - 每轮把结果**追加**到变更目录下的 `.ls-itest-log.md`，一行：`轮次 | 失败用例集指纹 | 本轮所改 | 结果`。**失败指纹** = 失败测试名 + 断言/异常类型的集合，规范化后（去时间戳/路径、排序）比较。
   - 失败 → 归纳失败点（哪个 IT、断言、栈）→ 回 `/ls:code` 修实现/测试 → 重跑第 2 步并追加日志。
   - **失败分类（P1）**：先判失败属 (a) **实现级**（断言/边界/接线/环境）→ 回 `/ls:code` 修；还是 (b) **设计级**（承重假设错、架构不支持，如"两处共享同一对象无法区分来源"这类）→ **停下升级到 `/ls:spec` 回改设计或人工**，不在 code 里空转。"设计级"与"失败指纹连续 2 轮不变"并列为两条升级触发。
   - **可判定的"无进展"**："进展" = 失败集缩小 **或** 出现新的失败指纹（换了新错误）。若最新指纹与上一轮**完全相同**记一次无进展；**连续 2 轮指纹不变（即同一批失败重复 3 轮）→ 停下升级人工**，附 `.ls-itest-log.md`。
   - 计数**读自 `.ls-itest-log.md`**（持久化）→ 会话重启也能续算，不会清零后无限重跑。

4. **外环退出**
   - 所有 `*IT` 绿 → 提示 `/ls:archive`。

**Output**
```
## 集成测试外环：<name>
运行: mvn -Dtest=*IT
结果: <Tests run: X, Failures: 0, Errors: 0> | BUILD SUCCESS ✓
（若失败）失败点: <IT/断言> → 回喂修复第 N 轮
全绿 → 运行 `/ls:archive <name>` 归档。
```

**Guardrails**
- 集成测试跑真模型、耗 token，仅在内环绿后跑。
- 失败先修**产品代码**，别改测试掩盖问题（除非测试本身错）。
- 无进展（失败指纹连续 2 轮不变）必须升级人工，禁止无限重跑；计数以 `.ls-itest-log.md` 为准（跨会话可续）。
- 失败识别为**设计级**（承重假设/架构问题）时回 `/ls:spec` 或升级人工，别在 `/ls:code` 死磕（P1）。
- `.ls-itest-log.md` 是 scratch 记录，可 `.gitignore`（或归档时清理），不进主 spec。
- 不动 openspec 归档（那是 `/ls:archive`）。
