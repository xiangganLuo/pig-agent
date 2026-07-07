package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.Map;

/**
 * REST over {@link io.pigagent.core.compression.CompressionService} for the current session —
 * mirrors {@code /compress} (now|status|off|on).
 *
 * <pre>
 *   GET  /api/compress       status
 *   POST /api/compress/now   compress now
 *   POST /api/compress       body {enabled}
 * </pre>
 */
public final class CompressHandler implements HttpHandler {

    private static final String BASE = "/api/compress";

    private final WebContext ctx;
    private final WebJson json;

    public CompressHandler(WebContext ctx, WebJson json) {
        this.ctx = ctx;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String[] p = Http.segments(ex, BASE);
            String sid = ctx.sessionManager().getCurrentSessionId();
            if (p.length == 0 && method.equals("GET")) {
                Http.json(ex, 200, json.toJson(json.compressStatus(ctx.compressionService().status(sid))));
            } else if (p.length == 1 && p[0].equals("now") && method.equals("POST")) {
                boolean did = ctx.compressionService().compressNow(sid);
                Http.json(ex, 200, json.toJson(Map.of("compressed", did)));
            } else if (p.length == 0 && method.equals("POST")) {
                Object enabled = json.parse(Http.body(ex)).get("enabled");
                if (!(enabled instanceof Boolean b)) {
                    Http.json(ex, 400, json.toJson(Map.of("error", "boolean 'enabled' is required"))); return;
                }
                ctx.compressionService().setEnabled(sid, b);
                Http.json(ex, 200, json.toJson(Map.of("enabled", b)));
            } else {
                Http.json(ex, 405, json.toJson(Map.of("error", "method not allowed")));
            }
        } catch (Exception e) {
            Http.json(ex, 500, json.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        } finally {
            ex.close();
        }
    }
}
