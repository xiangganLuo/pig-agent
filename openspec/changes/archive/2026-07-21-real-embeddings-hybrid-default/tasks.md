## 1. Spike（承重 — 离线可证 vs live 延后，结论落 `design.md §Spike`，不过不进编码）

> 目标：钉死「配了嵌入模型即默认 hybrid、否则 BM25-only、显式覆盖」这条派生 + 装配能否**离线确定性验证**，以及真实嵌入往返为何延后。（读现有代码 + 推理即可，无 provider 真机。）

- [x] 1.1 核验 E0 `resolveEmbedder`（`AgentBootstrap.java:969`）返回 `Embedder`（可注入 `DeterministicEmbedder`/fake）或 `null`——「是否解析出嵌入器」与真网络无关（`DeterministicEmbedder` 纯本地确定性）。
- [x] 1.2 核验 effective-hybrid 是纯函数 `explicitOverride != null ? explicitOverride : (embedder != null)`——无 I/O、确定性、离线可证；`MemorySearchIndex.vectorEnabled()` 已公开可断言。
- [x] 1.3 核验 M-B 对称修复目标：M-A `buildMemoryInjection` 已调同一 `resolveEmbedder` + 同一门面（无独立 `hybrid-enabled` 门），故 M-B 只需对齐**工具路径**（`hybrid-memory-search` 的 `hybrid-enabled` 门），不改 M-A。
- [x] 1.4 核验真实嵌入往返延后理由：`OpenAiCompatibleEmbedder` 裸 `/embeddings` 网络往返离线不可验（OD7：2.0 无 embedding Model API）；纯逻辑（`buildRequestBody`/`parseEmbedding`/`embeddingsUrl` + L2 归一化）经 `HttpPost` seam 离线可测；默认模型此前 403 → 真机验证延后 `/ls:itest`。
- [x] 1.5 结论 + 被否决备选（`hybrid-enabled` boolean+新增 `hybrid-mode` 枚举 / 装配点二次 `resolveEmbedder`）记入 `design.md §Spike` 与 §Decisions D1/D2/D4。**净结论：三态可空 Boolean（缺字段=auto 派生）+ 解析一次嵌入器 + 纯派生装配 + 真往返延后 IT。**

## 2. Config 三态开关（`pig-agent-config`）

- [x] 2.1 `PigAgentConfig.SearchConfig.hybridEnabled` 由 `boolean`（默认 false）改为**可空** `Boolean`（`@JsonProperty("hybrid-enabled")` + `@JsonInclude(NON_NULL)`，缺字段 → `null`=auto，显式 `true`/`false` 原样读回）；新增 `getHybridEnabled()`（可空）/`setHybridEnabled(Boolean)`/`hasExplicitHybrid()` + 纯派生辅助 `resolveHybrid(boolean embedderPresent)`，其余字段不变。
- [x] 2.2 更新既有读取点（`AgentBootstrap` 的 `searchCfg.isHybridEnabled()` → `resolveHybrid(...)`）为三态语义；config-resilience（mapper 级 ignore-unknown）下老配置（有/无 `hybrid-enabled` 字段、显式 `true`/`false`）读回不失败；更新既有 `MemorySearchConfigYamlTest` 到新 API。
- [x] 2.3 单测：`SearchConfigTriStateTest`——缺字段 → override null（auto）；YAML 显式 `true`/`false` → 原样读回；`resolveHybrid` 纯函数（override 未设 → 跟随 `embedderPresent`；override=true/false → 覆盖）。

## 3. 装配：解析一次嵌入器 + 纯派生 + 复用（`pig-agent-cli` `AgentBootstrap`）

- [x] 3.1 hybrid 装配（约 `:460`）改为：先 `Embedder searchEmbedder = resolveEmbedder(searchCfg.getEmbedderModelId(), modelManager)`（**解析一次**）→ `if (searchCfg.resolveHybrid(searchEmbedder != null))` 注册 `HybridMemorySearchTool`。（注：本分支基于 pre-E0 的 `resolveEmbedder`——`embedder-model-id` → `resolveStoredModel`；M-B 只消费其返回的 embedder-or-null，**E0-agnostic、前向兼容**：E0 合并后 `resolveEmbedder` 增 store 默认嵌入回退，M-B 装配无需改。）
- [x] 3.2 `buildMemorySearchIndex` 签名由 `(cfg, ws, upf, ModelManager)` 改为 `(cfg, ws, upf, Embedder)`，接受**已解析的** `embedder`（装配处解析一次、消除二次 `resolveEmbedder`）；消费方 `MemorySearchConfig`/loader/`InMemoryVectorStore` 不变。
- [x] 3.3 日志：启用时区分「derived from embedding model」vs「explicit」并标注 embedder（`none (BM25-only)` / `configured`，不回显 model id/key）。
- [x] 3.4 顶替取舍现默认触发：`PigAgent.Builder` 的 `disableMemoryTools()` 自协调（toolkit 内出现 `memory_search` 即触发）**未改动**，默认路径下照常生效；flush/consolidation hooks 保留（`MEMORY.md` 照写）。
- [x] 3.5 单测：`HybridDefaultWiringTest`——(a) 派生出（`DeterministicEmbedder`）嵌入器 + auto → `resolveHybrid` 真 + `MemorySearchIndex.vectorEnabled()` 真；(b) 无嵌入器 + auto → `resolveHybrid` 假 + BM25-only；(c) 显式 `false` + 有嵌入器 → 覆盖为假；(d) 显式 `true` + 无嵌入器 → 真、混合退化 BM25-only；(e) `resolveEmbedder("")`→null、`resolveEmbedder("emb-1", mm)`→非 null。既有 `MemoryInjectionWiring` 单测保持绿。

## 4. 真实嵌入器离线覆盖（`pig-agent-core` `OpenAiCompatibleEmbedder`）

- [x] 4.1 补离线单测（注入 `HttpPost` 喂 canned JSON，无真网络）：`parsePreservesVectorDimension`（维度=响应向量长度）；既有 `embedNormalizesParsedVectorViaFakeHttp`（L2 归一化 [3,4]→[0.6,0.8]）+ `joinsEndpointToleratingTrailingSlash` + `buildsOpenAiRequestBody` 已覆盖请求体/URL。
- [x] 4.2 **错误分类**离线单测：`parseRejectsMissingData`（空 data）+ `parseRejectsEmptyEmbeddingVector`（空向量）+ `embedClassifiesNon2xxByTypeWithoutEchoingKey`（非 2xx → 携带异常类型、不含 key）；`MemorySearchIndexTest.embedderFailureDegradesToBm25` 证降级 BM25-only（门面捕获、不抛）。
- [x] 4.3 `OpenAiCompatibleEmbedder` 逻辑不改（仅补测试）；`failureDoesNotEchoTheApiKey` + 新 non-2xx 测试确认凭据（`sk-`/Bearer）绝不出现在异常。

## 5. 集成测试（真模型 `*IT`，外环，延后 `/ls:itest`）

- [ ] 5.1 `RealEmbeddingsHybridIT`（真嵌入端点，可选凭据）：对一个真实 OpenAI 兼容 `/embeddings` 端点（Doubao/DashScope/OpenAI-compat 至少一家）——(a) `OpenAiCompatibleEmbedder.embed(...)` 真往返返回合理**维度**向量、L2 归一化、**错误分类**（无效凭据/端点）映射正确；(b) 配了该嵌入模型时 `hybrid-memory-search` 默认走混合、`memory_search` 召回「换个说法也能找到」（混合召回质量优于 BM25-only）。真嵌入质量为 live-model，不在离线跑。

## 6. 验收 + 文档

- [ ] 6.1 `mvn -pl pig-agent-cli -am compile` 绿；受影响模块定向 `mvn test`（`pig-agent-config`/`pig-agent-cli`/`pig-agent-core`）绿。
- [ ] 6.2 `CLAUDE.md` 记忆检索段落新增「配了嵌入模型即默认走混合（M-B）」+ 顶替原生工具默认触发 + 逃生舱（`hybrid-enabled: false`）；`memory.search.hybrid-enabled` 三态语义 + 与 E0/M-A 的边界（E0 供给、M-B 翻默认、M-A 同门面自动受益）同步。
- [ ] 6.3 归档时（`/ls:archive`）同步主 spec → `openspec/specs/hybrid-memory-search/`（MODIFIED「混合检索开关与向后兼容」+ 新增三条 ADDED；本 change 的 `openspec validate --strict` 已在 spec 阶段通过）。
