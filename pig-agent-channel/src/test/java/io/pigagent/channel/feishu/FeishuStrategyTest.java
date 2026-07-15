package io.pigagent.channel.feishu;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.WebhookSender;
import io.pigagent.channel.strategy.Inbound;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Inbound gating + outbound send tests for the Feishu strategy (fake WebhookSender, no network). */
class FeishuStrategyTest {

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

    private static InboundHttp post(String body) {
        return post(body, Map.of());
    }

    @Test
    void nonPostYields405() {
        FeishuStrategy strategy = new FeishuStrategy(null, null, null, new RecordingSender());
        assertThat(strategy.inbound(new InboundHttp("GET", Map.of(), "")).response().status()).isEqualTo(405);
    }

    @Test
    void urlVerificationEchoesChallenge() {
        FeishuStrategy strategy = new FeishuStrategy(null, null, null, new RecordingSender());
        Inbound in = strategy.inbound(post("{\"type\":\"url_verification\",\"challenge\":\"C-9\"}"));
        assertThat(in.isDirect()).isTrue();
        assertThat(in.response().status()).isEqualTo(200);
        assertThat(in.response().body()).contains("C-9");
    }

    @Test
    void messageEventIsRouted() {
        FeishuStrategy strategy = new FeishuStrategy(null, null, null, new RecordingSender());
        String body = "{\"header\":{\"event_type\":\"im.message.receive_v1\"},"
                + "\"event\":{\"message\":{\"message_type\":\"text\","
                + "\"content\":\"{\\\"text\\\":\\\"hello lark\\\"}\"}}}";
        Inbound in = strategy.inbound(post(body));
        assertThat(in.isDirect()).isFalse();
        assertThat(in.message()).isEqualTo("hello lark");
    }

    @Test
    void nonMessageEventIsIgnored() {
        FeishuStrategy strategy = new FeishuStrategy(null, null, null, new RecordingSender());
        Inbound in = strategy.inbound(post("{\"header\":{\"event_type\":\"im.chat.updated_v1\"}}"));
        assertThat(in.isDirect()).isFalse();
        assertThat(in.hasMessage()).isFalse();
    }

    @Test
    void invalidSignatureYields401AndValidPasses() {
        // Arrange
        String token = "verif-token";
        String ts = "1700000000";
        String nonce = "N1";
        String body = "{\"type\":\"url_verification\",\"challenge\":\"C\"}";
        String encrypt = FeishuCodec.encryptField(body); // "" (plaintext)
        String sig = FeishuCodec.signature(ts, nonce, encrypt, token);
        FeishuStrategy strategy = new FeishuStrategy(null, null, token, new RecordingSender());

        // Act + Assert: bad signature → 401
        assertThat(strategy.inbound(post(body, Map.of(
                "x-lark-request-timestamp", ts, "x-lark-request-nonce", nonce, "x-lark-signature", "bad")))
                .response().status()).isEqualTo(401);

        // Valid signature → challenge handshake proceeds
        Inbound ok = strategy.inbound(post(body, Map.of(
                "x-lark-request-timestamp", ts, "x-lark-request-nonce", nonce, "x-lark-signature", sig)));
        assertThat(ok.isDirect()).isTrue();
        assertThat(ok.response().status()).isEqualTo(200);
        assertThat(ok.response().body()).contains("C");
    }

    @Test
    void ackIs200() {
        FeishuStrategy strategy = new FeishuStrategy(null, null, null, new RecordingSender());
        assertThat(strategy.ack().status()).isEqualTo(200);
    }

    @Test
    void sendPostsSignedBodyWhenSecretConfigured() {
        RecordingSender sender = new RecordingSender();
        FeishuStrategy strategy = new FeishuStrategy(
                "https://open.feishu.cn/open-apis/bot/v2/hook/XYZ", "SECRET", null, sender);

        strategy.send("hello world");

        assertThat(sender.calls).isEqualTo(1);
        assertThat(sender.url).isEqualTo("https://open.feishu.cn/open-apis/bot/v2/hook/XYZ");
        assertThat(sender.body).contains("\"msg_type\":\"text\"").contains("hello world")
                .contains("\"sign\"").contains("\"timestamp\"");
    }

    @Test
    void sendPostsUnsignedBodyWithoutSecret() {
        RecordingSender sender = new RecordingSender();
        FeishuStrategy strategy = new FeishuStrategy("https://open.feishu.cn/hook/X", null, null, sender);
        strategy.send("hi");
        assertThat(sender.calls).isEqualTo(1);
        assertThat(sender.body).contains("\"msg_type\":\"text\"").doesNotContain("\"sign\"");
    }

    @Test
    void sendWithoutWebhookUrlDoesNotPost() {
        RecordingSender sender = new RecordingSender();
        FeishuStrategy strategy = new FeishuStrategy(null, "SECRET", null, sender);
        strategy.send("hi");
        assertThat(sender.calls).isZero();
    }

    @Test
    void identity() {
        FeishuStrategy strategy = new FeishuStrategy(null, null, null, new RecordingSender());
        assertThat(strategy.channelId()).isEqualTo("feishu");
        assertThat(strategy.displayName()).isEqualTo("Feishu/Lark Robot");
        assertThat(strategy.defaultPort()).isEqualTo(8689);
        assertThat(strategy.defaultPath()).isEqualTo("/feishu");
    }
}
