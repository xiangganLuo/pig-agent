package io.pigagent.cli.repl;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.session.SessionManager;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A chat turn must keep the {@code noteUserMessage → maybeCompress → chat → saveCurrent} order
 * (streaming through the kernel so the turn is interruptible + session-bound) and map the typed
 * {@link AgentEvent} stream to rendered output. Driven directly via {@code runTurn} so no JLine read
 * loop is needed.
 */
class AgentReplTurnTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private static Terminal dumbTerminal(ByteArrayOutputStream out) throws IOException {
        return TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
    }

    @Test
    void runTurn_ordersHooksAndStreamsThroughKernel() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        SessionManager sessions = mock(SessionManager.class);
        CompressionService compression = mock(CompressionService.class);
        when(kernel.activeId()).thenReturn("default");
        when(sessions.getCurrentSessionId()).thenReturn("s1");
        AgentEvent answer = new TextBlockDeltaEvent("r1", "b1", "hello world\n");
        when(kernel.chat(eq("default"), any(Msg.class), eq("s1"))).thenReturn(Flux.just(answer));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, kernel, null, null, null, null, compression, null, null,
                sessions, null, new AtomicReference<>(), null, null, null);

        repl.runTurn("hi", terminal);

        var order = inOrder(sessions, compression, kernel);
        order.verify(sessions).noteUserMessage("hi");
        order.verify(compression).maybeCompress("s1");
        order.verify(kernel).chat(eq("default"), any(Msg.class), eq("s1"));
        order.verify(sessions).saveCurrent();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("hello world");
    }

    @Test
    void renderStream_mapsToolResultToBlock() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        AgentEvent delta = new ToolResultTextDeltaEvent("r1", "tc1", "executeCommand", "exit=0");
        AgentEvent end = new ToolResultEndEvent("r1", "tc1", "executeCommand", ToolResultState.SUCCESS);

        repl.renderStream(Flux.just(delta, end), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("⏺").contains("executeCommand").contains("└").contains("exit=0");
    }

    @Test
    void renderStream_showsDeniedToolResult() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        AgentEvent denied = new ToolResultEndEvent("r1", "tc1", "writeFile", ToolResultState.DENIED);

        repl.renderStream(Flux.just(denied), terminal);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("writeFile").contains("denied");
    }

    @Test
    void renderStream_flushesBufferedAnswerBeforeToolBlock() throws IOException {
        // A partial answer line (no trailing newline) buffered by the printer must be flushed BEFORE a
        // mid-stream tool block renders — else it would print after the tool line and merge/garble (#3).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        AgentEvent partial = new TextBlockDeltaEvent("r1", "b1", "working on it");
        AgentEvent toolDelta = new ToolResultTextDeltaEvent("r1", "tc1", "executeCommand", "exit=0");
        AgentEvent toolEnd = new ToolResultEndEvent("r1", "tc1", "executeCommand", ToolResultState.SUCCESS);

        repl.renderStream(Flux.just(partial, toolDelta, toolEnd), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("working on it").contains("executeCommand");
        assertThat(printed.indexOf("working on it"))
                .as("buffered answer flushed before the tool block")
                .isLessThan(printed.indexOf("executeCommand"));
    }

    @Test
    void renderStream_printsToolHeadOnStartEvent() throws IOException {
        // The ⏺ head must appear as soon as the tool call starts (so a long command isn't silent) (#4).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        AgentEvent start = new ToolCallStartEvent("r1", "tc1", "executeCommand");

        repl.renderStream(Flux.just(start), terminal);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("⏺").contains("executeCommand");
    }

    @Test
    void renderStream_headPrintedOnceAcrossStartAndEnd() throws IOException {
        // Head on start + body on end → the head must not be printed twice for one call id (#4).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        AgentEvent start = new ToolCallStartEvent("r1", "tc1", "readFile");
        AgentEvent delta = new ToolResultTextDeltaEvent("r1", "tc1", "readFile", "42 lines");
        AgentEvent end = new ToolResultEndEvent("r1", "tc1", "readFile", ToolResultState.SUCCESS);

        repl.renderStream(Flux.just(start, delta, end), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        int heads = printed.split("readFile", -1).length - 1;
        assertThat(heads).as("readFile head printed once, body carries no name").isEqualTo(1);
        assertThat(printed).contains("└").contains("42 lines");
    }

    @Test
    void renderStream_rendersErrorToolResultDistinctly() throws IOException {
        // A failed (ERROR) tool result renders with the ✗ error marker, distinct from a success (#5).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        AgentEvent delta = new ToolResultTextDeltaEvent("r1", "tc1", "webSearch", "network down");
        AgentEvent end = new ToolResultEndEvent("r1", "tc1", "webSearch", ToolResultState.ERROR);

        repl.renderStream(Flux.just(delta, end), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("webSearch").contains("network down").contains("✗");
    }

    @Test
    void renderStream_emitsNoOutputMarkerWhenTurnRendersNothing() throws IOException {
        // A turn that produces no answer/tool/child output shows a dim [无输出] marker (LOW).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        repl.renderStream(Flux.just(new AgentEndEvent("r1", "assistant", "")), terminal);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("[无输出]");
    }

    @Test
    void renderStream_rendersSourceTaggedChildEventNested() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        // Parent answer (source == null) vs a forwarded subagent event (source = "main/reviewer").
        AgentEvent parent = new TextBlockDeltaEvent("r1", "b1", "parent says hi\n");
        AgentEvent childText = new TextBlockDeltaEvent("r2", "b2", "child found an issue")
                .withSource("main/reviewer");
        AgentEvent childEnd = new AgentEndEvent("r2", "reviewer", "done").withSource("main/reviewer");

        repl.renderStream(Flux.just(parent, childText, childEnd), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).as("parent answer still rendered").contains("parent says hi");
        assertThat(printed).as("child event rendered nested + source-labeled, distinct from parent")
                .contains("└").contains("[reviewer]").contains("child found an issue");
    }
}
