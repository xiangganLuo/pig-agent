## Context

`CompressionService` 在 token 预算超限时摘要较早回合、保留最近回合，只作用于内存对话，不碰持久化历史与记忆。`Session`（record）+ `FileSystemSessionRepository` 以 `meta.json` 存会话元数据，仓库对坏文件容错。当前压缩不留任何「从哪来」的谱系痕迹。

## Goals / Non-Goals

**Goals:**
- 压缩时记录并持久化 parent/child 谱系，可查询、可追溯来源。
- 旧无字段会话容错。
- 不改压缩既有语义。

**Non-Goals:**
- 不做谱系的 UI 展示（TUI 后置）。
- 不改压缩的触发/摘要/保留逻辑。
- 不引入新的会话存储后端（沿用 meta.json）。

## Decisions

- **D1 — lineage 承载于会话元数据**：在 `Session` record 增不可变字段（如 `parentSessionId` 或 `lineageId` + `parentId`），经 `withXxx` 拷贝；`FileSystemSessionRepository` 序列化/反序列化该字段，缺字段容错为空（无 parent）。理由：沿用既有会话元数据与容错模式，最小侵入。
- **D2 — 谱系语义**：压缩「派生」一个逻辑上的后继会话状态，其 parent 指向压缩前。具体是「同一 session id 记录一次压缩事件的 parent 快照引用」还是「派生新 session id」由实现定；spec 只要求「可从压缩后追溯来源」。倾向轻量：在当前会话元数据记录「上次压缩来源」的 lineage 标记，不强制新建 session 文件。
- **D3 — 仅记录不展示**：本 spec 到「持久化 + 可查询」为止；查询 API 供后续 TUI/前端消费。
- **D4 — 与压缩流程的接点**：在 `CompressionService` 完成一次压缩后回写会话元数据的 lineage；失败不影响压缩主流程（lineage 记录尽力而为，但成功路径应稳定写入）。

## Risks / Trade-offs

- **R1 — 谱系模型选择**：「同 id + 压缩事件链」vs「派生新 id」。前者简单、不扰乱会话列表；后者更接近 Hermes 的「子会话」。→ 本 spec 采轻量的前者（元数据记录来源），避免会话列表膨胀；若后续需要真子会话再演进。
- **R2 — 元数据兼容**：新增字段须容错旧文件（无字段=无 parent），遵循仓库既有容错约定。
- **R3 — 与压缩的耦合**：lineage 写入失败不应回滚/破坏压缩。→ 记录步骤独立于压缩核心，异常仅记日志。
