package io.pigagent.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link ModelManager#runProbe} — the bounded-timeout + friendly-message mapping
 * behind the connectivity {@code test()} (the network probe itself needs a live model). Drives the
 * injectable probe callable directly, so success / no-response / timeout / friendly-error all map
 * deterministically without touching the network.
 */
class ModelManagerTestProbeTest {

    private static Msg reply() {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("OK").build()).build();
    }

    @Test
    void reply_mapsToSuccess() {
        ModelManager.TestResult r = ModelManager.runProbe(ModelManagerTestProbeTest::reply, 5);

        assertThat(r.ok()).isTrue();
        assertThat(r.error()).isNull();
    }

    @Test
    void nullReply_mapsToNoResponseFailure() {
        ModelManager.TestResult r = ModelManager.runProbe(() -> null, 5);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("无响应");
    }

    @Test
    void slowProbe_timesOut_withClearReason() {
        ModelManager.TestResult r = ModelManager.runProbe(() -> {
            Thread.sleep(10_000); // longer than the 1s bound below
            return reply();
        }, 1);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("超时");
    }

    @Test
    void throwingProbe_mapsToFriendlyTaxonomy() {
        ModelManager.TestResult r = ModelManager.runProbe(() -> {
            throw new RuntimeException("401 Unauthorized");
        }, 5);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("API Key"); // ModelErrorMessages taxonomy, not raw text
        assertThat(r.error()).doesNotContain("401 Unauthorized");
    }

    @Test
    void throwingProbe_redactsCredentialsInFallthrough() {
        ModelManager.TestResult r = ModelManager.runProbe(() -> {
            throw new RuntimeException("odd failure sk-ABCDEF1234567890abcdef");
        }, 5);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).doesNotContain("sk-ABCDEF1234567890abcdef");
        assertThat(r.error()).contains("***");
    }
}
