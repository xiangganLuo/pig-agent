## 1. 瞬时错误分类 + 重试策略（pig-agent-core，先做核心逻辑）

- [x] 1.1 单测 `TransientErrorClassifierTest`：502/503/5xx → 可重试；`TimeoutException`/`IOException`/网络类 → 可重试；401/403/400/4xx → 不可重试；未知 → 保守判不可重试。（7/7）
- [x] 1.2 实现 `io.pigagent.core.retry.TransientErrorClassifier`（状态码文本 + 异常类型，cause 链遍历，4xx 优先）令 1.1 绿。
- [x] 1.3 单测 `RetryPolicyTest`：可重试错误重试到上限后抛出（subs=1+max）；不可重试立即透传；disabled 旁路；成功透传；listener 每次回调。（6/6）
- [x] 1.4 实现 `RetryPolicy`（`retryWhen(Retry.backoff.maxBackoff)` + 分类 `filter` + 每次尝试 `timeout` + `enabled` 旁路 + RetryListener）令 1.3 绿。
- [x] 1.5 单测 `RetryPolicyTest` pre-emission 守卫：早期失败重试、已发内容后失败不重试原样抛出（含 `containsExactly("partial")` 防重复）。
- [x] 1.6 `mvn -pl pig-agent-core -am test` 绿（13/13 新测 + 无回归）。

## 2. 配置块 model.retry（pig-agent-config）

- [ ] 2.1 单测：`model.retry` 反序列化默认值（enabled=true/max-retries=10/per-attempt-timeout=10/退避默认）；缺省块用默认；`enabled:false` 可读。
- [ ] 2.2 在 `PigAgentConfig` 加 `model.retry` 配置类 + 默认值令 2.1 绿。
- [ ] 2.3 `mvn -pl pig-agent-config -am test` 绿。

## 3. 应用到模型调用边界（pig-agent-core / cli / channel）

- [ ] 3.1 在 `PigAgent.stream`（+ 必要时 `call`）边界应用 `RetryPolicy`，`enabled:false` 时旁路；`/model test` 的 probe 走不套重试路径。
- [ ] 3.2 重试可见回调：core 暴露重试事件回调；`AgentRepl` 渲染 `[retry k/N] …` 提示；渠道路径同享重试（核对 `ChannelAgentBridge` 走 stream/call 后接入）。
- [ ] 3.3 单测：`enabled:false` 完全旁路（不重试）；`/model test` 不重试快速失败。
- [ ] 3.4 全模块 `mvn test` BUILD SUCCESS，无回归。

## 4. 集成测试（外环）+ 文档

- [ ] 4.1 端到端 `*IT`：mock/注入一个前 N 次抛 502、之后成功的模型，断言交互对话最终成功且重试次数符合预期；再断言 401 立即失败不重试。
- [ ] 4.2 文档：`CLAUDE.md` 模型章节补 `model.retry` 说明；`README`（中文）配置示例补 `model.retry` 块。
