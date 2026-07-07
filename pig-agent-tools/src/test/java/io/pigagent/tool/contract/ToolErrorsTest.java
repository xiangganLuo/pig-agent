package io.pigagent.tool.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical tool error contract: a failure is expressed as a compact {@code {"error":"..."}}
 * JSON object, credential-safe and JSON-escaped, so every tool reports failures uniformly.
 */
class ToolErrorsTest {

    @Test
    void wrapsReasonInErrorObject() {
        assertThat(ToolErrors.message("file not found")).isEqualTo("{\"error\":\"file not found\"}");
    }

    @Test
    void escapesQuotesAndBackslashesAndNewlines() {
        String out = ToolErrors.message("bad \"path\" \\x\nnext");
        assertThat(out).isEqualTo("{\"error\":\"bad \\\"path\\\" \\\\x\\nnext\"}");
    }

    @Test
    void redactsCredentialsInReason() {
        String out = ToolErrors.message("auth failed: api_key=SUPERSECRET123");
        assertThat(out).doesNotContain("SUPERSECRET123");
        assertThat(out).contains("***");
        assertThat(out).startsWith("{\"error\":\"");
    }

    @Test
    void nullReasonYieldsGenericError() {
        String out = ToolErrors.message(null);
        assertThat(out).isEqualTo("{\"error\":\"unknown error\"}");
    }
}
