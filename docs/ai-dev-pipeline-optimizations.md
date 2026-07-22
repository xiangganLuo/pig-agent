# `/ls:*` 开发流水线 —— AI 工作流优化建议（评审稿）

> **目的**：对已跑通的半自动 AI 循环开发流水线 `/ls:*`（澄清→spec→[code⇄itest]→归档）做一次批判性梳理，列出**工作流本身**的优化点，供评审后决定是否落地。
> **范围**：只分析流水线的 AI 工作流设计（命令编排、loop engine、人工门、状态传递、质量门），**不改产品代码**。
> **依据**：`.claude/commands/ls/*.md`（7 命令）+ `.claude/commands/opsx/*` + `.claude/rules/common/{ls-pipeline,development-workflow,testing}.md` + `docs/ai-dev-pipeline.md`（含首轮实战复盘的真实踩坑）。
> **状态**：**13 项全部已落地（2026-07-22，P0/P1/P2 三批）**，同步落到两处：源仓库 `pig-agent/.claude/commands/ls/` 与模板仓库 `ls-pipeline/assets/.claude/commands/ls/`（及各自 `rules/ls-pipeline.md`、`templates/ls-pipeline.config.md`）。

---

## 一、总体判断

流水线的**骨架是好的**：五阶段清晰、三道人工门定位准、两层 loop 分离（内环快离线 / 外环慢真模型）、复用 openspec 而非重造、多 spec 拆分有人工审。真实跑过一轮并沉淀了踩坑。

主要短板集中在**四类**：
1. **断点续跑与状态**：跨轮次/跨会话的流水线状态（当前阶段、外环轮次、spike 状态）没有持久载体，靠每次重新推断，易漂移、易让"3 轮无进展"这类护栏在会话重启后失效。
2. **护栏是"魔法数字 + 主观判断"**：外环"连续 3 轮无进展"、内环"同一 task 修 3 次"都缺**可判定的"进展"定义**，LLM 可能空转或过早放弃。
3. **质量门偏软**：内环只跑"本 task 测试 + 编译"，缺**全量回归**、**lint/类型/安全**、**逐组提交**——而复盘里恰恰栽在"漏提交测试文件""全量验证拖到最后"。
4. **规格闭环不闭**：研究复用、spike 回写、实现与 spec 一致性、安全评审都停留在"规约里写了"，没有**在命令流里成为显式步骤/门**。

下面按优先级给出建议。

## 二、优先级汇总

| # | 优化点 | 类别 | 优先级 | 落地点 |
|---|--------|------|:------:|--------|
| 1 | 逐组 green 后**强制提交**（含 test 目录） | 内环质量 | **P0 ✅** | `ls/code.md` |
| 2 | 内环每组后跑**全量/受影响回归**，非仅新测试 | 内环质量 | **P0 ✅** | `ls/code.md` + config |
| 3 | 外环"无进展"改为**可判定**（失败集/错误签名对比 + 结构化尝试日志） | 外环智能 | **P0 ✅** | `ls/itest.md` |
| 4 | 流水线**状态持久化**（当前阶段/外环轮次/spike），支持断点续跑 | 状态 | **P1 ✅** | `ls/status.md` 为准 + 轮次日志 |
| 5 | 外环失败**分类**：code-level 回 code / design-level 升 spec 或人工 | 外环智能 | **P1 ✅** | `ls/itest.md` |
| 6 | 内环质量门扩展：可选 **lint / typecheck / security-scan** | 内环质量 | **P1 ✅** | config 新字段 + `ls/code.md` |
| 7 | `/ls:spec` 显式**研究复用**步骤，结论写进 design | 规格闭环 | **P1 ✅** | `ls/spec.md` |
| 8 | 卡住时**升级/拆分**而非只"停下"（内环 task、spec 回改） | 内外环 | **P1 ✅** | `ls/code.md`、`ls/itest.md` |
| 9 | 归档前**实现↔spec 一致性**快检 + 敏感 diff 强制安全评审 | 规格闭环 | **P2 ✅** | `ls/archive.md` |
| 10 | 多 spec **依赖违例告警**（B 在 A 归档前起了 tasks） | 多 spec 协同 | **P2 ✅** | `ls/status.md` |
| 11 | 归档时把**教训写回** config「备注/踩坑」段（学习闭环） | 学习闭环 | **P2 ✅** | `ls/archive.md` + config |
| 12 | itest **触发判定显式化**（哪些变更需 IT，spec 打标） | 外环智能 | **P2 ✅** | `ls/spec.md` tasks 打标 + `ls/itest.md` |
| 13 | 上下文**重复读取**优化（只读 Decisions+tasks，缓存摘要） | 效率 | **P2 ✅** | 各命令 |

---

## 三、P0（先做，直击真实踩坑）

> **状态：#1 / #2 / #3 已落地（2026-07-22）**——同步改到 `pig-agent/.claude/commands/ls/{code,itest}.md` 与 `ls-pipeline/assets/.claude/commands/ls/{code,itest}.md`；#2 在 ls-pipeline 增补可选 config 字段 `full-test`（缺省回退 `unit-test`）+ `docs/config-reference.md`；#3 用变更目录下 `.ls-itest-log.md` 承载失败指纹与轮次（已加入 pig-agent `.gitignore`）。

### 1. 逐组 green 后强制提交（含测试目录）
**现象**：`ls/code.md` 内环退出条件只到"task 勾选 + 编译 + 单测绿"，提交是复盘里的"教训"而非命令步骤。复盘明确记录：某组只 `git add src/main`，**漏了 3 个测试文件**，切分支才暴露。
**建议**：在 `ls/code.md` 每组 task 绿之后加一步——`git add <本组涉及的模块目录（main+test）>` 并按 conventional-commit 逐组提交（`feat:`/`fix:`…）。给出"add 覆盖 main 与 test 两侧"的显式清单校验。
**代价**：提交更碎（但可 squash）；**收益**：消灭"漏测试文件/半成品跨分支"这一类真实错误，且给外环失败提供干净的回滚粒度。

### 2. 内环每组后跑全量/受影响回归，而非仅新测试
**现象**：`ls/code.md` 只跑"本 task 的 `<single-test>`"+ 组后 `<build>` 编译。新 task 可能悄悄打破前面 task 的测试，最坏拖到 itest 或（无 IT 时）漏网。复盘里"全量 `mvn test` 无回归"直到 Task6 才做。
**建议**：组退出门加"跑受影响模块的 `<unit-test>`（而非只新测试）"；条件允许（离线、快）则跑全量单测。为此在 config 增补可选 `affected-test`/`full-test` 语义（缺省回退到 `unit-test`）。
**代价**：内环变慢；**缓解**：单文件 TDD 内用 `<single-test>`，**组边界**才跑受影响/全量，平衡速度与回归保护。

### 3. 外环"无进展"改为可判定
**现象**：`ls/itest.md`「连续 3 轮无进展（同类失败反复）→ 升级人工」——"无进展"靠 LLM 主观判断，且**轮次计数不持久**（会话重启即清零，可能跨重启无限重跑）。
**建议**：定义"进展" = 失败用例集缩小 **或** 出现新的错误签名（失败测试名+断言/异常类型的规范化指纹）。维护一份**结构化尝试日志**（轮次 → 失败集指纹 → 本轮所改 → 结果），写进变更目录下的一个 scratch 文件（如 `tasks.md` 末尾或 `.ls-itest-log.md`）。当失败指纹**连续 N 轮不变**即判定无进展、升级人工。日志持久 → 计数**跨会话可续**。
**代价**：命令逻辑更重；**收益**：护栏从"感觉"变"可判定"，杜绝空转烧 token，且断点续跑安全。

---

## 四、P1（显著提升鲁棒性/闭环）

> **状态：#4/#5/#6/#7/#8 已落地（2026-07-22）**——同步改到两处 `commands/ls/{spec,status,dev,code,itest}.md`；#6 在 ls-pipeline 增补可选 config 字段 `lint`/`typecheck`/`security-scan`（缺省 `none` 跳过）+ `docs/config-reference.md`；#4 让 `/ls:status` 成为断点续跑权威状态源（读 `.ls-itest-log.md` 外环轮次）、`/ls:dev` 重入先跑 status；#5 外环失败分 实现级/设计级，设计级回 `/ls:spec`；#8 卡住给 拆分/回改 spec/升级人工 三档。

### 4. 流水线状态持久化，支持断点续跑
**现象**：每个命令从"参数/对话/分支名"重新推断 change 名与阶段；`/ls:dev` 总控被打断后靠重新推断续跑。外环轮次、spike 是否过、当前处于内环还是外环——**无单一状态源**。
**建议**：以 `openspec status --json` + tasks 勾选为**主状态源**（已有），再补一个**轻量流水线状态**（当前阶段、外环轮次、spike 状态）——可复用 #3 的尝试日志，或让 `/ls:status` 成为"读状态"的唯一权威并被 `/ls:dev` 每次续跑时先调用。
**代价**：小；**收益**：`/ls:dev` 与手动分阶段都能可靠续跑，多 spec 并行时状态清晰。

### 5. 外环失败分类：code-level vs design-level
**现象**：`ls/itest.md` 失败**一律回喂 `/ls:code` 修实现**。但集成测试暴露的可能是**设计缺陷**（如复盘中 Task5 才发现的"REPL 与渠道共享 agent"枢轴），此时磨代码是徒劳。
**建议**：外环失败先**归类**：(a) 实现级（断言/边界/接线）→ 回 `/ls:code`；(b) 设计级（承重假设错、架构不支持）→ **停下升级到 spec 回改或人工**，不进 code 循环。把"3 轮无进展"与"识别为设计级"并列为两条升级触发。
**代价**：需要 LLM 判断类别（可给判据）；**收益**：避免在设计缺陷上空转，呼应复盘的"设计枢轴"经验。

### 6. 内环质量门扩展：lint / typecheck / security-scan（可选）
**现象**：内环门 = 编译 + 单测 + 覆盖率。`security.md` 只在"触碰 auth/输入/文件/外部调用"时**由人记得**触发；lint/format/类型检查未纳入。
**建议**：config 增补可选字段 `lint` / `typecheck` / `security-scan`（缺省 `none` → 跳过，零回归）。内环组退出门：若配置了则一并跑绿。敏感目录变更自动提示走 `security-reviewer`。
**代价**：配置项增多；**收益**：质量从"规约里写了"变"门里卡了"，且默认关闭不影响现状。

### 7. `/ls:spec` 显式"研究复用"步骤
**现象**：`development-workflow.md` Step 0 强制"先查再造（GitHub/docs/registry）"，但 `ls/spec.md` 的生成流程没有把它做成显式步骤，复用结论也没落进 design。
**建议**：`ls/spec.md` 在生成 design 前加一步"复用扫描"，把"已有 X 可复用/可移植 / 决定自研因 Y"写进 `design.md` 的 Decisions。`ls/clarify.md` 已问"可复用性"，此处做实。
**代价**：spec 阶段稍慢；**收益**：减少重复造轮子，决策留痕。

### 8. 卡住时"升级/拆分"而非只"停下"
**现象**：`ls/code.md`「同一 task 连续 3 次修不好 → 停下报告」、外环同理。只有"停"，没有"缩小范围/拆 task/回改 design"的中间档。
**建议**：卡住时给三选一：(a) **拆分**当前 task 为更小步；(b) 判定为 **design 缺陷** → 回 spec；(c) 升级人工。让"停下"成为最后手段而非唯一手段。
**代价**：判断逻辑；**收益**：更少无谓中断，更快收敛。

---

## 五、P2（闭环增强 / 效率）

> **状态：#9–#13 已落地（2026-07-22）**——同步改到两处 `commands/ls/{archive,status,spec,itest,code,dev}.md`：#9 归档前抽查 spec↔实现一致性 + 敏感 diff 强制 `security-reviewer`；#10 `/ls:status` 增依赖违例告警 `⚠ 依赖未就绪`；#11 归档收尾把踩坑写回（ls=`ls-pipeline.config.md` 备注段 / pig=`docs/ai-dev-pipeline.md` 复盘）；#12 `/ls:spec` 给需 IT 的 task 打 `[IT]` 标、`/ls:itest` 据标决定范围；#13 code/dev 续跑读增量不重读全量 proposal。至此 13 项优化全部落地。

### 9. 归档前实现↔spec 一致性快检 + 敏感 diff 安全门
**现象**：`ls/archive.md` 检查 artifacts/tasks 完成度并 sync delta→主 spec，但**不校验实现是否真的兑现了 spec**（spec 漂移风险），敏感变更也无强制安全评审。
**建议**：归档前加轻量一致性快检（spec 的每条 Requirement 是否有对应实现/测试佐证）；若 diff 触及 auth/输入/文件/外部调用/加密，**强制**过一遍 `security-reviewer` 再归档。
**代价**：归档稍慢；**收益**：主 spec 与代码不脱节，安全不靠"记得"。

### 10. 多 spec 依赖违例告警
**现象**：`ls/clarify.md` 产出依赖表（并行/顺序），规则说"后一个 spec 在依赖归档后才细化 tasks"，但**无检测**——并行会话/worktree 下易违例（复盘提过跨分支重复归档冲突）。
**建议**：`ls/status.md` 增加"依赖视图"：若 spec B 已起 tasks 而其依赖 A 未归档，标 `⚠ 依赖未就绪`。依赖关系存进各 change 的 proposal/design 或一个顶层清单。
**代价**：status 逻辑增强；**收益**：并行开发少踩依赖坑。

### 11. 归档时把教训写回 config「备注/踩坑」
**现象**：踩坑靠人手写进复盘文档；模板 config 已留「备注/踩坑」段但无人喂。
**建议**：`ls/archive.md` 收尾时提示"本轮有无值得沉淀的工具链/流程踩坑？"，一句话追加到项目 `ls-pipeline.config.md` 的备注段——把"沉淀经验"制度化进流水线。
**代价**：极小；**收益**：经验随项目累积，下轮 agent 直接读到。

### 12. itest 触发判定显式化
**现象**：`ls/itest.md` 跳过与否靠 LLM 判"本变更是否涉及需真模型验证的端到端行为"或 config `integration-test: none`——项目**有** IT 但**本变更**无 IT 的场景判定模糊。
**建议**：让 `ls/spec.md` 在 tasks 里**给需 IT 的任务打标**（如 `[IT]`），`ls/itest.md` 据标决定跑哪些/是否跳过，减少误跑误跳。
**代价**：spec 需打标；**收益**：外环触发确定化。

### 13. 上下文重复读取优化
**现象**：code/itest/archive 每次可能重读全量 proposal/design/tasks/spec + contextFiles，长 spec 下 token 重复消耗、影响 prefix-cache。
**建议**：命令内约定"首次载入后，续跑只读 design 的 Decisions + tasks 勾选状态（增量），不重读全量 proposal"；依赖 `openspec status --json` 的增量信息。
**代价**：需谨慎不丢上下文；**收益**：省 token、更快。

---

## 六、建议的落地批次

- **第一批（P0，直击真实踩坑，改动小）✅ 已落地**：#1 逐组提交、#2 组级回归、#3 可判定的外环护栏。三项都改 `ls/code.md`/`ls/itest.md` 文案 + config 增补，风险低、收益即时。
- **第二批（P1，鲁棒性/闭环）✅ 已落地**：#4 状态持久化、#5 失败分类、#7 复用步骤、#8 卡住升级、#6 质量门（可选字段，默认关）。
- **第三批（P2，闭环/效率）✅ 已落地**：#9–#13。

> 每项落地都应**同步改两处**（pig-agent 源 + ls-pipeline 模板），并保持"命令读 config、不硬编码工具"的解耦原则；新增 config 字段一律**可选、缺省安全**（不填 = 现状，零回归）。

## 七、非目标 / 明确不动

- 不改五阶段骨架与三道人工门（已验证有效）。
- 不引入新的外部依赖或替换 openspec。
- 不把"自动"扩张到人工门（澄清/spec/归档仍必须人工确认）。
- 不为优化牺牲"复用而非重造"原则。

---

**评审请聚焦**：P0 三项是否认可先落地？#5（失败分类）与 #9（归档一致性/安全门）是否值得进 P1？是否有你实践中遇到但本稿未覆盖的痛点需要补入？
