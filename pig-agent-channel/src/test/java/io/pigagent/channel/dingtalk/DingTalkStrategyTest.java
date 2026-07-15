package io.pigagent.channel.dingtalk;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.WebhookSender;
import io.pigagent.channel.strategy.Inbound;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Inbound gating + outbound send tests for the DingTalk strategy (fake WebhookSender, no network). */
class DingTalkStrategyTest {

    /** Records the last (url, body) posted so we can assert without hitting the network. */
    private static final class RecordingSender implements WebhookSender {
        String url;
        String body;
        int calls;

        @Override
        public boolean post(String url, String jsonBody) {
            this.url = url;
            this.body = jsonBody;
            this.calls++;
            return true;
        }
    }

    private static InboundHttp post(String body, Map<String, String> headers) {
        return new InboundHttp("POST", headers, body);
    }

    @Test
    void nonPostYields405() {
        DingTalkStrategy strategy = new DingTalkStrategy(null, null, new RecordingSender());
        Inbound in = strategy.inbound(new InboundHttp("GET", Map.of(), ""));
        assertThat(in.isDirect()).isTrue();
        assertThat(in.response().status()).isEqualTo(405);
    }

    @Test
    void invalidSignYields401AndValidRoutes() {
        // Arrange
        String secret = "s3cr3t";
        String ts = "1700000000000";
        String sign = DingTalkCodec.sign(ts, secret);
        DingTalkStrategy strategy = new DingTalkStrategy(null, secret, new RecordingSender());
        String body = "{\"msgtype\":\"text\",\"text\":{\"content\":\"hi\"}}";

        // Act + Assert: missing/wrong sign → 401
        assertThat(strategy.inbound(post(body, Map.of("timestamp", ts, "sign", "wrong")))
                .response().status()).isEqualTo(401);
        assertThat(strategy.inbound(post(body, Map.of())).response().status()).isEqualTo(401);

        // Valid sign → routes the extracted message
        Inbound ok = strategy.inbound(post(body, Map.of("timestamp", ts, "sign", sign)));
        assertThat(ok.isDirect()).isFalse();
        assertThat(ok.message()).isEqualTo("hi");
    }

    @Test
    void withoutSecretRoutesWithoutVerification() {
        DingTalkStrategy strategy = new DingTalkStrategy(null, null, new RecordingSender());
        Inbound in = strategy.inbound(post("{\"msgtype\":\"text\",\"text\":{\"content\":\"hey\"}}", Map.of()));
        assertThat(in.hasMessage()).isTrue();
        assertThat(in.message()).isEqualTo("hey");
    }

    @Test
    void emptyMessageIsIgnored() {
        DingTalkStrategy strategy = new DingTalkStrategy(null, null, new RecordingSender());
        Inbound in = strategy.inbound(post("{\"msgtype\":\"text\"}", Map.of()));
        assertThat(in.isDirect()).isFalse();
        assertThat(in.hasMessage()).isFalse();
    }

    @Test
    void ackIs200() {
        DingTalkStrategy strategy = new DingTalkStrategy(null, null, new RecordingSender());
        assertThat(strategy.ack().status()).isEqualTo(200);
    }

    @Test
    void sendPostsSignedUrlAndTextPayload() {
        // Arrange
        RecordingSender sender = new RecordingSender();
        DingTalkStrategy strategy = new DingTalkStrategy(
                "https://oapi.dingtalk.com/robot/send?access_token=T", "s3cr3t", sender);

        // Act
        strategy.send("hello world");

        // Assert: signed URL + DingTalk text payload
        assertThat(sender.calls).isEqualTo(1);
        assertThat(sender.url).contains("&timestamp=").contains("&sign=");
        assertThat(sender.body).contains("\"msgtype\":\"text\"").contains("hello world");
    }

    @Test
    void sendWithoutSecretPostsUnsignedUrl() {
        RecordingSender sender = new RecordingSender();
        DingTalkStrategy strategy = new DingTalkStrategy("https://example.com/robot", null, sender);
        strategy.send("hi");
        assertThat(sender.calls).isEqualTo(1);
        assertThat(sender.url).isEqualTo("https://example.com/robot");
    }

    @Test
    void sendWithoutWebhookUrlDoesNotPost() {
        RecordingSender sender = new RecordingSender();
        DingTalkStrategy strategy = new DingTalkStrategy(null, "s3cr3t", sender);
        strategy.send("hi");
        assertThat(sender.calls).isZero();
    }

    @Test
    void identity() {
        DingTalkStrategy strategy = new DingTalkStrategy(null, null, new RecordingSender());
        assertThat(strategy.channelId()).isEqualTo("dingtalk");
        assertThat(strategy.displayName()).isEqualTo("DingTalk Robot");
        assertThat(strategy.defaultPort()).isEqualTo(8688);
        assertThat(strategy.defaultPath()).isEqualTo("/dingtalk");
    }
}
