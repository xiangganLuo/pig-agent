package io.pigagent.tool.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the credential redaction that every tool error message passes through: secret-like
 * values (API keys, bearer tokens, {@code key=…} assignments) MUST be replaced by {@code ***}
 * so a tool failure can never echo a credential back to the model.
 */
class CredentialSanitizerTest {

    @Test
    void redactsSkStyleApiKey() {
        String out = CredentialSanitizer.sanitize("auth failed for sk-ant-api03-abc123DEF456ghi789");
        assertThat(out).doesNotContain("abc123DEF456ghi789");
        assertThat(out).contains("***");
    }

    @Test
    void redactsBearerToken() {
        String out = CredentialSanitizer.sanitize("401 Unauthorized: Bearer eyJhbGciOiJetc.token.value");
        assertThat(out).doesNotContain("eyJhbGciOiJetc.token.value");
        assertThat(out).contains("Bearer ***");
    }

    @Test
    void redactsKeyValueAssignment() {
        String out = CredentialSanitizer.sanitize("request api_key=SUPERSECRETVALUE123 rejected");
        assertThat(out).doesNotContain("SUPERSECRETVALUE123");
        assertThat(out).contains("***");
    }

    @Test
    void redactsTokenInUrlQuery() {
        String out = CredentialSanitizer.sanitize("GET https://api.example.com/x?token=abcd1234efgh5678 failed");
        assertThat(out).doesNotContain("abcd1234efgh5678");
        assertThat(out).contains("***");
    }

    @Test
    void leavesInnocentTextUnchanged() {
        String out = CredentialSanitizer.sanitize("file not found: /home/user/report.txt");
        assertThat(out).isEqualTo("file not found: /home/user/report.txt");
    }

    @Test
    void handlesNull() {
        assertThat(CredentialSanitizer.sanitize(null)).isEmpty();
    }
}
