package io.pigagent.cli.repl;

import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.cli.Ansi;
import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpecRepository;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.ModelManager;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.SessionManager;
import org.jline.console.SystemRegistry;
import org.jline.console.impl.SystemRegistryImpl;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliCommands;
import picocli.shell.jline3.PicocliCommands.PicocliCommandsFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Interactive terminal REPL built on picocli-shell-jline3.
 *
 * <p>JLine owns the terminal and provides line editing, history and completion
 * (driven by picocli command metadata). Slash-prefixed lines are dispatched to the
 * picocli command tree via the JLine {@link SystemRegistry}; any other line is
 * streamed to the agent. Output is colored with Jansi strings (see {@link Ansi}).
 */
public final class AgentRepl {

    private final AgentHolder agentHolder;
    private final AgentRegistry agentRegistry;
    private final AgentSpecRepository agentRepository;
    private final AgentInstanceFactory instanceFactory;
    private final io.pigagent.core.agent.runner.AgentRunner agentRunner;
    private final Path reportsDir;
    private final ConfigurationManager configManager;
    private final ProtocolRegistry registry;
    private final ModelManager modelManager;
    private final CompressionService compressionService;
    private final McpManager mcpManager;
    private final List<ChannelAgentBridge> bridges;
    private final SessionManager sessionManager;
    private final Path workDir;
    private final AtomicReference<LineReader> readerRef;

    public AgentRepl(AgentHolder agentHolder, AgentRegistry agentRegistry,
                     AgentSpecRepository agentRepository, AgentInstanceFactory instanceFactory,
                     io.pigagent.core.agent.runner.AgentRunner agentRunner, Path reportsDir,
                     ConfigurationManager configManager, ProtocolRegistry registry,
                     ModelManager modelManager, CompressionService compressionService, McpManager mcpManager,
                     List<ChannelAgentBridge> bridges, SessionManager sessionManager, Path workDir,
                     AtomicReference<LineReader> readerRef) {
        this.agentHolder = agentHolder;
        this.agentRegistry = agentRegistry;
        this.agentRepository = agentRepository;
        this.instanceFactory = instanceFactory;
        this.agentRunner = agentRunner;
        this.reportsDir = reportsDir;
        this.configManager = configManager;
        this.registry = registry;
        this.modelManager = modelManager;
        this.compressionService = compressionService;
        this.mcpManager = mcpManager;
        this.bridges = bridges;
        this.sessionManager = sessionManager;
        this.workDir = workDir;
        this.readerRef = readerRef;
    }

    public void run() throws IOException {
        // Pick a single ANSI provider: let JLine (jna) own the terminal and render
        // ANSI; do NOT install Jansi's native hooks (would double-wrap on Windows).
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).jna(true).jansi(false).build()) {

            AtomicBoolean running = new AtomicBoolean(true);
            ReplContext ctx = new ReplContext(agentHolder, agentRegistry, agentRepository, instanceFactory,
                    agentRunner, reportsDir, configManager, registry, modelManager,
                    compressionService, mcpManager, bridges, sessionManager, terminal, running, readerRef);

            DefaultParser parser = replParser();
            PicocliCommandsFactory factory = new PicocliCommandsFactory();
            CommandLine cmd = ReplCommands.build(ctx, factory);
            PicocliCommands picocliCommands = new PicocliCommands(cmd);

            Supplier<Path> workDirSupplier = () -> workDir;
            SystemRegistry systemRegistry = new SystemRegistryImpl(parser, terminal, workDirSupplier, null);
            systemRegistry.setCommandRegistries(picocliCommands);
            factory.setTerminal(terminal);

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(systemRegistry.completer())
                    .parser(parser)
                    .variable(LineReader.LIST_MAX, 50)
                    .build();
            readerRef.set(reader);

            Ansi.println(terminal, Ansi.success("Agent ready.")
                    + Ansi.dim(" Type your message or /help for commands.\n"));

            String prompt = Ansi.prompt("you> ");
            while (running.get()) {
                try {
                    systemRegistry.cleanUp();
                    String line = reader.readLine(prompt);
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    String trimmed = line.strip();
                    if (trimmed.startsWith("/")) {
                        systemRegistry.execute(trimmed);
                    } else if (!modelManager.isConfigured()) {
                        Ansi.println(terminal, Ansi.error(
                                "No model configured. Use /model add to configure one."));
                    } else {
                        sessionManager.noteUserMessage(trimmed);
                        compressionService.maybeCompress(sessionManager.getCurrentSessionId());
                        streamToAgent(trimmed, terminal);
                        sessionManager.saveCurrent();
                    }
                } catch (UserInterruptException ignored) {
                    // Ctrl-C: discard the current line, keep the REPL alive.
                } catch (EndOfFileException eof) {
                    // Ctrl-D: exit the REPL.
                    break;
                } catch (Exception e) {
                    systemRegistry.trace(e);
                }
            }
        }
    }

    /**
     * The REPL line parser. Slash commands are the command names ({@code /help}, {@code /model}…),
     * but JLine's default {@code regexCommand} only recognizes names starting with a letter, so
     * {@code getCommand("/help")} yields "" and {@link SystemRegistry} raises "Invalid command".
     * Allowing an optional leading {@code /} in the command regex makes the whole slash-command
     * tree dispatch. Shared with tests so the config can't silently drift.
     */
    static DefaultParser replParser() {
        DefaultParser parser = new DefaultParser();
        parser.setRegexCommand("/?[a-zA-Z][a-zA-Z0-9_-]*");
        return parser;
    }

    // Package-private for the error-rendering regression test (AgentReplErrorPrintTest).
    void streamToAgent(String input, Terminal terminal) {
        Msg userMsg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(input).build()).build();

        Ansi.print(terminal, Ansi.prompt("agent> "));
        StringBuilder finalResponse = new StringBuilder();
        try {
            agentHolder.get().stream(userMsg).doOnNext(event -> {
                if (event.getType() == EventType.REASONING) {
                    Ansi.println(terminal, Ansi.dim("\n  [thinking] " + event.getMessage().getTextContent()));
                } else if (event.getType() == EventType.TOOL_RESULT) {
                    Ansi.println(terminal, Ansi.dim("  [tool] " + event.getMessage().getTextContent()));
                } else if (event.getType() == EventType.AGENT_RESULT) {
                    finalResponse.append(event.getMessage().getTextContent());
                }
            }).doOnComplete(() -> {
                if (!finalResponse.isEmpty()) {
                    Ansi.println(terminal, Ansi.info(finalResponse.toString()));
                }
            }).blockLast();
        } catch (Exception e) {
            // blockLast() re-throws the reactive error, so print it here only — printing in
            // both doOnError and this catch is what caused the duplicated "Error:" lines.
            Ansi.println(terminal, Ansi.error("\nError: " + e.getMessage()));
        }
        Ansi.println(terminal, "");
    }
}
