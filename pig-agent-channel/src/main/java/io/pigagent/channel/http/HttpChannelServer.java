package io.pigagent.channel.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thin wrapper over the JDK's built-in {@link HttpServer} (no third-party dependency). It is the
 * <em>only</em> place that touches the raw socket / {@link HttpExchange}: it translates an exchange
 * to an {@link InboundHttp}, delegates to a pure {@link RequestHandler}, and writes the returned
 * {@link OutboundHttp}. This keeps HTTP-based channels' request logic unit-testable without a socket;
 * the glue here is covered by a single loopback round-trip test.
 *
 * <p>Requests are dispatched on a daemon thread pool so each request runs on its own thread — this
 * lets a channel use a per-request {@code ThreadLocal} to correlate the agent reply with the
 * in-flight request. Not thread-safe for concurrent start/stop on the same instance.
 */
public final class HttpChannelServer {

    private static final Logger log = LoggerFactory.getLogger(HttpChannelServer.class);

    private HttpServer server;
    private ExecutorService executor;

    /** Bind to {@code port} (0 = ephemeral) and route {@code path} to {@code handler}, then start. */
    public void start(int port, String path, RequestHandler handler) throws IOException {
        String ctx = (path == null || path.isBlank()) ? "/" : (path.startsWith("/") ? path : "/" + path);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        AtomicInteger threadSeq = new AtomicInteger();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-channel-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext(ctx, exchange -> dispatch(exchange, handler));
        server.start();
    }

    /** The actual bound port (useful when starting on an ephemeral port). */
    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /** Stop the server and shut down the dispatch pool. Safe to call when not started. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void dispatch(HttpExchange exchange, RequestHandler handler) {
        try {
            InboundHttp request = readRequest(exchange);
            OutboundHttp response = handler.handle(request);
            writeResponse(exchange, response == null ? new OutboundHttp(500, null, null) : response);
        } catch (Exception e) {
            log.warn("HTTP channel request failed: {}", e.getMessage());
            safeError(exchange);
        } finally {
            exchange.close();
        }
    }

    private InboundHttp readRequest(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
        }
        return new InboundHttp(exchange.getRequestMethod(), headers, body);
    }

    private void writeResponse(HttpExchange exchange, OutboundHttp response) throws IOException {
        byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        if (response.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void safeError(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(500, -1);
        } catch (IOException ignored) {
            // response may already be committed — nothing more we can do
        }
    }
}
