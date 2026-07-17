package io.pigagent.core.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure {@link ModelErrorMessages} taxonomy: each HTTP status / exception family maps to its
 * localized one-liner, null is safe, and the fallthrough reason is credential-redacted.
 */
class ModelErrorMessagesTest {

    @Test
    void rateLimit_429() {
        assertThat(ModelErrorMessages.friendly(new RuntimeException("HTTP 429 Too Many Requests")))
                .contains("限流");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("rate_limit_exceeded")))
                .contains("限流");
    }

    @Test
    void upstream_5xx_and_overloaded() {
        assertThat(ModelErrorMessages.friendly(new RuntimeException("502: upstream_error")))
                .contains("暂时不可用");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("500 internal server error")))
                .contains("暂时不可用");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("503 Service Unavailable")))
                .contains("暂时不可用");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("Error 529: overloaded_error")))
                .contains("暂时不可用");
    }

    @Test
    void auth_401() {
        assertThat(ModelErrorMessages.friendly(new RuntimeException("401 Unauthorized")))
                .contains("API Key").contains("/model edit");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("invalid_api_key")))
                .contains("API Key");
    }

    @Test
    void forbidden_403() {
        assertThat(ModelErrorMessages.friendly(new RuntimeException("403 Forbidden")))
                .contains("访问被拒");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("account has overdue balance")))
                .contains("访问被拒");
    }

    @Test
    void badRequest_400_422_contextTooLong() {
        assertThat(ModelErrorMessages.friendly(new RuntimeException("400 Bad Request")))
                .contains("请求无效").contains("/compress now");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("422 Unprocessable Entity")))
                .contains("请求无效");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("maximum context length exceeded")))
                .contains("请求无效");
    }

    @Test
    void notFound_404_modelName() {
        assertThat(ModelErrorMessages.friendly(new RuntimeException("404 model_not_found")))
                .contains("模型名不存在");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("The model does not exist")))
                .contains("模型名不存在");
    }

    @Test
    void timeout_byExceptionType_andMessage() {
        assertThat(ModelErrorMessages.friendly(new HttpTimeoutException("request timed out")))
                .contains("超时");
        assertThat(ModelErrorMessages.friendly(new TimeoutException()))
                .contains("超时");
        assertThat(ModelErrorMessages.friendly(new RuntimeException("Read timed out")))
                .contains("超时");
    }

    @Test
    void network_byExceptionType_andMessage() {
        assertThat(ModelErrorMessages.friendly(new UnknownHostException("api.example.com")))
                .contains("无法连接");
        assertThat(ModelErrorMessages.friendly(new ConnectException("Connection refused")))
                .contains("无法连接");
        assertThat(ModelErrorMessages.friendly(new IOException("failed to connect to host")))
                .contains("无法连接");
    }

    @Test
    void nullThrowable_isSafe_neverNullText() {
        String out = ModelErrorMessages.friendly(null);
        assertThat(out).isNotBlank().doesNotContain("null");
    }

    @Test
    void nullMessage_usesClassNameNotLiteralNull() {
        String out = ModelErrorMessages.friendly(new IllegalStateException());
        // IllegalStateException is not a network/http family → fallthrough, class-based reason.
        assertThat(out).startsWith(ModelErrorMessages.FALLBACK_PREFIX);
        assertThat(out).contains("IllegalStateException").doesNotContain("null");
    }

    @Test
    void fallthrough_redactsSkKeyBearerAndAssignments() {
        Throwable t = new RuntimeException("weird failure sk-ABCDEF1234567890abcdef "
                + "token=supersecretvalue Bearer abcdefghijklmnop");
        String out = ModelErrorMessages.friendly(t);
        assertThat(out).startsWith(ModelErrorMessages.FALLBACK_PREFIX);
        assertThat(out).doesNotContain("sk-ABCDEF1234567890abcdef");
        assertThat(out).doesNotContain("supersecretvalue");
        assertThat(out).doesNotContain("abcdefghijklmnop");
        assertThat(out).contains("***");
    }

    @Test
    void fallthrough_masksBareGoogleKeyInUrl() {
        // A Gemini error may carry ?key=AIza... in a URL — the credential-leak floor must mask it.
        Throwable t = new RuntimeException(
                "unexpected shape at https://x/v1?key=AIzaSyA1234567890abcdefgh_1234567");
        String out = ModelErrorMessages.friendly(t);
        assertThat(out).doesNotContain("AIzaSyA1234567890abcdefgh_1234567");
        assertThat(out).contains("***");
    }

    @Test
    void fallthrough_masksBareGithubSlackAndJwtTokens() {
        assertThat(ModelErrorMessages.friendly(
                new RuntimeException("boom ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")))
                .doesNotContain("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345").contains("***");
        assertThat(ModelErrorMessages.friendly(
                new RuntimeException("boom xoxb-1234567890-abcdefghijkl")))
                .doesNotContain("xoxb-1234567890-abcdefghijkl").contains("***");
        assertThat(ModelErrorMessages.friendly(
                new RuntimeException("boom eyJhbGciOi.eyJzdWIiOiIx.SflKxwRJSMeKKF2QT")))
                .doesNotContain("eyJhbGciOi.eyJzdWIiOiIx.SflKxwRJSMeKKF2QT").contains("***");
    }

    @Test
    void unwrapsCauseChain_toClassifyWrappedStatus() {
        Throwable wrapped = new RuntimeException("agent failed",
                new IllegalStateException("downstream 401 unauthorized"));
        assertThat(ModelErrorMessages.friendly(wrapped)).contains("API Key");
    }

    @Test
    void codeMatchingIsWordBounded_not400InLongerNumber() {
        // "40000" must NOT be read as a 400 bad-request; with no other signal it falls through.
        String out = ModelErrorMessages.friendly(new RuntimeException("processed 40000 tokens ok-ish"));
        assertThat(out).startsWith(ModelErrorMessages.FALLBACK_PREFIX);
    }
}
