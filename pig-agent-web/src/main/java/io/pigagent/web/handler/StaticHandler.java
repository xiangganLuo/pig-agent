package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the console's static frontend from the classpath ({@code /web/…}, packaged in the jar).
 * {@code /} maps to {@code index.html}. Path traversal is refused. Intentionally tiny — the MVP
 * frontend is plain HTML/CSS/JS (D5), no build step, no heavy framework.
 */
public final class StaticHandler implements HttpHandler {

    private static final String ROOT = "/web";

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            if (path.contains("..")) { // no traversal outside the resource root
                send(ex, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String resource = ROOT + path;
            try (InputStream in = StaticHandler.class.getResourceAsStream(resource)) {
                if (in == null) {
                    send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(ex, 200, contentType(path), in.readAllBytes());
            }
        } finally {
            ex.close();
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
