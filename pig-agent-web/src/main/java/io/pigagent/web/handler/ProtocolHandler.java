package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

/** GET /api/protocols — the model protocol types, so the "add model" form knows the fields. */
public final class ProtocolHandler implements HttpHandler {

    private final WebContext ctx;
    private final WebJson json;

    public ProtocolHandler(WebContext ctx, WebJson json) {
        this.ctx = ctx;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET")) {
                Http.json(ex, 405, json.toJson(Map.of("error", "method not allowed")));
                return;
            }
            Http.json(ex, 200, json.toJson(ctx.protocolRegistry().getAllProtocols().stream()
                    .map(json::protocol).collect(Collectors.toList())));
        } catch (Exception e) {
            Http.json(ex, 500, json.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        } finally {
            ex.close();
        }
    }
}
