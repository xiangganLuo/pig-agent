---
name: "LS: Dev"
description: 全流程总控——串联 clarify→spec→[code⇄itest 外环]→archive 的半自动开发流水线
category: Workflow
tags: [workflow, ls-pipeline, orchestrator]
---

半自动 AI 开发流水线**总控**。端到端串联五阶段，显式驱动两层 loop engine，尊重人工门。

**Input**: `/ls:dev` 后跟一句需求描述（缺省时先问）。

**流程（半自动，人工门用 ⏸ 标注）**

```
⏸ 需求澄清   → /ls:clarify  （AskUserQuestion 问清 + 建 <type>/<name> 分支，人工确认）
⏸ spec 设计  → /ls:spec     （opsx:propose 生成 + validate --strict，人工审批 design/tasks）
┌─ 外环（自动，反复至绿）───────────────────────────┐
│  编码⇄单测 → /ls:code   （内环：逐 task TDD，mvn test/compile 绿）│
│  集成测试  → /ls:itest  （*IT 真模型；失败回喂 /ls:code 修复重跑）│
│  连续 3 轮无进展 → ⏸ 升级人工                        │
└──────────────────────────────────────────────────┘
⏸ 归档       → /ls:archive  （opsx:archive 同步主 spec + 移 archive，人工确认）
```

**Steps**

0. **续跑先恢复状态（P1，断点续跑）**：若是中途重入（会话重启/被打断），先执行 `/ls:status <name>` 读回 当前阶段 / 外环轮次（`.ls-itest-log.md`）/ spike 状态，再从对应阶段继续，不从头重来。

1. **澄清 + 建分支**：执行 `/ls:clarify` 逻辑。**在人工确认门停下**，得到确认再继续。
2. **spec**：执行 `/ls:spec` 逻辑。**在人工审批门停下**，批准后继续。若有 Spike 卡点，先做 spike，不过则停。
3. **外环循环**（自动）：
   - a. `/ls:code`：跑内环把所有 task 做绿（单测 + 编译）。
   - b. `/ls:itest`：跑 `*IT`；失败 → 回 a 修复 → 重跑 b。
   - c. 全 `*IT` 绿则退出外环；连续 3 轮无进展 → 停下升级人工。
   - 纯离线变更无 `*IT` → itest 跳过，内环绿即视为通过。
4. **归档**：执行 `/ls:archive` 逻辑。**在人工确认门停下**，确认后归档。

**自动 / 暂停策略（半自动核心）**
- **自动**：编码内环、单测、集成测试、失败回环。
- **人工门（必停）**：① 需求澄清确认 ② spec 审批 ③ 归档确认。
- **异常停下**：歧义、阻塞、设计需回改 spec、安全敏感改动、连续 3 轮无进展。

**Output（阶段推进时持续汇报）**
```
## /ls:dev 流水线：<name>（分支 <type>/<name>）
[✓] 澄清  [✓] spec  [进行中] 外环 code→itest（第 K 轮）  [ ] 归档
<当前阶段的关键结果>
```

**Guardrails**
- 每个人工门必须真正停下等确认，不得自行越过。
- 复用 `/ls:clarify|spec|code|itest|archive` 与其委托的 `/opsx:*`，不另起炉灶。
- 外环禁止无限重跑：3 轮无进展升级人工。
- 全程只动 spec + 产品代码 + tasks 勾选，不碰无关文件。
- 续跑读增量（design Decisions + tasks 勾选状态），不重读全量 proposal，省 token 护 prefix-cache（P2）。
