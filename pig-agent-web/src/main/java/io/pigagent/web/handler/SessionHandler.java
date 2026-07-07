package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.pigagent.session.Session;
import io.pigagent.session.SessionManager;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST over {@link SessionManager} — mirrors {@code /session} (list|new|fork|switch|rename|clear|
 * delete). {@code rename}/{@code clear} act on the current session, so they are fixed path
 * segments (not ids).
 */
public final class SessionHandler implements HttpHandler {

    private static final String BASE = "/api/sessions";

    private final WebContext ctx;
    private final WebJson json;

    public SessionHandler(WebContext ctx, WebJson json) {
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
        SessionManager sm = ctx.sessionManager();
        String method = ex.getRequestMethod();
        String[] p = Http.segments(ex, BASE);

        if (p.length == 0) {
            if (method.equals("GET")) { list(ex, sm); return; }
            if (method.equals("POST")) { create(ex, sm); return; }
            Http.json(ex, 405, err("method not allowed")); return;
        }
        if (p.length == 1) {
            if (method.equals("POST") && p[0].equals("rename")) { rename(ex, sm); return; }
            if (method.equals("POST") && p[0].equals("clear")) { clear(ex, sm); return; }
            if (method.equals("DELETE")) { delete(ex, sm, p[0]); return; }
            Http.json(ex, 405, err("method not allowed")); return;
        }
        if (method.equals("POST") && p[1].equals("use")) { use(ex, sm, p[0]); return; }
        Http.json(ex, 404, err("no such route"));
    }

    private void list(HttpExchange ex, SessionManager sm) throws IOException {
        String cur = sm.getCurrentSessionId();
        List<Map<String, Object>> body = sm.list().stream()
                .map(s -> json.session(s, cur)).collect(Collectors.toList());
        Http.json(ex, 200, json.toJson(body));
    }

    private void create(HttpExchange ex, SessionManager sm) throws IOException {
        Map<String, Object> req = json.parse(Http.body(ex));
        String name = Http.str(req.get("name"));
        boolean fork = Boolean.TRUE.equals(req.get("fork"));
        Session s = fork ? sm.fork(name.isBlank() ? null : name)
                : sm.createBlank(name.isBlank() ? null : name);
        Http.json(ex, 201, json.toJson(json.session(s, sm.getCurrentSessionId())));
    }

    private void use(HttpExchange ex, SessionManager sm, String id) throws IOException {
        if (sm.list().stream().noneMatch(s -> s.id().equals(id))) {
            Http.json(ex, 404, err("no such session: " + id)); return;
        }
        sm.activate(id);
        Http.json(ex, 200, json.toJson(Map.of("current", id)));
    }

    private void rename(HttpExchange ex, SessionManager sm) throws IOException {
        String name = Http.str(json.parse(Http.body(ex)).get("name"));
        if (name.isBlank()) { Http.json(ex, 400, err("name is required")); return; }
        sm.rename(name);
        Http.json(ex, 200, json.toJson(Map.of("name", name)));
    }

    private void clear(HttpExchange ex, SessionManager sm) throws IOException {
        boolean withMemory = Boolean.TRUE.equals(json.parse(Http.body(ex)).get("withMemory"));
        sm.clearConversation(withMemory);
        Http.json(ex, 200, json.toJson(Map.of("cleared", true, "withMemory", withMemory)));
    }

    private void delete(HttpExchange ex, SessionManager sm, String id) throws IOException {
        if (sm.list().stream().noneMatch(s -> s.id().equals(id))) {
            Http.json(ex, 404, err("no such session: " + id)); return;
        }
        sm.delete(List.of(id));
        Http.json(ex, 204, "");
    }

    private String err(String message) {
        return json.toJson(Map.of("error", message));
    }
}
