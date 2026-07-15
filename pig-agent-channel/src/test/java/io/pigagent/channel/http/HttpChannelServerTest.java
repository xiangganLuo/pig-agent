package io.pigagent.channel.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loopback round-trip covering the exchange↔record glue: start on an ephemeral port, POST to
 * localhost with a JDK HttpClient, assert the pure handler's {@link OutboundHttp} is written back.
 * Offline (localhost only) and deterministic.
 */
class HttpChannelServerTest {

    @Test
    void roundTripEchoesHandlerResponse() throws Exception {
        // Arrange: a handler that echoes the method + a header + the body
        HttpChannelServer server = new HttpChannelServer();
        RequestHandler handler = req -> OutboundHttp.json(
                200, "{\"method\":\"" + req.method() + "\",\"auth\":\"" + req.header("X-Auth-Token")
                        + "\",\"body\":\"" + req.body() + "\"}");
        try {
            server.start(0, "/echo", handler);
            int port = server.boundPort();
            assertThat(port).isGreaterThan(0);

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/echo"))
                    .header("X-Auth-Token", "secret")
                    .POST(HttpRequest.BodyPublishers.ofString("hello"))
                    .build();

            // Act
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Assert
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).get().asString().contains("application/json");
            assertThat(resp.body()).contains("\"method\":\"POST\"", "\"auth\":\"secret\"", "\"body\":\"hello\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void bodylessResponseHasNoContent() throws Exception {
        // Arrange
        HttpChannelServer server = new HttpChannelServer();
        try {
            server.start(0, "/empty", req -> new OutboundHttp(204, null, null));
            int port = server.boundPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/empty"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            // Act
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Assert
            assertThat(resp.statusCode()).isEqualTo(204);
            assertThat(resp.body()).isEmpty();
        } finally {
            server.stop();
        }
    }
}
