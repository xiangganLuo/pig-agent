package io.pigagent.cli.repl;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.cli.Ansi;
import io.pigagent.cli.render.StreamingMarkdownPrinter;
import io.pigagent.cli.render.ToolCallFormatter;
import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.ModelManager;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.SessionManager;
import io.pigagent.tool.availability.ToolAvailabilityReport;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Interactive terminal REPL built on picocli-shell-jline3, rendered Claude-Code style.
 *
 * <p>JLine owns the terminal and provides line editing, history and slash-command
 * completion (driven by picocli command metadata). Slash-prefixed lines are dispatched
 * to the picocli command tree via the JLine {@link SystemRegistry}; any other line is a
 * chat turn streamed to the active agent through the {@link AgentKernel} façade.
 *
 * <p>A chat turn streams via {@link AgentKernel#chat} (not the raw agent stream) so the
 * turn is registered as an interruptible unit: mid-turn Ctrl-C calls
 * {@link AgentKernel#interruptCurrent()} to cancel the in-flight model call and return to
 * the prompt without exiting. Answer text is rendered incrementally as Markdown → ANSI;
 * tool calls render as {@code ⏺ name / └ result} blocks; a {@code ⋯ thinking} spinner
 * marks reasoning. Output is colored with Jansi strings (see {@link Ansi}).
 */
public final class AgentRepl {

    private final AgentHolder agentHolder;
    private final AgentKernel agentKernel;
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
    private final ToolAvailabilityReport availabilityReport;

    public AgentRepl(AgentHolder agentHolder, AgentKernel agentKernel, Path reportsDir,
                     ConfigurationManager configManager, ProtocolRegistry registry,
                     ModelManager modelManager, CompressionService compressionService, McpManager mcpManager,
                     List<ChannelAgentBridge> bridges, SessionManager sessionManager, Path workDir,
                     AtomicReference<LineReader> readerRef, ToolAvailabilityReport availabilityReport) {
        this.agentHolder = agentHolder;
        this.agentKernel = agentKernel;
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
        this.availabilityReport = availabilityReport;
    }

    public void run() throws IOException {
        // Pick a single ANSI provider: let JLine (jna) own the terminal and render
        // ANSI; do NOT install Jansi's native hooks (would double-wrap on Windows).
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).jna(true).jansi(false).build()) {

            AtomicBoolean running = new AtomicBoolean(true);
            ReplContext ctx = new ReplContext(agentHolder, agentKernel, reportsDir,
                    configManager, registry, modelManager,
                    compressionService, mcpManager, bridges, sessionManager, terminal, running, readerRef,
                    availabilityReport);

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

            String prompt = Ansi.prompt("❯ ");
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
                        runTurn(trimmed, terminal);
                    }
                } catch (UserInterruptException ignored) {
                    // Ctrl-C at the prompt: discard the current line, keep the REPL alive.
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

    /**
     * Run one chat turn: record the user message, maybe compress, stream the answer through the
     * kernel (interruptible), then persist. Package-private so the ordering + event mapping can be
     * unit-tested without driving the JLine read loop.
     */
    void runTurn(String input, Terminal terminal) {
        sessionManager.noteUserMessage(input);
        compressionService.maybeCompress(sessionManager.getCurrentSessionId());
        Msg userMsg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(input).build()).build();
        renderStream(agentKernel.chat(agentKernel.activeId(), userMsg), terminal);
        sessionManager.saveCurrent();
    }

    /**
     * Subscribe to a turn's event stream and render it CC-style, blocking until it terminates.
     * While the turn runs, Ctrl-C (SIGINT) requests {@link AgentKernel#interruptCurrent()} and
     * disposes the subscription so control returns to the prompt (the process does not exit); the
     * previous INT handler is restored in {@code finally}. Errors are printed exactly once.
     * Package-private for the rendering/interrupt regression tests.
     */
    void renderStream(Flux<Event> stream, Terminal terminal) {
        StreamingMarkdownPrinter printer = new StreamingMarkdownPrinter();
        AtomicBoolean spinnerOn = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Ansi.println(terminal, "");

        Disposable sub = stream.subscribe(
                event -> onEvent(event, terminal, printer, spinnerOn),
                err -> {
                    error.set(err);
                    done.countDown();
                },
                () -> {
                    clearSpinner(terminal, spinnerOn);
                    printer.flush(line -> Ansi.println(terminal, line));
                    done.countDown();
                });

        Terminal.SignalHandler prev = terminal.handle(Terminal.Signal.INT, s -> {
            interrupted.set(true);
            if (agentKernel != null) {
                agentKernel.interruptCurrent();
            }
            sub.dispose();
            done.countDown();
        });
        try {
            done.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            terminal.handle(Terminal.Signal.INT, prev);
            sub.dispose();
        }

        clearSpinner(terminal, spinnerOn);
        if (interrupted.get()) {
            Ansi.println(terminal, Ansi.warn("[interrupted]"));
        } else if (error.get() != null) {
            Ansi.println(terminal, Ansi.error("Error: " + error.get().getMessage()));
        }
        Ansi.println(terminal, "");
    }

    private void onEvent(Event event, Terminal terminal, StreamingMarkdownPrinter printer,
                         AtomicBoolean spinnerOn) {
        EventType type = event.getType();
        if (type == EventType.REASONING) {
            showSpinner(terminal, spinnerOn);
        } else if (type == EventType.TOOL_RESULT) {
            clearSpinner(terminal, spinnerOn);
            Ansi.println(terminal, ToolCallFormatter.format(toolLabel(event), content(event)));
        } else if (type == EventType.AGENT_RESULT) {
            clearSpinner(terminal, spinnerOn);
            printer.accept(content(event), line -> Ansi.println(terminal, line));
        }
    }

    private static void showSpinner(Terminal terminal, AtomicBoolean spinnerOn) {
        if (spinnerOn.compareAndSet(false, true)) {
            Ansi.print(terminal, "\r" + Ansi.dim("⋯ thinking"));
        }
    }

    private static void clearSpinner(Terminal terminal, AtomicBoolean spinnerOn) {
        if (spinnerOn.compareAndSet(true, false)) {
            Ansi.print(terminal, "\r" + " ".repeat(12) + "\r");
        }
    }

    private static String content(Event event) {
        return event.getMessage() == null ? "" : event.getMessage().getTextContent();
    }

    /** Best-effort tool name from the event message; falls back to a generic label. */
    private static String toolLabel(Event event) {
        if (event.getMessage() == null) {
            return "tool";
        }
        String name = event.getMessage().getName();
        if (name == null || name.isBlank()) {
            return "tool";
        }
        String n = name.strip();
        if (n.equalsIgnoreCase("assistant") || n.equalsIgnoreCase("user") || n.equalsIgnoreCase("system")) {
            return "tool";
        }
        return n;
    }
}
