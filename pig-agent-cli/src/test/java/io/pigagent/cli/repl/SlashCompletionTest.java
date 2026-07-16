package io.pigagent.cli.repl;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliCommands;
import picocli.shell.jline3.PicocliCommands.PicocliCommandsFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slash-command completion drift guard. The JLine completer is built from
 * {@code SystemRegistry.completer()}, which draws its candidates from exactly the picocli
 * subcommands registered in {@link ReplCommands#build}. Asserting that the registered command
 * names cover the full documented set catches the "added a command but forgot to hang it into
 * completion" (or renamed/removed) regression.
 */
class SlashCompletionTest {

    /** Every slash command the REPL must expose for completion. */
    private static final List<String> ALL_COMMANDS = List.of(
            "/help", "/tasks", "/skills", "/config", "/protocols", "/model", "/agent",
            "/channels", "/session", "/mcp", "/permission", "/memory", "/compress",
            "/status", "/clear", "/quit");

    @Test
    void completionCoversEveryCommand() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
        ReplContext ctx = new ReplContext(
                null, null, null, null, null, null, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null);

        PicocliCommandsFactory factory = new PicocliCommandsFactory();
        factory.setTerminal(terminal);
        CommandLine cmd = ReplCommands.build(ctx, factory);
        PicocliCommands picocliCommands = new PicocliCommands(cmd);

        // commandNames() == the completion candidate set (system registry completer source).
        assertThat(picocliCommands.commandNames()).containsAll(ALL_COMMANDS);
        // Every exposed command is slash-prefixed so it matches what the user types after "/".
        assertThat(picocliCommands.commandNames()).allMatch(n -> n.startsWith("/"));
    }
}
