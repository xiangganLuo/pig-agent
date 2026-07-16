# 生产化路线图（2026-07-15）

> ⏸ **SHELVED（2026-07-16 用户拍板：生产化搁置，先解决内核）。** 本路线图暂缓，待 AgentScope 2.0 全量迁移（内核）落地后再启。当前优先级见 `kernel-first-plan-2026-07-16.md`。

> 站在**生产实际使用**角度：从"功能齐全的 beta"走向"能被真实用户安装、部署、长期运行"的产品。
> 现状基线：main 15 模块、630 单测全绿、jacoco 质量门、真模型 IT（Doubao）通过；六批能力 + 飞书/钉钉渠道 + 内置插件集合 + Web 控制台已合入。
> 分支命名沿用 `<type>/YYYYMMDD-<功能名>`；每项作为独立 spec 走 `/ls:*`，能并行则并行。

## 判断：现在缺的不是"功能"，是"可交付/可运维"

功能面已相当完整。真实生产使用的短板集中在**构建可靠性、可分发性、并发与运维、密钥与安全姿态**——用户装不上/跑不起来/不敢在服务器上长跑，比缺一个功能更致命。

---

## Tier 0 · 上线阻断（不解决就无法真正交付）

| # | spec 建议名 | 问题（生产视角） | 关键动作 |
|---|---|---|---|
| 0-1 | `build-jdk17-gate` | **必需 JDK 17 下 `mvn test` 直接失败**（Mockito inline self-attach 被拦）；此前全绿都在本机 JDK 21。CI/别人用 JDK 17 即红 | surefire `argLine` 加 `-Djdk.attach.allowAttachSelf=true`，与 jacoco 的 `@{argLine}` 晚绑定合并；或把 Mockito 配成 javaagent。JDK 17/21 双跑验证 |
| 0-2 | `runnable-distribution` | 只有 `mvn exec:java`（开发态），**没有可分发产物**——用户无法"下载即用" | maven-shade/assembly 打**可执行 fat-jar** + `pig-agent` 启动脚本（win/unix）；`java -jar` 一键起；版本号/manifest |
| 0-3 | `ci-pipeline` | 有 jacoco 门 + failsafe，但**无人自动跑**；无 CI 即无回归防线 | CI（build + `mvn verify` 覆盖率门 + 可选 `-Pit`）在 push/PR 触发；产物上传；徽章。发布流程（tag → 打包 → release） |

> **建议先做 Tier 0**：这是"能交付"的最小集，三者相互独立、可并行三个 spec。

## Tier 1 · 生产硬化（多用户 / 服务器长跑）

| # | spec 建议名 | 问题 | 关键动作 |
|---|---|---|---|
| 1-1 | `concurrency-safety` | `AgentRegistry` 非线程安全；REPL/Web/渠道共享单飞 agent，并发即撞 "Agent is still running" | registry 线程安全；每 agent 回合串行化/排队；Web 与渠道并发下的隔离或队列 |
| 1-2 | `secret-management` | 密钥仍明文 + 0600；服务器/共享主机不安全 | 支持环境变量 / OS keychain / 外部 secret 引用；`models.json`/`mcp.json` 只存引用；文档明确姿态 |
| 1-3 | `observability` | 无指标/健康检查；长跑无法监控 token 成本、延迟、错误率 | 结构化日志开关 + 指标（token/成本/延迟/错误）+ `/health`（Web）+ 成本预算/限流钩子 |
| 1-4 | `web-console-hardening` | Web 控制台最小面、无鉴权、仅 loopback、无 session/permission 平价 | 访问令牌鉴权；可选非 loopback 绑定（需鉴权+HTTPS）；Web 回合镜像 `noteUserMessage→maybeCompress→saveCurrent`；权限平价 |

## Tier 2 · 完整度 / 生态

| # | spec 建议名 | 内容 |
|---|---|---|
| 2-1 | `channel-completeness` | 飞书 AES 加密推送解密、Slack 出站真发、IM 3s 超时的**异步 ack**（先 ack 后跑）；Telegram/Discord 出站真实化 |
| 2-2 | `provider-matrix` | 5 协议全部真模型验证；每模型成本/速率预算；失败降级策略 |
| 2-3 | `coverage-raise` | 新模块（web/plugin/plugin-collection/channel）纳入棘轮；`AgentBootstrap` 拆可测；cli floor(0.27) 逐步抬 |
| 2-4 | `plugin-ecosystem` | 外部 jar 插件端到端验证 + 插件编写指南 + 更多内置插件 |

## Tier 3 · 部署与文档

- `deploy-docker`：Docker 镜像 + compose；systemd unit 定稿；数据卷/配置外挂。
- `docs-for-users`：安装/部署/插件编写/渠道接入指南（面向真实用户，非开发者笔记）。

---

## 推进建议（并行）

1. **第一波（并行三 spec）**：Tier 0 全部——`build-jdk17-gate`（小、先做，解锁 CI）、`runnable-distribution`、`ci-pipeline`。三者独立，可同时开三个自主 subagent。
2. **第二波**：Tier 1 的 `concurrency-safety` + `secret-management`（生产安全/稳定核心），可并行。
3. 之后按资源穿插 Tier 2 / Tier 3。

> 原则不变：每 spec 走 `/ls:*`、各自 `feat/YYYYMMDD-<名>` 分支、自主到归档、绿了再合 main；设计优先用模式而非面向功能。
