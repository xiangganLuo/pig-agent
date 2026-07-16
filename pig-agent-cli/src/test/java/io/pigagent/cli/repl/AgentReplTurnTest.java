package io.pigagent.cli.repl;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
                sessions, null, new AtomicReference<>(), null);

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
                null, new AtomicReference<>(), null);

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
                null, new AtomicReference<>(), null);

        AgentEvent denied = new ToolResultEndEvent("r1", "tc1", "writeFile", ToolResultState.DENIED);

        repl.renderStream(Flux.just(denied), terminal);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("writeFile").contains("denied");
    }
}
