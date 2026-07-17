package io.pigagent.cli.render;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The braille spinner is a pure frame cycler: {@code glyph(tick)} returns the tick-th frame,
 * wrapping around (and tolerating negative ticks via floor-mod). No I/O, no timing.
 */
class SpinnerTest {

    @Test
    void firstFrameIsBrailleDot() {
        assertThat(new Spinner().glyph(0)).isEqualTo("⠋");
    }

    @Test
    void tenDistinctFramesInOrder() {
        Spinner s = new Spinner();
        assertThat(s.frameCount()).isEqualTo(10);
        assertThat(new String[]{
                s.glyph(0), s.glyph(1), s.glyph(2), s.glyph(3), s.glyph(4),
                s.glyph(5), s.glyph(6), s.glyph(7), s.glyph(8), s.glyph(9)})
                .containsExactly("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏");
    }

    @Test
    void wrapsAroundAfterLastFrame() {
        Spinner s = new Spinner();
        assertThat(s.glyph(10)).isEqualTo(s.glyph(0));
        assertThat(s.glyph(11)).isEqualTo(s.glyph(1));
        assertThat(s.glyph(25)).isEqualTo(s.glyph(5));
    }

    @Test
    void negativeTickIsFloorModded() {
        Spinner s = new Spinner();
        assertThat(s.glyph(-1)).isEqualTo(s.glyph(9));
        assertThat(s.glyph(-10)).isEqualTo(s.glyph(0));
    }
}
