package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST over {@link ModelManager} — mirrors {@code /model} (list|add|switch|edit|delete). Switching
 * a model goes through the session layer ({@code bindCurrentSessionModel} + {@code reactivateCurrent})
 * exactly like the CLI, so the live agent is rebuilt. API keys are never returned.
 */
public final class ModelHandler implements HttpHandler {

    private static final String BASE = "/api/models";

    private final WebContext ctx;
    private final WebJson json;

    public ModelHandler(WebContext ctx, WebJson json) {
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
        ModelManager mm = ctx.modelManager();
        String method = ex.getRequestMethod();
        String[] p = Http.segments(ex, BASE);

        if (p.length == 0) {
            if (method.equals("GET")) { list(ex, mm); return; }
            if (method.equals("POST")) { add(ex, mm); return; }
            Http.json(ex, 405, err("method not allowed")); return;
        }
        String id = p[0];
        String sub = p.length > 1 ? p[1] : null;
        if (sub == null) {
            switch (method) {
                case "PUT" -> edit(ex, mm, id);
                case "DELETE" -> delete(ex, mm, id);
                default -> Http.json(ex, 405, err("method not allowed"));
            }
            return;
        }
        if (method.equals("POST") && sub.equals("use")) { use(ex, mm, id); return; }
        if (method.equals("POST") && sub.equals("test")) { test(ex, mm, id); return; }
        Http.json(ex, 404, err("no such route"));
    }

    private void list(HttpExchange ex, ModelManager mm) throws IOException {
        String def = mm.getDefaultId();
        String cur = mm.getCurrentModelId();
        List<Map<String, Object>> body = mm.list().stream()
                .map(m -> json.model(m, def, cur)).collect(Collectors.toList());
        Http.json(ex, 200, json.toJson(body));
    }

    private void add(HttpExchange ex, ModelManager mm) throws IOException {
        Map<String, Object> req = json.parse(Http.body(ex));
        String protocolId = Http.str(req.get("protocolId"));
        String modelName = Http.str(req.get("modelName"));
        if (protocolId.isBlank() || modelName.isBlank()) {
            Http.json(ex, 400, err("protocolId and modelName are required")); return;
        }
        String apiKey = emptyToNull(Http.str(req.get("apiKey")));
        String baseUrl = emptyToNull(Http.str(req.get("baseUrl")));
        StoredModel m = StoredModel.create(protocolId, apiKey, baseUrl, modelName);
        ModelManager.TestResult t = mm.test(m);
        if (!t.ok()) { Http.json(ex, 400, err("test failed: " + t.error())); return; }
        StoredModel saved = mm.add(m);
        Http.json(ex, 201, json.toJson(json.model(saved, mm.getDefaultId(), mm.getCurrentModelId())));
    }

    private void edit(HttpExchange ex, ModelManager mm, String id) throws IOException {
        Optional<StoredModel> found = mm.findById(id);
        if (found.isEmpty()) { Http.json(ex, 404, err("no such model: " + id)); return; }
        Map<String, Object> req = json.parse(Http.body(ex));
        StoredModel m = found.get();
        if (!Http.str(req.get("apiKey")).isBlank()) m = m.withApiKey(Http.str(req.get("apiKey")));
        if (!Http.str(req.get("baseUrl")).isBlank()) m = m.withBaseUrl(Http.str(req.get("baseUrl")));
        if (!Http.str(req.get("modelName")).isBlank()) m = m.withModelName(Http.str(req.get("modelName")));
        StoredModel updated = mm.edit(m);
        Http.json(ex, 200, json.toJson(json.model(updated, mm.getDefaultId(), mm.getCurrentModelId())));
    }

    private void delete(HttpExchange ex, ModelManager mm, String id) throws IOException {
        if (mm.findById(id).isEmpty()) { Http.json(ex, 404, err("no such model: " + id)); return; }
        if (id.equals(mm.getCurrentModelId())) {
            Http.json(ex, 409, err("cannot delete the model currently in use")); return;
        }
        mm.delete(id);
        Http.json(ex, 204, "");
    }

    private void use(HttpExchange ex, ModelManager mm, String id) throws IOException {
        Optional<StoredModel> found = mm.findById(id);
        if (found.isEmpty()) { Http.json(ex, 404, err("no such model: " + id)); return; }
        ModelManager.TestResult t = mm.test(found.get());
        if (!t.ok()) { Http.json(ex, 400, err("model unavailable: " + t.error())); return; }
        boolean global = Boolean.TRUE.equals(json.parse(Http.body(ex)).get("global"));
        if (global) {
            mm.setDefault(id);
            ctx.sessionManager().bindCurrentSessionModel(null);
        } else {
            ctx.sessionManager().bindCurrentSessionModel(id);
        }
        ctx.sessionManager().reactivateCurrent();
        Http.json(ex, 200, json.toJson(Map.of("current", id, "global", global)));
    }

    private void test(HttpExchange ex, ModelManager mm, String id) throws IOException {
        Optional<StoredModel> found = mm.findById(id);
        if (found.isEmpty()) { Http.json(ex, 404, err("no such model: " + id)); return; }
        ModelManager.TestResult t = mm.test(found.get());
        Http.json(ex, 200, json.toJson(Map.of("ok", t.ok(), "error", t.error() == null ? "" : t.error())));
    }

    private String err(String message) {
        return json.toJson(Map.of("error", message));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
