# Wave-1 交付复盘（2026-07-20）

内核三方向（工具体系 / 技能体系 / 记忆大脑）路线图的 **Wave-1** 已全链路交付并推送 `main`。

## 背景

2026-07-20 完成三方向并行规划（18 候选 spec，分 3 波），经用户拍板 4 项关键决定：
1. Wave-1 三线各起 1 个；
2. 技能体系 = 采纳原生 2.0 自学习闭环、藏 pig seam 后（不加厚自建）；
3. 立一个共享检索原语基础 spec（防三线评分漂移）；
4. 嵌入模型作 M-B 前置独立 spec（Wave-2）。

Wave-1 因此定为 4 个 spec，各自隔离 worktree + `feat/20260720-*` 分支并行开发，走 `/ls:spec → /ls:code` 流水线。

## 交付内容（4 spec，全部默认关 / 零回归 / 离线可验）

| spec | change | 能力 | 承重 spike | 关键约束 |
|------|--------|------|-----------|----------|
| **R0** | `shared-retrieval-primitive` | `Bm25Index`/`HybridRanker`/`Tokenizer` + 新 `SearchDocument{id,text}` 上提到内核包 `io.pigagent.core.search`，三线同源消费一套 ranker | HybridRanker 已纯泛型、Bm25Index 唯一耦合 `MemoryDocument`（低成本抽象） | 纯重构零行为变化；`hybrid-memory-search` 单测逐字不改仍绿；`MemorySearchIndex.search` 门面签名冻结；向量/嵌入层留 `memory.search`（YAGNI 收窄） |
| **T1** | `builtin-file-tools` | `editFile`(WRITE)/`searchFiles`/`findFiles`(READ_ONLY) 纯 Java 宿主无关工具 + T6 `ToolContext` builder 重构 | 无 | search/find 纯 Java 绝不 shell（PowerShell 无 grep 也能用）；复用凭据黑名单 + real-path 归一 + `{"error"}` 契约；T6「builder≡旧构造」单测锁零回归 |
| **M-A** | `memory-retrieval-injection` | RAG 式按需注入取代「整份 MEMORY.md 每回合塞满」：pinned 核心常驻缓存前缀 + query-aware top-K 走 ephemeral trailing | **prefix-cache 红线**：`onSystemPrompt` 签名不含 query → pinned 对不同 query 字节恒等；query-aware 只出现在 `onReasoning` trailing Msg（结构不变式离线证明，14 测试） | 默认 `enabled=false` 逐字节等于今日；只依赖 `MemorySearchIndex.search` 稳定门面、BM25-only、embedder=null 优雅降级 |
| **S1** | `native-skill-engine-bridge` | 采纳 AgentScope 2.0 原生技能引擎当**库**：`NativeRepositorySkillSource` 包 `FileSystemSkillRepository` 喂 `SkillRegistry` | **门整条技能线**：原生 `SkillCurator`/`SkillPromotionGate`/`SkillUsageStore` 纯库驱动（不装任何原生 middleware/prompt-provider）+ 解析等价——**可运行验证 GREEN** | 继续 `disableDynamicSkills`、绝不重开 `<available_skills>` 注入、不注册原生技能工具、`SkillsTool` @Tool + system prompt 字节稳定（红线守卫单测锁）；`skills.native.enabled` 默认 false |

## 集成与验证

- **合并顺序**：R0（基座，先合）→ T1 → M-A → S1，冲突全部手工解净（CLAUDE.md ×2 记忆段落、`AgentBootstrap` ×1 ToolContext builder vs S1 nativeSkillsCfg）。
- **main 全量 `mvn clean test` = BUILD SUCCESS**，16/17 模块 0 失败 0 错误（新增 `MemoryInjectionWiringTest` + CLI 202 测试全绿）。
- **归档**：4 个 delta spec 同步为主 spec（`openspec/specs/{shared-retrieval,builtin-file-tools,memory-retrieval-injection,native-skill-engine-bridge}/spec.md`），change 移入 `openspec/changes/archive/2026-07-20-*`。
- **推送**：`main -> main`（`3ef53ca`）。
- **清理**：4 个 worktree + 8 个临时分支删净，仅留 `main` + `v1-stable-20260716`。

## 诚实局限（延后到 Wave-2 / live-model `*IT`）

- **M-A 6.1** `MemoryRetrievalInjectionIT`：真机 prefix-cache 命中率 + 语义召回相关性（结构不变式离线已证，真机观测延后）。
- **S1**：技能蒸馏质量、`umbrellaPassMode` 语义合并质量属 live-model 项（本轮只落确定性状态机 + 采纳桥）。
- **live *IT 未跑**：四 spec 默认关且默认路径字节等价，全链路 IT 不走新路径；且默认模型此前 403。按 `/ls:itest`「纯离线变更无对应新 *IT → 跳过并说明」处理。

## 下一步（Wave-2 起点，见 backlog）

- **E0 嵌入模型层**（M-B 前置，独立）：`StoredModel` 嵌入类别 + `/model` 选嵌入模型 UX + `/embeddings` 连通性测试。
- 随后：M-B 真嵌入落地、M-E 画像自动蒸馏默认开、T2 hybrid `tool_search`（建 R0 上）、T3 `/tools`+可观测、S2 skill matching（建 R0 上）、S3 技能 curator+usage+分级晋级（建 S1 上，采纳原生 `SkillCurator`/`PromotionGate`）。
- 交叉点已定 owner：R0=三线检索唯一真源；程序性记忆归 SKILL.md、声明性事实归 MEMORY.md；`ToolRiskClassifier` 中央表统一改；fail-closed/HITL 复用同一确认循环。
