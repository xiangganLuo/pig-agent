package io.pigagent.cli.repl;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
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
 * (streaming through the kernel so the turn is interruptible) and map stream events to rendered
 * output. Driven directly via {@code runTurn} so no JLine read loop is needed.
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

    private static Event event(EventType type, String text) {
        Event e = mock(Event.class);
        Msg msg = mock(Msg.class);
        when(e.getType()).thenReturn(type);
        when(e.getMessage()).thenReturn(msg);
        when(msg.getTextContent()).thenReturn(text);
        return e;
    }

    @Test
    void runTurn_ordersHooksAndStreamsThroughKernel() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        SessionManager sessions = mock(SessionManager.class);
        CompressionService compression = mock(CompressionService.class);
        when(kernel.activeId()).thenReturn("default");
        when(sessions.getCurrentSessionId()).thenReturn("s1");
        Event answer = event(EventType.AGENT_RESULT, "hello world\n");
        when(kernel.chat(eq("default"), any(Msg.class))).thenReturn(Flux.just(answer));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, kernel, null, null, null, null, compression, null, null,
                sessions, null, new AtomicReference<>(), null);

        repl.runTurn("hi", terminal);

        var order = inOrder(sessions, compression, kernel);
        order.verify(sessions).noteUserMessage("hi");
        order.verify(compression).maybeCompress("s1");
        order.verify(kernel).chat(eq("default"), any(Msg.class));
        order.verify(sessions).saveCurrent();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("hello world");
    }

    @Test
    void renderStream_mapsToolResultToBlock() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null);

        Event tool = event(EventType.TOOL_RESULT, "exit=0");
        when(tool.getMessage().getName()).thenReturn("executeCommand");

        repl.renderStream(Flux.just(tool), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("⏺").contains("executeCommand").contains("└").contains("exit=0");
    }
}
