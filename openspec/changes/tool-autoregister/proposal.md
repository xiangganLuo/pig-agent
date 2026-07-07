## Why

新增内建工具当前要手动在装配处串 `toolkit.registration().tool(new X()).apply()`，容易漏、易漂移。借鉴 Hermes 的导入时自注册：工具「落文件即被发现」，无需维护手动清单。本 spec 用扫描/SPI 自动发现工具为主，同时**保留手动注册作兜底且可覆盖**（不强制全替换，稳、可回退）。依据路线图 `docs/planning/tui-and-core-roadmap.md`（Spec B⑥）。

## What Changes

- **工具自动发现**：通过 Java `ServiceLoader`（SPI）或受控 classpath 扫描，自动发现并注册内建工具，无需在装配处逐个手写。
- **保留手动注册兜底 + 可覆盖**：手动注册仍受支持；同名冲突时有明确覆盖/优先规则（如手动可覆盖自动、覆盖记 INFO 日志审计），杜绝静默重复注册。
- **加载隔离**：某个可选工具加载/实例化失败被捕获并记录，不影响其它工具注册（fail-safe），与既有权限/可用性维度叠加不变。

无 **BREAKING**：现有手动注册路径保留可用；自动发现是叠加能力，可关闭回退到纯手动。

## Capabilities

### New Capabilities
- `tool-autoregister`: 内建工具经 SPI/扫描自动发现并注册，免手动 wiring；保留手动注册作兜底且可覆盖（同名有明确优先规则），单个工具加载失败被隔离不影响其它。

### Modified Capabilities
<!-- 无：不改 tool-permissions / tool-availability 契约；本能力只改「工具如何进入 Toolkit」，属新增维度。 -->

## Impact

- **代码**：`pig-agent-tools`（自动发现机制：SPI 声明或扫描器 + 注册；冲突/覆盖规则；加载失败隔离）；装配处（`AgentWiring`/`AgentBootstrap`）从手动逐个注册改为「自动发现 + 少量手动兜底」。
- **协作/不改**：`ToolPermissionHook`（veto）、`tool-availability`（可见性门控，若已落地）、`tool-json-contract`（返回契约，若已落地）等维度不变，叠加于自动注册之上。
- **测试**：自动发现覆盖预期工具；手动兜底可覆盖自动（同名优先规则）；单个工具加载失败被隔离；回退纯手动可用。
- **文档**：`CLAUDE.md` 扩展章节「新增工具」更新为「落文件即自动发现（含手动兜底/覆盖）」。
