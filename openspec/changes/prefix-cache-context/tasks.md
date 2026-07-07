## 1. 记忆注入 user 侧

- [ ] 1.1 定位记忆当前进入调用的路径，改为在「本回合 user 消息构造处」追加记忆文本（`retrieve` 结果），不经 system prompt。
- [ ] 1.2 ephemeral：注入只用于本次 API 调用，不回写会话历史/存储；system prompt 组装不含记忆。
- [ ] 1.3 `/memory off` 时跳过检索与注入；启用时沿用两层合并 + 来源标注。

## 2. 单测

- [ ] 2.1 记忆启用时 user 消息含记忆、system prompt 不含。
- [ ] 2.2 system prompt 跨回合（记忆变化）字节级稳定。
- [ ] 2.3 持久化历史的原始 user 消息不含注入内容。
- [ ] 2.4 `/memory off` 不注入。

## 3. 验收

- [ ] 3.1 `mvn -pl pig-agent-core -am test` 绿；记忆/会话既有单测回归。
- [ ] 3.2 `CLAUDE.md` 两层记忆段落补「user 侧 ephemeral 注入以保 prefix cache」。
