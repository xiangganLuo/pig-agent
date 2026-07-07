## Why

`CompressionService` 压缩内存对话时把较早的回合摘要、保留最近回合，但压缩前后的会话没有可追溯的谱系关系——一旦压缩，看不出「这个精简会话是从哪个更长的会话摘要而来」。借鉴 Hermes 的压缩 lineage：压缩派生一个 parent/child 谱系 ID 记入会话元数据，使压缩可追溯、可回溯来源。本 spec 范围**仅记录 lineage、不做 UI 展示**（TUI 展示后置）。依据路线图 `docs/planning/tui-and-core-roadmap.md`（Spec B⑤）。

## What Changes

- **压缩派生 lineage**：一次压缩发生时，系统记录 parent/child 谱系关系（压缩后的会话状态标注其来源），写入会话元数据（`Session` 记录 / `meta.json`）。
- **仅记录不展示**：本 spec 只保证 lineage 被持久化且可查询；TUI/前端展示谱系留后续（`tui-frontend` 或其迭代）。
- **不改压缩既有语义**：压缩仍只作用于内存对话，MUST NOT 触碰持久化历史与记忆内容；保留最近回合、成对保留 tool 消息等现有行为不变。

无 **BREAKING**：lineage 为新增元数据字段；旧会话无 lineage 时按无 parent 处理。

## Capabilities

### New Capabilities
- `compression-lineage`: 上下文压缩时派生并持久化会话谱系（parent/child），使压缩可追溯到来源会话；本阶段仅记录与查询，不做 UI 展示。

### Modified Capabilities
<!-- 无既有 capability spec：压缩当前无 openspec 主 spec；本变更为新增能力，不改既有契约。 -->

## Impact

- **代码**：`pig-agent-core`（`compression`：压缩时生成 lineage 并回写）；`pig-agent-session`（`Session` 记录/`FileSystemSessionRepository` 增 lineage 字段：parent/child 或 lineage id，容错读取旧无字段文件）。
- **不改**：压缩只作用内存对话、不碰持久化历史/记忆等既有约束；会话激活/切换语义。
- **测试**：压缩后 lineage 被记录且可查（parent/child 正确）；旧无字段会话容错；压缩不触碰持久化历史。
- **文档**：`CLAUDE.md` 压缩段落补「压缩派生会话 lineage（记录，不展示）」。
