package io.pigagent.cli.repl;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring guard for the type-ahead slash completion. The interactive feel (list pops as you type,
 * ↑/↓ navigate) needs a real TTY, so this asserts the install contract instead: the auto-list/menu
 * options are on, the four widgets are registered, and printable keys route through
 * {@code slash-self-insert}. Capability-dependent binds (arrows) are skipped on this dumb terminal —
 * exactly the bare-gitbash degrade path — and installation must not throw.
 */
class SlashCompletionWidgetsTest {

    private LineReader newReader() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
        return LineReaderBuilder.builder().terminal(terminal).build();
    }

    @Test
    void install_enablesAutoListAndRegistersWidgets() throws IOException {
        LineReader reader = newReader();

        SlashCompletionWidgets.install(reader, List.of("/model", "/help"));

        assertThat(reader.isSet(LineReader.Option.AUTO_LIST)).isTrue();
        assertThat(reader.isSet(LineReader.Option.AUTO_MENU)).isTrue();
        assertThat(reader.getWidgets())
                .containsKeys("slash-self-insert", "slash-backward-delete",
                        "slash-menu-down", "slash-menu-up");
    }

    @Test
    void install_bindsPrintableKeysToSlashSelfInsert() throws IOException {
        LineReader reader = newReader();

        SlashCompletionWidgets.install(reader, List.of("/model", "/help"));

        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        assertThat(bound(main, "/")).isEqualTo("slash-self-insert");
        assertThat(bound(main, "m")).isEqualTo("slash-self-insert");
    }

    @Test
    void install_doesNotThrowWithNullCommands() throws IOException {
        LineReader reader = newReader();

        SlashCompletionWidgets.install(reader, null); // must degrade, not blow up
    }

    private static String bound(KeyMap<Binding> map, String key) {
        Binding binding = map.getBound(key);
        return binding instanceof Reference ref ? ref.name() : null;
    }
}
