package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.model.StoredModel;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/status — a summary mirroring the CLI {@code /status} (agent/model/session/memory/perms/mcp). */
public final class StatusHandler implements HttpHandler {

    private final WebContext ctx;
    private final WebJson json;

    public StatusHandler(WebContext ctx, WebJson json) {
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
            Map<String, Object> o = new LinkedHashMap<>();
            String activeId = ctx.agentKernel().activeId();
            o.put("agent", ctx.agentKernel().getAgent(activeId).map(i -> i.spec().name()).orElse("(none)"));
            o.put("model", ctx.modelManager().getCurrentModel().map(StoredModel::label).orElse("(none)"));
            o.put("session", ctx.sessionManager().getCurrentSession()
                    .map(s -> s.name() + " [" + s.id() + "]").orElse("(none)"));
            o.put("memory", ctx.sessionManager().isMemoryEnabled() ? "on" : "off");
            o.put("perms", ctx.configManager().getConfig().getPermissions().resolveMode().name().toLowerCase());
            long connected = ctx.mcpManager().list().stream().filter(s -> s.connected()).count();
            o.put("mcp", ctx.mcpManager().list().size() + " configured, " + connected + " connected");
            Http.json(ex, 200, json.toJson(o));
        } catch (Exception e) {
            Http.json(ex, 500, json.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        } finally {
            ex.close();
        }
    }
}
