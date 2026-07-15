## 1. 配置块（default-safe，向后兼容）

- [x] 1.1 `PigAgentConfig` 新增 `LoopDetectionConfig` 嵌套类：`enabled`(true) / `window-size`(20) / `warn-threshold`(3) / `stop-threshold`(5) + getter/setter；新增 `@JsonProperty("loop-detection")` 字段 + `getLoopDetection()`。
- [x] 1.2 单测：缺 `loop-detection` 块时读回全默认；`enabled:false` 可解析（复用既有 `ConfigurationManager` 容错，无需新增解析代码）。

## 2. 签名策略（Strategy，TDD）

- [x] 2.1 `LoopDecision` 枚举（`OK`/`WARN`/`STOP`）；`ToolCallSignatureStrategy` 接口（`String signature(String toolName, Map<String,Object> input)`）。
- [x] 2.2 `DefaultToolCallSignatureStrategyTest`（先写，RED）：`readFile` 同路径不同小行区间 → 同签名（同 200 桶）；跨桶（相差 ≥200 行）→ 不同签名；`writeFile` 同路径不同内容 → 不同签名；参数 key 顺序不影响签名；不同工具名 → 不同签名。
- [x] 2.3 `DefaultToolCallSignatureStrategy` 实现（GREEN）：`readFile` 分桶（200 行、行参数名候选、缺失=0、负数钳 0）；其余 `toolName|sha256(canonical(input))`；`canonical` 递归排序 map key、拼 list、标量 `String.valueOf`。

## 3. 检测器（滑动窗口，TDD）

- [x] 3.1 `LoopDetectorTest`（先写，RED）：无重复 → 全 `OK`；同签名第 3 次 → `WARN`；第 5 次 → `STOP`；`readFile` 行区间近似重复累计触发；不同工具/参数不触发；窗口逐出后计数回落（小窗口验证）；构造越界值容错钳制。
- [x] 3.2 `LoopDetector` 实现（GREEN）：`ArrayDeque` 窗口、`observe` 入窗+逐出+计数+判决、`reset()`、`synchronized`、构造钳制、默认注入 `DefaultToolCallSignatureStrategy`。

## 4. 适配 hook（Adapter）

- [x] 4.1 `LoopMessages`（纯文案）：`warnText(toolName, count)` + 哨兵收敛提示常量；`LoopMessagesTest`。
- [x] 4.2 `LoopDetectionHook implements Hook`：`priority()=10`；`PreActingEvent` 检测（跳过忽略集/禁用 → `observe` → STOP 改写为哨兵、WARN 记待发）；`PreReasoningEvent` 回合重置（user 计数变化 → `reset()`）+ ephemeral 注入待发 WARN；`SENTINEL_TOOL_NAME="loopDetected"` 常量。
- [x] 4.3 `LoopDetectionHookTest`（Mockito mock `PreActingEvent`/`PreReasoningEvent`）：WARN 后下一 PreReasoning 注入一条 user 提醒；STOP 时 `setToolUse` 被以哨兵名调用（原 id 保留）；忽略集内工具名/禁用时直通不计数；user 计数变化触发 `reset()`（下一次同签名重新从低计数起算）。

## 5. 哨兵工具 + 装配

- [x] 5.1 `LoopDetectedTool`（`pig-agent-tools`，`@Tool loopDetected()` 返回收敛提示，`TOOL_NAME` 引用 `LoopDetectionHook.SENTINEL_TOOL_NAME`）+ `LoopDetectedToolProvider` + `META-INF/services` 增一行。
- [x] 5.2 `AgentBootstrap`：手动兜底清单加 `LoopDetectedTool`；读 `config.getLoopDetection()` 构造交互式 `LoopDetector`+`LoopDetectionHook` 接入 `interactiveHooks`（忽略集含 `loopDetected`+`permissionDenied`，`enabled` 经 supplier 实时读）；同法接入渠道 hook 链（各自独享检测器）。
- [x] 5.3 `mvn -q -pl pig-agent-cli -am compile` 绿。

## 6. 文档 + 验收

- [x] 6.1 `CLAUDE.md` 新增一段「工具调用循环检测（loop-detection）」：检测器/hook 的 Strategy+Adapter、`readFile` 分桶、veto-to-sentinel 复用、hook 次序、按回合重置、配置。
- [x] 6.2 `mvn -q test` 单线程（`-DforkCount=1 -Dsurefire.rerunFailingTestsCount=0`）绿，读 surefire XML 计数确认。
