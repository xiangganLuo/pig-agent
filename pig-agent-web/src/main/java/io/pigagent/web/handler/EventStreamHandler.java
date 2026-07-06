package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.web.WebJson;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Server-Sent-Events adapter: subscribes to {@link AgentKernel#subscribeEvents()} and pushes each
 * {@link io.pigagent.core.agent.kernel.KernelEvent} to the browser as an SSE {@code data:} frame,
 * driving the live console (session stream, run monitor, report inbox). One server thread is held
 * open per connected client — fine for a single-user local console.
 *
 * <p>The stream opens with an SSE {@code retry:} hint so the browser's {@code EventSource}
 * auto-reconnects if the connection drops. On any write failure (client gone) the subscription is
 * disposed and the exchange closed (D4 + facade "no subscribers = zero cost").
 */
public final class EventStreamHandler implements HttpHandler {

    private static final long RECONNECT_MS = 3000;

    private final AgentKernel kernel;
    private final WebJson json;

    public EventStreamHandler(AgentKernel kernel, WebJson json) {
        this.kernel = kernel;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0); // 0 = chunked/streaming, keep open

        OutputStream os = ex.getResponseBody();
        CountDownLatch done = new CountDownLatch(1);
        Object lock = new Object();

        writeQuietly(os, lock, "retry: " + RECONNECT_MS + "\n\n", done);

        Disposable sub = kernel.subscribeEvents().subscribe(
                evt -> writeQuietly(os, lock, "data: " + json.toJson(json.event(evt)) + "\n\n", done),
                err -> done.countDown(),
                done::countDown);

        try {
            done.await(); // hold the thread until the client disconnects (write fails) or stream ends
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            sub.dispose();
            ex.close();
        }
    }

    private void writeQuietly(OutputStream os, Object lock, String frame, CountDownLatch done) {
        try {
            synchronized (lock) {
                os.write(frame.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException e) {
            done.countDown(); // client disconnected → release the handler thread
        }
    }
}
