---
name: "LS: Archive"
description: openspec 归档——委托 opsx:archive，归档前人工确认（AI 开发流水线第 5 步）
category: Workflow
tags: [workflow, ls-pipeline, archive, openspec]
---

openspec 归档。`/ls:*` 流水线第 5 步（**人工确认收尾**）。复用 `/opsx:archive`，不重造。

**前置**：编码内环 + 集成测试外环均已通过（`/ls:code`、`/ls:itest`）。**Input**: 变更名（缺省从对话/分支推断）。

**Steps**

1. **归档前检查（人工确认门）**
   - `openspec status --change "<name>" --json` 确认 artifacts 全 `done`。
   - 读 `tasks.md` 确认无遗留 `- [ ]`（有则列出，问用户是否仍归档）。
   - 打印摘要（变更、任务完成度、待同步的 delta spec），**请用户确认归档**。确认前不移动文件。

2. **执行归档（委托 /opsx:archive）**
   - 按 `.claude/commands/opsx/archive.md` 流程：若 delta spec 未同步，先 `openspec-sync-specs` 同步到 `openspec/specs/<capability>/spec.md`，再移动到 `openspec/changes/archive/YYYY-MM-DD-<name>/`。
   - 目标已存在则报错并给选项（改名/删旧/换日期）。

3. **收尾提交（可选）**
   - 归档改动了 `openspec/`（移动 + 新建主 spec），提示用户是否 `docs(openspec): 归档 <name>` 提交到当前分支。

**Output**
```
## Archive Complete

**Change:** <name>
**Archived to:** openspec/changes/archive/YYYY-MM-DD-<name>/
**Specs:** ✓ 同步到主 spec / 或 无 delta
**Tasks:** N/N 完成

流水线闭环。<提示是否提交归档改动。>
```

**Guardrails**
- 归档前必须人工确认（半自动收尾门）。
- 有未完成 task/artifact 时先告知，不擅自归档。
- 复用 `/opsx:archive` + `openspec-sync-specs`，别手动挪目录/拼主 spec。
- 若变更是从 main 拉的独立分支，注意与其它分支的重复归档冲突（先确认）。
