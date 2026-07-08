package io.pigagent.cli.repl.select;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectorModelTest {

    @Test
    void startsAtFirstOption() {
        assertThat(new SelectorModel(3).cursor()).isZero();
    }

    @Test
    void downAdvancesButClampsAtBottom() {
        SelectorModel m = new SelectorModel(2);

        m.down();
        assertThat(m.cursor()).isEqualTo(1);
        m.down(); // clamped
        assertThat(m.cursor()).isEqualTo(1);
    }

    @Test
    void upRetreatsButClampsAtTop() {
        SelectorModel m = new SelectorModel(2);
        m.down();

        m.up();
        assertThat(m.cursor()).isZero();
        m.up(); // clamped
        assertThat(m.cursor()).isZero();
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> new SelectorModel(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
