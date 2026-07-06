package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.agent.runner.AgentReport;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST adapter over {@link AgentKernel} — the only bridge between HTTP and the kernel. Every route
 * delegates straight to a façade method; there is zero business logic here (D1: Web is an adapter).
 *
 * <pre>
 *   GET    /api/agents            list
 *   GET    /api/agents/{id}       get one
 *   POST   /api/agents            create        body {id,name,modelId?}
 *   PUT    /api/agents/{id}       update model  body {modelId}
 *   DELETE /api/agents/{id}       delete
 *   POST   /api/agents/{id}/use   switch active
 *   POST   /api/agents/{id}/run   run mandate now
 * </pre>
 */
public final class AgentApiHandler implements HttpHandler {

    private static final String BASE = "/api/agents";

    private final AgentKernel kernel;
    private final WebJson json;

    public AgentApiHandler(AgentKernel kernel, WebJson json) {
        this.kernel = kernel;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (Exception e) {
            send(ex, 500, json.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String rest = path.length() > BASE.length() ? path.substring(BASE.length()) : "";
        // rest is "" | "/{id}" | "/{id}/use" | "/{id}/run"
        String[] parts = rest.isEmpty() ? new String[0] : rest.substring(1).split("/");

        if (parts.length == 0) {
            if (method.equals("GET")) { listAgents(ex); return; }
            if (method.equals("POST")) { createAgent(ex); return; }
            send(ex, 405, err("method not allowed")); return;
        }

        String id = parts[0];
        String sub = parts.length > 1 ? parts[1] : null;

        if (sub == null) {
            switch (method) {
                case "GET" -> getAgent(ex, id);
                case "PUT" -> updateAgent(ex, id);
                case "DELETE" -> deleteAgent(ex, id);
                default -> send(ex, 405, err("method not allowed"));
            }
            return;
        }
        if (method.equals("POST") && sub.equals("use")) { useAgent(ex, id); return; }
        if (method.equals("POST") && sub.equals("run")) { runAgent(ex, id); return; }
        send(ex, 404, err("no such route"));
    }

    private void listAgents(HttpExchange ex) throws IOException {
        String active = kernel.activeId();
        List<Map<String, Object>> body = kernel.listAgents().stream()
                .map(i -> json.agent(i, active))
                .collect(Collectors.toList());
        send(ex, 200, json.toJson(body));
    }

    private void getAgent(HttpExchange ex, String id) throws IOException {
        Optional<AgentInstance> inst = kernel.getAgent(id);
        if (inst.isEmpty()) { send(ex, 404, err("no such agent: " + id)); return; }
        send(ex, 200, json.toJson(json.agent(inst.get(), kernel.activeId())));
    }

    private void createAgent(HttpExchange ex) throws IOException {
        Map<String, Object> req = json.parse(body(ex));
        String id = str(req.get("id"));
        String name = str(req.get("name"));
        if (id.isBlank() || name.isBlank()) { send(ex, 400, err("id and name are required")); return; }
        if (kernel.getAgent(id).isPresent()) { send(ex, 409, err("agent id already exists: " + id)); return; }
        String modelId = str(req.get("modelId"));
        AgentSpec spec = AgentSpec.create(id, name).withModelId(modelId.isBlank() ? null : modelId);
        AgentInstance created = kernel.createAgent(spec);
        send(ex, 201, json.toJson(json.agent(created, kernel.activeId())));
    }

    private void updateAgent(HttpExchange ex, String id) throws IOException {
        Optional<AgentInstance> inst = kernel.getAgent(id);
        if (inst.isEmpty()) { send(ex, 404, err("no such agent: " + id)); return; }
        Map<String, Object> req = json.parse(body(ex));
        String modelId = str(req.get("modelId"));
        AgentInstance updated = kernel.updateAgent(inst.get().spec().withModelId(modelId.isBlank() ? null : modelId));
        send(ex, 200, json.toJson(json.agent(updated, kernel.activeId())));
    }

    private void deleteAgent(HttpExchange ex, String id) throws IOException {
        if (kernel.getAgent(id).isEmpty()) { send(ex, 404, err("no such agent: " + id)); return; }
        kernel.deleteAgent(id);
        send(ex, 204, "");
    }

    private void useAgent(HttpExchange ex, String id) throws IOException {
        if (!kernel.useAgent(id)) { send(ex, 404, err("no such agent: " + id)); return; }
        send(ex, 200, json.toJson(Map.of("active", id)));
    }

    private void runAgent(HttpExchange ex, String id) throws IOException {
        if (kernel.getAgent(id).isEmpty()) { send(ex, 404, err("no such agent: " + id)); return; }
        Optional<AgentReport> report = kernel.runNow(id);
        if (report.isEmpty()) { send(ex, 409, err("skipped: already running or no mandate/runner")); return; }
        send(ex, 200, json.toJson(Map.of("outcome", String.valueOf(report.get().outcome()))));
    }

    private String err(String message) {
        return json.toJson(Map.of("error", message));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
}
