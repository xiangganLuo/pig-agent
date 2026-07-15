package io.pigagent.channel.http;

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
 */
public final class JdkWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(JdkWebhookSender.class);
    private static final String JSON = "application/json; charset=utf-8";

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
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            boolean ok = status >= 200 && status < 300;
            if (!ok) {
                log.warn("Webhook POST to host '{}' returned {}", host(url), status);
            }
            return ok;
        } catch (Exception e) {
            log.warn("Webhook POST to host '{}' failed: {}", host(url), e.getClass().getSimpleName());
            return false;
        }
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
