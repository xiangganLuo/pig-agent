package io.pigagent.cli.repl;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PermissionMode;
import io.pigagent.mcp.McpManager;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import org.jline.reader.LineReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the picocli command tree dispatches slash-prefixed command names and
 * that commands write through the {@link ReplContext} terminal. Only commands that
 * depend solely on the terminal + running flag are exercised here (no agent / config
 * collaborators), so a dumb terminal and null collaborators suffice.
 */
class ReplCommandsTest {

    @TempDir
    Path tmp;

    private record Harness(CommandLine cmd, ByteArrayOutputStream out, AtomicBoolean running) {
        String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private Harness newHarness() throws IOException {
        return newHarness(null, null);
    }

    private Harness newHarness(McpManager mcpManager) throws IOException {
        return newHarness(mcpManager, null);
    }

    private Harness newHarness(McpManager mcpManager, ConfigurationManager configManager) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
        AtomicBoolean running = new AtomicBoolean(true);
        ReplContext ctx = new ReplContext(
                null, // agentHolder
                configManager,
                null, // registry
                null, // modelManager
                null, // compressionService
                mcpManager,
                null, // bridges
                null, // sessionManager
                terminal,
                running,
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
                .contains("/model")
                .contains("/session")
                .contains("/mcp")
                .contains("/permission")
                .contains("/compress")
                .contains("/quit");
    }

    @Test
    void permissionModeDispatchesAndPersists() throws IOException {
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        Harness h = newHarness(null, cfg);

        int code = h.cmd().execute("/permission", "mode", "plan");
        assertThat(code).isZero();
        assertThat(h.output()).contains("plan");
        assertThat(cfg.getConfig().getPermissions().resolveMode()).isEqualTo(PermissionMode.PLAN);

        h.cmd().execute("/permission", "status");
        assertThat(h.output()).contains("Mode").contains("plan");
    }

    @Test
    void permissionRejectsInvalidMode() throws IOException {
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        Harness h = newHarness(null, cfg);
        h.cmd().execute("/permission", "mode", "garbage");
        assertThat(h.output()).contains("Invalid mode");
    }

    @Test
    void mcpListDispatchesToManagerAndRendersEmpty() throws IOException {
        McpManager mcp = mock(McpManager.class);
        when(mcp.list()).thenReturn(List.of());
        Harness h = newHarness(mcp);
        int code = h.cmd().execute("/mcp", "list");
        assertThat(code).isZero();
        assertThat(h.output()).contains("MCP servers").contains("none");
    }

    @Test
    void mcpDefaultsToListWhenNoAction() throws IOException {
        McpManager mcp = mock(McpManager.class);
        when(mcp.list()).thenReturn(List.of());
        Harness h = newHarness(mcp);
        int code = h.cmd().execute("/mcp");
        assertThat(code).isZero();
        assertThat(h.output()).contains("MCP servers");
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
