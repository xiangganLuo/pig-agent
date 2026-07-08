package io.pigagent.cli.repl.select;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

import java.util.List;
import java.util.OptionalInt;

import static org.fusesource.jansi.Ansi.Color;
import static org.fusesource.jansi.Ansi.ansi;

/**
 * A line-based (never full-screen) inline list selector: renders the options in place, moves a
 * highlight with the arrow keys (or j/k), selects with Enter, cancels with Esc/q. Navigation state
 * lives in the pure {@link SelectorModel}; this class is the thin terminal adapter (raw mode + key
 * binding + redraw), so it is exercised interactively rather than unit-tested.
 *
 * <p>On a non-interactive terminal (dumb / no real TTY) {@link #isInteractive} is false and callers
 * fall back to numeric input — arrow keys are meaningless there.
 */
public final class InlineSelector {

    private enum Action { UP, DOWN, SELECT, CANCEL }

    private static final String ESC = "";

    private InlineSelector() {
    }

    /** Whether arrow-key selection is usable on this terminal. */
    public static boolean isInteractive(Terminal terminal) {
        return terminal != null && !Terminal.TYPE_DUMB.equals(terminal.getType())
                && !Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());
    }

    /**
     * Run the selector. Returns the chosen index, or empty if cancelled (Esc/q) or unusable.
     */
    public static OptionalInt select(Terminal terminal, String title, List<String> options) {
        if (options == null || options.isEmpty() || !isInteractive(terminal)) {
            return OptionalInt.empty();
        }
        SelectorModel model = new SelectorModel(options.size());
        BindingReader bindingReader = new BindingReader(terminal.reader());
        KeyMap<Action> keys = new KeyMap<>();
        keys.bind(Action.UP, KeyMap.key(terminal, Capability.key_up), "k");
        keys.bind(Action.DOWN, KeyMap.key(terminal, Capability.key_down), "j");
        keys.bind(Action.SELECT, "\r", "\n");
        keys.bind(Action.CANCEL, KeyMap.esc(), "q");

        if (title != null && !title.isBlank()) {
            terminal.writer().println(ansi().fgBright(Color.BLACK).a(title).reset().toString());
        }
        Attributes saved = terminal.enterRawMode();
        try {
            render(terminal, options, model.cursor(), false);
            while (true) {
                Action action = bindingReader.readBinding(keys);
                if (action == null) {
                    continue;
                }
                switch (action) {
                    case UP -> {
                        model.up();
                        render(terminal, options, model.cursor(), true);
                    }
                    case DOWN -> {
                        model.down();
                        render(terminal, options, model.cursor(), true);
                    }
                    case SELECT -> {
                        return OptionalInt.of(model.cursor());
                    }
                    case CANCEL -> {
                        return OptionalInt.empty();
                    }
                    default -> { /* ignore */ }
                }
            }
        } finally {
            terminal.setAttributes(saved);
            terminal.writer().println();
            terminal.flush();
        }
    }

    /** Draw the option list; when {@code redraw}, first move the cursor back up over it. */
    private static void render(Terminal terminal, List<String> options, int cursor, boolean redraw) {
        StringBuilder sb = new StringBuilder();
        if (redraw) {
            sb.append(ESC).append('[').append(options.size()).append('A'); // cursor up N lines
        }
        for (int i = 0; i < options.size(); i++) {
            sb.append('\r').append(ESC).append("[2K"); // carriage return + clear entire line
            if (i == cursor) {
                sb.append(ansi().fg(Color.CYAN).a("❯ ").bold().a(options.get(i)).reset().toString());
            } else {
                sb.append("  ").append(options.get(i));
            }
            sb.append('\n');
        }
        terminal.writer().print(sb);
        terminal.flush();
    }
}
