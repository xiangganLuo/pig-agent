package io.pigagent.cli.repl;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Pure filter logic behind the type-ahead slash-command completion menu.
 *
 * <p>The interactive menu (auto-listing as you type, arrow navigation) is driven by JLine widgets on
 * a real terminal (see {@link SlashCompletionWidgets}); those need a TTY and are exercised by hand.
 * The decisions that gate and shape the menu are isolated here so they can be unit-tested:
 * <ul>
 *   <li>{@link #isCommandBuffer} — the menu pops <em>only</em> when the buffer is a {@code /}-command,
 *       never during normal chat input;</li>
 *   <li>{@link #matching} — which command names survive the typed prefix (empty ⇒ don't pop an empty
 *       list).</li>
 * </ul>
 */
public final class SlashCommands {

    private SlashCommands() {
    }

    /** True when {@code buffer} (ignoring leading whitespace) is a slash command, not chat text. */
    public static boolean isCommandBuffer(String buffer) {
        return buffer != null && buffer.stripLeading().startsWith("/");
    }

    /**
     * Command names whose name starts with the buffer's leading token (case-insensitive). Returns an
     * empty list when the buffer is not a slash command, or when nothing matches — the caller uses
     * emptiness to avoid popping a useless menu.
     */
    public static List<String> matching(String buffer, Collection<String> commands) {
        if (!isCommandBuffer(buffer) || commands == null) {
            return List.of();
        }
        String token = buffer.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return commands.stream()
                .filter(c -> c.toLowerCase(Locale.ROOT).startsWith(token))
                .collect(Collectors.toList());
    }
}
