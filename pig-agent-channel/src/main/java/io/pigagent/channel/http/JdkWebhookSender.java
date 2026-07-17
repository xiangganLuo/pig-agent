package io.pigagent.channel.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A {@link WebhookSender} backed by the JDK {@link HttpClient} — no third-party HTTP dependency. Used
 * by robot channel strategies (DingTalk / Feishu) to POST a signed reply to a custom-robot webhook.
 * Never throws: any failure (bad URL, unreachable host, non-2xx) is logged at warn and returns
 * {@code false}. The log deliberately carries only the URL host and status — never the request body
 * or a credential.
 *
 * <p><b>Robot-level error check.</b> DingTalk/Feishu custom-robot endpoints answer HTTP 200 even when
 * they <em>reject</em> a message (bad sign, keyword miss, rate limit), carrying the real outcome in
 * the JSON body ({@code errcode} for DingTalk, {@code code}/{@code StatusCode} for Feishu). A naive
 * "2xx == success" therefore reports a delivery that never happened. So {@link #post} additionally
 * inspects the body and returns {@code false} when any present error code is non-zero (logging the
 * code, never the body/credential), so {@code /notify test} reports success only on a genuine send.
 */
public final class JdkWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(JdkWebhookSender.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JSON = "application/json; charset=utf-8";
    /** Robot-level status fields whose non-zero value means the message was rejected. */
    private static final String[] ERROR_CODE_FIELDS = {"errcode", "code", "StatusCode"};

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkWebhookSender() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(15));
    }

    JdkWebhookSender(HttpClient client, Duration requestTimeout) {
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public boolean post(String url, String jsonBody) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Content-Type", JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("Webhook POST to host '{}' returned {}", host(url), status);
                return false;
            }
            long errorCode = robotErrorCode(response.body());
            if (errorCode != 0) {
                // The robot answered 200 but rejected the message — surface it as a failure. Log only
                // the numeric code (never the body, which may echo signed content or a credential).
                log.warn("Webhook POST to host '{}' rejected by robot (code {})", host(url), errorCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Webhook POST to host '{}' failed: {}", host(url), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * The robot-level error code from a 2xx response body: the first present of {@code errcode}
     * (DingTalk) / {@code code} / {@code StatusCode} (Feishu). Returns {@code 0} (success) when the
     * body is blank, not JSON, or carries no such field — so a plain {@code {}} ack still counts as
     * delivered. Never throws.
     */
    static long robotErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            for (String field : ERROR_CODE_FIELDS) {
                JsonNode node = root.get(field);
                if (node != null && node.isNumber()) {
                    return node.asLong();
                }
            }
        } catch (Exception ignored) {
            // Non-JSON / unparseable body → treat as no robot error (2xx already gates delivery).
        }
        return 0;
    }

    /** Extract just the host for logging — never the full URL (may carry a signature query param). */
    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }
}
