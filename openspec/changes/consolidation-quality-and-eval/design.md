## Context

pig 记忆现状（`pa-memory-native` 已在 main）：工作区级两层记忆——日志层 `memory/YYYY-MM-DD.md`（per-turn flush 追加，无去重）→ 固化层 `MEMORY.md`（后台节流 consolidation 由 LLM 全量重写去重）。接线在 `AgentBootstrap.buildMemoryConfig(PigAgentConfig.MemoryConfig, ModelManager)`：把 pig config `memory` 块映射到原生 `io.agentscope.harness.agent.memory.MemoryConfig`（`flushTrigger`/`consolidationMinGap`/`consolidationMaxTokens`/`model`），**从不**调用 `flushPrompt(...)`/`consolidationPrompt(...)` → 一律用原生默认 prompt。

抽取/去重的**质量**目前无离线护栏、无评测：`CrossSessionMemoryTest` 用 stub 模型驱动 `memory_save`/`memory_search` 的纯文件 IO，只证「跨会话结构」；真正的「记得准、不丢关键、去重干净」全靠一次次跑真模型肉眼看。M-D 的目标不是重写记忆栈，而是在其上**叠加质量层**：更强 prompt（opt-in）+ 语义去重（复用 R0 检索原语）+ 可离线证结构的评测 harness。

**原生 prompt API（javap 核验，权威）**：
- `MemoryConfig.Builder` 有 `flushPrompt(String)` 与 `consolidationPrompt(String)`（`MemoryConfig` 构造期**不做**占位校验，仅赋值）。
- `MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT` 内含「`Keep total output within %d tokens (approximately %d characters)`」——**两个 `%d`**；consolidation 时以 `String.format(consolidationPrompt, maxMemoryTokens, maxMemoryChars)` 消费（javap: 连续两次 `getfield maxMemoryTokens` 后 `invokestatic String.format`）。故自定义 consolidation prompt 占位数不符/含裸 `%` 会在**后台 consolidation**（异步）处抛 `MissingFormatArgumentException`/`UnknownFormatConversionException`。
- `MemoryFlushManager.DEFAULT_FLUSH_PROMPT` 是**纯 SYSTEM prompt**；flush 时**不**对 flushPrompt 做 `String.format`（javap 里的 `String.format("\n## Memory Flush — %s\n%s\n", …)` 是日志文件头格式化，与 flushPrompt 无关）。故 flush prompt **无占位要求**。

既有可复用件：
- **检索原语（R0 `shared-retrieval`，已在 main）**：`io.pigagent.core.search` 的 `Bm25Index`（`index(List<? extends SearchDocument>)` + `score(query)→Map<id,score>`，Okapi BM25、CJK tokenizer）、`HybridRanker`（min-max 归一 + 融合）、`SearchDocument`（`id()`/`text()` 契约）、`Tokenizer`。
- **嵌入/向量（`hybrid-memory-search`/`embedding-model-layer`，已在 main）**：`io.pigagent.core.memory.search` 的 `Embedder`（可 mock 种子）、`DeterministicEmbedder`（离线确定性嵌入器）、`Vectors.cosine(float[],float[])`、`MemoryCorpusLoader`（读 `MEMORY.md`/`memory/*.md`/`USER.md`、Markdown 分块、容错）、`MemoryDocument implements SearchDocument`。`AgentBootstrap.resolveEmbedder(embedderModelId, modelManager)`：config id → store 默认嵌入指针 → null（→ BM25-only），与 M-B 同源。
- **后置服务 precedent**：`ProfileConsolidationService`（读 `MEMORY.md` → 蒸馏 → 写 `USER.md`，节流 `Duration`、容错、原子移动、挂 `TaskScheduler`，模型调用在 `ProfileDistiller` seam 背后）；`SkillCuratorService`（定期 aging，dry-run 默认）。语义去重 curator 直接镜像其形态。
- **结构性离线证 precedent**：`CrossSessionMemoryTest`（用 stub 模型 + 纯文件 IO 证结构不变式，真质量交 IT）。

## Goals / Non-Goals

**Goals**
- 可选定制 flush/consolidation prompt（`MemoryConfig.builder().flushPrompt/.consolidationPrompt`），**默认（未配置）逐字节等于今日**（原生默认 prompt）。
- consolidation prompt 的 **pig 侧占位校验**：恰含两个 `%d`、无其它裸 `%` 转换；**校验失败安全回退默认**（记 warn、绝不崩、绝不让后台 consolidation 抛）。
- pig 侧**语义去重后置 curator**：读 `MEMORY.md` → 事实单元 → 两两语义相似度（BM25 或真嵌入器 cosine）→ 超阈值近重复丢弃（保留代表条）→ 原子重写；**确定性、离线以 `DeterministicEmbedder`/BM25 可测**；节流 + 容错；默认关。
- **记忆质量评测 harness**：固定对话 fixture + 「记住/去重净/未丢」断言框架，离线以确定性驱动可跑；真质量基线延后 live `*IT`；可供 M-C 复用。
- 定义/对齐一个**向后兼容**的 `MEMORY.md` 分层格式契约，供后续 M-C 键入。
- config `memory.consolidation-quality` 块，**全默认关/additive**。

**Non-Goals**
- 真 consolidation 质量基线的离线验证（属 live-model，交 `/ls:itest`；默认模型此前 403）。
- 重写/替换原生 flush/consolidation 引擎（本 spec 是其上的**后置**叠加，不接管其触发/写入）。
- LLM 语义**合并改写**近重复（那是原生 consolidation 的 LLM 全量重写的活）；pig 侧去重是**确定性丢弃保留代表条**，不做模型改写（保离线可证）。
- 实现 M-C 的分层衰减本身（本 spec 只**定义契约**，不建衰减机制——避免越界 over-build M-C）。
- 改 `pa-memory-native` 两层布局 / flush·consolidation 触发 / `/memory` 语义；改 `memory-retrieval-injection` 的注入切分；A5 压缩；权限/沙箱/渠道语义。
- 程序性 how-to 的记忆（归技能线 `SKILL.md`）。

## Spike（阻塞前置 Task 组 1 — 全案最关键决策，不过不进编码）

> 目标：钉死三个「离线可证 vs live 延后」的边界，结论落本节。**离线可证**的进编码，**live 延后**的只留 `*IT` 骨架。

**S1 — consolidation prompt 占位契约（离线可证，承重）**
- javap 已证：原生以 `String.format(consolidationPrompt, maxTokens, maxChars)` 消费 → 自定义 prompt **必须恰含两个 `%d`**（顺序：tokens、chars），且**不得含其它裸 `%`**（`100%` 会触发 `UnknownFormatConversionException`；`%s` 等其它转换会与两个 int 实参不匹配）。占位少于 2 时 `String.format` 忽略多余实参（不抛但丢失预算指令）；多于 2 时抛 `MissingFormatArgumentException`。
- **离线可证的校验器**：`ConsolidationPromptValidator.isValid(prompt)` = 纯字符串扫描——统计有效 `%` 转换：恰两个 `%d`、无其它转换标记（`%%` 转义除外）。可单测穷举（0/1/2/3 个 `%d`、`%s`、裸 `%`、`%%`）。**结论**：占位校验完全离线可证，不需真模型。
- flush prompt **无占位要求**（javap 证 flush 不 `String.format`）→ 只做非空校验，空白回退默认。

**S2 — 语义去重逻辑（离线可证）**
- 去重 = 对 `MEMORY.md` 的事实单元集合，两两算语义相似度、超阈值判近重复。两条相似度通道均已在 main 且离线可算：
  - **BM25 通道**：把事实单元建成 `SearchDocument` 灌 `Bm25Index`，用每个单元的文本当 query `score(...)`，取对其它单元的最高分（归一化）作相似度信号——纯 Java、确定性。
  - **cosine 通道**：`Embedder.embed(unit)` → `Vectors.cosine(a,b)`。离线用 `DeterministicEmbedder`（token 哈希嵌入，共享 token 的文本向量相近），足以确定性驱动阈值分支；真语义质量用真嵌入器（延后 IT）。
- 保留策略：近重复组里保留**代表条**（确定性 tie-break：更长 / 更靠后 / 更高信号，取一条并写死顺序），丢其余。**结论**：去重逻辑纯确定性、`DeterministicEmbedder`/BM25 全离线可测；真语义召回质量（换个说法算不算重复）延后 IT。

**S3 — eval harness 结构（离线可证）vs 真质量基线（live 延后）**
- harness = `MemoryEvalFixture`（固定对话 + 期望被记住的关键事实集 + 期望无近重复）+ `MemoryQualityAssertions`（`assertKeyFactsRemembered` / `assertNoNearDuplicates` / `assertNoKeyFactLost`）。
- **离线**：以**确定性驱动**跑通 harness 骨架——一个 fake 抽取（把 fixture 里标注的关键事实原样落日志层）+ 真 `SemanticDeduplicator`，断言框架能正确判「记住/去重净/未丢」；证的是 **harness 与断言框架本身可用**（不是真模型的抽取质量）。
- **live 延后**：`MemoryQualityIT` 用真模型跑真 flush/consolidation，喂同一 fixture、断同三指标——这才是**真质量基线**。默认模型此前 403，诚实延后 `/ls:itest`。**结论**：harness 结构 + 断言框架离线可证；真质量基线 live 延后。

**Spike 净结论**：三项的「离线可证核心 + live 延后真质量」边界清晰且各有 precedent（占位校验=纯字符串；去重=R0 检索原语 + `DeterministicEmbedder`；harness=`CrossSessionMemoryTest` 式结构证 + `*IT` 真质量）。全部 opt-in/默认关，默认路径逐字节等于今日。进编码。

## Decisions

- **D1 — prompt 定制 opt-in、additive，默认=原生默认（零回归）。** `memory.consolidation-quality.flush-prompt`/`consolidation-prompt` 空（默认）→ `buildMemoryConfig` **不**调 `flushPrompt/consolidationPrompt` → 原生默认 prompt，行为逐字节等于今日。非空 → 校验通过后调对应 builder 方法。**理由**：prompt 是质量的最低成本杠杆，但改默认有回归风险且真质量难离线验，故默认不动、翻开开关才生效。**备选**：直接换更强默认 prompt——否决（改默认行为、真质量未离线验、破坏字节稳定）。
- **D2 —（承重）consolidation prompt 占位校验 + 失败安全回退，pig 侧兜底原生的 `String.format` 契约。** 自定义 consolidation prompt MUST 恰含两个 `%d`、无其它裸 `%`；`ConsolidationPromptValidator` 纯逻辑校验，**失败 → 记 warn + 回退原生默认 prompt**（不调 `.consolidationPrompt(bad)`），绝不崩、绝不让**异步后台** consolidation 在 `String.format` 处抛。flush prompt 只非空校验。**理由**：原生构造期不校验占位，坏 prompt 会在后台任务里炸且难定位；pig 侧前置校验 + fail-safe 回退是唯一稳妥兜底（与 pig「容错、绝不静默崩」一致）。**备选**：信任用户/让它炸——否决（后台异步崩、无 UI、难诊断）。
- **D3 — 语义去重 = pig 侧后置 curator，确定性丢弃保代表，复用 R0 检索原语，不做 LLM 改写。** 原生 consolidation 之后，`MemoryConsolidationCurator` 读 `MEMORY.md` → 事实单元 → `SemanticDeduplicator` 两两相似度（真嵌入器 cosine 或降级 BM25）→ 超阈值近重复丢弃保代表 → 原子重写。**去重是确定性丢弃**（不调模型合并改写——那是原生 consolidation 的活，且破坏离线可证）。**理由**：原生 LLM 全量重写去重不总干净（同义复述漏网）；pig 侧确定性后置去重是**可离线证的安全网**，复用既有 `core.search`/`Embedder` 零新依赖。**备选**：(a) 只靠原生 consolidation——现状，漏网；(b) pig 侧 LLM 二次去重——否决（多一次模型调用、不确定、离线不可证）。
- **D4 — 嵌入器与 M-B 同源，无嵌入器降级 BM25（不硬依赖 M-B）。** 去重的 cosine 通道经 `AgentBootstrap.resolveEmbedder`（config `embedder-model-id` → store 默认嵌入指针 → null）取真嵌入器；null → `SemanticDeduplicator` 走 BM25 相似度通道（零依赖、离线全绿）。**理由**：与 `hybrid-memory-search`/`memory-retrieval-injection`/M-B 同一 `resolveEmbedder` seam，不重复造；BM25 降级保证本 spec 不被 M-B 阻塞。**备选**：硬依赖真嵌入器——否决（阻塞、离线不可证）。
- **D5 — eval harness：离线证结构 + 断言框架，真质量基线延后 live IT，且 harness 供 M-C 复用。** `MemoryEvalFixture` + `MemoryQualityAssertions` 离线以确定性驱动证「断言框架可用」；`MemoryQualityIT` 真模型跑真质量基线（延后）。harness 设计**通用**（不绑死去重），M-C 分层衰减可复用同一 fixture/断言。**理由**：真质量只能 live 验（默认模型 403 诚实延后），但 harness 结构必须离线可跑才能持续用；通用化让 M-C 白拿。**备选**：只写 IT——否决（离线无护栏、CI 不跑真模型时质量回归无感知）。
- **D6 — `MEMORY.md` 分层格式契约：M-D 定义、向后兼容、只在启用结构化 consolidation 时产出，供 M-C 键入。** 定义一个稳定的分层/分段约定（如约定 Markdown 标题段划分「常驻核心 / 一般事实 / 易失近期」层）；定制 consolidation prompt 产出符合该契约、去重 curator 保留其分层标记。契约**向后兼容**旧无分层 `MEMORY.md`（缺标记 → 视为单层，不报错）。**本 spec 只定义契约 + 保证兼容，不建衰减**（衰减是 M-C）。**理由**：M-C 需要一个可键入的稳定格式；M-D 正好在改 consolidation prompt/写 curator，顺手对齐契约，避免 M-C 再回改 M-D。**备选**：留给 M-C 定——否决（M-C 会被迫回改 consolidation prompt + 去重，返工；本 spec 先定更省）。
- **D7 — curator 节流 + 容错 + 原子重写，镜像 `ProfileConsolidationService`。** `min-gap-minutes` 节流（不每回合跑）、读/写失败降级为**不改动原文件** + 记 warn（绝不崩）、写用临时文件 + 原子移动。挂现有 `TaskScheduler`（与 profile consolidation/outreach briefing 同机制）。**理由**：直接复用 pig 成熟的后置服务形态，零新范式。
- **D8 — config `memory.consolidation-quality`，全默认关/additive。** 内嵌于 `MemoryConfig`；`flush-prompt`/`consolidation-prompt` 空、`dedup.enabled` 默认 false（→ 无 curator、无 schedule）、`dedup.similarity-threshold`（默认保守偏高，如 0.9，减少误杀）、`dedup.embedder-model-id` 空→BM25、`dedup.min-gap-minutes` 节流。非法值 clamp、null-tolerant（镜像 `SearchConfig`/`InjectionConfig`）。命名避开既有 flat `memory.consolidation-min-gap-minutes`/`consolidation-max-tokens`（属 `pa-memory-native`，不动）。**理由**：默认关确保零回归、一步启用。
- **D9 — 交叉依赖显式登记（见 §交叉依赖）。** 与 M-C（分层契约 + eval 供给，顺序依赖：M-D 先归档）、M-B（嵌入器同源、BM25 降级不硬依赖）、技能线（声明性 vs 程序性划界）。

## Architecture

```
memory.consolidation-quality.*                     (config, 默认全关)
        │  AgentBootstrap.buildMemoryConfig / build
        ▼
① prompt 定制（opt-in）
   flush-prompt / consolidation-prompt 非空？
     ├─ consolidation-prompt: ConsolidationPromptValidator.isValid?   ← 恰两个 %d、无裸 %
     │     ok  → MemoryConfig.builder().consolidationPrompt(custom)
     │     bad → warn + 回退原生默认（不调）                          ← D2 fail-safe
     ├─ flush-prompt: 非空 → .flushPrompt(custom)（无占位要求）
     └─ 空(默认) → 不调 → 原生默认 prompt（今日行为，字节等价）        ← D1

② 语义去重后置 curator（dedup.enabled，默认关）                     ← 挂 TaskScheduler（节流）
   MemoryConsolidationCurator.run():                                 (镜像 ProfileConsolidationService)
     units   = split(MEMORY.md 事实单元)                             ← 复用 MemoryCorpusLoader 式分块
     sim(a,b)= embedder!=null ? Vectors.cosine(embed a, embed b)     ← D4 真嵌入器
                              : bm25Similarity(a,b)                  ←    降级 BM25（零依赖）
     dups    = SemanticDeduplicator.dedup(units, threshold)          ← 超阈值丢弃保代表（确定性）
     原子重写 MEMORY.md（保留分层格式契约标记 D6；读/写失败→不改动原文件+warn D7）

③ 记忆质量评测 harness
   离线: MemoryEvalFixture(对话, 期望关键事实, 期望无近重复)
        + MemoryQualityAssertions{记住 / 去重净 / 未丢}  ← 确定性驱动证框架可用（供 M-C 复用 D5）
   live: MemoryQualityIT 真模型跑真 flush/consolidation → 真质量基线（延后 /ls:itest）
```

## 交叉依赖（与 M-C / M-B / 技能线）

- **M-C（分层衰减，后续，顺序依赖）**：M-D **先定义/对齐** `MEMORY.md` 分层格式契约（D6）+ **eval harness 供 M-C 复用**（D5）。**顺序**：M-D 先归档，M-C 在其基础上细化 tasks（分层衰减键入 M-D 定的契约、复用 M-D 的 fixture/断言）。design 显式登记此供给层与「M-D 先、M-C 后」的顺序（**非并行**）。
- **M-B（真嵌入，Wave-2）**：语义去重 cosine 通道的嵌入器 = `AgentBootstrap.resolveEmbedder`（与 `hybrid-memory-search`/`memory-retrieval-injection`/M-B **同一 E0 seam**）。**无嵌入器 → BM25 相似度去重**（零依赖降级），故本 spec **不硬依赖 M-B 完成**——可并行/先行，M-B 落地后去重自动升级为真语义。
- **技能线边界**：**声明性事实**（用户是谁/偏好/发生过什么）归 `MEMORY.md`（本 spec 的去重/评测对象）；**程序性 how-to**（怎么做某事）归 `SKILL.md`（技能线 curator，不在本能力）。两条线不重叠。

## Risks / Trade-offs

- **R1 —（承重）自定义 consolidation prompt 坏占位会在异步后台 consolidation 炸。** → D2 pig 侧 `ConsolidationPromptValidator` 前置校验（恰两个 `%d`、无裸 `%`）+ 失败回退默认；纯字符串校验离线穷举可证，绝不让坏 prompt 进 `.consolidationPrompt`。
- **R2 — 语义去重误杀（把不同事实当重复丢了，丢关键信息）。** → `similarity-threshold` 默认保守偏高（如 0.9）+ 确定性保留代表条（不同事实通常低于阈值）；eval harness 的 `assertNoKeyFactLost` 正是防误杀的护栏；默认关，启用需显式配阈值。真误杀率延后 live IT 观测。
- **R3 — BM25 相似度是词面匹配，「换个说法」的近重复漏网。** → 本 spec 去重目标是**确定性安全网**（补原生 LLM 去重的一部分漏网），BM25 已能抓词面近重复；真语义（换说法）需真嵌入器（D4，`embedder-model-id` 可选启用、延后 IT）；默认 BM25 全离线可证。
- **R4 — 真 consolidation/去重质量未离线验。** → 诚实延后 live `*IT`（默认模型此前 403）；离线证的是**结构 + 确定性逻辑 + 断言框架**（占位校验、阈值去重分支、harness 可用），这是质量的**护栏**而非质量本身。
- **R5 —（交叉依赖）M-C 依赖本 spec 定的 `MEMORY.md` 分层契约。** → D6 本 spec 先定契约 + 向后兼容（缺标记视为单层）；顺序依赖显式登记（M-D 先归档，M-C 后细化），避免 M-C 回改。
- **R6 —（交叉依赖）去重嵌入器与 M-B 同源但 M-B 未必先到。** → D4 无嵌入器降级 BM25，本 spec 不硬依赖 M-B；M-B 落地后自动升级，无需回改去重逻辑。
- **R7 — curator 后置重写与原生 consolidation/其它写者竞争 `MEMORY.md`。** → D7 节流（错开高频）+ 原子重写（临时文件 + 原子移动）+ 读快照（读时 MEMORY.md 内容快照，写回是整文件替换）；失败降级为不改动原文件。真并发安全性（与原生 consolidation 同时写）延后 IT 观测；节流 + 后置（在 consolidation 之后跑）已大幅降低撞车概率。
- **R8 — enabled=false / prompt 空的字节等价性。** → 默认路径**不调**任何 prompt builder、**不建** curator、**不挂** schedule；单测断言 `buildMemoryConfig` 默认产出与今日一致、`CrossSessionMemoryTest`/`buildMemoryConfig` 现有测试仍绿。

## 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 | 落点 | 状态 |
|---|---|---|
| consolidation 质量只有 live 可验、无离线护栏 | Why；D1/D2/D3/D5 | 落 tasks 组 1/2/3/4 |
| 更强 flush/consolidation prompt（opt-in、默认不变） | D1 | 落 tasks 组 2 |
| consolidation prompt 须含两个 `%d`（承重，原生 `String.format`） | Spike S1；D2 | 落 tasks 组 1（校验器）+ 组 2（接线回退） |
| 占位校验失败安全回退默认（绝不后台崩） | D2；R1 | 落 tasks 组 1/2 |
| flush prompt 无占位要求（纯 SYSTEM prompt） | Spike S1；D1 | 落 tasks 组 2 |
| 语义去重（非纯字符串）、复用 `core.search` BM25/`Embedder` cosine | Spike S2；D3 | 落 tasks 组 1（去重逻辑）+ 组 3（curator） |
| `DeterministicEmbedder` 离线可测、无嵌入器降级 BM25 | Spike S2；D4；R3 | 落 tasks 组 1/3 |
| 去重确定性丢弃保代表（不 LLM 改写） | D3 | 落 tasks 组 1 |
| curator 节流/容错/原子重写、镜像 `ProfileConsolidationService` | D7；R7 | 落 tasks 组 3 |
| eval harness 结构（fixture + 断言框架，离线确定性驱动） | Spike S3；D5 | 落 tasks 组 4 |
| 真质量基线延后 live `*IT`（默认模型 403） | Spike S3；D5；R4 | 落 tasks 组 6（IT，延后） |
| eval harness 供 M-C 复用 | D5；交叉依赖 | 落 tasks 组 4（通用化）+ 登记 |
| `MEMORY.md` 分层格式契约供 M-C、向后兼容 | D6；R5；交叉依赖 | 落 tasks 组 3/5 + 登记（顺序依赖 M-D 先） |
| 去重嵌入器与 M-B 同源、不硬依赖 M-B | D4；R6；交叉依赖 | 落 tasks 组 3 + 登记 |
| 技能线边界：声明性事实 vs 程序性 how-to | 交叉依赖 | 已登记（边界） |
| config `memory.consolidation-quality`、全默认关 = 今日行为 | D8；R8 | 落 tasks 组 5；断言字节等价 tasks 组 2/3 |
| 误杀防护（阈值保守 + assertNoKeyFactLost） | R2 | 落 tasks 组 1/4 |
