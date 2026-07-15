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

    /** POST {@code jsonBody} to {@code url} with a JSON content type; return {@code true} on a 2xx. */
    boolean post(String url, String jsonBody);

    /** The default JDK {@code HttpClient}-backed sender. */
    static WebhookSender jdk() {
        return new JdkWebhookSender();
    }
}
