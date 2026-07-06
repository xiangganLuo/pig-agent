## 1. 瞬时错误分类 + 重试策略（pig-agent-core，先做核心逻辑）

- [x] 1.1 单测 `TransientErrorClassifierTest`：502/503/5xx → 可重试；`TimeoutException`/`IOException`/网络类 → 可重试；401/403/400/4xx → 不可重试；未知 → 保守判不可重试。（7/7）
- [x] 1.2 实现 `io.pigagent.core.retry.TransientErrorClassifier`（状态码文本 + 异常类型，cause 链遍历，4xx 优先）令 1.1 绿。
- [x] 1.3 单测 `RetryPolicyTest`：可重试错误重试到上限后抛出（subs=1+max）；不可重试立即透传；disabled 旁路；成功透传；listener 每次回调。（6/6）
- [x] 1.4 实现 `RetryPolicy`（`retryWhen(Retry.backoff.maxBackoff)` + 分类 `filter` + 每次尝试 `timeout` + `enabled` 旁路 + RetryListener）令 1.3 绿。
- [x] 1.5 单测 `RetryPolicyTest` pre-emission 守卫：早期失败重试、已发内容后失败不重试原样抛出（含 `containsExactly("partial")` 防重复）。
- [x] 1.6 `mvn -pl pig-agent-core -am test` 绿（13/13 新测 + 无回归）。

## 2. 配置块 model.retry（pig-agent-config）

- [x] 2.1 单测 `RetryConfigTest`：默认值（enabled=true/max-retries=10/timeout=10/backoff 500·8000）；缺省块用默认；`enabled:false` 与自定义值可读。（3/3）
- [x] 2.2 `PigAgentConfig.ModelConfig` 加 `retry` 块 + `RetryConfig`（含默认值 + getter/setter）令 2.1 绿。
- [x] 2.3 `mvn -pl pig-agent-config -am test` 绿。

## 3. 应用到模型调用边界（pig-agent-core / cli / channel）

- [x] 3.1 `PigAgent.stream` 应用 `RetryPolicy`（builder 加 `retryPolicy`，null=不重试）；`enabled:false` 由 `RetryPolicy.apply` 旁路；`ModelManager.test` 的 probe 无 policy 且用 `call()`→结构性豁免。
- [x] 3.2 重试可见：`RetryPolicy.RetryListener` 回调；`PigAgentCli` 交互版打印 `[retry k/N] cause, backing off Nms…` 到终端、渠道版记 stderr；`AgentFactory` 加 `retryPolicy` 参数贯通交互 + 渠道（两者都走 `stream()`，单点覆盖）。
- [x] 3.3 `enabled:false` 旁路由 `RetryPolicyTest.disabled_bypassesRetryEntirely` 覆盖；`/model test` 豁免为结构性（probe 不带 policy）。
- [x] 3.4 全模块 `mvn test` BUILD SUCCESS，无回归。

## 4. 集成测试 + 文档

- [x] 4.1 端到端**离线确定性**测试 `ModelRetryWiringTest`（假 `Model` 驱动真实 `PigAgent.stream`/`ReActAgent`）：前 2 次 502→重试到成功且响应正常；401→不重试模型仅调 1 次。比真上游 502 的 IT 更强（可复现、无需 key）。
- [x] 4.2 文档：`CLAUDE.md` 加 Model retry 段（分类/策略/pre-emission/豁免/配置）；`README` 配置示例补 `model.retry` 块（含中文注释）。
