---
name: "LS: Status"
description: 整体进度汇报——跨 澄清/设计/规格/任务 维度统计所有活跃 spec（含多 spec 并行）
category: Workflow
tags: [workflow, ls-pipeline, status, report]
---

汇报流水线整体进度：扫描所有活跃 openspec change（spec），按 **澄清 / 设计 / 规格 / 任务** 维度统计并汇总。只读，不改任何文件。支持多 spec 并行视图。

**Input**: `/ls:status` 后可跟一个 change 名只看单个；缺省看全部活跃 change。

**Steps**

1. **列出活跃 change**
   ```powershell
   openspec list --json
   ```
   （或直接列 `openspec/changes/` 下非 `archive/` 的目录。）

2. **逐个取状态**
   对每个 change：
   ```powershell
   openspec status --change "<name>" --json
   ```
   解析 `artifacts` 得到各 artifact 的 `status`（done / 其他）：
   - **澄清** = `proposal`（Why/What）
   - **设计** = `design`（How/Decisions）
   - **规格** = `specs`（delta spec）
   - 读该 change 的 `tasks.md`，统计 `- [x]` / `- [ ]` → **任务** N/M。

3. **每个 change 判定总体阶段**（取最靠前的未完成维度）：
   `待澄清` → `待设计` → `待规格` → `编码中(任务 N/M)` → `外环第 K 轮` → `待归档(任务全完)`。
   若含未做的 **Spike 卡点**（tasks 第 1 组未 `[x]`），标 `⚠ spike 未过`。
   **外环轮次（P1，断点续跑）**：若变更目录有 `.ls-itest-log.md`，读它的最后轮次 K 与最近失败指纹一并汇报（`外环第 K 轮 / 上轮失败: …`），作为 `/ls:itest` 续跑的计数依据。

4. **渲染表格 + 汇总**
   ```
   ## 流水线进度（<K> 个活跃 spec）

   | spec | 分支 | 澄清 | 设计 | 规格 | 任务 | 阶段 |
   |------|------|:---:|:---:|:---:|:----:|------|
   | permission-system | feat/permission-system | ✓ | ✓ | ✓ | 18/19 | 待归档 |
   | <name2> | feat/<name2> | ✓ | ✓ | ○ | 0/8 | 待规格 |

   汇总: 澄清 K/K · 设计 A/K · 规格 B/K · 任务合计 X/Y · 待归档 Z 个
   下一步建议: <哪个 spec 该推进 / 哪个可归档 / 哪个 spike 卡着>
   ```
   分支列：优先取 `git branch --list "*/<name>"` 或从 change 名推断 `feat/<name>`。`✓`=done、`○`=未完成、`—`=无该 artifact。

**Guardrails**
- 纯只读：只跑 `openspec ... --json`、读 tasks.md、`git branch`。不改文件、不切分支、不建 spec。
- 多 spec 并行时一屏看全，突出**卡点**（spike 未过 / 长期停在某阶段 / 可归档未归档）。
- 单 change 参数时只渲染那一行 + 它的任务分组明细。
- **断点续跑的权威状态源（P1）**：阶段 / 外环轮次（`.ls-itest-log.md`）/ spike 状态均以本命令读出为准；`/ls:dev` 重入时先跑本命令恢复状态，再从对应阶段继续。
