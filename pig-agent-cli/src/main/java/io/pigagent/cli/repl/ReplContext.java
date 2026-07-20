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
import io.pigagent.tool.skills.curator.SkillCuratorService;
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
        SkillGate skillGate,
        SubagentSwitchState subagentSwitch,
        SkillCuratorService skillCuratorService) {

    /**
     * Backward-compatible constructor (pre-subagent-online-switch call sites): supplies a fresh
     * {@link SubagentSwitchState} and a null {@link SkillCuratorService}. Command tests and any caller
     * that does not need the shared switch pointer / curator keep working unchanged; {@link AgentRepl}
     * uses the full constructor to share its own instance with the run loop so {@code /agent sub
     * switch|back} and {@code runTurn} agree.
     */
    public ReplContext(
            AgentHolder agentHolder, AgentKernel agentKernel, Path reportsDir,
            ConfigurationManager configManager, ProtocolRegistry registry, ModelManager modelManager,
            CompressionService compressionService, McpManager mcpManager, List<ChannelAgentBridge> bridges,
            SessionManager sessionManager, Terminal terminal, AtomicBoolean running,
            AtomicReference<LineReader> readerRef, ToolAvailabilityReport availabilityReport,
            NotificationService notificationService, SkillGate skillGate) {
        this(agentHolder, agentKernel, reportsDir, configManager, registry, modelManager,
                compressionService, mcpManager, bridges, sessionManager, terminal, running, readerRef,
                availabilityReport, notificationService, skillGate, new SubagentSwitchState(), null);
    }

    /** Convenience (S3): the backward-compat args plus a {@link SkillCuratorService}. */
    public ReplContext(
            AgentHolder agentHolder, AgentKernel agentKernel, Path reportsDir,
            ConfigurationManager configManager, ProtocolRegistry registry, ModelManager modelManager,
            CompressionService compressionService, McpManager mcpManager, List<ChannelAgentBridge> bridges,
            SessionManager sessionManager, Terminal terminal, AtomicBoolean running,
            AtomicReference<LineReader> readerRef, ToolAvailabilityReport availabilityReport,
            NotificationService notificationService, SkillGate skillGate,
            SkillCuratorService skillCuratorService) {
        this(agentHolder, agentKernel, reportsDir, configManager, registry, modelManager,
                compressionService, mcpManager, bridges, sessionManager, terminal, running, readerRef,
                availabilityReport, notificationService, skillGate, new SubagentSwitchState(),
                skillCuratorService);
    }

    /** Convenience accessor for the current agent. */
    public io.pigagent.core.agent.PigAgent agent() {
        return agentHolder.get();
    }
}
