## Context

内建工具是带 `@Tool` 方法的类，当前在 `AgentWiring`/`AgentBootstrap` 手动 `toolkit.registration().tool(new X()).apply()` 逐个注册。新增工具须手改装配处，易漏易漂移。MCP 工具由 `McpManager` 动态注册（不在本范围）。

## Goals / Non-Goals

**Goals:**
- SPI/扫描自动发现内建工具，免手动 wiring。
- 保留手动兜底 + 同名可覆盖（有审计），不静默重复。
- 单工具加载失败隔离。

**Non-Goals:**
- 不改 MCP 动态工具注册路径。
- 不改权限 veto / 可用性门控 / 返回契约等其它维度。
- 不强制删除所有手动注册（可回退纯手动）。

## Decisions

- **D1 — 发现机制优先 SPI**：优先用 Java `ServiceLoader`（`META-INF/services` 声明工具提供者），比 classpath 扫描更显式、无反射扫包的性能/安全隐患、跨打包方式稳定。理由：AgentScope/多模块环境下 SPI 可靠；扫描作为备选（若 SPI 声明成本过高再评估）。
- **D2 — 手动兜底 + 覆盖规则**：注册顺序「自动发现 → 手动注册」，手动可覆盖同名自动实现；覆盖记 INFO 日志（对齐 Hermes 的 `override=True` 审计思路）。未预期的同名重复按规则取一并记 warn，不静默叠加。
- **D3 — 加载隔离**：每个工具的加载/实例化包 try/catch，失败记 warn（含类名/原因）后跳过，不中断整体（fail-safe），与既有「坏文件跳过」的容错风格一致。
- **D4 — 可回退**：提供开关或保留纯手动路径，便于出问题时回退到旧行为（降低引入风险）。
- **D5 — 与其它维度叠加**：自动注册只决定「工具是否进 Toolkit」；进入后仍受 `tool-availability`（是否进 schema）、`ToolPermissionHook`（执行 veto）、`tool-json-contract`（返回契约）约束。

## Risks / Trade-offs

- **R1 — SPI 声明维护**：SPI 需在 `META-INF/services` 列出实现，仍是一处「清单」，但比装配代码更轻、更靠近工具本身。权衡后仍优于手写注册代码；可配合注解处理器自动生成 services 文件进一步免手写（可选增强）。
- **R2 — classpath 扫描风险**：若选扫描，需限定包、防误扫/性能问题。→ D1 优先 SPI 规避。
- **R3 — 覆盖规则误用**：同名覆盖若无审计会隐藏问题。→ D2 覆盖记 INFO、异常重复记 warn，保证可见。
