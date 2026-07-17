package io.pigagent.cli.repl;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.pigagent.cli.Ansi;
import io.pigagent.cli.render.StreamingMarkdownPrinter;
import io.pigagent.cli.render.SubagentEventRenderer;
import io.pigagent.cli.render.ToolCallFormatter;
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
import org.jline.console.SystemRegistry;
import org.jline.console.impl.SystemRegistryImpl;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliCommands;
import picocli.shell.jline3.PicocliCommands.PicocliCommandsFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(AgentRepl.class);

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
    private final NotificationService notificationService;
    private final SkillGate skillGate;

    public AgentRepl(AgentHolder agentHolder, AgentKernel agentKernel, Path reportsDir,
                     ConfigurationManager configManager, ProtocolRegistry registry,
                     ModelManager modelManager, CompressionService compressionService, McpManager mcpManager,
                     List<ChannelAgentBridge> bridges, SessionManager sessionManager, Path workDir,
                     AtomicReference<LineReader> readerRef, ToolAvailabilityReport availabilityReport,
                     NotificationService notificationService, SkillGate skillGate) {
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
        this.notificationService = notificationService;
        this.skillGate = skillGate;
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
                    availabilityReport, notificationService, skillGate);

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

            // Type-ahead slash completion (auto-list + arrow navigation) needs a real TTY; on a dumb
            // terminal (bare gitbash/mintty) we degrade to plain Tab completion.
            if (io.pigagent.cli.repl.select.InlineSelector.isInteractive(terminal)) {
                SlashCompletionWidgets.install(reader, picocliCommands.commandNames());
            }

            Ansi.println(terminal, Ansi.success("Agent ready.")
                    + Ansi.dim(" Type your message or /help for commands.\n"));

            String prompt = Ansi.prompt("❯ ");
            while (running.get()) {
                try {
                    systemRegistry.cleanUp();
                    Ansi.println(terminal,
                            StatusLine.from(modelManager, sessionManager, configManager, planModeActive()));
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

    /** Bound on HITL confirm/resume rounds within a single turn (defence against a loop). */
    private static final int MAX_CONFIRM_ROUNDS = 20;

    /** Native Plan Mode's exit tool (its {@code checkPermissions} returns ASK → surfaces as HITL). */
    private static final String PLAN_EXIT_TOOL = "plan_exit";

    /**
     * Whether native Plan Mode is active for the current session (drives the {@code ⏸ PLAN} status
     * badge). Reads through the active agent; any failure degrades to {@code false} so a status read
     * never breaks the prompt (plan mode may simply not be enabled on this build).
     */
    private boolean planModeActive() {
        try {
            var agent = agentHolder == null ? null : agentHolder.get();
            if (agent == null) {
                return false;
            }
            String sid = sessionManager == null ? null : sessionManager.getCurrentSessionId();
            return agent.isPlanModeActive(sid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Run one chat turn: record the user message, maybe compress, stream the answer through the
     * kernel (interruptible, session-bound), resolving any HITL confirmations, then persist.
     * Package-private so the ordering + event mapping can be unit-tested without the JLine read loop.
     */
    void runTurn(String input, Terminal terminal) {
        sessionManager.noteUserMessage(input);
        String sessionId = sessionManager.getCurrentSessionId();
        compressionService.maybeCompress(sessionId);
        Msg userMsg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(input).build()).build();
        renderTurn(userMsg, sessionId, terminal);
        sessionManager.saveCurrent();
        maybeAutoPromoteSkills(terminal);
    }

    /**
     * Opt-in interactive auto-promotion: when {@code skills.autonomous.auto-promote} is on, promote any
     * staged skill drafts (each still scanned + deduped by the gate) at the end of an interactive turn.
     * This runs ONLY on the REPL turn path, so channel/autonomous tracks (no turn hook) never
     * auto-promote — fail-closed. Default off → no-op. Any failure is swallowed (never breaks a turn).
     */
    private void maybeAutoPromoteSkills(Terminal terminal) {
        if (skillGate == null || configManager == null
                || !configManager.getConfig().getSkills().getAutonomous().isAutoPromote()) {
            return;
        }
        try {
            List<String> promoted = skillGate.autoPromotePending();
            for (String name : promoted) {
                Ansi.println(terminal, Ansi.success("[auto-promoted skill '" + name + "']"));
            }
        } catch (RuntimeException e) {
            log.warn("Auto-promote of staged skills failed: {}", e.toString());
        }
    }

    /**
     * Render a turn, resolving native HITL confirmations (ASK-mode {@link RequireUserConfirmEvent})
     * by prompting the user and re-issuing the turn with the collected {@link ConfirmResult}s until
     * it needs no more confirmation (or the round cap is hit). Each round streams a fresh, separately
     * interruptible {@link AgentKernel#chat} bound to {@code sessionId} (av2 Phase 4 per-session state).
     */
    void renderTurn(Msg first, String sessionId, Terminal terminal) {
        Msg toSend = first;
        for (int round = 0; round < MAX_CONFIRM_ROUNDS; round++) {
            RequireUserConfirmEvent pending =
                    renderStream(agentKernel.chat(agentKernel.activeId(), toSend, sessionId), terminal);
            if (pending == null) {
                return; // completed, errored, or interrupted
            }
            List<ConfirmResult> results = confirm(pending, terminal);
            toSend = resumeMessage(results);
        }
        Ansi.println(terminal, Ansi.warn("[too many confirmation rounds — stopping]"));
    }

    /**
     * Subscribe to a turn's typed {@link AgentEvent} stream and render it CC-style, blocking until it
     * terminates. Answer text streams from {@link TextBlockDeltaEvent} (Markdown → ANSI); tool calls
     * render as {@code ⏺ name / └ result} on {@link ToolResultEndEvent} (DENIED results are shown as a
     * denial); a {@code ⋯ thinking} spinner marks reasoning. If the turn suspends on a
     * {@link RequireUserConfirmEvent} (ASK permission), that event is returned so the caller can
     * confirm + resume; otherwise returns {@code null}. Mid-turn Ctrl-C requests
     * {@link AgentKernel#interruptCurrent()} and disposes the subscription (the process does not exit);
     * the previous INT handler is restored in {@code finally}. Package-private for the tests.
     */
    RequireUserConfirmEvent renderStream(Flux<AgentEvent> stream, Terminal terminal) {
        StreamingMarkdownPrinter printer = new StreamingMarkdownPrinter();
        AtomicBoolean spinnerOn = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<RequireUserConfirmEvent> confirm = new AtomicReference<>();
        Map<String, StringBuilder> toolResults = new LinkedHashMap<>();
        // av2 Phase 6a: forwarded subagent (child) text, accumulated per source path ("main/reviewer")
        // and flushed as a dim nested "└ [reviewer] …" line — distinct from the parent's answer.
        Map<String, StringBuilder> childText = new LinkedHashMap<>();
        CountDownLatch done = new CountDownLatch(1);

        Ansi.println(terminal, "");

        Disposable sub = stream.subscribe(
                event -> onEvent(event, terminal, printer, spinnerOn, toolResults, childText, confirm),
                err -> {
                    error.set(err);
                    done.countDown();
                },
                () -> {
                    clearSpinner(terminal, spinnerOn);
                    printer.flush(line -> Ansi.println(terminal, line));
                    flushAllChildText(terminal, childText);
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
            return null;
        }
        if (error.get() != null) {
            Ansi.println(terminal, Ansi.error("Error: " + error.get().getMessage()));
            return null;
        }
        Ansi.println(terminal, "");
        return confirm.get();
    }

    private void onEvent(AgentEvent event, Terminal terminal, StreamingMarkdownPrinter printer,
                         AtomicBoolean spinnerOn, Map<String, StringBuilder> toolResults,
                         Map<String, StringBuilder> childText,
                         AtomicReference<RequireUserConfirmEvent> confirm) {
        // av2 Phase 6a: a non-null source means this event was FORWARDED from a synchronous subagent
        // (child) — render it nested + dim, distinct from the parent (source == null). Background
        // (async) tasks are not forwarded; their completion arrives as a <system-reminder> the parent
        // reasons over, surfacing naturally as parent answer text on the next step.
        String source = event.getSource();
        if (source != null && !source.isBlank()) {
            onChildEvent(event, source, terminal, spinnerOn, childText);
            return;
        }
        if (event instanceof SubagentExposedEvent exposed) {
            // Phase 6b will bridge expose_to_user to a Channel (chat.sendToSubagent); for now, note it
            // (never a credential) so the operator sees a subagent was exposed. Graceful no-op otherwise.
            clearSpinner(terminal, spinnerOn);
            String who = exposed.getLabel() != null ? exposed.getLabel() : exposed.getAgentId();
            Ansi.println(terminal, Ansi.dim("[subagent exposed: " + who + "]"));
            return;
        }
        if (event instanceof ModelCallStartEvent || event instanceof ThinkingBlockStartEvent) {
            showSpinner(terminal, spinnerOn);
        } else if (event instanceof TextBlockDeltaEvent delta) {
            clearSpinner(terminal, spinnerOn);
            printer.accept(delta.getDelta(), line -> Ansi.println(terminal, line));
        } else if (event instanceof ToolCallStartEvent) {
            clearSpinner(terminal, spinnerOn);
        } else if (event instanceof ToolResultTextDeltaEvent d) {
            toolResults.computeIfAbsent(key(d.getToolCallId()), k -> new StringBuilder()).append(d.getDelta());
        } else if (event instanceof ToolResultEndEvent end) {
            clearSpinner(terminal, spinnerOn);
            Ansi.println(terminal, ToolCallFormatter.format(
                    toolLabel(end.getToolCallName()), toolSummary(end, toolResults)));
        } else if (event instanceof ExceedMaxItersEvent) {
            clearSpinner(terminal, spinnerOn);
            Ansi.println(terminal, Ansi.warn("[reached max reasoning iterations]"));
        } else if (event instanceof AllToolsDeniedEvent) {
            clearSpinner(terminal, spinnerOn);
            Ansi.println(terminal, Ansi.warn("[all tool calls denied by permission policy]"));
        } else if (event instanceof RequireUserConfirmEvent ask) {
            clearSpinner(terminal, spinnerOn);
            confirm.set(ask);
        } else if (event instanceof AgentEndEvent) {
            clearSpinner(terminal, spinnerOn);
        }
    }

    /**
     * Render a forwarded subagent (child) event (av2 Phase 6a). Child answer text is accumulated per
     * source and flushed as one dim {@code └ [child] …} line at the child's text/agent-end; a child's
     * tool call surfaces immediately as a nested dim line. Kept distinct from the parent's streamed
     * answer so it's clear which agent produced what.
     */
    private void onChildEvent(AgentEvent event, String source, Terminal terminal,
                              AtomicBoolean spinnerOn, Map<String, StringBuilder> childText) {
        if (event instanceof TextBlockDeltaEvent delta) {
            childText.computeIfAbsent(source, k -> new StringBuilder()).append(delta.getDelta());
        } else if (event instanceof TextBlockEndEvent || event instanceof AgentEndEvent) {
            flushChildText(terminal, source, childText);
        } else if (event instanceof ToolResultEndEvent end) {
            clearSpinner(terminal, spinnerOn);
            Ansi.println(terminal, SubagentEventRenderer.format(
                    source, "⏺ " + toolLabel(end.getToolCallName())));
        } else if (event instanceof AgentStartEvent) {
            clearSpinner(terminal, spinnerOn);
        }
    }

    /** Flush one source's accumulated child text as a single dim nested line, then clear it. */
    private static void flushChildText(Terminal terminal, String source,
                                       Map<String, StringBuilder> childText) {
        StringBuilder acc = childText.remove(source);
        if (acc != null && !acc.toString().isBlank()) {
            Ansi.println(terminal, SubagentEventRenderer.format(source, acc.toString()));
        }
    }

    /** Flush any remaining child text buffers at stream completion (children that never sent an end). */
    private static void flushAllChildText(Terminal terminal, Map<String, StringBuilder> childText) {
        for (Map.Entry<String, StringBuilder> e : childText.entrySet()) {
            if (e.getValue() != null && !e.getValue().toString().isBlank()) {
                Ansi.println(terminal, SubagentEventRenderer.format(e.getKey(), e.getValue().toString()));
            }
        }
        childText.clear();
    }

    private static String key(String toolCallId) {
        return toolCallId == null ? "" : toolCallId;
    }

    /** The rendered summary for a finished tool call: its output text, or a denial/error marker. */
    private static String toolSummary(ToolResultEndEvent end, Map<String, StringBuilder> toolResults) {
        StringBuilder acc = toolResults.get(key(end.getToolCallId()));
        String text = acc == null ? "" : acc.toString();
        if (end.getState() == ToolResultState.DENIED) {
            return text.isBlank() ? "denied by permission policy" : text;
        }
        if (end.getState() == ToolResultState.INTERRUPTED) {
            return "interrupted";
        }
        return text;
    }

    /**
     * Prompt the user to confirm each pending tool call (y = once, a = always, N = deny) and build the
     * native {@link ConfirmResult}s. "always" persists the tool to {@code permissions.allowlist.tools}
     * so future agent builds auto-allow it; there is no interactive reader → deny (fail-closed).
     */
    private List<ConfirmResult> confirm(RequireUserConfirmEvent ask, Terminal terminal) {
        List<ConfirmResult> results = new ArrayList<>();
        LineReader reader = readerRef.get();
        for (ToolUseBlock call : ask.getToolCalls()) {
            if (reader == null) {
                results.add(new ConfirmResult(false, call)); // no reader → fail-closed
                continue;
            }
            // Plan Mode's exit is human-gated: render the HITL exit prompt distinctly so the user
            // understands they are approving the written plan and leaving the read-only plan phase to
            // begin execution (reject → stay in plan mode). Other tools keep the generic prompt.
            String prompt = PLAN_EXIT_TOOL.equals(call.getName())
                    ? "Approve the plan and exit Plan Mode to begin execution? (y=approve / N=stay in plan) "
                    : "Allow tool '" + call.getName() + "'? (y=once / a=always / N=deny) ";
            String ans = reader.readLine(Ansi.warn(prompt));
            String s = ans == null ? "" : ans.strip().toLowerCase();
            if (s.equals("a")) {
                rememberTool(call.getName());
                results.add(new ConfirmResult(true, call));
            } else {
                results.add(new ConfirmResult(s.equals("y"), call));
            }
        }
        return results;
    }

    /** Persist a tool to the config allowlist so a future agent build auto-allows it. */
    private void rememberTool(String toolName) {
        configManager.updateConfig(c -> {
            List<String> tools = c.getPermissions().getAllowlist().getTools();
            if (!tools.contains(toolName)) {
                tools.add(toolName);
            }
        });
    }

    /** A resume message carrying the confirm results the native permission engine consumes. */
    private static Msg resumeMessage(List<ConfirmResult> results) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .textContent("permission decision")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
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

    /** Best-effort tool name; falls back to a generic label for blank/role-like names. */
    private static String toolLabel(String name) {
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
