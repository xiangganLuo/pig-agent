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

    @Test
    void redactsBareGoogleApiKeyEvenInUrl() {
        String out = ToolCallFormatter.format("fetchUrl",
                "GET https://x/v1?key=AIzaSyA1234567890abcdefgh_1234567 failed");

        assertThat(out).doesNotContain("AIzaSyA1234567890abcdefgh_1234567");
        assertThat(out).contains("***");
    }

    @Test
    void redactsBareGithubToken() {
        String out = ToolCallFormatter.format("mcp",
                "auth failed with ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");

        assertThat(out).doesNotContain("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");
        assertThat(out).contains("***");
    }

    @Test
    void redactsBareSlackTokens() {
        String bot = ToolCallFormatter.format("slack", "using xoxb-1234567890-abcdefghijkl now");
        assertThat(bot).doesNotContain("xoxb-1234567890-abcdefghijkl").contains("***");

        String app = ToolCallFormatter.format("slack", "using xapp-1-A012-abcdefghijkl now");
        assertThat(app).doesNotContain("xapp-1-A012-abcdefghijkl").contains("***");
    }

    @Test
    void redactsBareJwt() {
        String out = ToolCallFormatter.format("http",
                "denied token eyJhbGciOi.eyJzdWIiOiIx.SflKxwRJSMeKKF2QT");

        assertThat(out).doesNotContain("eyJhbGciOi.eyJzdWIiOiIx.SflKxwRJSMeKKF2QT");
        assertThat(out).contains("***");
    }

    @Test
    void errorBodyIsDistinctFromSuccessBody() {
        String ok = ToolCallFormatter.body("done");
        String err = ToolCallFormatter.errorBody("boom");

        assertThat(err).contains("✗").contains("boom");
        assertThat(ok).doesNotContain("✗");
    }

    @Test
    void headAndBodyComposeToTheFullFormat() {
        String full = ToolCallFormatter.format("readFile", "42 lines");

        assertThat(full).isEqualTo(ToolCallFormatter.head("readFile") + "\n"
                + ToolCallFormatter.body("42 lines"));
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        // 🙂 is a surrogate pair; a run of them padded past MAX_SUMMARY must truncate cleanly.
        String emoji = "🙂".repeat(300);
        String out = ToolCallFormatter.format("tool", emoji);

        String summary = out.split("\n", -1)[1];
        assertThat(summary).doesNotContain("�"); // no replacement char from a broken pair
        // Every emoji code point kept intact: no lone high/low surrogate in the summary.
        for (int i = 0; i < summary.length(); i++) {
            char c = summary.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < summary.length() && Character.isLowSurrogate(summary.charAt(i + 1)))
                        .as("high surrogate at %d is paired", i).isTrue();
            }
        }
    }
}
