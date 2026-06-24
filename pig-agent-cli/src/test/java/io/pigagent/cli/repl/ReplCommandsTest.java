package io.pigagent.cli.repl;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import org.jline.reader.LineReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the picocli command tree dispatches slash-prefixed command names and
 * that commands write through the {@link ReplContext} terminal. Only commands that
 * depend solely on the terminal + running flag are exercised here (no agent / config
 * collaborators), so a dumb terminal and null collaborators suffice.
 */
class ReplCommandsTest {

    private record Harness(CommandLine cmd, ByteArrayOutputStream out, AtomicBoolean running) {
        String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private Harness newHarness() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
        AtomicBoolean running = new AtomicBoolean(true);
        ReplContext ctx = new ReplContext(null, null, null, null, null, terminal, running,
                new AtomicReference<LineReader>());
        CommandLine cmd = ReplCommands.build(ctx, CommandLine.defaultFactory());
        return new Harness(cmd, out, running);
    }

    @Test
    void quitStopsTheLoop() throws IOException {
        Harness h = newHarness();
        int code = h.cmd().execute("/quit");
        assertThat(code).isZero();
        assertThat(h.running().get()).isFalse();
        assertThat(h.output()).contains("Goodbye");
    }

    @Test
    void helpListsSlashCommands() throws IOException {
        Harness h = newHarness();
        h.cmd().execute("/help");
        assertThat(h.output())
                .contains("/tasks")
                .contains("/switch")
                .contains("/providers")
                .contains("/quit");
    }

    @Test
    void clearRunsWithoutStoppingTheLoop() throws IOException {
        Harness h = newHarness();
        int code = h.cmd().execute("/clear");
        assertThat(code).isZero();
        assertThat(h.running().get()).isTrue();
    }

    @Test
    void unknownSlashCommandIsRejected() throws IOException {
        Harness h = newHarness();
        int code = h.cmd().execute("/bogus");
        // picocli returns a non-zero usage error for an unmatched subcommand.
        assertThat(code).isNotZero();
        assertThat(h.running().get()).isTrue();
    }
}
