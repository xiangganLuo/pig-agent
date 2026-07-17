# user-profile 设计（`USER.md` 用户画像，Hermes 蓝本）

> 阶段：`/ls:spec`。分支 `feat/20260717-user-profile`（隔离 worktree，off `main`）。依赖 **#1 `pa-memory-native`（已归档、在 main）**。
> 设计事实源：`docs/design/personal-assistant-core-design.md` §6.2（用户画像 `USER.md`）、§4-P3（画像形态前提挑战）、§9（spec 拆分：#2 依赖 #1）。
> 已批准决策 **OD4-A**：用户画像 = `USER.md` Markdown 文件（结构化存储 / 外接 Honcho = 未来，非本 spec）。

## 1. 目标与非目标

**目标**：给个人助理一份**专门的用户画像** `USER.md`（身份/偏好/工作方式），稳定注入 system prompt，让助理「知道你是谁、越用越懂你」——区别于 `pa-memory-native` 的 `MEMORY.md`（通用事实日志）。维护走两条路：(a) 一个确定性的 `updateProfile` 写工具（agent 确认一条偏好即时落盘）；(b) 一个可选的、廉价模型驱动的后台蒸馏（把稳定偏好从 `MEMORY.md`/近期蒸馏进去重后的 `USER.md`）。

**非目标**：不做结构化 profile 存储（JSON/SQLite schema）或外接 Honcho 辩证建模（P3-B/C，未来）；不做混合向量检索（spec-4）；不引入新的记忆引擎——复用 `pa-memory-native` 的注入位与廉价模型套路。

## 2. `USER.md` vs `MEMORY.md`（为何独立）

| 维度 | `MEMORY.md`（`pa-memory-native`） | `USER.md`（本 spec） |
|---|---|---|
| 内容 | 通用长期事实日志（去重固化） | **策展的用户画像**：身份、长期偏好、工作方式 |
| 写入 | 原生 flush（每回合抽取）+ consolidation（后台去重） | `updateProfile` 工具（确定性即时）+ 可选后台蒸馏 |
| 结构 | LLM 自由格式 | 结构化字段行 `- **<Field>**: <value>` |
| 注入 | system prompt（`NativeMemoryContextMiddleware`） | system prompt（`UserProfileContextMiddleware`，**在 `MEMORY.md` 之前**） |
| 稳定性 | 会话内稳定（consolidation 节流） | 更稳定（画像变更罕见） |

二者都进 system prompt（可缓存前缀），画像在前、通用事实在后——这正是 Hermes 的做法（`USER.md`+`MEMORY.md` 同注入 system prompt，保护前缀缓存）。

## 3. 组件设计（design-pattern-oriented）

### 3.1 `UserProfileStore`（`pig-agent-core.profile`）——Repository/值访问
封装 `USER.md` 的读写，单一职责、容错、凭据安全：
- `String read(int maxChars)`：有界读（注入用）——超上限截断并加标记；缺失/不可读 → 空串（**不抛**）。
- `String readFull()`：完整读（合并写用）。
- `boolean updateField(String field, String value)`：**确定性 set/merge**——找 `- **<field>**:`（字段名大小写不敏感、去空白匹配）行：存在→替换其值；不存在→追加一行；文件空/缺→建 `# User Profile\n\n- **<field>**: <value>\n`。字段与值先经 `CredentialSanitizer` 脱敏、内部换行折叠为空格（保持列表项确定）。原子写、容错。

### 3.2 `UserProfileContextMiddleware`（`pig-agent-core.profile`）——注入（`NativeMemoryContextMiddleware` 兄弟）
`implements MiddlewareBase`，只实现 `onSystemPrompt`（**Transformer** hook，左→右管道）：
- 自门控 `BooleanSupplier enabled`：`false` → 恒等（identity）返回 currentPrompt（禁用即今日行为）。
- 启用：`store.read(maxChars)`（有界、凭据脱敏）；空 → identity；非空 → `currentPrompt + "\n\n" + HEADER + "\n" + profile + "\n"`。
- **顺序**：加入中间件链、位于 `NativeMemoryContextMiddleware`（`PigAgent.build` 内末尾追加）**之前**，故管道结果 = `base + USER + MEMORY`（其余中间件对 `onSystemPrompt` 恒等）。无需改 `PigAgent.Builder`/`AgentFactory`——中间件自门控 + 加入现有 `middlewares` 列表即可（最小侵入）。
- 无状态（仅 final 字段：path/maxChars/enabled）→ 一个实例可跨 interactive/channel/peer 复用（符合「middleware 实例跨 agent 复用、不缓存请求态」的规约）。

### 3.3 `UserProfileTool`（`pig-agent-tools.profile`）——`updateProfile` @Tool
- `@Tool updateProfile(field, value)` → `store.updateField(...)`；成功返回确认行，失败返回 `ToolErrors.message(...)`（canonical `{"error"}`，凭据脱敏）——**不抛**。
- `ToolRiskClassifier`：`updateProfile` = **WRITE**（经权限体系；`ask` 模式确认、`auto` 放行、`plan` 拒绝）。
- `implements ToolAvailability`：`checkAvailability()` 读 `user-profile.enabled` supplier——禁用 → 从模型 schema 隐藏（镜像 `NotifyUserTool` 门控 `outreach.enabled`）。
- Provider `UserProfileToolProvider`（SPI，`META-INF/services` 一行）：`ToolContext.userProfileFile()==null` → 返回 null（未接线不注册）；否则 `new UserProfileTool(store, enabledSupplier)`。

### 3.4 后台蒸馏（可选，默认关）——Strategy seam
- `ProfileDistiller`（函数式 seam）：`String distill(String currentProfile, String memory)` → 新的策展 `USER.md` 正文。**可 mock**（离线测试用 fake，确定性）。
- `ProfileConsolidationService`：节流（`min-gap`，`Clock` 可注入）+ 读 `MEMORY.md`+`USER.md` → `distill` → 经 store 写（有界、凭据安全）；**容错**（任何失败记 warn 并吞掉，绝不破坏）；`maybeConsolidate()`/`consolidateNow()`。镜像 `CompressionService` 的 seam 结构。
- `ModelProfileDistiller`（live 实现，reuse 廉价模型）：起一个 throwaway agent（`PigAgent.builder().name("profile-distiller").sysPrompt(...).model(cheapModel).build()`）跑一次蒸馏——**镜像 `CompressionService.ModelSummarizer`**。真实蒸馏质量属 live-model → `/ls:itest`。
- 接线：`AgentBootstrap` 在 `user-profile.consolidation.enabled` 时建 service（distiller 的模型 = `consolidation.model-id` → `memory.model-id` → 主模型，reuse 廉价模型套路），经 `TaskScheduler` 后台按 `min-gap` 触发（`*/<seconds>` 定频，service 自身再节流）。默认关 = 不调度、零影响。

## 4. Config（`user-profile` 块）

```yaml
user-profile:
  enabled: true            # 关闭 → 不注入 USER.md、updateProfile 隐藏、不蒸馏（= 今日行为）
  path: USER.md            # 工作区相对路径
  max-chars: 4000          # 注入上限（超出截断）
  consolidation:
    enabled: false         # 后台蒸馏默认关（真实质量属 live-model → IT）
    min-gap-minutes: 60     # 后台蒸馏最小间隔
    model-id: ""            # 廉价模型 id；空 → memory.model-id → 主模型
```
全部可选、默认安全、`@JsonIgnoreProperties` 容错（继承根配置的 mapper 级容错）。

## 5. 安全 / 容错

- **凭据不外泄**：`updateProfile` 写入前 `CredentialSanitizer.sanitize`（字段+值）；注入读取时也脱敏（防手编的 `USER.md` 含密钥被注入）。
- **有界**：注入 `max-chars` 截断（防画像撑爆 system prompt）。
- **容错**：`USER.md` 缺失/空/不可读 → 注入恒等、工具从空建；蒸馏失败 → 记 warn 吞掉。启动不因画像崩溃。
- **禁用路径 = 今日行为**：中间件自门控 identity + 工具 availability 隐藏 + 不调度蒸馏。

## 6. 跨会话证明（镜像记忆修复）

`USER.md` 是**工作区级单文件**（无会话作用域）——天然跨会话。测试镜像 `CrossSessionMemoryTest` 叙事：会话 A 经 `updateProfile` 写名字 → 会话 B 的 `UserProfileContextMiddleware.onSystemPrompt` 注入的画像含该名字（同一 `USER.md`，不随 `/session new` 丢失）。

## 7. 落实追踪表（评审/发现项 → 落点 + 状态）

| 发现项 / 决策 | 落点 | 状态 |
|---|---|---|
| OD4-A：画像 = `USER.md` Markdown（非结构化存储/Honcho） | §2/§3.1；`UserProfileStore` + `USER.md` | 已实现 |
| Hermes 蓝本：`USER.md` 独立于 `MEMORY.md`、同注入 system prompt、画像在前 | §2/§3.2；`UserProfileContextMiddleware` 位于 memory 中间件之前 | 已实现 |
| 复用 `pa-memory-native` 注入位（不重造记忆引擎） | §3.2；兄弟中间件复用 `onSystemPrompt` 管道 | 已实现 |
| 维护双路：即时写工具 + 后台蒸馏 | §3.3（`updateProfile`）+ §3.4（`ProfileConsolidationService`） | 已实现（蒸馏默认关） |
| `updateProfile` = WRITE、`{"error"}` 契约、availability 门控 | §3.3；`ToolRiskClassifier`+`UserProfileTool` | 已实现 |
| 复用廉价模型套路（不重造） | §3.4；`ModelProfileDistiller` 镜像 `CompressionService.ModelSummarizer`，模型走 `consolidation.model-id`→`memory.model-id`→主 | 已实现 |
| 首次运行从空开始；可选从 `MEMORY.md` 名字种子身份 | §1；无种子模板 + 蒸馏 prompt（live） | 空起已实现；种子属蒸馏 prompt → IT |
| 凭据不外泄 / 有界 / 禁用=今日行为 | §5 | 已实现 |
| 真实蒸馏质量（live-model） | §3.4 | 延后 `/ls:itest`（`UserProfileIT`） |
| 结构化存储 / Honcho 辩证建模 | P3-B/C | 延后（未来 spec，非本 spec） |
| `/profile show|edit` CLI | 设计 §6.2 提及为「后续 CLI」 | 延后（本 spec 不含；工具+注入已足够闭环） |

## 8. openspec 落点

- 变更目录：`openspec/changes/user-profile/`（本文件 + `proposal.md` + `tasks.md` + `specs/user-profile/spec.md`）。
- 新增能力 `user-profile`；与 `pa-memory-native` 是**独立叠加**（复用注入位/廉价模型，不覆盖其 spec）。
- 归档（`/ls:archive`）：同步主 spec 到 `openspec/specs/user-profile/spec.md`，变更移到 `openspec/changes/archive/2026-07-17-user-profile/`。
- `openspec validate user-profile --strict` 须通过。
