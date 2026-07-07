package io.pigagent.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small shared HTTP helpers for the JSON REST handlers — request-body reading, JSON responses, and
 * path splitting. Extracted so every handler shares one framing (DRY), instead of re-declaring the
 * same private send/body methods.
 */
public final class Http {

    private Http() {
    }

    /** Read the full request body as UTF-8 (small JSON payloads only). */
    public static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * The trailing path segments after {@code base}. E.g. base {@code /api/models} and path
     * {@code /api/models/abc/use} → {@code ["abc","use"]}; exactly {@code /api/models} → {@code []}.
     */
    public static String[] segments(HttpExchange ex, String base) {
        String path = ex.getRequestURI().getPath();
        String rest = path.length() > base.length() ? path.substring(base.length()) : "";
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return rest.isEmpty() ? new String[0] : rest.split("/");
    }

    /** Send a JSON body with the given status. {@code 204} / empty body sends no content. */
    public static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        if (status == 204 || bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
