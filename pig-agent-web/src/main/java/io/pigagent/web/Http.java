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

    /** Hard cap on a request body — REST/chat payloads are tiny; reject runaway bodies (defensive). */
    private static final int MAX_BODY_BYTES = 1 << 20; // 1 MiB

    private Http() {
    }

    /** Read the full request body as UTF-8 (small JSON payloads only, bounded by {@link #MAX_BODY_BYTES}). */
    public static String body(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IOException("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The trailing path segments after {@code base}. E.g. base {@code /api/agents} and path
     * {@code /api/agents/abc/use} → {@code ["abc","use"]}; exactly {@code /api/agents} → {@code []}.
     */
    public static String[] segments(HttpExchange ex, String base) {
        String path = ex.getRequestURI().getPath();
        String rest = path.length() > base.length() ? path.substring(base.length()) : "";
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return rest.isEmpty() ? new String[0] : rest.split("/");
    }

    /** Send a JSON body with the given status. {@code 204} / empty body sends no content (and,
     *  per RFC 9110, no {@code Content-Type} — the header is only set when a body is written). */
    public static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (status == 204 || bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
