package io.pigagent.cli.repl;

import org.jline.console.SystemRegistry;
import org.jline.console.impl.SystemRegistryImpl;
import org.jline.reader.LineReader;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliCommands;
import picocli.shell.jline3.PicocliCommands.PicocliCommandsFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the REAL runtime dispatch path (JLine {@link SystemRegistryImpl} + picocli), which
 * {@code ReplCommandsTest} bypasses by calling {@code CommandLine.execute} directly. Guards
 * against "Invalid command: /help" — commands failing to register in the system registry.
 */
class CommandDispatchTest {

    @TempDir
    Path tmp;

    @Test
    void slashHelp_isDispatched_notInvalidCommand() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
        AtomicBoolean running = new AtomicBoolean(true);
        ReplContext ctx = new ReplContext(
                null, null, null, null, null, null, null, null, null, null, null,
                terminal, running, new AtomicReference<LineReader>());

        DefaultParser parser = AgentRepl.replParser();
        PicocliCommandsFactory factory = new PicocliCommandsFactory();
        CommandLine cmd = ReplCommands.build(ctx, factory);
        PicocliCommands picocliCommands = new PicocliCommands(cmd);

        SystemRegistry systemRegistry = new SystemRegistryImpl(parser, terminal, () -> tmp, null);
        systemRegistry.setCommandRegistries(picocliCommands);
        factory.setTerminal(terminal);

        // The command names picocli exposes to the registry — must include the slash names.
        assertThat(picocliCommands.commandNames()).contains("/help", "/agent", "/model");
        // Root cause guard: the parser must extract the slash-prefixed command name.
        assertThat(parser.getCommand("/help")).isEqualTo("/help");

        try {
            systemRegistry.execute("/help");
        } catch (Exception e) {
            throw new AssertionError("Dispatch of /help failed: " + e, e);
        }

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("Commands:");
    }
}
