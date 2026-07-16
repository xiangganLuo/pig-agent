package io.pigagent.cli.render;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase 6a — the pure {@link SubagentEventRenderer}: a forwarded subagent (child) event becomes a
 * dim, nested, source-labeled single line, distinct from the parent's answer, with credential
 * redaction + truncation reused from {@link ToolCallFormatter}.
 */
class SubagentEventRendererTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    @Test
    void labelIsTheLastSourcePathSegment() {
        assertThat(SubagentEventRenderer.label("main/reviewer")).isEqualTo("reviewer");
        assertThat(SubagentEventRenderer.label("main/team/researcher")).isEqualTo("researcher");
        assertThat(SubagentEventRenderer.label("reviewer")).isEqualTo("reviewer");
        assertThat(SubagentEventRenderer.label(null)).isEmpty();
    }

    @Test
    void formatNestsAndLabelsTheChildLine() {
        String line = SubagentEventRenderer.format("main/reviewer", "found 2 issues");
        assertThat(line).contains("└").contains("[reviewer]").contains("found 2 issues");
    }

    @Test
    void formatRedactsSecretsAndCollapsesToOneLine() {
        String line = SubagentEventRenderer.format("main/worker",
                "token=abcdef123456\nsecond line");
        assertThat(line).doesNotContain("abcdef123456");
        assertThat(line).contains("token=***");
        // Newline collapsed to a single rendered line (no embedded raw newline in the content).
        assertThat(line.strip()).doesNotContain("\n");
    }

    @Test
    void formatTruncatesLongChildOutput() {
        String line = SubagentEventRenderer.format("main/worker", "x".repeat(500));
        assertThat(line).contains("…");
    }
}
