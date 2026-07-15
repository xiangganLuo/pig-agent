package io.pigagent.web;

import com.sun.net.httpserver.HttpServer;
import io.pigagent.web.handler.AgentApiHandler;
import io.pigagent.web.handler.ChatHandler;
import io.pigagent.web.handler.EventStreamHandler;
import io.pigagent.web.handler.StaticHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The embedded local Web console: an {@code AgentKernel} adapter over the JDK's built-in
 * {@link HttpServer} (no external web framework — light, offline-safe). Bound to a loopback host
 * only (D3: personal-computer, single-user, no auth, no DB), it starts/stops with the CLI process.
 *
 * <p>Routes (minimal, first version): {@code /api/agents…} (REST → kernel), {@code /api/chat}
 * (SSE chat stream → {@code kernel.chat}), {@code /api/events} (SSE ← {@code subscribeEvents}),
 * {@code /} (static frontend). Construct with {@code (WebContext, host, port)}; call
 * {@link #start()} / {@link #stop()}. Port {@code 0} picks an ephemeral port — read it back via
 * {@link #boundPort()}.
 */
public final class WebConsole {

    private final WebContext ctx;
    private final String host;
    private final int port;
    private HttpServer server;

    public WebConsole(WebContext ctx, String host, int port) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
    }

    /** Bind + start the server. Throws if already started. */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("web console already started");
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
        WebJson json = new WebJson();
        s.createContext("/api/agents", new AgentApiHandler(ctx.agentKernel(), json));
        s.createContext("/api/chat", new ChatHandler(ctx, json));
        s.createContext("/api/events", new EventStreamHandler(ctx.agentKernel(), json));
        s.createContext("/", new StaticHandler());
        // A small pool: SSE handlers block a thread each; REST calls need free threads meanwhile.
        s.setExecutor(Executors.newCachedThreadPool(daemonFactory()));
        s.start();
        this.server = s;
    }

    /** Stop the server, releasing the port. Safe to call when not started. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** The actual bound port (useful when constructed with port 0). -1 if not started. */
    public synchronized int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public String host() {
        return host;
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "web-console-" + n.incrementAndGet());
            t.setDaemon(true); // never block JVM shutdown on an open SSE connection
            return t;
        };
    }
}
