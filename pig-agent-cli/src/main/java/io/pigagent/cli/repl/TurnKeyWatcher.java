package io.pigagent.cli.repl;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Watches terminal key input <em>during a streaming chat turn</em> so that {@code ESC} or {@code
 * Ctrl-C} interrupt the turn. While a turn runs, JLine's {@code LineReader} is not reading, so key
 * presses would otherwise be invisible until the turn ends. This watcher puts the terminal into
 * character-input mode (disabling only {@code ICANON}/{@code ISIG}/{@code ECHO} — output flags are
 * left intact so streamed output is not affected) and reads bytes on a daemon thread: {@code ESC}
 * (27) and {@code Ctrl-C} (3) both fire the same interrupt callback; every other key typed mid-turn
 * is discarded.
 *
 * <p>Because {@code ISIG} is disabled, {@code Ctrl-C} arrives as the byte {@code 0x03} rather than a
 * {@code SIGINT} and no JLine {@code readLine} is active — so {@code UserInterruptException} can be
 * neither thrown nor printed while a turn runs. On terminals where char-input mode can't be entered
 * (dumb / non-TTY), {@link #start} returns {@code null} and the caller falls back to the {@code
 * Signal.INT} handler.
 *
 * <p>{@link #close()} stops the reader thread and restores the saved attributes; it MUST run in a
 * {@code finally} so the terminal never stays in char-input mode past the turn.
 */
final class TurnKeyWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnKeyWatcher.class);

    /** ESC key code. */
    static final int ESC = 27;
    /** Ctrl-C (ETX) byte, delivered as input once {@code ISIG} is disabled. */
    static final int CTRL_C = 3;
    /** Poll slice so the loop notices {@link #running} going false promptly after {@link #close()}. */
    private static final int POLL_TIMEOUT_MS = 100;

    private final Terminal terminal;
    private final Runnable onInterrupt;
    private final Attributes saved;
    private final Thread thread;
    private volatile boolean running = true;

    private TurnKeyWatcher(Terminal terminal, Runnable onInterrupt, Attributes saved) {
        this.terminal = terminal;
        this.onInterrupt = onInterrupt;
        this.saved = saved;
        this.thread = new Thread(this::loop, "pig-repl-turn-key");
        this.thread.setDaemon(true);
    }

    /** Whether a read key code should interrupt the current turn (ESC or Ctrl-C). Pure, for tests. */
    static boolean isInterruptKey(int c) {
        return c == ESC || c == CTRL_C;
    }

    /**
     * Enter char-input mode and start the daemon watcher. Returns {@code null} (caller keeps the
     * {@code Signal.INT} fallback) if attributes can't be read/set on this terminal.
     */
    static TurnKeyWatcher start(Terminal terminal, Runnable onInterrupt) {
        try {
            Attributes saved = terminal.getAttributes();
            Attributes charMode = new Attributes(saved);
            charMode.setLocalFlag(Attributes.LocalFlag.ICANON, false); // per-char, not per-line
            charMode.setLocalFlag(Attributes.LocalFlag.ECHO, false);   // don't echo mid-turn keys
            charMode.setLocalFlag(Attributes.LocalFlag.ISIG, false);   // Ctrl-C as byte 0x03, no SIGINT
            charMode.setControlChar(Attributes.ControlChar.VMIN, 0);
            charMode.setControlChar(Attributes.ControlChar.VTIME, 1);
            terminal.setAttributes(charMode);
            TurnKeyWatcher watcher = new TurnKeyWatcher(terminal, onInterrupt, saved);
            watcher.thread.start();
            return watcher;
        } catch (RuntimeException e) {
            log.debug("Turn key watcher unavailable; using SIGINT fallback: {}", e.toString());
            return null;
        }
    }

    private void loop() {
        NonBlockingReader reader = terminal.reader();
        while (running) {
            int c;
            try {
                c = reader.read(POLL_TIMEOUT_MS);
            } catch (IOException e) {
                return;
            }
            if (!running || c == NonBlockingReader.EOF) {
                return;
            }
            if (c == NonBlockingReader.READ_EXPIRED) {
                continue; // idle slice, re-check running
            }
            if (isInterruptKey(c)) {
                running = false;
                try {
                    onInterrupt.run();
                } catch (RuntimeException e) {
                    log.debug("Turn interrupt callback failed: {}", e.toString());
                }
                return;
            }
            // any other mid-turn keystroke is intentionally discarded
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            terminal.setAttributes(saved); // restore cooked mode for the next prompt
        } catch (RuntimeException e) {
            log.debug("Restoring terminal attributes failed: {}", e.toString());
        }
    }
}
