package io.pigagent.cli.repl;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

import java.util.Collection;
import java.util.List;

/**
 * Type-ahead slash-command completion, fish / Claude-Code style: as soon as the buffer starts with
 * {@code /} the candidate list pops automatically (no Tab needed) and narrows on every keystroke;
 * {@code ↑/↓} move the highlight, Enter accepts, Esc/backspace back out. Normal chat input is left
 * completely alone — the extra completion only fires when {@link SlashCommands#isCommandBuffer} holds
 * and there is at least one match.
 *
 * <p>Implementation: JLine's own completion machinery (the {@code SystemRegistry} completer already
 * installed on the reader) does the listing; this class only re-triggers it automatically. It
 * overrides {@code self-insert} / {@code backward-delete-char} over the printable range to run the
 * real edit and then, for slash buffers, {@code expand-or-complete} (auto-list). {@code ↑/↓} are
 * rebound to {@code menu-complete} / {@code reverse-menu-complete} while a slash buffer is active and
 * otherwise fall through to history — so the three REPL states (idle Ctrl-C, mid-turn Ctrl-C,
 * Ctrl-D) and history recall are untouched (those keys are outside the printable range).
 *
 * <p>Requires a real TTY (arrow-key capabilities). On a dumb terminal (bare gitbash/mintty) the
 * caller skips installation and the REPL degrades to plain Tab completion; capability binds are also
 * guarded here so installation never throws when a capability is absent.
 */
public final class SlashCompletionWidgets {

    private static final String SELF_INSERT = "slash-self-insert";
    private static final String BACKWARD_DELETE = "slash-backward-delete";
    private static final String MENU_DOWN = "slash-menu-down";
    private static final String MENU_UP = "slash-menu-up";

    private final LineReader reader;
    private final List<String> commandNames;

    private SlashCompletionWidgets(LineReader reader, Collection<String> commandNames) {
        this.reader = reader;
        this.commandNames = commandNames == null ? List.of() : List.copyOf(commandNames);
    }

    /** Install the auto-listing slash completion + arrow navigation onto {@code reader}. */
    public static SlashCompletionWidgets install(LineReader reader, Collection<String> commandNames) {
        SlashCompletionWidgets widgets = new SlashCompletionWidgets(reader, commandNames);
        widgets.bind();
        return widgets;
    }

    private void bind() {
        reader.setOpt(LineReader.Option.AUTO_LIST);
        reader.setOpt(LineReader.Option.AUTO_MENU);
        reader.setOpt(LineReader.Option.LIST_PACKED);

        reader.getWidgets().put(SELF_INSERT, (Widget) this::selfInsert);
        reader.getWidgets().put(BACKWARD_DELETE, (Widget) this::backwardDelete);
        reader.getWidgets().put(MENU_DOWN, (Widget) this::menuDown);
        reader.getWidgets().put(MENU_UP, (Widget) this::menuUp);

        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        // Every printable ASCII char (space..~) routes through self-insert-then-maybe-list.
        main.bind(new Reference(SELF_INSERT), KeyMap.range(" -~"));
        Terminal terminal = reader.getTerminal();
        bindKey(main, BACKWARD_DELETE, KeyMap.del());
        bindKey(main, BACKWARD_DELETE, KeyMap.key(terminal, Capability.key_backspace));
        bindKey(main, MENU_DOWN, KeyMap.key(terminal, Capability.key_down));
        bindKey(main, MENU_UP, KeyMap.key(terminal, Capability.key_up));
    }

    private static void bindKey(KeyMap<Binding> map, String widget, String seq) {
        if (seq != null && !seq.isEmpty()) {
            map.bind(new Reference(widget), seq);
        }
    }

    boolean selfInsert() {
        reader.callWidget(LineReader.SELF_INSERT);
        maybeList();
        return true;
    }

    boolean backwardDelete() {
        // Delete only — do NOT re-run expand-or-complete here. Re-completing after a delete would
        // re-insert the just-removed char whenever the shrunken buffer still uniquely matches a
        // command (e.g. "/model" → ⌫ → "/mode" → auto-completes back to "/model"), making the
        // command impossible to delete. The candidate menu still narrows on the next typed char.
        reader.callWidget(LineReader.BACKWARD_DELETE_CHAR);
        return true;
    }

    boolean menuDown() {
        reader.callWidget(slashActive() ? LineReader.MENU_COMPLETE : LineReader.DOWN_LINE_OR_HISTORY);
        return true;
    }

    boolean menuUp() {
        reader.callWidget(slashActive() ? LineReader.REVERSE_MENU_COMPLETE : LineReader.UP_LINE_OR_HISTORY);
        return true;
    }

    private void maybeList() {
        if (slashActive()) {
            reader.callWidget(LineReader.EXPAND_OR_COMPLETE);
        }
    }

    /** A slash command is being typed and at least one command still matches its prefix. */
    private boolean slashActive() {
        String buffer = reader.getBuffer().toString();
        return SlashCommands.isCommandBuffer(buffer)
                && !SlashCommands.matching(buffer, commandNames).isEmpty();
    }
}
