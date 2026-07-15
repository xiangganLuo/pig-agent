# deerflow 借鉴改进 —— 迭代计划（2026-07-15 夜，自主执行）

> 来源：deerflow book 学习（`docs/` 无书本身，学习结论见本轮对话）。用户拍板把 A1/A2/A3/A4/A5/A8 纳入迭代。
> 落点：**v1 `main`**（当晚可交付的活产品）。AgentScope 2.0 全量迁移是独立长线（见 `agentscope-v2-migration.md`），不在本迭代内。
> 执行方式：每项一个独立 `/ls` spec，隔离 worktree 自主跑到归档，逐个合并回 `main`（在主树驱动合并）。分支命名 `feat/20260715-<name>`。

## 纳入的 6 项（A 档：2.0 也没有 / novel）

| 编号 | spec | 内容 | 落点 | 波次 |
|---|---|---|---|---|
| A1 | loop-detection | 工具调用循环检测：`LoopDetector`(hash+滑窗) + PreActing hook，warn@3/stop@5，read_file 行段分桶 | pig-agent-core hook + AgentBootstrap | **Wave 1** |
| A2 | deferred-tools | deferred 工具 + `tool_search`：大/罕用工具移出初始 schema、运行时按需检索，省 token | pig-agent-tools + AgentBootstrap | **Wave 1** |
| A8 | sandbox-warn-tier | 命令沙箱 warn 三级（block/warn/pass）：pip/sudo/chmod777 运行但注入 ⚠️ 注记（exec-sandbox 的 D12 收尾） | pig-agent-tools CommandGuard | **Wave 1** |
| A3 | composite-skill | 复合 Skill：SKILL.md 前置元数据 + 支持目录 + 渐进加载 + 内容安全扫描 + 安装加固（zip-bomb/符号链接/穿越/原子写） | pig-agent-skills-builtin + SkillsTool | **Wave 2** |
| A4 | memory-extraction | 记忆 LLM 抽取：分类事实 + confidence≥0.7 + correction 高置信 + 异步去抖写 + 过滤工具噪声/剥离会话级事件 | pig-agent-core memory | **Wave 2** |
| A5 | context-engineering | 上下文增强：三层预算 + 递归摘要 + 重要度选择性保留 + 压缩前后一致性校验（代码/精确文本不递归摘要） | pig-agent-core compression | **Wave 2** |

## 排期分析（我的判断）
- **Wave 1 = A1 / A2 / A8**：自包含、低风险、v2 无关（v2 里也用得上）。先跑。
- **Wave 2 = A3 / A4 / A5**：A3 较大（技能体系）；A4/A5 触碰 core 记忆/压缩。Wave 1 合并后再起，控资源与 AgentBootstrap 合并冲突。
- **合并冲突热点** = `AgentBootstrap`（A1/A2/A3 都接线）——逐个合并时解决（本轮已多次实践）。
- A4/A5 与 AgentScope 2.0 原生分层记忆/结构化压缩重叠：本迭代在 v1 交付；v2 迁移时按 `agentscope-v2-migration.md` 的"evaluate vs native"再取舍（不浪费——设计/测试/洞察可迁移）。

## 状态（滚动）
- Wave 1：✅ **全部合入 `main` 且全量测试 BUILD SUCCESS**。
  - A1 loop-detection（`16293ca`/`a0994ae`，808 测试）→ merge 合入。
  - A8 sandbox-warn-tier（`8b91aa2`/`503df36`，823 测试）→ merge 合入（自动解决相邻改动）。
  - A2 deferred-tools（`f3c9cc1`/`d16f51f`，815 测试）→ merge `c491cef`（AgentBootstrap 冲突已解=保留两 helper 方法）。
  - 收口：`main` 全量 `mvn test` = BUILD SUCCESS，0 失败/0 错误。
- Wave 2：✅ **全部合入 `main` 且全量测试 BUILD SUCCESS**。
  - A3 composite-skill（`b286c04`/`45fa3e1`）→ clean merge。
  - A5 context-engineering（`198fc2d`/`1952a5c`，927 测试）→ clean merge（默认值保持旧行为，现有压缩测试零改动）。
  - A4 memory-extraction（`09c35b6`/`5ca1534`，954 测试）→ merge `a11c9fc`（CLAUDE.md 两处冲突已解=模块表合并 + 保留 A4/A5 两段、删旧压缩段）。
- **最终收口**：`main` 全量 `mvn test` = BUILD SUCCESS，**1015 测试 / 161 套件，0 失败/0 错误**（2 个真模型 *IT 按约跳过）。
- 6 个子任务 worktree 已清理；分支 `feat/20260715-*` 均保留（已并入 main 的 merge 提交）。
- 合并/归档前流程：全部自主处理，绿了合 main。

## 并行长线：AgentScope 2.0 迁移
- Phase 0 ✅ 完成（`av2/20260715-foundation`）：2.0 制品可解析、core+providers 在 2.0 编译且 116 测试绿、两个风险等价点（ephemeral-memory prefix-cache / permission veto）均已复刻、逐模块迁移地图产出（`docs/planning/agentscope-v2-migration.md`）、无 blocker。
- v2 集成线：worktree `agentscope-v2`（branch `worktree-agentscope-v2`，基于最新 main）；Phase 0 成果待并入。
- v1 备份：`main-v1-backup` + tag `v1.0.12-final`。

## 明天交付
用户回来将看到：`main`（v1）合入 A1-A5,A8 后的增强产品（全量测试绿）+ AgentScope 2.0 迁移 Phase 0 里程碑（可行性验证 + 迁移地图）。
