## Why

当前长期记忆的 `record`（`EphemeralMemoryContextHook` 在 `PostCallEvent` → `CompositeLongTermMemory.record` → 会话层 `FileSystemLongTermMemory.record`）是**死记原始回合**：把消息文本按 20 字符阈值截断成 200 字的条目直接 append。问题：

- **记原始文本、不抽结构**：记下的是「用户说了什么/助手答了什么」的原文片段，没有分类、没有置信度，检索时把一堆冗长原文回喂模型。
- **噪声入库**：工具调用/工具结果、以及「我跑了 mvn test」这类**会话瞬时事件**也会被当成条目落盘，成为长期噪声。
- **无纠正机制**：用户纠正一个旧事实（"不对，其实是 X"）不会覆盖旧记忆，只会再 append 一条，检索时新旧并存、互相矛盾。
- **同步阻塞**：`record` 在回合关键路径上执行（即便当前只是写文件）。

借鉴 deerflow 的「LLM 抽取记忆」：把「死记原始回合」升级为**抽取结构化事实**——让当前模型从一个完成的回合中抽出**带类别 + 置信度**的规范化事实，只持久化高置信度的、经去噪的、按主题去重/覆盖后的记忆。

## What Changes

- **`MemoryExtractor`（Strategy + 可 mock 接缝）**：接口 `List<ExtractedFact> extract(List<Msg> turn)`；LLM 实现 `LlmMemoryExtractor` 复用压缩里「在当前模型上起一个一次性 agent」的做法，抽出**分类事实**（`user-preference`/`project-fact`/`reference`），每条带**置信度 [0,1]** 与一句规范化陈述。抽取用的 **prompt + JSON 解析（`FactJsonParser`）+ 分类** 全部落在这里。接口即接缝——单测里用假实现替换，无需真模型。
- **置信度门（`ConfidenceGate`，纯函数）**：只有 `confidence ≥ 阈值`（默认 0.7，可配）的事实才持久化。
- **纠正覆盖（`FactMerger`，纯函数）**：抽取器把「用户纠正旧事实」标记为 `correction` → 视为**高置信度**、**按 subject 覆盖**旧事实（去重/替换，而非盲目 append）。
- **噪声过滤（`MemoryNoiseFilter`，纯谓词）**：剥离工具调用/结果消息与**会话瞬时事件**（"我跑了 mvn test"、临时状态），使其不进入耐久记忆。独立单测。
- **异步去抖写入（`AsyncMemoryExtractionScheduler` + `ExtractingLongTermMemory` Decorator）**：抽取 + 写入在**回合关键路径之外**发生（不阻塞回复），**去抖合并**快速连续的回合（一个小 executor + 去抖间隔，合并到最近一次全量快照的近窗）；关闭时 **flush 无泄漏**；抽取失败 = **log + skip，绝不打断回合**（沿用压缩谱系的「吞掉并记日志」约定）。
- **配置 `memory.extraction`**（全部可选、默认安全）：`enabled`（**默认 false**，见 design D8：默认不改变行为，需显式开启）、`confidence-threshold`（0.7）、`debounce-ms`（2000）。禁用时**逐字节保留今天的原始 record 行为**。
- **只动会话层**：抽取结果只写会话层临时记忆（与今天的 record 一致），**不动全局层写路径**；`PreReasoning` 的 ephemeral 注入半部**完全不变**。

无 **BREAKING**：默认 `enabled=false` → `SessionManager` 用默认工厂建纯 `FileSystemLongTermMemory`，行为与引入本能力前逐字节一致；检索路径、`/memory` 开关、全局层、注入半部均不变。

## Capabilities

### New Capabilities
- `memory-extraction`: 从完成的回合中用当前模型抽取**带类别 + 置信度**的结构化事实，经置信度门 + 纠正覆盖 + 噪声过滤后，异步去抖写入会话层长期记忆；默认关闭，开启前行为不变。

### Modified Capabilities
<!-- 记忆当前无 openspec 主 spec（prefix-cache-context 只覆盖注入半部，不覆盖 record）。本变更为新增能力，不改注入契约、不改全局层、不改 /memory 开关。 -->

## Impact

- **代码**：`pig-agent-core` 新增 `memory/extraction/` 包（`FactCategory`/`ExtractedFact`/`MemoryExtractor`/`LlmMemoryExtractor`/`FactJsonParser`/`MemoryNoiseFilter`/`ConfidenceGate`/`FactMerger`/`FactStore`/`MarkdownFactStore`/`MemoryExtractionPipeline`/`AsyncMemoryExtractionScheduler`/`ExtractingLongTermMemory`）；`pig-agent-session` 新增 `SessionMemoryFactory` 接缝 + `SessionManager` 增一个重载构造器经工厂建会话层记忆；`pig-agent-config` 增 `memory.extraction` 块；`pig-agent-cli` `AgentBootstrap` 接线（启用时建 scheduler + 抽取工厂，关闭时 flush）。
- **不改**：`CompositeLongTermMemory` 两层合并/来源标注/只写会话层；全局层 `FileSystemLongTermMemory`；`EphemeralMemoryContextHook` 的注入半部与 `record` 委托签名；`/memory` 开关；`CompressionService`。
- **测试**：噪声过滤（工具/瞬时事件被剥离）；置信度门（≥0.7 留、<0.7 丢）；纠正按 subject 覆盖旧事实；去抖合并快速 record + 关闭时 flush；抽取器抛异常被吞掉且回合不受影响；禁用时与原始 record 逐字节一致；JSON 解析 + 分类 + 置信度钳制。全部离线、mock 模型/抽取器。
- **文档**：`CLAUDE.md` 两层记忆段落补「会话层 record 可选经 LLM 抽取结构化事实（分类 + 置信度门 + 纠正覆盖 + 噪声过滤 + 异步去抖），默认关闭」。
- **诚实边界**：抽取质量与真模型强相关——单测只用 mock 模型验证**接线/门控/覆盖/去噪/去抖/降级**的确定性逻辑，真实抽取质量需 `*IT` 真模型验证（本次不跑，避免配额）。
