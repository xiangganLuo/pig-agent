package io.pigagent.cli.render;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownAnsiRendererTest {

    /** The ANSI escape character (0x1B) that begins every SGR sequence. */
    private static final String ESC = Character.toString(27);

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    @Test
    void boldMarkersRemovedAndStyled() {
        String out = MarkdownAnsiRenderer.renderLine("say **hi** now", false);

        assertThat(out).contains("hi").contains(ESC).doesNotContain("**");
    }

    @Test
    void inlineCodeStyledAndBackticksRemoved() {
        String out = MarkdownAnsiRenderer.renderLine("run `mvn test` please", false);

        assertThat(out).contains("mvn test").contains(ESC).doesNotContain("`");
    }

    @Test
    void headingStyledAndHashesRemoved() {
        String out = MarkdownAnsiRenderer.renderLine("## Title", false);

        assertThat(out).contains("Title").contains(ESC).doesNotContain("#");
    }

    @Test
    void listItemGetsBullet() {
        String out = MarkdownAnsiRenderer.renderLine("- item", false);

        assertThat(out).contains("item").contains("•").doesNotContain("- item");
    }

    @Test
    void starListItemGetsBullet() {
        String out = MarkdownAnsiRenderer.renderLine("* item", false);

        assertThat(out).contains("item").contains("•");
    }

    @Test
    void insideCodeBlockKeepsMarkersLiteral() {
        String out = MarkdownAnsiRenderer.renderLine("x = **not bold**", true);

        assertThat(out).contains("**not bold**");
    }

    @Test
    void fenceDetection() {
        assertThat(MarkdownAnsiRenderer.isFence("```")).isTrue();
        assertThat(MarkdownAnsiRenderer.isFence("```java")).isTrue();
        assertThat(MarkdownAnsiRenderer.isFence("  ```")).isTrue();
        assertThat(MarkdownAnsiRenderer.isFence("plain code")).isFalse();
        assertThat(MarkdownAnsiRenderer.isFence("`inline`")).isFalse();
    }

    @Test
    void unclosedBoldLeftLiteral() {
        String out = MarkdownAnsiRenderer.renderLine("a **b c", false);

        assertThat(out).contains("**b c");
    }

    @Test
    void unclosedInlineCodeLeftLiteral() {
        String out = MarkdownAnsiRenderer.renderLine("a `b c", false);

        assertThat(out).contains("`b c");
    }

    @Test
    void renderFullBlockTogglesCodeFence() {
        String md = "before\n```\n**x**\n```\nafter **y**";

        String out = MarkdownAnsiRenderer.render(md);

        // Inside the fence markers are literal; outside they are styled away.
        assertThat(out).contains("**x**");
        assertThat(out).contains("y").doesNotContain("**y**");
    }

    @Test
    void renderPreservesLineCount() {
        String md = "one\ntwo\nthree";

        String out = MarkdownAnsiRenderer.render(md);

        assertThat(out.split("\n", -1)).hasSize(3);
    }

    @Test
    void multipleBoldSegmentsOnOneLine() {
        String out = MarkdownAnsiRenderer.renderLine("**a** and **b**", false);

        assertThat(out).contains("a").contains("b").doesNotContain("**");
    }
}
