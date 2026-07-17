# Personal-Assistant Core 详细设计（记忆 + 自主沉淀 skills）

> 阶段：`/ls:spec` 设计（仅设计，无产品代码）。分支 `feat/20260717-pa-core`（隔离 worktree，off `main` = 2.0 主线）。
> 本文是「个人助理核心能力」重设计的单一事实源：基准对标 → 记忆设计 → 自主 skills 设计 → 参考能力菜单 → 前提挑战 → OPEN DECISIONS → spec 拆分。
> 触发：一个真 bug —— 助理跨 `/session new` 记不住你的名字（`bug/20260717-cross-session-memory` 上有 RED 复现 `CrossSessionMemoryTest`）。用户要求**按行业蓝本重设计**，而非打补丁。

---

## 0. TL;DR（先给结论）

1. **根因（结构性，非 v2 回归）**：pig 自建的 `CompositeLongTermMemory.record()` **只写会话层**，全局层（`workspace/context/memory.md`）只被读、从不被 `record` 自动写。`/session new` 换掉会话层 → 之前说过的事实随之消失。这是 **v1 就有的设计缺口**。附带诱因：`FileSystemLongTermMemory.MIN_TEXT_LENGTH=20` 把「我叫 X」这类短事实直接丢弃。
2. **蓝本一致的修法**：AgentScope 2.0 `HarnessAgent` **原生**就实现了跨会话的两层记忆（`memory/YYYY-MM-DD.md` 日志层 → `MEMORY.md` 固化层，工作区级、每步注入 system prompt），以及 flush/consolidation/compaction 三段 LLM 流水线、`memory_search`/`memory_get`/`session_search`。这与 Hermes、OpenClaw 三家蓝本**高度同构**，且正是 pig 北极星「harness 外包给 2.0」的落点。**pig 目前主动 `disableMemoryHooks()/disableMemoryTools()` 关掉了它**，改用自建记忆——这正是缺口的来源。
3. **推荐主线**：**采用 2.0 原生两层记忆**（重开 memory hooks/tools，MEMORY.md 工作区级固化层直接修好跨会话），**退役** pig 自建 `CompositeLongTermMemory` 两层 + `FileSystemLongTermMemory` 的 20 字过滤 + A4 抽取（被原生 flush 取代）；**保留** A5 上下文工程压缩（pig 差异化，仍 `disableCompaction()`）；用**廉价模型（Doubao lite）**跑 flush/consolidation。
4. **pig 差异化叠加**（分期）：`USER.md` 用户画像（Hermes/Honcho 蓝本）+ **混合检索 BM25+向量**（OpenClaw 0.7/0.3 蓝本，原生 `memory_search` 仅关键词）。
5. **自主沉淀 skills**：把「解过的任务/工作流」蒸馏成可复用 `SKILL.md`，草稿→**人工门**→安装，去重/老化清理。可**采用 2.0 原生 self-learning loop**（`propose_skill`→`enableSkillPromotionGate`→`enableSkillCurator`），或**在 pig 现有 `WorkspaceSkillSource`+composite-skill 上自建 `proposeSkill`**。默认**人工门**（OpenClaw 模型，最稳）。
6. **⚠ 版本现实（必须先验证）**：2.0 的 `HarnessAgent` 原生记忆/skills API 在本机 **只有文档、无字节码可证**——本地 m2 只缓存了 `agentscope-1.0.12.jar`，POM 虽 pin `2.0.0`（`agentscope-core`/`agentscope-harness`）但 2.0 jar 不在本地。**实现前 P0**：对真实 2.0 jar `javap` 确认 `MemoryConfig`/`HarnessAgent.Builder.memory(...)`/`MemoryFlushManager`/`memory_search` 等 API 后再动手。全文凡 2.0 原生 API 均标 `（需 javap 验证）`。

---

## 1. 触发与目标

**Bug**（`bug/20260717-cross-session-memory`，`CrossSessionMemoryTest`，`pig-agent-core`）：
- 场景：会话 A「我叫罗湘赣…」→ `/session new`（`SessionManager.activate → setSessionMemory(fresh B)`）→ 会话 B 问「我叫什么？」→ 记不住。
- 关键断言（RED 现状：前两条在 `main` 通过=坐实诊断，第三条失败=期望行为未实现）：
  ```java
  assertThat(sessionA.isEmpty()).as("fact was recorded to session A's tier").isFalse();
  assertThat(global.isEmpty()).as("global tier is never auto-grown by record() — the root cause").isTrue();
  assertThat(recalledInB).as("name stated in session A should be recalled in session B").contains("罗湘赣"); // FAILS on main
  ```
- 测试用 `InMemoryTier` fake 隔离**路由**缺口（避开 `FileSystemLongTermMemory` 的 20 字过滤），Javadoc 明确「两者都贡献，但跨会话路由是结构性根因」。

**目标**：让 pig 成为「记得住你、越用越懂你、会自己沉淀经验」的 24h 个人助理——对齐 `docs/planning/product-north-star.md`（CC 之上的 super-assistant、harness 外包给 2.0、差异化在上层）。**这是重设计不是补丁**：核心记忆改为行业蓝本一致的两层跨会话模型，并规划用户画像、自主 skills、混合检索。

**非目标**：不做多用户/多租户记忆隔离（pig 单用户 `userId="pig"`）；不在本 spec 引入向量库（分期）；不改渠道/自主 agent 的既有权限与 fail-closed 语义。

---

## 2. 基准对标表（Benchmark）

维度：跨会话持久 / 两层 log→固化 / 自动 flush 抽取 / 固化去重 / 混合检索(关键词+向量) / 用户画像建模 / 经验沉淀 skill(是否自主) / 注入策略 / 本地优先。

| 维度 | **pig（现状）** | **AgentScope 2.0 原生** | **Hermes（Nous）** | **OpenClaw（小龙虾）** |
|---|---|---|---|---|
| 跨会话持久 | ✗ **`record` 只写会话层，全局层从不自动增长（本 bug）** | ✓ `MEMORY.md` 工作区级、每步注入 | ✓ `MEMORY.md`+`USER.md` 每会话加载 | ✓ 日志+`MEMORY.md` 落盘 |
| 两层 log→固化 | ◐ 有全局+会话两层，但**无 log→固化 rollup**，全局层无自动写路径 | ✓ `memory/YYYY-MM-DD.md`→`MEMORY.md`（两层永不互覆） | ◐ SQLite 转录 与 curated `MEMORY.md/USER.md` 分离；固化=按量 agent 自编辑非定时 rollup | ✓✓ **最干净**：日志→`MEMORY.md`（被反复提及/确认/引用即提升） |
| 自动 flush 抽取 | ◐ A4 抽取（默认关、**仅会话层**、LLM 驱动）；`MIN_TEXT_LENGTH=20` 丢短事实 | ✓ 每次 `call()` 末 flush（异步、LLM、**无字数过滤**） | ✓ 回合后后台 self-improvement review（agent 托管） | ✓ ~75% 上下文静默 flush（compaction 前落盘） |
| 固化去重 | ◐ A4 `FactMerger` 按 subject 去重（仅会话层）；**无全局固化** | ✓ 后台节流 consolidation（LLM 合并+去重、整文件重写） | ✓ 精确匹配去重；溢出时 agent 自合并 | ◐ 按重复/确认提升；显式去重算法未文档化 |
| 混合检索(关键词+向量) | ✗ 整文件读、尾截 3000 字，无检索无向量 | ◐ `memory_search` **仅关键词**（≤30 命中）；向量仅经外接 mem0/bailian | ◐ FTS5 关键词；向量仅经外接 Mem0/Honcho | ✓✓ **BM25+向量、精确 0.7/0.3**（sqlite-vec+FTS5，候选×4） |
| 用户画像建模 | ✗ | ✗（`MEMORY.md` 是通用事实，无专门用户模型） | ✓✓ **`USER.md`+Honcho 辩证用户模型**（最强） | ◐ 隐式经 `MEMORY.md/USER.md` 提升，无辩证引擎 |
| 经验沉淀 skill(自主?) | ✗ `SkillsTool` 只读（listSkills/loadSkill，均 readOnly） | ✓ **`propose_skill`→gate→curator** 自学习环 | ✓ `skill_manage` 自主；`write_approval` 门**默认关**（自由写） | ✓✓ Skill Workshop 自主提案 + **强制人工审**（最稳） |
| 注入策略 | ✓✓ **user 侧 ephemeral、system prompt 字节恒定**（前缀缓存最优） | ✓ `MEMORY.md` 冻结进 system prompt（会话内稳定；consolidation 时偶发失效） | ✓ 同 2.0（冻结进 system prompt）；Honcho 在 API 调用时注入 user 消息 | ✓ search-then-inject 仅相关命中（auto-recall） |
| 本地优先 | ✓ 工作区 Markdown | ✓ 工作区 Markdown | ✓ `~/.hermes/`（Honcho 除外=服务端） | ✓✓ Markdown 为源 + 每 agent SQLite，可版本化 |

**读表结论**：
- pig 唯一**领先**项是「注入策略」（user 侧 ephemeral 前缀缓存）——这是 pig 真差异化，重设计要**尽量保住**（见 §4 前提挑战 P2）。
- pig 在**跨会话持久 / 两层 rollup / 全局固化 / 检索 / 用户画像 / 经验沉淀**六项全面落后；而这六项**恰好是 2.0 原生 + 三家蓝本的共识能力**。→ 强烈指向「采用原生两层记忆 + 叠加差异化」而非继续扩自建。
- 三家蓝本的**独门强项**分别是：Hermes=用户画像（`USER.md`/Honcho）、OpenClaw=混合检索（0.7/0.3）与经验沉淀强制人工审、2.0 原生=开箱即得两层+flush/consolidation+curator。pig 差异化路线 = 站在 2.0 原生地基上，把 Hermes 的 `USER.md` 与 OpenClaw 的混合检索**都吸收**。

来源：2.0 原生 = `D:\Users\admin\Documents\en\docs\harness\memory.md`（:8/:10-11/:19-31/:42/:196-207/:228-235/:249-257/:263）、`skill.md`（:15-39/:183-190/:209-271/:275-307）、`integration\memory\{overview,mem0,reme,bailian}.md`；Hermes = hermes-agent.nousresearch.com/docs（memory/honcho/skills）；OpenClaw = docs.openclaw.ai（concepts/memory-builtin、reference/memory-config、tools/self-learning、tools/skill-workshop）+ gaodalie.substack、shivamagarwal7.medium；pig 现状 = 代码勘察（见 §3）。

---

## 3. pig 现状与「keep / replace」（重设计对照）

代码事实（`pig-agent-core/.../memory/*`、`pig-agent-tools/.../skills/*`、`pig-agent-cli/.../AgentBootstrap.java`）：

| pig 组件 | 现状 | 重设计动作 | 理由 |
|---|---|---|---|
| `CompositeLongTermMemory`（全局+会话两层，`record` 只写会话层） | 本 bug 根因 | **REPLACE**（退役，代之以原生两层记忆；如需过渡可留薄 shim 实现 `LongTermMemory` 转调原生） | 全局层无自动写路径，结构性缺口 |
| `FileSystemLongTermMemory`（`MIN_TEXT_LENGTH=20`、尾截 3000、单条 200 字截断、跳 TOOL/JSON） | 附带诱因（丢短事实） | **REPLACE**（原生 flush 由 LLM 决定落什么，无字数过滤） | 20 字硬阈值丢「我叫 X」 |
| `EphemeralMemoryMiddleware`（onReasoning 注入 user 侧 ephemeral、onSystemPrompt identity、onAgent record；`CachingLongTermMemory` 包裹） | pig 唯一领先项（前缀缓存） | **RECONCILE**（见 §4 P2：默认改采原生 system-prompt 注入；或保留它注入 `USER.md` 画像于 user 侧以保字节恒定——OPEN DECISION OD2） | 权衡缓存经济性 vs 字节恒定 |
| A4 抽取 `memory/extraction/*`（`LlmMemoryExtractor`/`ConfidenceGate 0.7`/`FactMerger`/`MarkdownFactStore`，默认关、仅会话层） | 半成品、只作用会话层、不修 bug | **REPLACE**（原生 flush+consolidation 覆盖抽取+去重+固化，且工作区级） | 避免双抽取系统；原生更全 |
| A5 压缩 `compression/CompressionService`+`ContextEngineer`（递归摘要/重要度/逐字保护，仅内存对话，`disableCompaction()`） | pig 差异化，运作良好 | **KEEP**（继续 `disableCompaction()`，与原生记忆正交） | 比原生 compaction 更精细；不碰长期记忆 |
| 两层记忆的**会话层隔离** | 每会话独立 temp-memory | **DROP/DEMOTE**（原生记忆工作区级、按日期非按会话；个人助理**要**事实跨会话——正是修复方向） | 会话隔离与「记住你」相悖 |
| `SkillsTool`+`SkillSource`/`SkillRegistry`/`WorkspaceSkillSource`+composite-skill+`SkillSecurity`（只读 list/load） | 只读、无自建能力 | **KEEP + EXTEND**（读路径保留；自主沉淀在其上加写路径，见 §6） | 已投资的 SPI/加固，复用 |
| 原生记忆开关 `disableMemoryHooks()/disableMemoryTools()`（`PigAgent.Builder.build()` L500-509 / L626-634） | 关掉了原生两层记忆 | **FLIP**（重开 memory hooks/tools；改由 `MemoryConfig` 驱动） | 缺口来源；重开即修 bug |

**关于 `disableMemoryHooks()` 的历史理由需澄清**：迁移账本/Javadoc 写「native STATIC memory 在调用路径注入并被持久化进历史、逐轮累积」。**这描述的是被废弃的 1.x `StaticLongTermMemoryHook`（`LongTermMemory` STATIC 模式）**，pig 当年正确地拒绝了它并用 `EphemeralMemoryMiddleware` 修复。但 2.0 的 `disableMemoryHooks()/disableMemoryTools()` 关掉的是**另一套东西**——2.0 harness 的两层记忆（`MemoryFlushMiddleware`+consolidation+`memory_search`），它**不写会话历史**（flush 落 `memory/*.md` 日志、consolidation 重写 `MEMORY.md`、只把 `MEMORY.md` 注入 system prompt），前缀缓存友好（会话内 `MEMORY.md` 稳定）。**pig 很可能是用「针对旧废弃机制的理由」关掉了新的好机制**——这是本设计要挑战的头号前提（§4 P1）。

**javap 确认（1.0.12，仅供 SPI 形状参考——非 pig 2.0 运行时）**：`LongTermMemory` 接口即 `Mono<Void> record(List<Msg>)` + `Mono<String> retrieve(Msg)` 两法（pig `CompositeLongTermMemory` 实现之；CLAUDE.md 称该接口在 2.0「deprecated-but-present」）；`LongTermMemoryMode{AGENT_CONTROL,STATIC_CONTROL,BOTH}`；`StaticLongTermMemoryHook` 存在（即旧注入 hook）；外接后端 `Mem0LongTermMemory`（向量）/`ReMeLongTermMemory`（轨迹）/`BailianLongTermMemory`（托管+rerank/judge/rewrite）均 javap 确认。**2.0 的 `HarnessAgent`/`MemoryConfig`/`MemoryFlushManager`/`SkillManageConfig`/`SkillCuratorConfig` 在本地 m2 无 jar，均 `（需 javap 验证）`。**

---

## 4. 前提挑战（office-hours 严格度）

每条：**前提 → 2–3 备选（工作量/风险/利弊）→ 推荐**。对应的用户决策见 §5 OPEN DECISIONS。

### P1 — 采用原生两层记忆 vs 继续扩自建
**前提**：修跨会话最省的是「给全局层加个自动写路径」；但那只补一个洞，仍是自建、仍缺 log→固化/flush/consolidation/检索/用户画像。北极星是「harness 外包 2.0」。
- **A. 采用 2.0 原生 `MemoryConfig` 两层记忆**（推荐）。工作量：中（重开 hooks/tools + 廉价模型接线 + 退役自建 + 迁移旧 `memory.md`）。风险：中（原生 API 本地不可 javap 验证=P0 前置；MEMORY.md 进 system prompt 影响 pig 字节恒定，见 P2）。利：**跨会话开箱修好**；一并得 flush/consolidation/去重/`memory_search`/`session_search`；对齐三家蓝本 + 北极星；删大量自建代码。弊：受原生形状约束（工作区级、按日期）；偶发 consolidation 缓存失效。
- **B. 扩自建**：给 `CompositeLongTermMemory.record` 加「事实提升到全局层」+ 自建 consolidation/检索。工作量：大（等于重造 2.0 已有的）。风险：中高（自建 LLM 流水线的质量/维护）。利：完全掌控格式与注入、保住 user 侧 ephemeral。弊：**违背北极星**（重复造 harness）；长期维护负担；仍要自建检索/画像。
- **C. 外接后端**（mem0/bailian 做长期记忆）。工作量：中。风险：中高（联网依赖、隐私、非本地优先、需 API key）。利：现成向量检索。弊：**违背本地优先**；引入外部服务与凭据面；与「工作区 Markdown 可版本化」相悖。
- **推荐：A**。B 是「补洞式自建」，与北极星冲突；C 破坏本地优先。A 用原生地基 + §5 的差异化叠加，既修 bug 又对齐蓝本。**前置 P0：先 javap 确认 2.0 记忆 API。**

### P2 — 记忆注入：原生 system-prompt 冻结 vs 保留 pig 的 user 侧 ephemeral
**前提**：pig 现在把记忆放 user 侧末尾、system prompt 字节恒定（前缀缓存最优）；原生把 `MEMORY.md` 冻结进 system prompt（会话内稳定，consolidation 时变）。
- **A. 采用原生 system-prompt 注入**（推荐）。利：`MEMORY.md` 进入**可缓存前缀**（记忆本身被缓存，反而更省——pig 现方案记忆在末尾**永不进缓存前缀**、每轮重发）；Hermes 用的正是此法并称「保护前缀缓存」。弊：consolidation 重写 `MEMORY.md` 后**下一轮**system prompt 变一次（偶发一次缓存 miss，罕见=后台节流 ≥30min）。
- **B. 保留 `EphemeralMemoryMiddleware` 注入原生 `MEMORY.md` 于 user 侧**。利：system prompt 绝对字节恒定。弊：记忆每轮重发不进缓存前缀（更费 token）；要 hack 原生（读 `MEMORY.md` 自己注入、`disableMemory*` 只留读）——**与「采用原生」自相矛盾**、复杂度高。
- **C. 混合**：`MEMORY.md`（大、稳）走原生 system prompt；`USER.md` 画像（小、更稳）也进 system prompt；只把**易变小块**（如当前时间）留 user 侧。利：缓存经济性最好。弊：需精细划分注入位置。
- **推荐：A（长期倾向 C）**。缓存经济性上「稳定内容进 system prompt」优于「末尾重发」。放弃 pig 的绝对字节恒定洁癖，换取蓝本一致 + 更好缓存。**这项要用户拍板**（OD2）。

### P3 — 用户画像：`USER.md` 文件 vs 结构化存储 vs 外接 Honcho
**前提**：Hermes 证明「专门的用户画像」比「混在通用事实里」显著更懂用户（`USER.md` 1375 字 + Honcho 辩证建模）。
- **A. `USER.md` Markdown 文件**（推荐）。工作量：小-中（一个 profile 文件 + LLM 维护 + 稳定注入）。风险：低。利：Hermes 直接先例；本地优先、人可读、可版本化、可手编；与原生记忆同构（工作区 Markdown）。弊：非结构化、查询弱（但画像本就小、整体注入即可）。
- **B. 结构化存储**（JSON/SQLite 的 profile schema，含字段/置信度）。工作量：中。风险：中。利：可编程查询/校验、可与向量索引联动。弊：过度设计（画像小）；人不可读、难手编；YAGNI。
- **C. 外接 Honcho**（辩证用户建模服务）。工作量：中。风险：高（服务端、非本地、凭据、成本）。利：最强的用户建模（1-3 遍辩证推理）。弊：破坏本地优先；重依赖。
- **推荐：A**。画像小且需人可读/可版本化，Markdown 最贴 pig 的本地优先。辩证建模（Honcho 式「回合后离线推理更新画像」）可作为 `USER.md` 的**维护策略**用廉价模型本地实现，不必上 Honcho。

### P4 — 自主 skill 安装：人工门 vs 自动提升 vs 可配
**前提**：让 agent 写自己的行为（skill = 改变未来行为的可执行文档）风险高；两家蓝本处置相反——Hermes `write_approval` 默认关（自由写），OpenClaw **始终强制人工审**。
- **A. 默认人工门（OpenClaw 模型）**（推荐）。工作量：中（草稿区 + 审阅命令 + 提升）。风险：低。利：最稳；防 prompt 注入把恶意 skill 写进未来行为；符合 pig 的安全基线（权限 fail-closed、凭据不外泄）。弊：需人参与才生效（个人助理场景可接受——你就是那个人）。
- **B. 默认自动提升（Hermes 模型）**。工作量：小。风险：**高**（自写自用无门 = 自主放大攻击面）。利：无摩擦、越用越强最快。弊：安全不可接受为默认。
- **C. 可配（默认 A，开关到 B）**。工作量：中。风险：中（取决用户）。利：灵活。弊：给了危险开关。
- **推荐：A，并提供 C 的开关（默认人工门）**。渠道/自主 agent 场景（无 confirmer）下**必须 fail-closed = 只提案不安装**（沿用 pig 现有 ASK→DENY 语义）。

### P5 — 自主 skills 机制：采用原生 self-learning loop vs 在 pig `WorkspaceSkillSource` 上自建
**前提**：2.0 原生有完整 `enableSkillManageTool`(propose_skill)+`enableSkillPromotionGate`(LocalApprovalGate)+`enableSkillCurator`；但 pig 已 `disableDynamicSkills` 并用自己的 `SkillsTool` 读路径。
- **A. 采用原生 self-learning loop**。工作量：小-中（重开原生 skill 写工具 + gate + curator）。风险：中（原生 API 需 javap；**读路径耦合**——原生写工具写入原生 skill 目录，可能连带拉回原生 `<available_skills>`/`load_skill_through_path` 读法，与 pig `SkillsTool` 形成**双读系统**）。利：免费得 curator（老化/归档/umbrella merge）+ usage 统计 + gate；北极星一致。弊：与 pig 现有 skill 读栈冲突需消解。
- **B. 在 pig `WorkspaceSkillSource`+composite-skill 上自建写路径**（推荐）。工作量：中（`proposeSkill`/`skillManage` @Tool 写 `workspace/skills/_drafts/` → 人工门提升到 `workspace/skills/<name>/` → `WorkspaceSkillSource` 即时发现，无需重启）。风险：低（复用 pig 的 `SkillSecurity` 加固、`SkillManifestParser`、只读 `SkillsTool` 不变）。利：**读路径不动**（无双系统、无模型面回归）；安全加固复用；契合已投资的 SPI。弊：curator（去重/老化）要另外实现（可后续从原生移植思路）。
- **C. 混合**：写路径自建（B），去重/老化 curator 后续参考原生实现移植。
- **推荐：B（curator 走 C 分期）**。pig 既已投资 `SkillSource`/composite-skill/`SkillSecurity` 且已弃用原生 dynamic skills，B 摩擦最小、无双读系统。**这项也需用户拍板**（OD6）。关键事实：`SkillRegistry.all()/find()` 每次调用都 re-`discover()`，`WorkspaceSkillSource` 每次重扫目录——**新写的 skill 下一次 `listSkills`/`loadSkill` 即生效，无需重启**（写路径落地即用）。

### P6 — 会话层记忆的去留
**前提**：原生记忆工作区级（按日期非按会话）；pig 有每会话 temp-memory 隔离层。个人助理要「事实跨会话」，会话隔离与之相悖。
- **A. 去掉会话层，durable 事实全走工作区级**（推荐）。利：直接修 bug；与原生同构；简单。弊：失去「本会话临时便签」概念（但对个人助理无损）。
- **B. 保留薄会话层做 ephemeral 便签**（不进 durable）。利：保留会话隔离语义。弊：与原生并存复杂；YAGNI。
- **推荐：A**。会话隔离恰是 bug 的成因方向；退役之。

---

## 5. OPEN DECISIONS（请用户批量决策）

> 按 async 复审设计，一次性列出。方括号是本设计的推荐默认。

- **OD1｜记忆主线**：采用 2.0 原生两层记忆（P1-A）？还是扩自建（B）/外接（C）？**［推荐 A：采用原生］**
- **OD2｜注入策略**：采用原生 system-prompt 冻结注入（P2-A，放弃绝对字节恒定、换缓存经济性 + 蓝本一致）？还是保留 pig user 侧 ephemeral（B）/混合（C）？**［推荐 A，长期 C］**
- **OD3｜A4 抽取去留**：原生 flush 上线后**退役** pig A4 抽取（`memory-extraction` 能力）？还是保留为可选？**［推荐 退役］**
- **OD4｜用户画像形态**：`USER.md` Markdown（P3-A）/ 结构化存储（B）/ 外接 Honcho（C）？**［推荐 A：USER.md］**
- **OD5｜自主 skill 安装门**：默认人工门（P4-A）/ 默认自动提升（B）/ 可配（C）？**［推荐 A + 提供 C 开关，默认人工门；渠道/自主 fail-closed=只提案］**
- **OD6｜自主 skills 机制**：采用原生 self-learning loop（P5-A）？还是在 pig `WorkspaceSkillSource` 上自建写路径（B）？**［推荐 B，curator 分期移植］**
- **OD7｜混合检索后端**（分期 spec-4）：pig 本地 sqlite-vec/LanceDB 式索引 / 外接 AgentScope 后端（mem0/reme/bailian）？**［推荐 本地优先，spec-4 再定］**
- **OD8｜辅助模型**：flush/consolidation/画像维护用 Doubao lite？请确认具体型号与调用方式（`MemoryConfig.model(Model实例)`，pig 用自有 `ModelManager` 而非原生 `ModelRegistry`，需注入 `Model` 实例）。**［推荐 Doubao lite］**
- **OD9｜旧数据迁移**：`workspace/context/memory.md`（现全局层）与各 `sessions/{id}/temp-memory.md` 是否一次性迁入新 `MEMORY.md`/日志层？还是「新库从空开始、旧文件只读保留」（同 2.0 session 迁移的既有做法）？**［推荐 一次性把旧全局 `memory.md` 迁入 `MEMORY.md`；会话层不迁］**

---

## 6. 记忆设计（主）——目标态

### 6.1 采用 2.0 原生两层记忆（spec `pa-memory-native`，首个、修 bug）
- **两层落盘**（工作区级，跨会话）：日志层 `memory/YYYY-MM-DD.md`（append，raw，不去重）→ 固化层 `MEMORY.md`（LLM 合并去重、整文件重写、**每步注入 system prompt**）。（`memory.md`:10-11/:47-48/:263）`（需 javap 验证）`
- **三段 LLM（前两段属记忆）**：
  - **Flush**（每次 `call()` 末，异步 fire-and-forget，`flushTrigger` 可 ALWAYS/NEVER/THROTTLED）：从对话窗口抽长期事实 → 追加日志层。**无字数过滤**（修 `MIN_TEXT_LENGTH` 短事实丢失）。
  - **Consolidation**（后台节流 `consolidationMinGap≈30min`）：合并日志 → 重写 `MEMORY.md`（`consolidationMaxTokens≈4000`）。
  - Compaction（第三段，属**上下文压缩**）：**不采用原生**，继续用 pig A5（`disableCompaction()` 保留）。
- **检索工具**：重开 `memory_search`（关键词，≤30 命中）/`memory_get`（行区间）/`session_search`（转录）。（`memory.md`:228-235）`（需 javap 验证）`
- **注入**：`MEMORY.md` 冻结进 system prompt（会话内稳定 → 前缀缓存友好；见 P2/OD2）。
- **廉价模型**：`MemoryConfig.model(Doubao lite 的 Model 实例)` 跑 flush/consolidation（OD8）。
- **开关**：`PigAgent.Builder` 去掉 `disableMemoryHooks()/disableMemoryTools()`，改由 `MemoryConfig` 驱动；pig `/memory on|off` 语义映射到 `flushTrigger`/hooks 开关。
- **退役**：`CompositeLongTermMemory` 两层、`FileSystemLongTermMemory` 过滤、A4 抽取（OD3）。可留一个实现 `LongTermMemory` 的**薄 shim**过渡（如 pig 别处仍依赖该接口读取）。
- **验收**：`CrossSessionMemoryTest` 第三条断言转 GREEN（会话 A 说的名字在会话 B 被召回）；系统 prompt 会话内稳定；`/memory off` 不注入不 flush。

### 6.2 用户画像 `USER.md`（spec `user-profile`，依赖 6.1）
- **文件**：`workspace/context/USER.md`（身份/偏好/工作方式/目标），本地 Markdown、可手编、可版本化（Hermes `USER.md` 先例）。
- **维护**：回合后用廉价模型「辩证式」增量更新（Honcho 思路本地化，非上 Honcho）：只在检测到稳定偏好/身份/工作方式时更新，避免噪声。可复用原生 flush 的 prompt 定制（`flushPrompt` 分流画像 vs 通用事实），或独立 pig 维护器。
- **注入**：小而稳 → 进 system prompt（可缓存，见 P2-C）。
- **人可控**：`/profile show|edit`（后续 CLI），画像永远人可读、可删。

### 6.3 混合检索 BM25+向量（spec `hybrid-memory-search`，依赖 6.1，分期最后）
- 原生 `memory_search` 仅关键词。叠加 OpenClaw 式**混合**：`score = vector×0.7 + keyword×0.3`（候选×4、归一化、按 minScore 过滤、返回 top-N Markdown 片段）。
- **后端**（OD7）：优先 pig 本地（sqlite-vec + FTS5 BM25，本地嵌入回退链 GGUF→云→BM25-only，OpenClaw 蓝本，本地优先）；或外接 AgentScope mem0/bailian（破坏本地优先，仅备选）。
- 索引源 = 6.1 的 `MEMORY.md`+`memory/*.md`（+ 6.2 `USER.md`）。

---

## 7. 自主沉淀 skills 设计（spec `autonomous-skills`）

**目标**：把「解过的任务/工作流」蒸馏成可复用 `SKILL.md`，让 pig「越用越会」——Hermes/OpenClaw 的 skill-from-experience。

- **触发（自主，self-directed）**：借鉴两家——
  - 成功完成**非平凡**任务后（Hermes：≥5 次工具调用；OpenClaw：成功前台回合 ≥10 次模型迭代，且「能省未来 ≥2 次往返」）。
  - 用户**纠正**了 agent 的做法（instruction capture，即使失败回合也捕获）。
  - 发现了**非平凡工作流 / 走通了死胡同的恢复路径**。
  - **克制**：对例行/一次性/纯偏好/瞬时失败/通用建议/含密内容一律不沉淀（OpenClaw 明确的 abstain 清单）。
- **机制（推荐 P5-B：pig 自建写路径）**：
  - 新 `@Tool`（`pig-agent-tools`，非只读、受权限治理，分类 WRITE）：`proposeSkill(name, description, body, keywords)` 写**草稿** `workspace/skills/_drafts/<name>/SKILL.md`；`skillManage(action, ...)` 编辑草稿。
  - **人工门**（OD5，默认开）：草稿不自动生效；`/skills pending|diff|approve|reject`（CLI）人工审后提升到 `workspace/skills/<name>/`；提升即被 `WorkspaceSkillSource` **下一次调用发现**（无需重启，已验证 seam）。渠道/自主 agent（无 confirmer）**fail-closed=只提案不提升**。
  - **内容安全扫描**：提升前跑 pig 现有 `SkillSecurity`（目录/路径/大小加固）+ 凭据扫描（`CredentialSanitizer`，skill 正文不得含密钥/token）+ 结构校验（`SkillManifestParser` 解析 front-matter）。
  - **去重**：提升时按 name/description 语义近似检测与既有 skill 冲突（先做保守的 name 冲突拒绝 + 描述相似告警；curator 式自动 umbrella-merge 后续分期，可参考原生 `enableSkillCurator` 思路）。
  - **老化清理**（分期）：usage 统计（哪 skill 被 `loadSkill` 用过）→ 长期未用标 stale → 归档 `workspace/skills/.archive/`。
- **读路径不变**：`SkillsTool.listSkills/loadSkill`（只读）+ composite-skill 渐进加载**逐字保留**（无模型面回归）。
- **备选（P5-A）**：采用原生 `enableSkillManageTool`+`enableSkillPromotionGate(LocalApprovalGate)`+`enableSkillCurator`——免费得 curator/gate/usage，但需消解与 pig 只读 skill 栈的双读系统（见 P5）。`（需 javap 验证）`

---

## 8. 其它参考能力（菜单，按优先级）

| 能力 | 来源 | 采纳时机 | 说明 |
|---|---|---|---|
| auto-recall（检索后注入相关命中） | OpenClaw | **NOW**（随 spec-1） | 原生 `memory_search` 即 agent 驱动召回；无需额外 spec |
| auto-capture（自动 flush 落盘） | OpenClaw/2.0 | **NOW**（随 spec-1） | 原生每回合 flush |
| `session_search`（搜历史转录） | 2.0/Hermes | **NOW**（随 spec-1） | 重开 memory tools 即得 |
| 用户画像驱动个性化 | Hermes/Honcho | **SOON**（spec-2） | `USER.md` |
| 混合 BM25+向量检索 | OpenClaw | **LATER**（spec-4） | 原生仅关键词，向量分期 |
| 主动记忆浮现（proactively 提醒相关记忆） | Hermes | **LATER** | 复用 pig 现有 `proactive-outreach`；spec-2 后 |
| 外接托管记忆（mem0/reme/bailian） | 2.0 集成 | **可选/备选** | 破坏本地优先；仅在 OD7 选外接时 |
| skill usage 统计 + 老化 curator | 2.0/OpenClaw | **LATER**（spec-3 后半） | 自主 skills 的清理层 |

---

## 9. Spec 拆分 + 依赖 + 排期

> 遵循 `/ls:clarify` 多 spec 规则：拆成可独立上线的 spec；依赖显式标注；后一个 spec 在其依赖归档后才细化 tasks。**拆分方案须人工审。**

| # | Spec | 范围 | 依赖 | 顺序/并行 | 独立上线价值 |
|---|---|---|---|---|---|
| 1 | **`pa-memory-native`** | 采用 2.0 原生两层记忆；重开 memory hooks/tools；廉价模型 flush/consolidation；退役自建两层/A4/20字过滤；保留 A5；旧数据迁移 | 无（地基） | **最先** | **直接修跨会话 bug**（`CrossSessionMemoryTest` 转绿） |
| 2 | **`user-profile`** | `USER.md` 用户画像：LLM 维护 + 稳定注入 + `/profile` | 依赖 #1（记忆基座 + system prompt 注入位） | #1 归档后 | 「越用越懂你」 |
| 3 | **`autonomous-skills`** | 经验沉淀：`proposeSkill`/`skillManage` @Tool + 人工门 + 安全扫描 + 去重 | **不依赖记忆**（skills 子系统独立） | 可与 #2 **并行**（均在 #1 后启动 tasks 细化） | 「越用越会」 |
| 4 | **`hybrid-memory-search`** | BM25+向量混合检索（0.7/0.3）于记忆库 | 依赖 #1（需记忆库可索引） | **最后** | 检索质量 |

**依赖图**：`#1 →（#2 ∥ #3）→`；`#1 → #4`。
- **#1 无依赖**，先行（修 bug 最高优先）。
- **#2 顺序依赖 #1**（需要记忆基座与注入位）。
- **#3 与记忆解耦**，技术上可与 #1 并行；但按 /ls 规则「后一个 spec 在依赖归档后细化 tasks」，且 #3 优先级低于 bug 修复，标注为**可与 #2 并行**（都在 #1 归档后启动 tasks）。**不得把 #2 的顺序依赖误标成并行**——#2 必须 #1 后。
- **#4 顺序依赖 #1**，排最后。

**排期**：#1（本轮，走完整 clarify→spec→code⇄itest→archive）→ #1 归档后 (#2 ∥ #3) → 最后 #4。本文档只**轻描** #2/#3/#4，按 /ls「later specs 归档前不细化 tasks」，待 #1 归档再逐个细化。

---

## 10. 首个 spec 的 openspec 落点

- 变更目录：`openspec/changes/pa-memory-native/`（`.openspec.yaml` + `proposal.md` + `design.md` + `tasks.md` + `specs/pa-memory-native/spec.md`）。
- 新增能力 `pa-memory-native`；与既有主 spec 的关系（归档时消解）：**取代/收敛** `prefix-cache-context`（注入策略改为原生，见 P2/OD2）、`memory-extraction`（A4 退役，OD3）、并调整 `context-memory-efficiency` 中「回合内记忆检索缓存」（`CachingLongTermMemory` 随自建退役而失去意义）。`builtin-skills`/`composite-skill` 不受本 spec 影响（skills 在 spec-3）。
- 验收锚点：复用 `bug/20260717-cross-session-memory` 的 RED `CrossSessionMemoryTest` 作为跨会话验收；新增 system-prompt 会话内稳定、`/memory off` 语义、flush 无字数过滤 等断言。
- `openspec validate pa-memory-native --strict` 须通过。

---

## 11. 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| 跨会话 bug 根因 = `record` 只写会话层、全局层无自动写路径 | §1/§3；spec-1 采用工作区级 `MEMORY.md` | 已定位，落 spec-1 |
| 附带诱因 = `MIN_TEXT_LENGTH=20` 丢短事实 | §3；原生 flush 无字数过滤（REPLACE `FileSystemLongTermMemory`） | 落 spec-1 |
| 2.0 原生两层记忆开箱修跨会话，pig 却 `disableMemoryHooks/Tools` 关掉了 | §3/§0；P1；spec-1 FLIP 开关 | 落 spec-1（OD1） |
| `disableMemoryHooks` 的历史理由针对的是旧废弃 `StaticLongTermMemoryHook`，非 2.0 两层记忆 | §3「历史理由需澄清」；P1 头号前提 | 已澄清，待用户确认 OD1 |
| pig 唯一领先项=user 侧 ephemeral 前缀缓存，重设计要权衡 | P2；OD2 | 待用户拍板 OD2 |
| `USER.md` 用户画像（Hermes 先例）作为差异化 | §6.2；spec-2 | 落 spec-2（OD4） |
| 混合 BM25+向量（OpenClaw 0.7/0.3）作为差异化 | §6.3；spec-4 | 落 spec-4（OD7） |
| 自主沉淀 skills（Hermes/OpenClaw skill-from-experience）+ 人工门 + 安全扫描 | §7；spec-3 | 落 spec-3（OD5/OD6） |
| A5 压缩保留为差异化（与原生记忆正交） | §3 KEEP；spec-1 继续 `disableCompaction()` | 落 spec-1 |
| 会话层记忆去留 | P6；§3 DROP | 落 spec-1（推荐去掉） |
| 旧数据迁移策略 | OD9；§6.1 退役说明 | 待用户拍板 OD9 |
| **版本现实：2.0 记忆/skills API 本地无 jar、不可 javap 验证** | §0/§3；标 `（需 javap 验证）` | **实现前 P0 强制验证** |
| 辅助模型 Doubao lite 接线（`MemoryConfig.model(Model实例)`，pig 用自有 ModelManager） | §6.1；OD8 | 待用户确认 OD8 |

---

## 附录 A — 来源清单

**AgentScope 2.0 原生（文档，本地 `D:\Users\admin\Documents\en\`）**
- `docs\harness\memory.md` — 两层模型、3 段 LLM、`MemoryConfig` 字段表、`memory_search`/`memory_get`、后台维护、跨会话、`disableMemoryHooks/Tools`。
- `docs\harness\skill.md` — 四层 skill、`load_skill_through_path`、self-learning loop（`enableSkillManageTool`/`enableSkillPromotionGate`/`enableSkillCurator`）。
- `docs\integration\memory\{overview,mem0,reme,bailian}.md` — 外接后端（mem0=向量、reme=轨迹、bailian=托管+rerank）。
- javap（`agentscope-1.0.12.jar`，仅 SPI 形状参考）：`LongTermMemory`(record/retrieve)、`LongTermMemoryMode`、`StaticLongTermMemoryHook`、`AutoContextMemory`、`SkillBox`/`AgentSkillRepository`；外接后端 builder。**2.0 `HarnessAgent`/`MemoryConfig`/`SkillManageConfig` 本地无 jar → 全部 `（需 javap 验证）`。**

**Hermes（Nous Research）** — hermes-agent.nousresearch.com/docs（memory/honcho/skills/memory-providers）、honcho.dev/docs（Hermes 集成）、marktechpost（/learn）、ssojet（self-evolving skills）、aibuilderclub（review）。要点：`MEMORY.md`+`USER.md`、FTS5 关键词（**非** LLM 摘要）、Honcho 辩证用户建模、`skill_manage` 自主 + `write_approval` 默认关。

**OpenClaw（小龙虾 / Peter Steinberger）** — docs.openclaw.ai（concepts/memory-builtin、reference/memory-config、tools/self-learning、tools/skill-workshop）、gaodalie.substack、shivamagarwal7.medium、pingcap（local-first RAG）、GitHub #7629（memory-lancedb 混合）。要点：日志→`MEMORY.md`、**混合 BM25+向量 0.7/0.3**（sqlite-vec+FTS5、候选×4）、本地优先、Skill Workshop 自主提案 + **强制人工审**。「memory-core」命名未获证实（实为 builtin `memorySearch` + `memory-lancedb` 插件）。

**pig 现状** — 代码勘察：`CompositeLongTermMemory`(record 只写会话层)、`FileSystemLongTermMemory`(MIN_TEXT_LENGTH=20)、`EphemeralMemoryMiddleware`、`memory/extraction/*`(A4)、`compression/*`(A5)、`skills/*`+`pig-agent-skills-builtin`、`AgentBootstrap` 接线、`PigAgent.Builder.disableMemoryHooks/Tools`；`bug/20260717-cross-session-memory` 的 `CrossSessionMemoryTest`；POM `agentscope2.version=2.0.0`；`docs/planning/product-north-star.md`。
</content>
</invoke>
