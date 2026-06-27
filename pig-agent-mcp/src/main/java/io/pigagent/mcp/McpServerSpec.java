package io.pigagent.mcp;

import java.util.List;
import java.util.Map;

/**
 * 一个 MCP 服务器配置（不可变）。{@code name} 是唯一键，也是注册进 Toolkit 的 MCP client 名。
 *
 * <p>必须恰好设置 {@code command}（stdio）或 {@code url}（SSE / streamable-http）之一。
 * 紧凑构造器做校验并把集合字段拷成不可变副本。
 */
public record McpServerSpec(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        boolean streamableHttp,
        Map<String, String> headers,
        boolean enabled) {

    public McpServerSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server name is required");
        }
        boolean hasCommand = command != null && !command.isBlank();
        boolean hasUrl = url != null && !url.isBlank();
        if (hasCommand == hasUrl) {
            throw new IllegalArgumentException(
                    "MCP server '" + name + "' must set exactly one of command / url");
        }
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    /** "stdio" | "sse" | "streamable-http"。 */
    public String transport() {
        if (isStdio()) {
            return "stdio";
        }
        return streamableHttp ? "streamable-http" : "sse";
    }

    public McpServerSpec withEnabled(boolean newEnabled) {
        return new McpServerSpec(name, command, args, env, url, streamableHttp, headers, newEnabled);
    }

    public McpServerSpec withName(String newName) {
        return new McpServerSpec(newName, command, args, env, url, streamableHttp, headers, enabled);
    }

    /** 列表展示用标签，例如 "filesystem (stdio)"。 */
    public String label() {
        return name + " (" + transport() + ")";
    }
}
