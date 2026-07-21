## 1. Spike —— 确认共享检索原语可独立索引技能元数据（承重，前置，低风险）

- [ ] 1.1 小离线验证 `SkillDocumentSpikeTest`（镜像 `ToolDocumentSpikeTest`）：构造若干 `SkillDocument(id=技能名, text=名+描述+keywords) implements io.pigagent.core.search.SearchDocument` + `Bm25Index.index(...)` + `score(query)`，断言"更相关技能得分更高、无关技能不得分"。
- [ ] 1.2 CJK 命中：中文名/描述技能以中文 query `score(...)` 得分高于无关技能（复用 `Tokenizer` unigram+bigram）。
- [ ] 1.3 `HybridRanker.rank(bm25, Map.of(), 1.0, 0.0, 0.0, K)`（空向量 → BM25-only 降级）归一化 top-K 与直接 BM25 排序一致。
- [ ] 1.4 记录结论：原语脱离记忆/工具语料可用（`Bm25Index.index(List<? extends SearchDocument>)` 泛型 + T2 `ToolDocumentSpikeTest` + `Bm25IndexTest` 既有实证）；走主路径，无回退。

## 2. SkillDocument + 技能匹配排序（同源复用 R0，`pig-agent-tools`）

- [ ] 2.1 新增 `io.pigagent.tool.skills.SkillDocument implements io.pigagent.core.search.SearchDocument`（`id`=`Skill.name()`、`text`=名+描述+keywords；`of(Skill)`/`of(SkillMetadata)` 工厂）；仅 import `io.pigagent.core.search`，不牵扯记忆域。
- [ ] 2.2 **渐进加载硬约束**：`SkillDocument.text` 只从 `Skill.metadata()`（名+描述+keywords）构造，MUST NOT 触 `Skill.content()`。
- [ ] 2.3 技能匹配排序：对 `SkillRegistry.all()` 快照建 `Bm25Index.index(docs)` → `bm25.score(query)` → `HybridRanker.rank(bm25, 空向量, 1.0, 0.0, minScore, topK)` 取 top-K 回映为技能（镜像 `DeferredToolRegistry.search` 的每次搜索建索引）。
- [ ] 2.4 单测：更相关技能排前（`SkillDocumentSpikeTest`/匹配器测试）、中文命中中文名/描述、`content()` 探针证明排序不触正文（渐进护栏）。
- [ ] 2.5 `mvn -pl pig-agent-tools -am compile` 绿。

## 3. listSkills 可选 query + config + backward-safe 逐字节无感（`pig-agent-config` + `pig-agent-tools` + `pig-agent-cli`）

- [ ] 3.1 `PigAgentConfig.SkillsConfig` 新增 `matching` 子块 `MatchingSkillsConfig`（`enabled` 默认 false、`top-k` 默认 10、`min-score` 默认 0.0；null/缺块安全、非法值 clamp）；`MatchingSkillsConfigTest` 覆盖默认/缺块/非法值 clamp。
- [ ] 3.2 `SkillsTool`：提供带可选 `query` 入参的 `listSkills` 变体（`@Tool` 名仍 `listSkills`）——`query` 空/缺省 → 平铺全部（复用今日 `formatListing`）；非空 → top-K；非空无匹配 → 回退平铺全部（不报错不空）。`loadSkill` 逐字不动。
- [ ] 3.3 `AgentBootstrap`/`SkillsToolProvider`：据 `skills.matching.enabled` 注册合适的 `listSkills` 变体（关→今日无参变体，schema 与引入前逐字节一致；开→带可选 `query` 变体）；不装配匹配 seam 当关闭。
- [ ] 3.4 backward-safe 回归测试：`enabled=false` 时 `listSkills` 无 `query` 入参、输出与引入前逐字节等价、`loadSkill` 不变；`enabled=true` 空 query 回退平铺、非空 query top-K、非空无匹配回退平铺。
- [ ] 3.5 `SkillsTool` `@Tool` 面字节稳定：`loadSkill` 在任意配置下名/签名/兜底字符串逐字不变；`listSkills` 名恒为 `listSkills`。

## 4. 验收（离线，硬约束=全绿）

- [ ] 4.1 `mvn -pl pig-agent-tools -am test` 全绿（新增 spike + 匹配 + 渐进护栏 + backward-safe 回归用例）。
- [ ] 4.2 现有 `builtin-skills` / `composite-skill` / `shared-retrieval` 单测保持绿（`SkillsToolTest`/`SkillRegistryTest`/`Bm25IndexTest`/`HybridRankerTest`/`TokenizerTest` 等，逐字不改）。
- [ ] 4.3 `mvn -pl pig-agent-cli -am compile` 绿（覆盖 `AgentBootstrap`/`SkillsToolProvider` 下游接线）。
- [ ] 4.4 `CLAUDE.md`「Built-in skills」段落更新：技能匹配同源复用 `io.pigagent.core.search`（三线同源第三个消费者）、`listSkills` 可选 top-K（`skills.matching` 默认关字节稳定）；模块表补 `SkillDocument`/`MatchingSkillsConfig`。
