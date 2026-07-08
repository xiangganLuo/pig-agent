package io.pigagent.cli.render;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallFormatterTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    @Test
    void rendersMarkerLabelAndResultOnTwoLines() {
        String out = ToolCallFormatter.format("executeCommand", "exit=0, 3 files");

        String[] lines = out.split("\n", -1);
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("⏺").contains("executeCommand");
        assertThat(lines[1]).contains("└").contains("exit=0, 3 files");
    }

    @Test
    void collapsesMultilineResultToSingleSummaryLine() {
        String out = ToolCallFormatter.format("readFile", "line1\nline2\nline3");

        assertThat(out.split("\n", -1)).hasSize(2);
        assertThat(out).doesNotContain("line2\nline3");
    }

    @Test
    void truncatesLongResult() {
        String big = "x".repeat(500);

        String out = ToolCallFormatter.format("tool", big);

        // Summary line must be bounded well under the raw length.
        String summary = out.split("\n", -1)[1];
        assertThat(summary.length()).isLessThan(300);
        assertThat(summary).contains("…");
    }

    @Test
    void blankLabelFallsBackToGenericTool() {
        String out = ToolCallFormatter.format("  ", "done");

        assertThat(out.split("\n", -1)[0]).contains("tool");
    }

    @Test
    void redactsApiKeyLikeTokens() {
        String out = ToolCallFormatter.format("mcp", "connected with sk-ABCDEF1234567890abcdef");

        assertThat(out).doesNotContain("sk-ABCDEF1234567890abcdef");
        assertThat(out).contains("***");
    }

    @Test
    void redactsKeyValueSecrets() {
        String out = ToolCallFormatter.format("mcp", "apiKey=supersecretvalue token=anothersecret");

        assertThat(out).doesNotContain("supersecretvalue");
        assertThat(out).doesNotContain("anothersecret");
    }

    @Test
    void redactsBearerAuthorization() {
        String out = ToolCallFormatter.format("http", "Authorization: Bearer abcdef123456789012345");

        assertThat(out).doesNotContain("abcdef123456789012345");
    }
}
