package io.pigagent.cli.repl;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.pigagent.core.agent.kernel.AgentKernel;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mid-turn Ctrl-C must call {@link AgentKernel#interruptCurrent()}, dispose the subscription and
 * return control to the prompt (the render call returns; the process does not exit).
 */
class AgentReplInterruptTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    @Test
    void ctrlCMidTurn_interruptsAndReturns() throws Exception {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.interruptCurrent()).thenReturn(true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
        AgentRepl repl = new AgentRepl(null, kernel, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null);

        // A turn that starts (reasoning) then hangs — simulates an in-flight model call.
        Event reasoning = mock(Event.class);
        Msg msg = mock(Msg.class);
        when(reasoning.getType()).thenReturn(EventType.REASONING);
        when(reasoning.getMessage()).thenReturn(msg);
        Flux<Event> stream = Flux.concat(Flux.just(reasoning), Flux.never());

        Thread turn = new Thread(() -> repl.renderStream(stream, terminal), "turn");
        turn.start();

        // Raise SIGINT until the hung turn unwinds (the handler is installed just after subscribe).
        long deadline = System.currentTimeMillis() + 3000;
        while (turn.isAlive() && System.currentTimeMillis() < deadline) {
            terminal.raise(Terminal.Signal.INT);
            Thread.sleep(50);
        }
        turn.join(2000);

        assertThat(turn.isAlive()).as("turn returned to prompt").isFalse();
        verify(kernel, atLeastOnce()).interruptCurrent();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("[interrupted]");
    }
}
