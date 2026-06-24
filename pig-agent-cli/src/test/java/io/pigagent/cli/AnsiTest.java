package io.pigagent.cli;

import org.fusesource.jansi.Ansi.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnsiTest {

    /** The ANSI escape character (0x1B) that begins every SGR color/style sequence. */
    private static final String ESC = Character.toString(27);

    @BeforeAll
    static void enableAnsi() {
        // Ensure the Jansi builder emits escape codes regardless of console detection.
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    @Test
    void infoPreservesPlainText() {
        assertThat(Ansi.info("hello")).contains("hello");
    }

    @Test
    void successEmitsEscapeAndText() {
        assertThat(Ansi.success("ok")).contains("ok").contains(ESC);
    }

    @Test
    void warnEmitsEscapeAndText() {
        assertThat(Ansi.warn("careful")).contains("careful").contains(ESC);
    }

    @Test
    void errorEmitsEscapeAndText() {
        assertThat(Ansi.error("boom")).contains("boom").contains(ESC);
    }

    @Test
    void promptEmitsEscapeAndText() {
        assertThat(Ansi.prompt("you> ")).contains("you> ").contains(ESC);
    }

    @Test
    void headingEmitsEscapeAndText() {
        assertThat(Ansi.heading("Title")).contains("Title").contains(ESC);
    }

    @Test
    void dimEmitsEscapeAndText() {
        assertThat(Ansi.dim("trace")).contains("trace").contains(ESC);
    }

    @Test
    void boldEmitsEscapeAndText() {
        assertThat(Ansi.bold("X", Color.CYAN)).contains("X").contains(ESC);
    }
}
