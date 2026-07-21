## 1. Spike —— 确认共享检索原语可独立索引技能元数据（承重，前置，低风险）

- [x] 1.1 小离线验证 `SkillDocumentSpikeTest`（镜像 `ToolDocumentSpikeTest`）：构造若干 `SkillDocument(id=技能名, text=名+描述+keywords) implements io.pigagent.core.search.SearchDocument` + `Bm25Index.index(...)` + `score(query)`，断言"更相关技能得分更高、无关技能不得分"。
- [x] 1.2 CJK 命中：中文名/描述技能以中文 query `score(...)` 得分高于无关技能（复用 `Tokenizer` unigram+bigram）。
- [x] 1.3 `HybridRanker.rank(bm25, Map.of(), 1.0, 0.0, 0.0, K)`（空向量 → BM25-only 降级）归一化 top-K 与直接 BM25 排序一致。
- [x] 1.4 记录结论：原语脱离记忆/工具语料可用（`Bm25Index.index(List<? extends SearchDocument>)` 泛型 + T2 `ToolDocumentSpikeTest` + `Bm25IndexTest` 既有实证）；走主路径，无回退。

## 2. SkillDocument + 技能匹配排序（同源复用 R0，`pig-agent-tools`）

- [x] 2.1 新增 `io.pigagent.tool.skills.SkillDocument implements io.pigagent.core.search.SearchDocument`（`id`=`Skill.name()`、`text`=名+描述+keywords；`of(Skill)`/`of(SkillMetadata)` 工厂）；仅 import `io.pigagent.core.search`，不牵扯记忆域。
- [x] 2.2 **渐进加载硬约束**：`SkillDocument.text` 只从 `Skill.metadata()`（名+描述+keywords）构造，MUST NOT 触 `Skill.content()`。
- [x] 2.3 排序器：对 `SkillRegistry.all()` 快照建 `Bm25Index.index(docs)` → `bm25.score(query)` → `HybridRanker.rank(bm25, 空向量, 1.0, 0.0, minScore, topK)` 取 top-K 回映为技能（镜像 `DeferredToolRegistry.search` 的每次搜索建索引）。
- [x] 2.4 单测：更相关技能排前（`SkillDocumentSpikeTest`/排序器测试）、中文命中中文名/描述、`content()` 探针证明排序不触正文（渐进护栏）。
- [x] 2.5 `mvn -pl pig-agent-tools -am compile` 绿。

## 3. 独立 skill_search 工具 + config + backward-safe 默认关不注册（`pig-agent-config` + `pig-agent-tools` + `pig-agent-cli`）

- [x] 3.1 `PigAgentConfig.SkillsConfig` 新增 `matching` 子块 `MatchingSkillsConfig`（`enabled` 默认 false、`top-k` 默认 10、`min-score` 默认 0.0；null/缺块安全、非法值 clamp）；`MatchingSkillsConfigTest` 覆盖默认/缺块/非法值 clamp。
- [x] 3.2 `SkillsTool` 增 `SkillRegistry registry()` 访问器（**非 `@Tool`**，供 `skill_search` 与 `listSkills` 共享同一 registry）；`listSkills`/`loadSkill` 逐字不动。
- [x] 3.3 新增 `io.pigagent.tool.skills.SkillSearchTool`（`@Tool name="skill_search"`, `readOnly=true`，镜像 `ToolSearchTool`）：持 `SkillRegistry` + `top-k`/`min-score`；query 非空 → 组 2 排序器取 top-K；空/无匹配 → 回退平铺全部（复用 `listSkills` 平铺格式，不报错不空）；`{"error"}` 契约兜底。
- [x] 3.4 `ToolRiskClassifier` 登记 `skill_search=READ_ONLY`（对齐 `tool_search`/`memory_search`）；用例断言分级。
- [x] 3.5 `AgentBootstrap`：`skills.matching.enabled` 时，在 `ToolContractGuard.install` **前**注册 `new SkillSearchTool(skillsTool.registry(), topK, minScore)`（从 `builtinTools` 取已注册的 `SkillsTool` 实例）；关闭时**不注册**、不装配任何匹配 seam。
- [x] 3.6 backward-safe 回归测试：`enabled=false` 时 toolkit schema **无** `skill_search`、`listSkills`/`loadSkill` 逐字不变；`enabled=true` 空 query 回退平铺、非空 query top-K、非空无匹配回退平铺、分级 READ_ONLY。

## 4. 验收（离线，硬约束=全绿）

- [x] 4.1 `mvn -pl pig-agent-tools -am test` 全绿（新增 spike + 排序器 + 渐进护栏 + `skill_search` + backward-safe 回归用例）。
- [x] 4.2 现有 `builtin-skills` / `composite-skill` / `shared-retrieval` 单测保持绿（`SkillsToolTest`/`SkillRegistryTest`/`Bm25IndexTest`/`HybridRankerTest`/`TokenizerTest` 等，逐字不改）。
- [x] 4.3 `mvn -pl pig-agent-cli -am compile` 绿（覆盖 `AgentBootstrap` 下游接线）。
- [x] 4.4 `CLAUDE.md`「Built-in skills」段落更新：技能匹配经独立 `skill_search` 同源复用 `io.pigagent.core.search`（三线同源第三个消费者）、默认关不注册字节稳定；模块表补 `SkillDocument`/`SkillSearchTool`/`MatchingSkillsConfig`。
