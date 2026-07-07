package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.config.PermissionMode;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST over the permission config (there is no PermissionManager — reads/writes go through
 * {@link io.pigagent.config.ConfigurationManager}, exactly like {@code /permission}).
 *
 * <pre>
 *   GET  /api/permission          status {mode, channelMode, tools[], commands[]}
 *   POST /api/permission/mode     body {mode}
 *   POST /api/permission/allow    body {name, tool?}
 *   POST /api/permission/revoke   body {name}
 *   POST /api/permission/reset
 * </pre>
 */
public final class PermissionHandler implements HttpHandler {

    private static final String BASE = "/api/permission";

    private final WebContext ctx;
    private final WebJson json;

    public PermissionHandler(WebContext ctx, WebJson json) {
        this.ctx = ctx;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (Exception e) {
            Http.json(ex, 500, json.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String[] p = Http.segments(ex, BASE);
        if (p.length == 0) {
            if (method.equals("GET")) { status(ex); return; }
            Http.json(ex, 405, err("method not allowed")); return;
        }
        if (!method.equals("POST")) { Http.json(ex, 405, err("method not allowed")); return; }
        switch (p[0]) {
            case "mode" -> setMode(ex);
            case "allow" -> allow(ex);
            case "revoke" -> revoke(ex);
            case "reset" -> reset(ex);
            default -> Http.json(ex, 404, err("no such route"));
        }
    }

    private void status(HttpExchange ex) throws IOException {
        Http.json(ex, 200, json.toJson(json.permission(ctx.configManager().getConfig().getPermissions())));
    }

    private void setMode(HttpExchange ex) throws IOException {
        String raw = Http.str(json.parse(Http.body(ex)).get("mode"));
        PermissionMode m = PermissionMode.fromString(raw, null);
        if (m == null) { Http.json(ex, 400, err("invalid mode: " + raw + " (plan|ask|auto|bypass)")); return; }
        String v = m.name().toLowerCase();
        ctx.configManager().updateConfig(cfg -> cfg.getPermissions().setMode(v));
        Http.json(ex, 200, json.toJson(Map.of("mode", v)));
    }

    private void allow(HttpExchange ex) throws IOException {
        Map<String, Object> req = json.parse(Http.body(ex));
        String name = Http.str(req.get("name"));
        if (name.isBlank()) { Http.json(ex, 400, err("name is required")); return; }
        boolean tool = Boolean.TRUE.equals(req.get("tool"));
        ctx.configManager().updateConfig(cfg -> {
            List<String> list = tool ? cfg.getPermissions().getAllowlist().getTools()
                    : cfg.getPermissions().getAllowlist().getCommands();
            if (!list.contains(name)) {
                list.add(name);
            }
        });
        Http.json(ex, 200, json.toJson(json.permission(ctx.configManager().getConfig().getPermissions())));
    }

    private void revoke(HttpExchange ex) throws IOException {
        String name = Http.str(json.parse(Http.body(ex)).get("name"));
        if (name.isBlank()) { Http.json(ex, 400, err("name is required")); return; }
        ctx.configManager().updateConfig(cfg -> {
            cfg.getPermissions().getAllowlist().getTools().remove(name);
            cfg.getPermissions().getAllowlist().getCommands().remove(name);
        });
        Http.json(ex, 200, json.toJson(json.permission(ctx.configManager().getConfig().getPermissions())));
    }

    private void reset(HttpExchange ex) throws IOException {
        ctx.configManager().updateConfig(cfg -> {
            cfg.getPermissions().getAllowlist().getTools().clear();
            cfg.getPermissions().getAllowlist().getCommands().clear();
        });
        Http.json(ex, 200, json.toJson(json.permission(ctx.configManager().getConfig().getPermissions())));
    }

    private String err(String message) {
        return json.toJson(Map.of("error", message));
    }
}
