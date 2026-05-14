package io.pigagent.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.pigagent.config.PigAgentConfig.McpConfig;
import io.pigagent.config.PigAgentConfig.McpServerConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages MCP client connections and registers their tools with the agent's Toolkit.
 * Supports stdio, SSE, and streamable HTTP transports.
 */
public final class McpManager {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private final List<McpClientWrapper> clients = new ArrayList<>();

    public void connectAll(McpConfig mcpConfig, Toolkit toolkit) {
        if (mcpConfig == null || mcpConfig.getServers().isEmpty()) {
            return;
        }
        for (Map.Entry<String, McpServerConfig> entry : mcpConfig.getServers().entrySet()) {
            String name = entry.getKey();
            McpServerConfig serverConfig = entry.getValue();
            try {
                McpClientWrapper client = connectServer(name, serverConfig);
                toolkit.registerMcpClient(client).block(DEFAULT_TIMEOUT);
                clients.add(client);
                System.err.println("[MCP] Connected: " + name);
            } catch (Exception e) {
                System.err.println("[MCP] Failed to connect " + name + ": " + e.getMessage());
            }
        }
    }

    private McpClientWrapper connectServer(String name, McpServerConfig config) {
        McpClientBuilder builder = McpClientBuilder.create(name)
                .timeout(DEFAULT_TIMEOUT)
                .initializationTimeout(DEFAULT_TIMEOUT);

        String command = config.getCommand();
        String url = config.getUrl();

        if (command != null && !command.isBlank()) {
            List<String> args = config.getArgs() != null ? config.getArgs() : List.of();
            Map<String, String> env = config.getEnv() != null ? config.getEnv() : Map.of();
            builder.stdioTransport(command, args, env);
        } else if (url != null && !url.isBlank()) {
            if (config.isStreamableHttp()) {
                builder.streamableHttpTransport(url);
            } else {
                builder.sseTransport(url);
            }
            Map<String, String> headers = config.getHeaders();
            if (headers != null) {
                builder.headers(headers);
            }
        } else {
            throw new IllegalArgumentException("MCP server '" + name + "' must have either 'command' or 'url'");
        }

        return builder.buildSync();
    }

    public void closeAll() {
        for (McpClientWrapper client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                System.err.println("[MCP] Error closing " + client.getName() + ": " + e.getMessage());
            }
        }
        clients.clear();
    }

    public List<McpClientWrapper> getClients() {
        return List.copyOf(clients);
    }
}
