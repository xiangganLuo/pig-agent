## 1. Spike —— 确认抽象成本可控（前置，无产品代码改动）

- [ ] 1.1 确认 `HybridRanker` 已纯泛型：`grep MemoryDocument` 在 `pig-agent-core/.../memory/search/HybridRanker.java` 零命中；核对 `rank(...)` 仅收/发 `Map<String,Double>` + `Scored(String,double)`。结论 → 逐字不动、仅随包移动。
- [ ] 1.2 确认 `Bm25Index` 对 `MemoryDocument` 的唯一耦合：核对全部代码级引用仅在 `Bm25Index.index(...)`（用 `doc.id()`+`doc.text()`），`score(String)→Map<String,Double>` 无文档类型。结论 → 通用接口只需 `{id(),text()}`。
- [ ] 1.3 枚举 blast radius：确认 `MemoryDocument.sourceLabel()`/`text()` 读取方 = `HybridMemorySearchTool` + 门面/工具测试；确认 `AgentBootstrap` 不引用 `MemoryDocument`/`sourceLabel`（`grep` 零命中）。结论 → `sourceLabel` 排除出通用接口，门面继续返回 `MemoryDocument`。
- [ ] 1.4 定形并记录（design.md 已含）：`SearchDocument={id,text}`；`Bm25Index.index(List<? extends SearchDocument>)` 通配符为向后兼容命门；通用原语迁 `io.pigagent.core.search`，记忆域留 `io.pigagent.core.memory.search`。抽象成本 = 1 接口 + 1 `implements` + 1 处签名放宽 + 包移动 → 可控，走主路径。

## 2. 通用契约 `SearchDocument`（`io.pigagent.core.search`）

- [ ] 2.1 新增接口 `SearchDocument`：`String id()`（全局唯一）+ `String text()`（参与打分正文）；javadoc 说明它是三线（记忆/工具/技能）共享的检索文档契约，域特有字段留各实现类。
- [ ] 2.2 `MemoryDocument`（留 `io.pigagent.core.memory.search`）新增 `implements SearchDocument`——`id()`/`text()` 已存在，`sourceLabel` 保持；零字段/零语义改动。
- [ ] 2.3 单测：`MemoryDocument instanceof SearchDocument`，且经接口引用取 `id()`/`text()` 与 record 访问器一致。

## 3. 上提通用原语到 `io.pigagent.core.search`（纯搬运 + 一处签名放宽）

- [ ] 3.1 迁移 `Bm25Index`、`HybridRanker`、`Tokenizer`、`VectorStore`、`InMemoryVectorStore`、`Embedder`、`DeterministicEmbedder`、`OpenAiCompatibleEmbedder`、`Vectors` 到新包（改 `package` + 互相 import；`HybridRanker`/`Tokenizer` 逻辑逐字不动）。
- [ ] 3.2 `Bm25Index.index(List<MemoryDocument>)` → `index(List<? extends SearchDocument>)`；方法体不变（仍 `doc.text()`/`doc.id()`）。
- [ ] 3.3 编译 `pig-agent-core`：`mvn -q -pl pig-agent-core -am compile` 绿。

## 4. 记忆域消费新包 + 门面稳定（`io.pigagent.core.memory.search`）

- [ ] 4.1 `MemorySearchIndex`、`MemoryCorpusLoader` 更新 import 指向 `io.pigagent.core.search` 原语；`MemorySearchIndex` 公共表面（`search(String,int)→List<MemoryDocument>`、`vectorEnabled()`、`invalidate()`、构造器）**逐字不变**；内部 `bm25.index(docs)`（`docs` 为 `List<MemoryDocument>`）经 `? extends` 通配符零改动通过。
- [ ] 4.2 确认 `MemorySearchConfig` 保留原位、原名（不进 R0 范围，D5）。
- [ ] 4.3 确认 `HybridMemorySearchTool` 无需改动（仍经门面 + 读 `MemoryDocument.sourceLabel()`/`text()`）；`AgentBootstrap` 无需改动（不引用 `MemoryDocument`）。

## 5. 测试迁移与门面稳定性证据（离线，硬约束=全绿）

- [ ] 5.1 门面/工具测试**逐字不改**：`MemorySearchIndexTest`、`HybridMemorySearchToolTest` 保持原样并绿——作为"门面/工具契约未破"的直接证据。
- [ ] 5.2 原语测试随类迁到 `io.pigagent.core.search` 测试包：`HybridRankerTest` 仅改 `package`/import（只用 `Map<String,Double>`）；`Bm25IndexTest` 用通用夹具 `record TestDoc(String id, String text) implements SearchDocument` 替 `MemoryDocument`，**断言/期望排序逐字相同**（相关文档优先、中文"罗湘赣"、空索引、无命中）。
- [ ] 5.3 新增窄测：`Bm25Index` 直接吃自定义 `SearchDocument` 实现（非 `MemoryDocument`）能索引并按 id 打分 → 证明泛化对第三方文档类型生效（为 T2/S2 预演）。

## 6. 编译兜底

- [ ] 6.1 `mvn -q -pl pig-agent-cli -am compile` 绿（覆盖 cli/tools 下游 import）。

## 7. 验收

- [ ] 7.1 `mvn -q test`（或 `mvn -q -pl pig-agent-core,pig-agent-tools -am test`）全绿：`hybrid-memory-search` 相关全部单测（`Bm25IndexTest`/`HybridRankerTest`/`MemorySearchIndexTest`/`HybridMemorySearchToolTest`）0 失败 0 错误 —— 硬约束达成，零行为变化。
- [ ] 7.2 `CLAUDE.md` `hybrid-memory-search` 段落增补一句「检索原语已上提为 `io.pigagent.core.search` 通用基座，供记忆/工具/技能三线共享」。
