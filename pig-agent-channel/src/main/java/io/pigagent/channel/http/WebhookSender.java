package io.pigagent.channel.http;

/**
 * The outbound counterpart to {@link HttpChannelServer}: a tiny seam over "POST a JSON body to a
 * webhook URL". Abstracting it lets channel strategies be unit-tested by injecting a recording fake
 * (asserting the signed URL + payload) without touching the network — the JDK-backed
 * {@link JdkWebhookSender} is the only implementation that performs real I/O.
 *
 * <p>Implementations MUST NOT throw: a transport failure returns {@code false} (logged at warn, with
 * no request body or credential in the message).
 */
@FunctionalInterface
public interface WebhookSender {

    /**
     * POST {@code jsonBody} to {@code url} with a JSON content type. Return {@code true} only on a
     * genuine success — a 2xx <em>and</em>, for a robot endpoint that answers 200 with a status body,
     * a zero {@code errcode}/{@code code}/{@code StatusCode}. A transport failure or a non-zero robot
     * error code returns {@code false} (see {@link JdkWebhookSender}).
     */
    boolean post(String url, String jsonBody);

    /** The default JDK {@code HttpClient}-backed sender. */
    static WebhookSender jdk() {
        return new JdkWebhookSender();
    }
}
