package io.pigagent.cli.repl;

import io.agentscope.core.event.AgentEvent;
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

/**
 * Regression guard: when the turn stream errors (e.g. an upstream 502), the REPL must print
 * the error exactly ONCE. Previously both {@code doOnError} and a surrounding {@code catch}
 * printed it, yielding duplicated "Error:" lines.
 */
class AgentReplErrorPrintTest {

    @Test
    void streamError_printsErrorExactlyOnce() throws IOException {
        // Arrange — a turn stream that fails, and a REPL to render it.
        Flux<AgentEvent> stream = Flux.error(new RuntimeException("502: upstream_error"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();

        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);

        // Act
        repl.renderStream(stream, terminal);

        // Assert — exactly one "Error:" line, carrying the upstream message.
        String printed = out.toString(StandardCharsets.UTF_8);
        int occurrences = printed.split("Error:", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
        assertThat(printed).contains("502: upstream_error");
    }
}
