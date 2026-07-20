## Why

pig 的延迟工具层（capability `deferred-tools`，已在 main）里，`tool_search` 的排序仍是**朴素关键词重叠**（`DeferredToolRegistry.score`：名称/关键词/描述命中各加固定分），而记忆检索线（`hybrid-memory-search`）早已用 BM25+向量的混合 ranker。同一个 query 在"找工具"与"找记忆"两条线里排序口径不一致——这正是内核路线图 R0 立项要根除的**评分漂移**。R0（`shared-retrieval`，已合并）已把检索原语泛型化上提到 `io.pigagent.core.search`（`SearchDocument{id,text}` + `Bm25Index.index(List<? extends SearchDocument>)` + `HybridRanker` + CJK `Tokenizer`），就是为了让工具检索（Tool-OS T2）**同源复用**这套 ranker。

同时，延迟工具能力至今**默认关闭**（`tools.deferred.enabled=false`）——意味着最该受益的场景（MCP 服务器越加越多、工具 schema 越吃越大）默认拿不到省 token 的收益，用户得手动开。本 spec 把默认策略从「关」改为「**随规模智能开**」：小工具集逐字节无感，工具膨胀到阈值以上时自动把 MCP 工具移出初始 schema。

第三，`tool_search` 的**揭示（reveal）状态不随 `Toolkit.copy()` 传播**——peer/子agent 实例经 copy 派生后，其上下文里 `tool_search` 揭示的工具会静默作用到另一实例上而丢失（CLAUDE.md `deferred-tools` 段记录的已知限制）。本 spec 一并修掉。

## What Changes

- **`tool_search` 排序换 hybrid（同源复用 R0）**：`DeferredToolRegistry` 的排序从朴素关键词计分改为复用 `io.pigagent.core.search`——把每个延迟工具建成一个 `SearchDocument`（`id`=工具名，`text`=名+描述+关键词），喂 `Bm25Index` 建索引，用 BM25（可选叠加 `HybridRanker` 混合）对 query 排序。与 `memory_search`（M-A）、后续技能匹配（S2）**共用同一套 ranker/分词**，杜绝三套评分漂移。`pig-agent-tools` 已依赖 `pig-agent-core`，无需新模块依赖；`Tokenizer` 的 CJK unigram+bigram 让中文关键词也能命中中文工具描述。
- **延迟工具默认「随规模智能开」**：`tools.deferred.enabled` 默认值 **false → true**（`auto-defer-mcp` 默认已 true、`threshold` 默认已 25，不变）。启用后：**当且仅当**工具总数超过阈值时才把 MCP 工具移出初始 schema。**阈值制 = 小配置零行为变化**：当延迟计划为空（工具总数 ≤ 阈值且无显式清单命中）时，系统 MUST NOT 注册 `tool_search`、MUST NOT 分组、MUST NOT 隐藏任何工具——初始 schema 与引入本能力前逐字节一致。`enabled=false` 仍是**完全禁用**逃生口。
- **修复揭示状态跨 `Toolkit.copy()` 一致**：被揭示的延迟工具，其"可见/可调用"状态 MUST 在发起 `tool_search` 的 agent 实际运行的 toolkit 实例上生效；经 `Toolkit.copy()` 派生（peer 的 `AgentWiring.toolkitFor` / 子agent 的 `childToolkit`）且包含该延迟工具的实例，MUST NOT 因 copy 使揭示静默作用于另一实例而丢失。
- `tool_search` 的**风险分级不变**（`ToolRiskClassifier` 中仍为 `READ_ONLY`）；其 `@Tool` 名称/签名/入参/空查询与无匹配的提示语义不变；MCP 工具的 attach-时分组（`mcp:<server>`）与热移除安全性不变。

无 **BREAKING**：默认智能开经阈值制保证小工具集用户逐字节无感（见 design.md「backward-safe 论证」）；`tool_search`/延迟/揭示的对外契约（工具名、只读分级、揭示语义、MCP 生命周期）全部保持。硬约束：现有 `deferred-tools`/`shared-retrieval` 全部单测保持绿。

## Capabilities

### New Capabilities
<!-- 无新增能力。本 spec 是对既有 deferred-tools 能力的排序升级 + 默认策略调整 + 揭示状态修复，不引入新 capability。检索原语来自已合并的 shared-retrieval（R0），此处仅消费其契约、不改其 REQUIREMENT。 -->

### Modified Capabilities
- `deferred-tools`：**(1)** `tool_search`/登记表的排序从朴素关键词重叠改为复用 `io.pigagent.core.search` 的 BM25（+可选混合）ranker（同源、杜绝漂移）；**(2)** 默认策略从「关」改为「随规模智能开」，并新增「延迟计划为空时逐字节无感」的 backward-safe 保证；**(3)** 新增「揭示状态跨 `Toolkit.copy()` 派生实例一致」的要求。`tool_search` 只读分级、揭示语义、MCP 分组/热移除等既有契约不变。

## Impact

- **代码（`deferred-tools` 内部升级，行为对小工具集无感）**：
  - `pig-agent-tools` `io.pigagent.tool.deferred`：`DeferredToolRegistry.search(...)` 的排序改为经 `io.pigagent.core.search`（新增内部 `ToolDocument implements SearchDocument`，`Bm25Index` 索引 + 排序）实现，替换 `score(...)` 朴素计分；`DeferredTool` 元数据（名/描述/关键词）不变。
  - `pig-agent-config` `PigAgentConfig.DeferredToolsConfig`：`enabled` 默认 `false → true`（其余字段默认不变）。
  - `pig-agent-cli` `AgentBootstrap`：延迟接线调整为"计划为空则不注册 `tool_search`、不分组、不隐藏"（保证逐字节无感）；揭示 seam 修复为对 agent 运行的 toolkit 实例生效（`AgentWiring.toolkitFor` / `PigAgent.childToolkit` 的 copy 路径）。
  - 揭示状态跨实例一致的修复落点在 `DeferredToolGate.reveal`/`ToolSearchTool` 与 per-agent toolkit 构造之间（具体机制见 design.md D5，需承重确认）。
- **不改**：`tool_search` 的 `@Tool` 契约与 `ToolRiskClassifier` 分级（`READ_ONLY`）；`shared-retrieval`（R0）的任何 REQUIREMENT（仅消费其契约）；`ToolAvailabilityGate`（静态可见性）、`ToolContractGuard`（返回契约）、`PermissionEngine`（执行 veto）、`McpManager` 的默认路径与 attach-时分组语义。
- **依赖**：消费已合并的 `shared-retrieval`（R0）——本 spec 是 R0「Tool-OS T2 同源复用」承诺的兑现（见 design.md「交叉依赖」）。
- **测试（离线，硬约束=全绿）**：新增 registry 混合排序用例（更相关工具排前、中文关键词命中中文描述、揭示后不再被搜为待发现）；planner/gate 默认智能开 + 计划为空时逐字节无感（不注册 `tool_search`、不分组）；揭示状态跨 `Toolkit.copy()` 实例一致（peer/子agent）；现有 `deferred-tools`/`shared-retrieval` 单测保持绿。
- **文档**：`CLAUDE.md` 的「Deferred tools + tool_search」段落增补——排序已同源复用 `io.pigagent.core.search`、默认改为随规模智能开（阈值制 backward-safe）、揭示状态跨 copy 一致。
