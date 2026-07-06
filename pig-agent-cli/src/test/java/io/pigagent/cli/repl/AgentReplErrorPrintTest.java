package io.pigagent.cli.repl;

import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard: when the agent stream errors (e.g. an upstream 502), the REPL must print
 * the error exactly ONCE. Previously both {@code doOnError} and the surrounding {@code catch}
 * printed it, yielding duplicated "Error:" lines.
 */
class AgentReplErrorPrintTest {

    @Test
    void streamError_printsErrorExactlyOnce() throws IOException {
        // Arrange — a PigAgent whose stream fails, wired through a real AgentHolder.
        PigAgent agent = mock(PigAgent.class);
        when(agent.stream(any(Msg.class)))
                .thenReturn(Flux.<Event>error(new RuntimeException("502: upstream_error")));
        AgentHolder holder = new AgentHolder(agent);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();

        AgentRepl repl = new AgentRepl(holder, null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>());

        // Act
        repl.streamToAgent("hello", terminal);

        // Assert — exactly one "Error:" line, carrying the upstream message.
        String printed = out.toString(StandardCharsets.UTF_8);
        int occurrences = printed.split("Error:", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
        assertThat(printed).contains("502: upstream_error");
    }
}
