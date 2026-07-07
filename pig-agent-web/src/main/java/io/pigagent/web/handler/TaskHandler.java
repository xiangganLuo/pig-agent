package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.task.Task;
import io.pigagent.task.TaskManager;
import io.pigagent.task.TaskStatus;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST over {@link TaskManager} — structured task CRUD (the CLI {@code /tasks} delegates to the
 * agent in natural language; the Web console drives the manager directly for a real task UI).
 *
 * <pre>
 *   GET    /api/tasks         list
 *   POST   /api/tasks         create      body {title, description}
 *   PUT    /api/tasks/{id}    set status  body {status}
 *   DELETE /api/tasks/{id}    delete
 * </pre>
 */
public final class TaskHandler implements HttpHandler {

    private static final String BASE = "/api/tasks";

    private final WebContext ctx;
    private final WebJson json;

    public TaskHandler(WebContext ctx, WebJson json) {
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
        TaskManager tm = ctx.taskManager();
        String method = ex.getRequestMethod();
        String[] p = Http.segments(ex, BASE);

        if (p.length == 0) {
            if (method.equals("GET")) { list(ex, tm); return; }
            if (method.equals("POST")) { create(ex, tm); return; }
            Http.json(ex, 405, err("method not allowed")); return;
        }
        String id = p[0];
        switch (method) {
            case "PUT" -> updateStatus(ex, tm, id);
            case "DELETE" -> delete(ex, tm, id);
            default -> Http.json(ex, 405, err("method not allowed"));
        }
    }

    private void list(HttpExchange ex, TaskManager tm) throws IOException {
        List<Map<String, Object>> body = tm.getAllTasks().stream()
                .map(json::task).collect(Collectors.toList());
        Http.json(ex, 200, json.toJson(body));
    }

    private void create(HttpExchange ex, TaskManager tm) throws IOException {
        Map<String, Object> req = json.parse(Http.body(ex));
        String title = Http.str(req.get("title"));
        if (title.isBlank()) { Http.json(ex, 400, err("title is required")); return; }
        Task t = tm.createTask(title, Http.str(req.get("description")));
        Http.json(ex, 201, json.toJson(json.task(t)));
    }

    private void updateStatus(HttpExchange ex, TaskManager tm, String id) throws IOException {
        String raw = Http.str(json.parse(Http.body(ex)).get("status"));
        TaskStatus status;
        try {
            status = TaskStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Http.json(ex, 400, err("invalid status: " + raw)); return;
        }
        if (tm.getTask(id).isEmpty()) { Http.json(ex, 404, err("no such task: " + id)); return; }
        Http.json(ex, 200, json.toJson(json.task(tm.updateStatus(id, status))));
    }

    private void delete(HttpExchange ex, TaskManager tm, String id) throws IOException {
        if (tm.getTask(id).isEmpty()) { Http.json(ex, 404, err("no such task: " + id)); return; }
        tm.deleteTask(id);
        Http.json(ex, 204, "");
    }

    private String err(String message) {
        return json.toJson(Map.of("error", message));
    }
}
