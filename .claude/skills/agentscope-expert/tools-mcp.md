# Tools & MCP

> 源: `D:\Users\admin\Documents\en\docs\building-blocks\tool.md`

---

## 核心概念

AgentScope Java 2.0 工具体系由三个层次组成：

- **`AgentTool`** — 工具契约接口，通常通过继承 `ToolBase` 实现，或通过 `@Tool` 注解的普通 Java 方法（反射工具）实现。
- **`Toolkit`** — 工具容器，负责注册工具/MCP 客户端/技能，向模型暴露 JSON Schema，并将工具调用分发到对应工具对象。
- **`ToolGroup`** — 命名工具束，可作为整体激活/停用，代理通过内置 meta tool 在运行时切换分组。

```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new TodoTools());
toolkit.registerTool(new MyCustomTools());
```

`registerTool(Object)` 将所有 `@Tool` 方法注册到保留的 `"basic"` 分组（始终激活）。

---

## `AgentTool` / `ToolBase` 契约

`ToolBase` 是 `AgentTool` 的抽象实现。关键属性/方法：

| 方法 | 说明 |
|------|------|
| `getName()` | 工具名（暴露给模型） |
| `getDescription()` | 工具描述 |
| `getParameters()` | `Map<String, Object>` — JSON Schema 参数定义 |
| `isConcurrencySafe()` | 是否支持并发调用 |
| `isReadOnly()` | 是否只读/无副作用 |
| `isExternalTool()` | `true` 时执行委托给外部（human-in-the-loop） |
| `isMcp()` | 是否来自 MCP 服务器 |
| `getMcpName()` | MCP 服务器名称（`isMcp()=true` 时有效） |
| `checkPermissions(toolInput, context)` | 运行时权限检查，返回 `Mono<PermissionDecision>` |
| `callAsync(ToolCallParam)` | 工具执行体，返回 `Mono<ToolResultBlock>` |

---

## 内置工具

| 工具类 | 工具方法名 | 只读 | 说明 |
|--------|-----------|------|------|
| `io.agentscope.core.tool.builtin.TodoTools` | `todoWrite` | 否 | 维护会话任务列表（全量替换语义） |

Toolkit 会在有额外 ToolGroup 或技能时自动注册 `reset_tools`（meta tool）和 `load_skill_through_path`（技能查看工具），无需手动注册。

---

## 注解方式定义工具（`@Tool` / `@ToolParam`）

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

public class SimpleTools {

    @Tool(
            name = "get_current_time",
            description = "Returns the current time in a given IANA timezone.",
            readOnly = true,
            concurrencySafe = true)
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "IANA timezone, e.g. Asia/Shanghai")
                    String timezone) {
        // ...
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SimpleTools());
```

`@Tool` 常用属性：

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 工具名（默认方法名） |
| `description` | `String` | 描述 |
| `readOnly` | `boolean` | 是否只读（默认 `false`） |
| `concurrencySafe` | `boolean` | 是否可并发（默认 `false`） |
| `stateInjected` | `boolean` | 注入 `AgentState` 为额外参数（默认 `false`） |
| `converter` | `Class<? extends ToolResultConverter>` | 自定义返回值转换器 |

**无 `@ToolParam` 的参数**由框架自动注入，注入优先级：`ToolEmitter` → `Agent` → `AgentState` → `RuntimeContext` → `ToolExecutionContext`（已弃用）→ 用户 POJO（通过 `RuntimeContext.builder().put(Type, value)` 注册）。

---

## 继承 `ToolBase` 定义工具

```java
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import reactor.core.publisher.Mono;

public class WebSearchTool extends ToolBase {

    public WebSearchTool() {
        super(ToolBase.builder()
                .name("WebSearch")
                .description("Search the web for information on a given query.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string")),
                        "required", List.of("query")))
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, ToolExecutionContext context) {
        return Mono.just(PermissionDecision.allow("Read-only."));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = (String) param.getInput().get("query");
        RuntimeContext rc = param.getRuntimeContext(); // 推荐，替代已弃用的 getContext()
        // ...
    }
}
```

`ToolCallParam` 暴露：`getInput()` / `getId()` / `getRuntimeContext()` / `getAgent()` / `getEmitter()` / `getToolUseBlock()`。

---

## 外部执行工具（human-in-the-loop）

设 `.externalTool(true)`，不实现 `callAsync`。代理发出 `RequireExternalExecutionEvent` 并暂停，等待外部通过 `ExternalExecutionResultEvent` 回填结果。

```java
ToolBase.builder()
    .name("HumanApproval")
    // ...
    .externalTool(true)
    .build();
```

---

## ToolGroup 与 meta tool

```java
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.ToolGroupScope;

ToolGroup database = new ToolGroup(
        "database",
        "Tools for database operations.",
        ToolGroupScope.SESSION,
        /* active = */ false);
database.addTool("db_query");
toolkit.registerToolGroup(database);

ReActAgent agent = ReActAgent.builder()
        .toolkit(toolkit)
        .enableMetaTool(true)   // 自动注册 reset_tools
        .build();
```

- `"basic"` 分组始终激活，meta tool 不影响它。
- `reset_tools` 每次调用**全量覆写**非 basic 分组激活状态（不是 delta）。
- `SkillToolGroup`：技能加载时自动激活对应工具分组（调用 `toolkit.createSkillToolGroup(...)`）。

---

## MCP 集成

> 源: `D:\Users\admin\Documents\en\docs\building-blocks\tool.md` § MCP

AgentScope 支持三种传输方式：

| 传输 | 工厂方法 |
|------|---------|
| STDIO（本地进程） | `McpClientBuilder.stdio()` |
| Streamable HTTP（远程） | `McpClientBuilder.streamableHttp()` |
| SSE（远程） | `McpClientBuilder.sse()` |

MCP 工具在 Toolkit 内以 `mcp__{server_name}__{tool_name}` 命名空间注册，防止冲突。标记 `readOnlyHint` 的工具被权限系统自动放行。

### STDIO

```java
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

McpClientWrapper filesystem =
        McpClientBuilder.stdio()
                .name("filesystem")
                .command("mcp-server-filesystem")
                .args("--root", "/my/project")
                .build();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(filesystem).block();
```

### Streamable HTTP

```java
McpClientWrapper weather =
        McpClientBuilder.streamableHttp()
                .name("weather")
                .url("https://api.weather.com/mcp")
                .header("Authorization", "Bearer xxx")
                .build();

toolkit.registerMcpClient(weather).block();
```

### SSE

```java
McpClientWrapper search =
        McpClientBuilder.sse()
                .name("search")
                .url("https://api.search.com/mcp/sse")
                .build();

toolkit.registerMcpClient(search).block();
```

注：`registerMcpClient(McpClientWrapper)` 返回 `Mono`，必须 `.block()` 或订阅后工具才真正注册。

---

## 技能（Skill）

技能是基于 Markdown 的能力包（每个技能目录含 `SKILL.md`），不是直接可调用的工具。

```java
import io.agentscope.core.skill.repository.FileSystemSkillRepository;

ReActAgent agent = ReActAgent.builder()
        .skillRepository(new FileSystemSkillRepository(Paths.get("/path/to/skills"), false))
        .build();
```

运行时代理通过 `load_skill_through_path` 工具（`skillId` + `path` 参数）读取技能内容并激活对应工具分组。

---

## 关键类速查

| 类/接口 | 包 |
|--------|-----|
| `AgentTool` | `io.agentscope.core.tool` |
| `ToolBase` | `io.agentscope.core.tool` |
| `Toolkit` | `io.agentscope.core.tool` |
| `ToolGroup` | `io.agentscope.core.tool` |
| `ToolGroupScope` | `io.agentscope.core.tool` |
| `Tool` (注解) | `io.agentscope.core.tool` |
| `ToolParam` (注解) | `io.agentscope.core.tool` |
| `ToolCallParam` | `io.agentscope.core.tool` |
| `ToolResultBlock` | `io.agentscope.core.message` |
| `PermissionDecision` | `io.agentscope.core.permission` |
| `McpClientBuilder` | `io.agentscope.core.tool.mcp` |
| `McpClientWrapper` | `io.agentscope.core.tool.mcp` |
| `TodoTools` | `io.agentscope.core.tool.builtin` |
| `ShellCommandTool` | `io.agentscope.core.tool.coding` |
| `ReadFileTool` | `io.agentscope.core.tool.file` |
| `WriteFileTool` | `io.agentscope.core.tool.file` |
| `RuntimeContext` | `io.agentscope.core.agent` |
| `ToolExecutionContext` | `io.agentscope.core.tool`（已弃用，用 `RuntimeContext` 替代） |
