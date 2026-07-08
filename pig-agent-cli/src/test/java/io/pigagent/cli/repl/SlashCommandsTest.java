package io.pigagent.cli.repl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure slash-command filter that drives auto-completion. The interactive menu
 * (arrow navigation) needs a real TTY and is exercised by hand; this filter is the load-bearing,
 * unit-testable core: it decides when the completion list pops (only for {@code /}-prefixed buffers)
 * and which command names survive the typed prefix.
 */
class SlashCommandsTest {

    private static final List<String> COMMANDS = List.of(
            "/help", "/tasks", "/skills", "/config", "/protocols", "/model", "/agent",
            "/channels", "/session", "/mcp", "/permission", "/memory", "/compress",
            "/status", "/clear", "/quit");

    @Test
    void isCommandBuffer_trueOnlyForSlashPrefix() {
        // Arrange / Act / Assert
        assertThat(SlashCommands.isCommandBuffer("/")).isTrue();
        assertThat(SlashCommands.isCommandBuffer("/model")).isTrue();
        assertThat(SlashCommands.isCommandBuffer("   /model")).isTrue(); // leading space tolerated
        assertThat(SlashCommands.isCommandBuffer("hello")).isFalse();
        assertThat(SlashCommands.isCommandBuffer("")).isFalse();
        assertThat(SlashCommands.isCommandBuffer(null)).isFalse();
    }

    @Test
    void matching_emptyBufferSlashReturnsAll() {
        List<String> result = SlashCommands.matching("/", COMMANDS);

        assertThat(result).containsExactlyInAnyOrderElementsOf(COMMANDS);
    }

    @Test
    void matching_narrowsByPrefix() {
        assertThat(SlashCommands.matching("/mo", COMMANDS)).containsExactly("/model");
        assertThat(SlashCommands.matching("/m", COMMANDS))
                .containsExactlyInAnyOrder("/mcp", "/memory", "/model");
    }

    @Test
    void matching_caseInsensitive() {
        assertThat(SlashCommands.matching("/MO", COMMANDS)).containsExactly("/model");
    }

    @Test
    void matching_usesLeadingTokenOnly() {
        // Once a subcommand/arg is typed, the command token still resolves to the command name.
        assertThat(SlashCommands.matching("/model add", COMMANDS)).containsExactly("/model");
    }

    @Test
    void matching_nonSlashOrUnknownReturnsEmpty() {
        assertThat(SlashCommands.matching("hello", COMMANDS)).isEmpty();
        assertThat(SlashCommands.matching("/zzz", COMMANDS)).isEmpty();
        assertThat(SlashCommands.matching(null, COMMANDS)).isEmpty();
        assertThat(SlashCommands.matching("/", null)).isEmpty();
    }
}
