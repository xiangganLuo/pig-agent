package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST for memory — toggle (via {@link io.pigagent.session.SessionManager}) plus a read-only view of
 * the global + current-session memory content (read straight off disk; the memory object only
 * exposes {@code retrieve}). Mirrors {@code /memory} and adds content display the CLI lacks.
 *
 * <pre>
 *   GET  /api/memory   {enabled, global, session}
 *   POST /api/memory   body {enabled}
 * </pre>
 */
public final class MemoryHandler implements HttpHandler {

    private static final String TEMP_MEMORY_FILE = "temp-memory.md";

    private final WebContext ctx;
    private final WebJson json;

    public MemoryHandler(WebContext ctx, WebJson json) {
        this.ctx = ctx;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (method.equals("GET")) {
                get(ex);
            } else if (method.equals("POST")) {
                set(ex);
            } else {
                Http.json(ex, 405, json.toJson(Map.of("error", "method not allowed")));
            }
        } catch (Exception e) {
            Http.json(ex, 500, json.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        } finally {
            ex.close();
        }
    }

    private void get(HttpExchange ex) throws IOException {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", ctx.sessionManager().isMemoryEnabled());
        o.put("global", readOrEmpty(ctx.globalMemoryFile()));
        String sid = ctx.sessionManager().getCurrentSessionId();
        Path sessionMem = (ctx.sessionsDir() != null && sid != null)
                ? ctx.sessionsDir().resolve(sid).resolve(TEMP_MEMORY_FILE) : null;
        o.put("session", readOrEmpty(sessionMem));
        Http.json(ex, 200, json.toJson(o));
    }

    private void set(HttpExchange ex) throws IOException {
        Object enabled = json.parse(Http.body(ex)).get("enabled");
        if (!(enabled instanceof Boolean b)) {
            Http.json(ex, 400, json.toJson(Map.of("error", "boolean 'enabled' is required"))); return;
        }
        ctx.sessionManager().setMemoryEnabled(b);
        Http.json(ex, 200, json.toJson(Map.of("enabled", b)));
    }

    private static String readOrEmpty(Path p) {
        if (p == null || !Files.isRegularFile(p)) {
            return "";
        }
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }
}
