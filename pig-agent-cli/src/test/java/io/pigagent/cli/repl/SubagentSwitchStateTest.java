package io.pigagent.cli.repl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure switch/back state machine for subagent-online-switch. */
class SubagentSwitchStateTest {

    @Test
    void startsInactive() {
        SubagentSwitchState s = new SubagentSwitchState();
        assertThat(s.isActive()).isFalse();
        assertThat(s.current()).isNull();
    }

    @Test
    void switchTo_thenActiveWithId() {
        SubagentSwitchState s = new SubagentSwitchState();
        s.switchTo("sub-abc");
        assertThat(s.isActive()).isTrue();
        assertThat(s.current()).isEqualTo("sub-abc");
    }

    @Test
    void back_returnsToParent() {
        SubagentSwitchState s = new SubagentSwitchState();
        s.switchTo("sub-abc");
        s.back();
        assertThat(s.isActive()).isFalse();
        assertThat(s.current()).isNull();
    }

    @Test
    void switchTo_nullOrBlank_isIgnored() {
        SubagentSwitchState s = new SubagentSwitchState();
        s.switchTo(null);
        s.switchTo("  ");
        assertThat(s.isActive()).isFalse();
    }

    @Test
    void back_isIdempotent() {
        SubagentSwitchState s = new SubagentSwitchState();
        s.back();
        s.back();
        assertThat(s.isActive()).isFalse();
    }
}
