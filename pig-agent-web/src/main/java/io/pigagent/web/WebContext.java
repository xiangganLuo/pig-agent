package io.pigagent.web;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.ModelManager;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.SessionManager;
import io.pigagent.task.TaskManager;

import java.nio.file.Path;

/**
 * Immutable bundle of collaborators shared with every Web handler — the Web console's analogue of
 * the CLI's {@code ReplContext}. Agent lifecycle / chat / events go through {@link AgentKernel}; the
 * rest of the capabilities reuse the SAME manager instances the CLI drives (single source of truth,
 * no duplicated business logic). This is why the Web is "just another adapter" over the kernel.
 *
 * <p>{@code globalMemoryFile} and {@code sessionsDir} let the memory handler read memory content
 * (the memory object itself only exposes {@code retrieve}); any field may be {@code null} in tests
 * that exercise a single handler.
 */
public record WebContext(
        AgentKernel agentKernel,
        ModelManager modelManager,
        SessionManager sessionManager,
        CompressionService compressionService,
        McpManager mcpManager,
        TaskManager taskManager,
        ProtocolRegistry protocolRegistry,
        ConfigurationManager configManager,
        Path globalMemoryFile,
        Path sessionsDir) {

    /** Minimal context for tests/adapters that only need the kernel (agents + events + chat). */
    public static WebContext ofKernel(AgentKernel kernel) {
        return new WebContext(kernel, null, null, null, null, null, null, null, null, null);
    }
}
