# AI 开发流水线（`/ls:*`）—— 强制流程

> **本规约为强制约束（MUST）。** 任何"写代码实现某个需求/特性/修复"的任务，都必须走这条流水线。不得跳阶段、不得越人工门、不得自行替用户拍板。违反即视为流程错误，须停下纠正。

## 适用范围

- **必须走**：新特性、需求实现、非平凡 bug 修复、重构、涉及 spec/产品代码的改动。
- **可不走**：纯问答/调研、单行 typo、无行为变更的说明性改动。拿不准时，按"必须走"处理。

## 五阶段与三道人工门

```
⏸ 需求澄清   → /ls:clarify   （AskUserQuestion 问清 + 拆分 + 建 <type>/YYYYMMDD-<name> 分支）
⏸ spec 设计  → /ls:spec      （/opsx:propose 生成 proposal/design/tasks + delta spec，validate --strict）
┌─ 外环（自动，反复至绿）──────────────────────────┐
│  编码⇄单测 → /ls:code   （内环：逐 task TDD，mvn test/compile 绿）│
│  集成测试  → /ls:itest  （*IT 真模型；失败回喂 /ls:code）        │
│  连续 3 轮无进展 → ⏸ 升级人工                                   │
└────────────────────────────────────────────────┘
⏸ 归档       → /ls:archive   （/opsx:archive 同步主 spec + 移 archive）
```

**三道人工门（MUST 真正停下等确认，禁止自行越过）**：① 需求澄清确认　② spec 审批　③ 归档确认。

## 硬性规则（MUST）

1. **从 `/ls:clarify` 起步，不得跳到 `/ls:spec`。** 澄清阶段负责问清需求 **并做多 spec 拆分**；跳过它 = 跳过拆分与人工确认门。这是必须避免的头号流程错误。
2. **大需求必须拆成多个可独立上线的 spec，且拆分方案须人工审。** 不得由执行方单方面决定拆几个、怎么拆。拆分结果要落进设计文档的「Spec 拆分」清单（含依赖关系与顺序/并行标注）。
3. **每个 spec 独立走完整流水线**：`clarify(确认) → spec(审批) → code⇄itest → archive(确认)`。**后一个 spec 在其依赖的前一个 spec 归档后才启动 tasks 细化**——依赖未定就写全 tasks 会返工。
4. **依赖关系要显式标注**：顺序依赖 vs 可并行。不得把"顺序依赖"误标成"并行"。
5. **评审/待优化发现项必须显式落进 spec，不留无归属 backlog。** 每一项要在对应 spec 的 `design.md` 里用「落实追踪表」映射到：落点（哪条决策/哪个 task/延后哪个 spec）+ 状态（已实现/延后）。设计文档若也保留同名清单，须同步标注状态，不得停留在"待补实"这类悬空措辞。
6. **每道人工门必须真正停下。** 歧义、阻塞、设计需回改 spec、安全敏感改动、连续 3 轮 itest 无进展 —— 一律停下升级人工，不得强行推进。
7. **只动三类东西**：spec artifacts + 产品代码 + tasks 勾选。不碰无关文件。
8. **复用既有命令**：`/ls:clarify|spec|code|itest|archive` 与其委托的 `/opsx:*`，不另起炉灶、不手搓 openspec 结构。

## 分支约定

- 命名格式：**`<type>/YYYYMMDD-<功能名>`**（`YYYYMMDD`=建分支当天日期，`功能名` kebab-case），例如 `feat/20260715-plugin-collection`。
- `type` 前缀：`feat`（特性）、`bug`（修复）、`docs`（文档）、`opt`（优化）。
- `bug/` 分支的提交信息仍用 conventional-commit 的 `fix:`。
- 从 `origin/main` 拉分支；多 spec 可各自分支（依赖允许时并行）。

## 自动 / 暂停边界

- **自动推进**：编码内环、单测、集成测试、失败回环（外环至绿）。
- **必停（人工门）**：需求澄清确认、spec 审批、归档确认。
- **异常停下**：歧义 / 阻塞 / 设计需回改 spec / 安全敏感 / 连续 3 轮无进展。

## 阶段推进时持续汇报

```
## /ls 流水线：<name>（分支 <type>/YYYYMMDD-<name>）
[✓] 澄清  [✓] spec  [进行中] 外环 code→itest（第 K 轮）  [ ] 归档
<当前阶段的关键结果 + 下一步>
```

## 关联规约

- [development-workflow.md](development-workflow.md) —— 研究/复用、TDD、代码评审等阶段内做法。
- [git-workflow.md](git-workflow.md) —— 提交信息与 PR 规范。
- [testing.md](testing.md) —— 内环单测 ≥80% 覆盖、外环 `*IT`。
- 完整指南与实战复盘：`docs/ai-dev-pipeline.md`。
