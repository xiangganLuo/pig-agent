## 1. 配置面（pig-agent-config）

- [x] 1.1 `PigAgentConfig` 新增 `@JsonProperty("memory") MemoryConfig memory`，含嵌套 `MemoryExtractionConfig`（`enabled` 默认 false、`confidence-threshold` 默认 0.7、`debounce-ms` 默认 2000）+ getter/setter。
- [x] 1.2 单测：默认值（禁用、阈值 0.7、去抖 2000）；YAML 反序列化 `memory.extraction.*`。（`MemoryExtractionConfigTest`）

## 2. 值对象 + 分类枚举 + 解析（pig-agent-core `io.pigagent.core.memory.extraction`）

- [x] 2.1 `FactCategory`（enum：USER_PREFERENCE/PROJECT_FACT/REFERENCE + `label()` + 容错 `fromLabel(s, default)`）。
- [x] 2.2 `ExtractedFact`（record：subject/category/statement/confidence/correction；紧致构造器钳制 confidence 到 [0,1] + null 安全；`withConfidence`/`asCorrection`）。
- [x] 2.3 `FactJsonParser`（纯：`parse(String) → List<ExtractedFact>`；容错：非 JSON/缺字段/未知类别 → 跳过或回退，绝不抛）。
- [x] 2.4 单测：`FactCategory` 容错解析（未知/null→默认）；`ExtractedFact` 置信度钳制 + 不可变；`FactJsonParser` 解析多事实 / 缺字段跳过 / 非 JSON→空 / 未知类别回退 / 置信度钳制。

## 3. 纯函数三件套（noise / gate / merger）

- [x] 3.1 `MemoryNoiseFilter`（`isDurable(Msg)` / `isEphemeral(text)` / `filter(List<Msg>)`：剥离 TOOL 角色 + 含 ToolUse/ToolResult 块的消息 + 瞬时状态句式，产出干净文本消息）。
- [x] 3.2 `ConfidenceGate`（`gate(facts, threshold)`：保留 ≥阈值 或 correction；correction 规范化为高置信度绕过门）。
- [x] 3.3 `FactMerger`（`merge(existing, incoming)`：按 subject 去重覆盖；incoming 覆盖同 subject 的 existing；incoming 内部 correction 优先、否则高 conf 优先）。
- [x] 3.4 单测：noise 剥离工具/瞬时事件、保留实质；gate ≥0.7 留/<0.7 丢/阈值可配/correction 绕过；merger 按 subject 覆盖 + correction 优先 + 普通去重。

## 4. 存储 + 抽取器实现（FactStore / LlmMemoryExtractor）

- [x] 4.1 `FactStore` 接口（`load()` / `save(facts)`）+ `MarkdownFactStore`（文件实现，同 `temp-memory.md`；save 写「人类可读 + 可解析元数据」的 Markdown；load 容错解析）。
- [x] 4.2 `MemoryExtractor` 接口（`@FunctionalInterface List<ExtractedFact> extract(List<Msg>)`）+ `LlmMemoryExtractor`（`Supplier<Model>` → 一次性 PigAgent 发 EXTRACTION_PROMPT + 渲染回合 → `FactJsonParser.parse`；任何异常吞掉→空）。
- [x] 4.3 单测：`MarkdownFactStore` 写读往返 / 容错解析坏行；`LlmMemoryExtractor` happy path（假 Model 返回固定 JSON → 得事实）+ 降级（Supplier 抛异常 → 空）。

## 5. 管线 + 去抖调度 + Decorator

- [x] 5.1 `MemoryExtractionPipeline`（`process(turn)`：近窗 → noise → extract → gate → load → merge → save；整体 try/catch → log.warn+return）。
- [x] 5.2 `AsyncMemoryExtractionScheduler`（`AutoCloseable`：单 daemon 线程 + 单槽未决 job + `submit`（合并去抖）/ `flush` / `close`（flush+shutdown+await），synchronized 线程安全）。
- [x] 5.3 `ExtractingLongTermMemory`（Decorator implements `LongTermMemory`：`retrieve` 委托；`record` enabled=false→委托原始，enabled=true→submit 后立即返回 Mono.empty）。
- [x] 5.4 单测：pipeline 门控+覆盖+写（假 extractor）/ extractor 抛异常被吞且 store 不变 / noise 剥离；scheduler 去抖合并（N 次 submit→1 次执行）+ flush-on-close；decorator 禁用→原始 record 等价、启用→异步不阻塞、抽取抛异常→record 仍完成、retrieve 委托。

## 6. 接线（session 工厂接缝 + cli AgentBootstrap）

- [x] 6.1 `pig-agent-session` 新增 `SessionMemoryFactory`（`@FunctionalInterface create(Path) → LongTermMemory`，`DEFAULT = FileSystemLongTermMemory::new`）；`SessionManager` 增 8 参重载构造器（多 `SessionMemoryFactory`），旧 7 参委托 `DEFAULT`；`activate` 用工厂建会话层记忆。
- [x] 6.2 `AgentBootstrap`：读 `memory.extraction`；启用时建共享 extractor/noise/gate/merger/scheduler + 包装 `SessionMemoryFactory` 传 `SessionManager`，scheduler 存 `Services` 可关闭字段、`shutdownCommon()` `close()`；未启用传 `DEFAULT`、不建 scheduler。
- [x] 6.3 `SessionManager` 既有单测不回归（7 参构造器仍在）。

## 7. 验收

- [x] 7.1 `mvn -q -T 1 test` 单线程全绿：954 测试 0 失败 0 错误 2 跳过（既有 IT 跳过）；既有记忆/会话/压缩/配置测试不回归。新增 64 测试（core 抽取 60 + config 4）。
- [x] 7.2 `mvn -q -pl pig-agent-cli -am compile` 绿。
- [x] 7.3 `CLAUDE.md` 两层记忆段落增补记忆抽取（默认关闭）说明。
