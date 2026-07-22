package io.pigagent.cli.repl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure key-classification used by the mid-turn ESC/Ctrl-C watcher. The terminal-mode + threaded
 * reading is exercised interactively (needs a real TTY); this locks the decision that ESC (27) and
 * Ctrl-C (3) interrupt, while ordinary keys / timeout / EOF do not.
 */
class TurnKeyWatcherTest {

    @Test
    void escAndCtrlC_areInterruptKeys() {
        assertThat(TurnKeyWatcher.isInterruptKey(TurnKeyWatcher.ESC)).isTrue();
        assertThat(TurnKeyWatcher.isInterruptKey(TurnKeyWatcher.CTRL_C)).isTrue();
    }

    @Test
    void ordinaryKeysAndSentinels_areNotInterruptKeys() {
        assertThat(TurnKeyWatcher.isInterruptKey('a')).isFalse();
        assertThat(TurnKeyWatcher.isInterruptKey('/')).isFalse();
        assertThat(TurnKeyWatcher.isInterruptKey('\r')).isFalse();
        assertThat(TurnKeyWatcher.isInterruptKey(-1)).isFalse(); // EOF
        assertThat(TurnKeyWatcher.isInterruptKey(-2)).isFalse(); // READ_EXPIRED
    }
}
