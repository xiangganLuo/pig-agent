package io.pigagent.cli.repl;

import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.ModelManager;
import io.pigagent.provider.registry.ProviderRegistry;
import io.pigagent.session.SessionManager;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable bundle of collaborators shared with every REPL command.
 *
 * <p>The agent is reached via {@link AgentHolder} (not a fixed reference) so commands always
 * see the current agent after a runtime model switch. {@code running} lets {@code /quit} stop
 * the loop; {@code readerRef} (set by {@link AgentRepl} after the reader is built) is used for
 * interactive confirmation prompts.
 */
public record ReplContext(
        AgentHolder agentHolder,
        ConfigurationManager configManager,
        ProviderRegistry registry,
        ModelManager modelManager,
        CompressionService compressionService,
        McpManager mcpManager,
        List<ChannelAgentBridge> bridges,
        SessionManager sessionManager,
        Terminal terminal,
        AtomicBoolean running,
        AtomicReference<LineReader> readerRef) {

    /** Convenience accessor for the current agent. */
    public io.pigagent.core.agent.PigAgent agent() {
        return agentHolder.get();
    }
}
