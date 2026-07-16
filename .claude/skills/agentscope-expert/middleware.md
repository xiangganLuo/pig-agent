# Middleware

> 源: `D:\Users\admin\Documents\en\docs\building-blocks\middleware.md`

## 1. 概述

Middleware 允许在 agent 执行流程的关键节点注入自定义逻辑（日志、tracing、输入改写、访问控制……），无需修改 agent 或 model 代码。

接口：`MiddlewareBase` (`io.agentscope.core.middleware`)。一个类可实现 5 个 hook 中的任意子集，未实现的 hook 默认为 `next.apply(input)`，零成本跳过。

---

## 2. 五个 Hook 阶段（完整枚举）

| Hook 名 | 类型 | 语义 |
|---------|------|------|
| `onAgent` | **Onion**（洋葱） | 包裹完整回复流——涵盖所有 ReAct 轮次、工具执行、最终输出 |
| `onReasoning` | **Onion** | 包裹一次 reasoning 步骤（输入组装 → 模型调用 → 流式解码） |
| `onActing` | **Onion** | 包裹单次工具调用执行 |
| `onModelCall` | **Onion** | 包裹底层 `ChatModel` API 调用——最靠近模型 |
| `onSystemPrompt` | **Transformer**（管道） | 在 reasoning 步骤组装系统提示时触发；多个 middleware 依次变换，前一个输出是下一个输入 |

**嵌套层级：**

```text
onAgent/
└── ReAct loop (per round)/
    ├── onReasoning/
    │   ├── onSystemPrompt  (组装系统提示)
    │   └── onModelCall     (模型 API 调用)
    └── onActing            (每次工具调用)
```

两种类型的区别：
- **Onion**：中间件包裹 `next`，可在 `next.apply(input)` 前后插入逻辑，或用 Reactor 算子观察/改写事件流。
- **Transformer**：形成管道，前一个输出即下一个输入，无"内层"概念。

> `onActing` 仅跟踪 agent runtime 内部的工具执行；外部执行（external execution）不被跟踪。

---

## 3. 注册方式

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import java.util.List;

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .sysPrompt("You are a helpful assistant.")
                .model(model)
                .toolkit(toolkit)
                .middlewares(List.of(new OtelTracingMiddleware()))
                .build();
```

- `.middleware(m)` — 追加单个
- `.middlewares(List<? extends MiddlewareBase>)` — 批量追加

---

## 4. 执行顺序

**Onion hooks**（`onAgent` / `onReasoning` / `onActing` / `onModelCall`）——**列表中第一个最外层**：

```
middlewares = [mw1, mw2]
// mw1 pre → mw2 pre → inner → mw2 post → mw1 post
```

流式/事件发射时，内层 middleware 先收到事件：

```
mw1_pre → mw2_pre → mw2_event → mw1_event → ... → mw2_post → mw1_post
```

**Transformer hook**（`onSystemPrompt`）——**从左到右管道**：

```
middlewares = [mw1, mw2]
// originalPrompt → mw1.onSystemPrompt() → mw2.onSystemPrompt() → final
```

---

## 5. Hook 输入 Record 类型（`io.agentscope.core.middleware`）

| Hook | 输入 Record | 主要字段 |
|------|------------|----------|
| `onAgent` | `AgentInput` | `msgs: List<Msg>` |
| `onReasoning` | `ReasoningInput` | `messages: List<Msg>`, `tools: List<ToolSchema>`, `options: GenerateOptions` |
| `onActing` | `ActingInput` | `toolCalls: List<ToolUseBlock>` |
| `onModelCall` | `ModelCallInput` | `messages`, `tools`, `options`, `model: Model` |
| `onSystemPrompt` | `String` | 当前系统提示字符串 |

改写下游输入：构造新 Record 再调 `next.apply(newInput)`。

---

## 6. 自定义 Middleware 示例

### 全观测性 middleware

```java
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class FullObservabilityMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        System.out.println("[agent] start for " + agent.getName());
        return next.apply(input)
                .doOnComplete(() -> System.out.println("[agent] end for " + agent.getName()));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        System.out.println("[reasoning] start");
        return next.apply(input).doOnComplete(() -> System.out.println("[reasoning] end"));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        System.out.println("[model_call] " + input.model().getClass().getSimpleName());
        return next.apply(input).doOnComplete(() -> System.out.println("[model_call] done"));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        System.out.println("[system_prompt] length=" + currentPrompt.length());
        return Mono.just(currentPrompt);
    }
}
```

> **javap 歧义**：文档中 `TimingMiddleware` 示例的 `onModelCall` 签名缺少 `RuntimeContext ctx` 参数；`DynamicContextMiddleware` 示例的 `onSystemPrompt` 也缺少 `RuntimeContext ctx`。实际接口签名以 `FullObservabilityMiddleware`（含 `RuntimeContext`）为准，但**需 javap 验证**两种形式是否均为合法重载。

### 动态系统提示

```java
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;
import java.util.function.Supplier;

public class DynamicContextMiddleware implements MiddlewareBase {
    private final Supplier<String> contextFn;
    public DynamicContextMiddleware(Supplier<String> contextFn) { this.contextFn = contextFn; }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        return Mono.just(currentPrompt + "\n\n## Current Context\n" + contextFn.get());
    }
}
// 注册: .middlewares(List.of(new DynamicContextMiddleware(() -> "Time: " + Instant.now())))
```

### 拦截全部工具拒绝后停止 agent

```java
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;

public class StopOnAllDeniedMiddleware implements MiddlewareBase {
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .flatMap(event -> {
                    if (event instanceof AllToolsDeniedEvent) {
                        return Flux.just(
                                event,
                                new RequestStopEvent(
                                        "All tools denied by user",
                                        GenerateReason.ALL_TOOLS_DENIED));
                    }
                    return Flux.just(event);
                });
    }
}
```

---

## 7. RuntimeContext 读写

每个 hook 的第二参数是 `RuntimeContext` (`io.agentscope.core.agent.RuntimeContext`)——当次 `call`/`stream` 绑定的请求上下文：

```java
public Flux<AgentEvent> onAgent(
        Agent agent, RuntimeContext ctx, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
    System.out.printf("[req] user=%s session=%s reqId=%s%n",
            ctx.getUserId(), ctx.getSessionId(), ctx.get("request_id"));
    ctx.put("trace_id", java.util.UUID.randomUUID().toString());  // 对下游 hook/工具可见
    return next.apply(input);
}
```

- 同一 `RuntimeContext` 实例在一次回复的所有 hook 和工具间共享；其 map 线程安全。
- **不要**把请求级状态缓存在 middleware 实例字段上——middleware 实例通常跨 agent/调用复用。
- builder 上的全局 `toolExecutionContext` 由框架合并到 per-call context（per-call 优先）。

---

## 8. 内置 Middleware

### OtelTracingMiddleware

`io.agentscope.core.tracing.OtelTracingMiddleware`：实现 `onAgent` / `onModelCall` / `onActing`，产生嵌套 span：
- `invoke_agent <name>` — 完整回复
- `chat <model>` — 每次模型 API 调用
- `execute_tool <name>` — 每次工具执行

无 OTel SDK 配置时所有 hook 直通 `next.apply(input)`，近零开销。

### TaskReminderMiddleware

`io.agentscope.core.middleware.TaskReminderMiddleware`：配合内置 `TodoTools`，每次 reasoning 步骤前将 `AgentState.tasksContext` 渲染为 `<system-reminder>` 注入上下文。通过 `.enableTaskList(true)` 启用（无需手动 `new`）。

---

参考示例：`agentscope-examples/.../middleware/CustomizedMiddlewareExample.java`、`ModelCallMiddlewareExample.java`、`SystemPromptMiddlewareExample.java`。
