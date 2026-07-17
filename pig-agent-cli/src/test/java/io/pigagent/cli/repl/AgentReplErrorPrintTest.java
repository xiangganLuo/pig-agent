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
 * Regression guard: when the turn stream errors (e.g. an upstream 502), the REPL must print the error
 * exactly ONCE (previously both {@code doOnError} and a surrounding {@code catch} printed it, yielding
 * duplicated lines). The error is now rendered as a friendly, localized, credential-redacted one-liner
 * (fix #2 / F1b) rather than a raw {@code Error: <message>} dump — so the once-only invariant is
 * asserted against that friendly text, and the raw upstream detail must NOT be shown.
 */
class AgentReplErrorPrintTest {

    /** Stable substring of the 5xx/overloaded friendly message (see ModelErrorMessages). */
    private static final String FRIENDLY_5XX = "模型服务暂时不可用";

    @Test
    void streamError_printsFriendlyErrorExactlyOnce() throws IOException {
        // Arrange — a turn stream that fails with an upstream 502, and a REPL to render it.
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

        // Assert — exactly one friendly error line; raw upstream detail is not surfaced.
        String printed = out.toString(StandardCharsets.UTF_8);
        int occurrences = printed.split(FRIENDLY_5XX, -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
        assertThat(printed).doesNotContain("upstream_error");
    }
}
