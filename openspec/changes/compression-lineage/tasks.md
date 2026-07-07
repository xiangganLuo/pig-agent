## 1. 会话元数据增 lineage 字段

- [ ] 1.1 `Session` record 增不可变 lineage 字段（`parentSessionId`/`lineageId`），加 `withXxx` 拷贝。
- [ ] 1.2 `FileSystemSessionRepository` 序列化/反序列化该字段；旧无字段 `meta.json` 容错为空（无 parent），不崩列表。
- [ ] 1.3 单测：往返序列化字段完整；旧无字段文件容错读取。

## 2. 压缩派生 lineage

- [ ] 2.1 `CompressionService` 完成一次压缩后回写会话 lineage（记录来源/parent）。
- [ ] 2.2 lineage 记录独立于压缩核心：写入失败仅记日志，不破坏压缩。
- [ ] 2.3 提供可查询入口（从压缩后会话追溯来源），供后续 TUI 消费。
- [ ] 2.4 单测：压缩后 lineage 正确记录且可查；压缩不触碰持久化历史/记忆。

## 3. 验收

- [ ] 3.1 `mvn -pl pig-agent-core -am test`（含 session）绿；压缩/会话既有单测回归。
- [ ] 3.2 `CLAUDE.md` 压缩段落补「压缩派生会话 lineage（记录，不展示）」。
