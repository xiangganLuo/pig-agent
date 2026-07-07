package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.mcp.McpManager;
import io.pigagent.mcp.McpServerSpec;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST over {@link McpManager} — mirrors {@code /mcp} (list|add|remove|enable|disable|test).
 * Listings/responses omit {@code env}/{@code headers} (credential redaction, per the CLI convention).
 *
 * <pre>
 *   GET    /api/mcp                 list
 *   POST   /api/mcp                 add     body {name, command|url, args?, env?, streamableHttp?, headers?}
 *   DELETE /api/mcp/{name}          remove
 *   POST   /api/mcp/{name}/enable   enable
 *   POST   /api/mcp/{name}/disable  disable
 *   POST   /api/mcp/{name}/test     test
 * </pre>
 */
public final class McpHandler implements HttpHandler {

    private static final String BASE = "/api/mcp";

    private final WebContext ctx;
    private final WebJson json;

    public McpHandler(WebContext ctx, WebJson json) {
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
        McpManager mcp = ctx.mcpManager();
        String method = ex.getRequestMethod();
        String[] p = Http.segments(ex, BASE);

        if (p.length == 0) {
            if (method.equals("GET")) { list(ex, mcp); return; }
            if (method.equals("POST")) { add(ex, mcp); return; }
            Http.json(ex, 405, err("method not allowed")); return;
        }
        String name = p[0];
        String sub = p.length > 1 ? p[1] : null;
        if (sub == null) {
            if (method.equals("DELETE")) { remove(ex, mcp, name); return; }
            Http.json(ex, 405, err("method not allowed")); return;
        }
        if (!method.equals("POST")) { Http.json(ex, 405, err("method not allowed")); return; }
        switch (sub) {
            case "enable" -> { requireServer(ex, mcp, name, () -> { mcp.enable(name); return "enabled"; }); }
            case "disable" -> { requireServer(ex, mcp, name, () -> { mcp.disable(name); return "disabled"; }); }
            case "test" -> test(ex, mcp, name);
            default -> Http.json(ex, 404, err("no such route"));
        }
    }

    private void list(HttpExchange ex, McpManager mcp) throws IOException {
        List<Map<String, Object>> body = mcp.list().stream()
                .map(json::mcp).collect(Collectors.toList());
        Http.json(ex, 200, json.toJson(body));
    }

    private void add(HttpExchange ex, McpManager mcp) throws IOException {
        Map<String, Object> req = json.parse(Http.body(ex));
        String name = Http.str(req.get("name"));
        if (name.isBlank()) { Http.json(ex, 400, err("name is required")); return; }
        McpServerSpec spec;
        try {
            spec = new McpServerSpec(
                    name,
                    emptyToNull(Http.str(req.get("command"))),
                    strList(req.get("args")),
                    strMap(req.get("env")),
                    emptyToNull(Http.str(req.get("url"))),
                    Boolean.TRUE.equals(req.get("streamableHttp")),
                    strMap(req.get("headers")),
                    true);
        } catch (IllegalArgumentException e) {
            Http.json(ex, 400, err(e.getMessage())); return;
        }
        McpManager.TestResult t = mcp.test(spec);
        if (!t.ok()) { Http.json(ex, 400, err("test failed: " + t.error())); return; }
        try {
            McpServerSpec added = mcp.add(spec);
            Http.json(ex, 201, json.toJson(Map.of("name", added.name(), "transport", added.transport())));
        } catch (Exception e) {
            Http.json(ex, 409, err(e.getMessage()));
        }
    }

    private void remove(HttpExchange ex, McpManager mcp, String name) throws IOException {
        if (mcp.findByName(name).isEmpty()) { Http.json(ex, 404, err("no such server: " + name)); return; }
        mcp.remove(name);
        Http.json(ex, 204, "");
    }

    private void test(HttpExchange ex, McpManager mcp, String name) throws IOException {
        Optional<McpServerSpec> spec = mcp.findByName(name);
        if (spec.isEmpty()) { Http.json(ex, 404, err("no such server: " + name)); return; }
        McpManager.TestResult t = mcp.test(spec.get());
        Http.json(ex, 200, json.toJson(Map.of(
                "ok", t.ok(), "toolCount", t.toolCount(), "error", t.error() == null ? "" : t.error())));
    }

    private void requireServer(HttpExchange ex, McpManager mcp, String name, java.util.function.Supplier<String> op)
            throws IOException {
        if (mcp.findByName(name).isEmpty()) { Http.json(ex, 404, err("no such server: " + name)); return; }
        String result = op.get();
        Http.json(ex, 200, json.toJson(Map.of("name", name, "result", result)));
    }

    private String err(String message) {
        return json.toJson(Map.of("error", message));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object e : l) out.add(String.valueOf(e));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strMap(Object o) {
        if (!(o instanceof Map<?, ?> m)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return out;
    }
}
