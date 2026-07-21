## 1. 承重 spike（阻塞前置：定 `consolidation.enabled` 默认值 — 安全/隐私）

- [ ] 1.1 复核离线守卫覆盖面（S1）：确认 `SecretRedactor` 覆盖所有蒸馏写路径（`UserProfileStore.write`）、`ProfileConsolidationService` 的容错 try/catch、seam 返回 null 即中止、`minGap` 节流——列出「离线能充分兜住」的四类（凭据泄漏/结构越界/崩溃-破坏/无模型空转）
- [ ] 1.2 复核离线守卫兜不住的残余风险（S2/S3）：语义幻觉（抽错人/错偏好）与非密形隐私内容（`SecretRedactor` 不识别），并确认 `USER.md` 注入 system prompt 的放大效应
- [ ] 1.3 **定默认值**：据 1.1/1.2 判定——离线无法充分兜住语义/隐私残余风险 → **保持 `ProfileConsolidationConfig.enabled` 默认 `false`，只交付机制**（默认翻转延后到 live-model IT 一行开启）；结论已落 `design.md §Spike`（净结论「兜不住则默认关」）
- [ ] 1.4 确认默认翻转是一行改动（配置缺省值 + 新工作区种子）、机制无需再动——写入 `design.md §Migration Plan`

## 2. 首次身份 seeding（seam 契约 + prompt 强化）

- [ ] 2.1 单测：空 `USER.md` + `MEMORY.md` 含身份 → seam 走 seeding 分支（`currentProfile` 为空）、产出经保守过滤后写入 `USER.md`（用 mock `ProfileDistiller`）
- [ ] 2.2 单测：空 `USER.md` + `MEMORY.md` 无可 seed 身份/偏好 → 不写（保持空），不抛
- [ ] 2.3 单测：seeding 复用既有 `ProfileDistiller.distill(currentProfile, memory)` 契约（非新路径）——断言 `ProfileConsolidationService` 对空 current 仍单次调用同一 seam
- [ ] 2.4 强化 `ModelProfileDistiller.DISTILL_PROMPT`：空画像→从 `MEMORY.md` seed 身份（姓名/称呼）+ durable 偏好；只输出**高置信** identity/preference；DROP 瞬时/任务性/非关于用户内容（prompt 文本改动，质量属 live 验）

## 3. 写入前的确定性保守过滤（纯逻辑 `DistilledProfileGuard`）

- [ ] 3.1 单测（RED）：过滤器只保留 `# User Profile` 标题 + 白名单 identity/preference 字段行；丢弃自由文本段落、白名单外字段、超长/不合规行
- [ ] 3.2 实现纯逻辑 `DistilledProfileGuard.filter(distilled) → kept`（`io.pigagent.core.profile`，无模型调用；保守白名单硬编码为安全常量）
- [ ] 3.3 单测：过滤 + `SecretRedactor` 叠加——形似密钥/token 的值不落盘
- [ ] 3.4 单测：过滤结果无合规行 → `ProfileConsolidationService` 视同中止，不调 `store.write`，既有 `USER.md` 原样
- [ ] 3.5 接线：`ProfileConsolidationService.consolidateNow` 在拿到 `distilled`、`store.write` 之前经 `DistilledProfileGuard.filter`（单测断言过滤在写盘前）

## 4. 无模型 no-op 降级 + 容错/默认值断言

- [ ] 4.1 单测：`ModelProfileDistiller` 在 `modelSupplier.get()==null` 时返回 null → `consolidateNow` 不写、不崩（no-op）
- [ ] 4.2 单测：`AgentBootstrap` 在廉价模型三级回落都解析不到时不调度/no-op（无副作用）——按实现择「不调度」或「调度但 seam no-op」
- [ ] 4.3 单测：蒸馏 seam 抛错 / 读 `MEMORY.md` 出错 → `USER.md` 原样、记 warn（复用既有容错，补断言 seeding/过滤路径同样容错）
- [ ] 4.4 单测：`ProfileConsolidationConfig.enabled` 默认 `false`（本 spec 结论）；缺配置块 → 蒸馏关、无调度、逐字节等于今日
- [ ] 4.5 组后 `mvn -pl pig-agent-core -am test` + `mvn -pl pig-agent-cli -am compile` 绿

## 5. 集成测试（外环，延后 — 默认翻转前置门）

- [ ] 5.1 `*IT`（真廉价模型，`/ls:itest`）：从含姓名/偏好的 `MEMORY.md` seed 出**正确**的身份/偏好到 `USER.md`（抽取正确性）
- [ ] 5.2 `*IT`：验证无**非密形隐私内容**误写入 `USER.md`（保守抽取质量）
- [ ] 5.3 IT 达标后（另行 spec/一行改动）：翻 `ProfileConsolidationConfig.enabled` 缺省值 + 新工作区种子——**本 spec 不做**（默认关，机制齐备）

## 6. 文档同步

- [ ] 6.1 `CLAUDE.md` `user-profile` 段落补「自动蒸馏（seeding + 写入前保守过滤 + 无模型 no-op + 默认仍关的 spike 结论）」
- [ ] 6.2 配置文档：`user-profile.consolidation`（默认关，手动 `enabled=true` 开启的说明）同步
