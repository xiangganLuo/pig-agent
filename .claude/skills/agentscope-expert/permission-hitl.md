# Permission Engine & HITL

> 源: D:\Users\admin\Documents\en\docs\building-blocks\permission-system.md
> 源: D:\Users\admin\Documents\en\docs\harness\plan-mode.md

---

## 权限引擎总体模型

包路径：`io.agentscope.core.permission`

每次工具调用都经过三层决策，**优先级从高到低**：

1. **Deny Rules**（精确拒绝，不可绕过，含 BYPASS 模式）
2. **Ask Rules / Built-in Checks**（运行时检查）
3. **Allow Rules → Mode fallback**（兜底策略）

最终产出三种决定之一：**ALLOW · DENY · ASK**。

---

## PermissionMode 枚举

`io.agentscope.core.permission.PermissionMode`

| 枚举值 | 行为 | 典型场景 |
|--------|------|---------|
| `DEFAULT` | 无显式规则则 ASK | 最安全，推荐默认 |
| `ACCEPT_EDITS` | 工作目录内文件操作自动 ALLOW | 用户在场开发时 |
| `EXPLORE` | 只读：写/命令全部 DENY | 代码探索/规划阶段 |
| `BYPASS` | 全部 ALLOW（Deny 规则仍生效） | 完全受信沙箱 |
| `DONT_ASK` | ASK 降级为 DENY | 无人值守/CI 运行 |

> **关键**：Deny rules 与 dangerous-path checks 在任何 Mode 下均不可绕过。

---

## PermissionBehavior 枚举

`io.agentscope.core.permission.PermissionBehavior`

取值：`ALLOW` / `DENY` / `ASK` / `PASSTHROUGH`

---

## PermissionRule（record）

`io.agentscope.core.permission.PermissionRule`

字段：
- `toolName: String` — 目标工具名，如 `"todo_write"` 或自定义工具名
- `ruleContent: String | null` — 匹配模式（语义由工具的 `matchRule()` 解释；`null` = 匹配所有调用）
- `behavior: PermissionBehavior` — `ALLOW` / `DENY` / `ASK` / `PASSTHROUGH`
- `source: String` — 来源标签，如 `"userSettings"` / `"projectSettings"` / `"session"` / `"suggested"`

---

## PermissionContextState 构建 & 挂载

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;

PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("safe_read",
                        new PermissionRule("safe_read", null, PermissionBehavior.ALLOW, "userSettings"))
                .addAskRule("dangerous_delete",
                        new PermissionRule("dangerous_delete", null, PermissionBehavior.ASK, "userSettings"))
                .addDenyRule("drop_table",
                        new PermissionRule("drop_table", null, PermissionBehavior.DENY, "userSettings"))
                .build();

ReActAgent agent = ReActAgent.builder()
        .name("my_agent")
        .sysPrompt("...")
        .model(model)
        .permissionContext(permCtx)   // <-- 挂载入口
        .build();
```

`ACCEPT_EDITS` + 工作目录追加：

```java
import io.agentscope.core.permission.AdditionalWorkingDirectory;

PermissionContextState ctx = PermissionContextState.builder()
        .mode(PermissionMode.ACCEPT_EDITS)
        .addWorkingDirectory(
                "/my/project",
                new AdditionalWorkingDirectory("/my/project", "userSettings"))
        .build();
```

---

## Built-in Checks（ToolBase 级，不可绕过）

`ToolBase#checkPermissions(Map<String,Object> toolInput, ToolExecutionContext context)`
返回 `Mono<PermissionDecision>`

`PermissionDecision` 四个静态工厂：`allow(message)` / `deny(message)` / `ask(message)` / `passthrough(message)`

返回 `PASSTHROUGH` = 不决策，交还引擎继续评估 rules + mode。

```java
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;

public class MyTool extends ToolBase {
    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, ToolExecutionContext context) {
        Object target = toolInput.get("target");
        if (target instanceof String s && s.startsWith("prod-")) {
            return Mono.just(PermissionDecision.ask("Operation targets production resource: " + s));
        }
        return Mono.just(PermissionDecision.passthrough("default"));
    }
}
```

危险路径常量：`ToolDangerousPathConstants`（`.ssh/`、`.aws/`、`.env`、`.gitconfig` 等），匹配后即触发 ASK（BYPASS 模式也不例外）。

---

## HITL 确认流（blocking call()）

**信号**：`result.getGenerateReason() == GenerateReason.PERMISSION_ASKING`
**状态过滤**：`ToolCallState.ASKING`
**恢复携带**：`Msg.METADATA_CONFIRM_RESULTS` metadata key

```java
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;

Msg result = agent.call(new UserMessage("Delete /tmp/important.txt")).block();

if (result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
    List<ToolUseBlock> askingTools = result.getContent().stream()
            .filter(b -> b instanceof ToolUseBlock)
            .map(ToolUseBlock.class::cast)
            .filter(t -> t.getState() == ToolCallState.ASKING)
            .toList();

    boolean approved = askUser();
    List<ConfirmResult> confirmResults = askingTools.stream()
            .map(t -> new ConfirmResult(approved, t))
            .toList();

    Map<String, Object> meta = new HashMap<>();
    meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);   // key 常量
    Msg resumeMsg = Msg.builder()
            .name("user").role(MsgRole.USER)
            .textContent(approved ? "approved" : "denied")
            .metadata(meta).build();

    Msg finalResult = agent.call(List.of(resumeMsg)).block();
}
```

**接受 suggested rules**：`ConfirmResult` 第三参数传 `toolCall.getSuggestedRules()`，引擎自动写入。

---

## HITL 确认流（streaming streamEvents()）

流式模式直接监听 `RequireUserConfirmEvent`，无需手动过滤 `ToolUseBlock`：

```java
import io.agentscope.core.event.RequireUserConfirmEvent;

agent.streamEvents(List.of(new UserMessage("...")))
        .doOnNext(event -> {
            if (event instanceof RequireUserConfirmEvent confirmEvent) {
                List<ToolUseBlock> pending = confirmEvent.getToolCalls();
                // 展示给用户，收集决定后 resume（同 blocking 方式）
            }
        })
        .blockLast();
```

全部工具被拒时：监听 `AllToolsDeniedEvent` + 发送 `RequestStopEvent`；
之后 `Msg.getGenerateReason()` 返回 `GenerateReason.ALL_TOOLS_DENIED`。

---

## 无人值守模式（CI/cron）

```java
PermissionContextState headless = PermissionContextState.builder()
        .mode(PermissionMode.DONT_ASK)    // ASK 自动降级为 DENY
        .addAllowRule("safe_read",
                new PermissionRule("safe_read", null, PermissionBehavior.ALLOW, "policy"))
        .build();
```

---

## Plan Mode

> 源: D:\Users\admin\Documents\en\docs\harness\plan-mode.md

**入口**：`HarnessAgent.builder()`（非 `ReActAgent.builder()`）

```java
import io.agentscope.harness.HarnessAgent;

HarnessAgent agent = HarnessAgent.builder()
        .name("planner")
        .model(model)
        .workspace(workspace)
        .enablePlanMode()                   // 安装三件套工具
        .planFileDirectory("plans")         // 可选，默认 "plans"
        .allowShellInPlanMode()             // 可选：允许 execute（软限制）
        .build();
```

| 构建方法 | 默认 | 说明 |
|---------|------|-----|
| `enablePlanMode()` / `enablePlanMode(boolean)` | `false` | 开启 Plan Mode |
| `planFileDirectory(String)` | `"plans"` | 计划文件根目录（workspace 相对） |
| `allowShellInPlanMode()` | `false` | Plan 阶段允许 execute（仍屏蔽 write_file/edit_file） |

**Plan 阶段三工具**：`plan_enter` / `plan_write` / `plan_exit`（+ `todo_write`）
其他工具调用直接拒绝，模型收到 `[Tool denied — plan mode is active]`。

**plan_exit 触发 HITL**：底层复用权限系统的 ASK 机制，不可绕过。

**运行时 API**（`RuntimeContext ctx = RuntimeContext.builder().sessionId("...").build()`）：

```java
agent.enterPlanMode(ctx);        // 等价于 LLM 调用 plan_enter
agent.exitPlanMode(ctx);         // 等价于 plan_exit；程序化退出不触发 HITL
agent.isPlanModeActive(ctx);
```

**运行时 Mode 切换**（bypass 临时逃生口）：

```java
agent.setPermissionMode(ctx, PermissionMode.BYPASS);  // 当前会话免提示
// ... 执行需完全权限的操作 ...
agent.setPermissionMode(ctx, PermissionMode.DEFAULT);  // 恢复

PermissionMode current = agent.getPermissionMode(userId, sessionId);
```

`setPermissionMode` 保留已有 rules/working-dir，仅切换 mode，对**下次** call 生效。

Plan Mode 状态随 `AgentState` 自动持久化，进程重启/副本切换后恢复。

---

## 关键标识符速查

| 类型 | 名称 |
|------|------|
| 枚举 | `PermissionMode.DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK` |
| 枚举 | `PermissionBehavior.ALLOW/DENY/ASK/PASSTHROUGH` |
| 枚举 | `ToolCallState.ASKING` |
| 枚举 | `GenerateReason.PERMISSION_ASKING`、`ALL_TOOLS_DENIED` |
| 常量 | `Msg.METADATA_CONFIRM_RESULTS` |
| 接口方法 | `ToolBase#checkPermissions(Map, ToolExecutionContext): Mono<PermissionDecision>` |
| 静态工厂 | `PermissionDecision.allow/deny/ask/passthrough(String)` |
| 事件 | `RequireUserConfirmEvent`、`AllToolsDeniedEvent`、`RequestStopEvent` |
| Plan 工具 | `plan_enter`、`plan_write`、`plan_exit`、`todo_write` |
