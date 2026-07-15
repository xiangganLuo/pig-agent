# 今夜交付小结（2026-07-15 → 07-16 晨）

> 用户下班前授权：A1-A5/A8 纳入迭代、排期与归档前流程全自主处理、明晨看整个产品交付结果。以下是结果。

## 一句话
`main`（v1）合入 **6 个 deerflow 借鉴改进**，全量测试 **1015 测试 / 161 套件全绿（0 失败 0 错误）**；AgentScope 2.0 迁移 **Phase 0 里程碑**完成（可行性验证 + 迁移地图）。产品定位（北极星）已锁定成文。

## 一、v1 增强：6 个改进已合入 main（两波）

| # | spec | 价值 | 落点 | merge |
|---|------|------|------|-------|
| A1 | loop-detection | 工具调用循环检测：滑窗签名，warn@3/stop@5，read_file 行段分桶；第 5 层正交安全网，防死循环烧 token | core hook + AgentBootstrap | `16293ca`→合 |
| A2 | deferred-tools | 大/罕用工具移出初始 schema + `tool_search` 按需检索（AgentScope 原生 tool-group「注册但隐藏」），随 MCP 增多省提示词 token | tools + AgentBootstrap | `f3c9cc1`→`c491cef` |
| A8 | sandbox-warn-tier | 命令沙箱 **block/warn/pass 三级**：pip/sudo/chmod777 运行但注入 ⚠️ 注记，不改 block/pass 契约 | tools CommandGuard | `8b91aa2`→合 |
| A3 | composite-skill | 复合 Skill：SKILL.md 前置元数据 + 支持文件 + **渐进加载**（列表只读元数据省 token）+ 遍历/符号链接/尺寸加固 | skills + skills-builtin | `b286c04`→合 |
| A5 | context-engineering | 上下文增强：三层预算 + 递归摘要 + 重要度保留 + **代码/精确文本保护** + 一致性校验→安全回退 | core compression | `198fc2d`→合 |
| A4 | memory-extraction | 记忆 LLM 抽取：分类事实 + 置信度门(0.7) + 纠正覆盖 + 异步去抖 + 噪声过滤（默认关，Decorator 装饰旧记忆） | core memory + session | `09c35b6`→`a11c9fc` |

**共性**：全部设计模式导向（Strategy/Decorator/Factory/枚举登记/值对象），默认值保持旧行为（向后兼容），离线单测覆盖，各自走完整 `/ls` 到 openspec 归档，逐个合并回 main 解冲突后全量验证绿。

**冲突处理**：A2 与 A4 在 `AgentBootstrap`/`CLAUDE.md` 各有一处冲突，均按"保留双方新增、删过时旧段"人工解，编译+全量测试双绿。

## 二、AgentScope 2.0 迁移：Phase 0 里程碑
- 2.0 制品可解析；core + providers 在 2.0 编译且 116 测试绿；两个高风险等价点（ephemeral-memory 的 prefix-cache 语义、permission veto 语义）均已复刻；逐模块迁移地图产出 → `docs/planning/agentscope-v2-migration.md`；**无 blocker**。
- 集成线：worktree `agentscope-v2`（分支 `worktree-agentscope-v2`）。备份：`main-v1-backup` + tag `v1.0.12-final`。
- 长线，不在本夜合并范围。

## 三、产品定位锁定（北极星）
`docs/planning/product-north-star.md` + 记忆 `pig-agent-positioning`：
> **24h 不停转、CC 之上的超级助手：编排 CC 干编程、自有多协议大脑、多渠道触达人、专注解决人的事情；底层 harness 外包给 AgentScope 2.0。**
- 旗舰新方向（待你确认后启动）：**CC 编排能力**（`cc-orchestration`）——把 Claude Code 当被编排的子 agent。
- 由「24h + 主动 + 多渠道」引出：常驻守护进程、**主动外呼/通知**、渠道模态扩展。

## 四、当前分支拓扑
- `main` = v1 增强线（HEAD `a11c9fc`，含 6 改进，全绿）。
- `main-v1-backup` + tag `v1.0.12-final` = 迁移前备份。
- `worktree-agentscope-v2`（worktree）= v2 迁移线。
- `feat/20260715-*`（6 条）= 已并入 main 的特性分支（保留可查）。

## 五、下一步建议（待你拍板）
1. **CC 编排能力** 作为迁移后旗舰自研（先设计一次 spike）。
2. **AgentScope 2.0 Phase 1** 按迁移地图推进（session-state / tools-permission / cli-event-model）。
3. 生产化 Tier 0（`build-jdk17-gate` / `runnable-distribution` / `ci-pipeline`，见 `production-roadmap-2026-07-15.md`）——注意 **JDK 17 下 `mvn test` 需 `-Djdk.attach.allowAttachSelf=true`**（本夜绿在 JDK 21）。
4. A 档剩余（A6 签名审计 / A7 MCP OAuth）视需要。

> 已知限制（诚实记录）：A4/A5 的模型侧质量只经 mock 验证，真实抽取/摘要质量需 `*IT`；A8/沙箱是 best-effort 正则而非对抗边界；A3 符号链接用例在 Windows 自动跳过。详见各 spec 的 design.md 与归档。
