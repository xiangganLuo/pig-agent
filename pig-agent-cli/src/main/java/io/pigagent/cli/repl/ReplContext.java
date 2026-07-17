package io.pigagent.cli.repl;

import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.ModelManager;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.SessionManager;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import io.pigagent.tool.skills.authoring.SkillGate;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable bundle of collaborators shared with every REPL command.
 *
 * <p>Agent management goes through the {@link AgentKernel} façade (not the internal registry /
 * repository / factory), so the CLI is just one adapter over the kernel. The active agent is read
 * via {@link AgentHolder} (the kernel keeps it pointed at the active instance) so commands always
 * see the current agent after a switch. {@code running} lets {@code /quit} stop the loop;
 * {@code readerRef} (set by {@link AgentRepl}) is used for interactive confirmation prompts.
 */
public record ReplContext(
        AgentHolder agentHolder,
        AgentKernel agentKernel,
        Path reportsDir,
        ConfigurationManager configManager,
        ProtocolRegistry registry,
        ModelManager modelManager,
        CompressionService compressionService,
        McpManager mcpManager,
        List<ChannelAgentBridge> bridges,
        SessionManager sessionManager,
        Terminal terminal,
        AtomicBoolean running,
        AtomicReference<LineReader> readerRef,
        ToolAvailabilityReport availabilityReport,
        NotificationService notificationService,
        SkillGate skillGate) {

    /** Convenience accessor for the current agent. */
    public io.pigagent.core.agent.PigAgent agent() {
        return agentHolder.get();
    }
}
