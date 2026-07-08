package io.pigagent.cli.render;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingMarkdownPrinterTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    @Test
    void emitsCompleteLinesAsNewlinesArrive() {
        List<String> lines = new ArrayList<>();
        StreamingMarkdownPrinter printer = new StreamingMarkdownPrinter();

        printer.accept("hello ", lines::add);
        assertThat(lines).isEmpty(); // no newline yet -> buffered

        printer.accept("world\nsecond", lines::add);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("hello world");

        printer.flush(lines::add);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).contains("second");
    }

    @Test
    void chunkSplitAcrossBoldMarkerStillRenders() {
        List<String> lines = new ArrayList<>();
        StreamingMarkdownPrinter printer = new StreamingMarkdownPrinter();

        // A bold span split across two chunks but completed within one line.
        printer.accept("a **bo", lines::add);
        printer.accept("ld** b\n", lines::add);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("bold").doesNotContain("**");
    }

    @Test
    void codeFenceStateSpansLines() {
        List<String> lines = new ArrayList<>();
        StreamingMarkdownPrinter printer = new StreamingMarkdownPrinter();

        printer.accept("```\n**x**\n```\nafter **y**\n", lines::add);

        assertThat(lines).hasSize(4);
        assertThat(lines.get(1)).contains("**x**");          // inside fence: literal
        assertThat(lines.get(3)).doesNotContain("**y**");    // outside: styled
    }

    @Test
    void tracksWhetherAnyOutputWasProduced() {
        List<String> lines = new ArrayList<>();
        StreamingMarkdownPrinter printer = new StreamingMarkdownPrinter();
        assertThat(printer.hasOutput()).isFalse();

        printer.accept("text\n", lines::add);

        assertThat(printer.hasOutput()).isTrue();
    }

    @Test
    void flushWithEmptyBufferEmitsNothing() {
        List<String> lines = new ArrayList<>();
        StreamingMarkdownPrinter printer = new StreamingMarkdownPrinter();

        printer.flush(lines::add);

        assertThat(lines).isEmpty();
    }
}
