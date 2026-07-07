package io.pigagent.web;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.task.FileSystemTaskRepository;
import io.pigagent.task.TaskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the non-chat REST handlers over real HTTP against real managers (temp dirs), covering the
 * delegation for permission (ConfigurationManager), tasks (TaskManager) and models (ModelManager).
 */
class RestHandlersTest {

    private WebConsole console;
    private HttpClient client;
    private String base;

    private void startWith(WebContext ctx) throws IOException {
        console = new WebConsole(ctx, "127.0.0.1", 0);
        console.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + console.boundPort();
    }

    @AfterEach
    void tearDown() {
        if (console != null) console.stop();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json").method(method, pub).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private WebContext ctx(ConfigurationManager cfg, TaskManager tm, ModelManager mm) {
        return new WebContext(null, mm, null, null, null, tm, null, cfg, null, null);
    }

    @Test
    void permission_statusAndModeChange(@TempDir Path dir) throws Exception {
        ConfigurationManager cfg = new ConfigurationManager(dir.resolve("application.yaml"));
        startWith(ctx(cfg, null, null));

        assertThat(send("GET", "/api/permission", null).body()).contains("\"mode\"");

        HttpResponse<String> set = send("POST", "/api/permission/mode", "{\"mode\":\"auto\"}");
        assertThat(set.statusCode()).isEqualTo(200);
        assertThat(cfg.getConfig().getPermissions().resolveMode().name()).isEqualTo("AUTO");

        assertThat(send("POST", "/api/permission/mode", "{\"mode\":\"garbage\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void permission_allowAndReset(@TempDir Path dir) throws Exception {
        ConfigurationManager cfg = new ConfigurationManager(dir.resolve("application.yaml"));
        startWith(ctx(cfg, null, null));

        send("POST", "/api/permission/allow", "{\"name\":\"writeFile\",\"tool\":true}");
        assertThat(cfg.getConfig().getPermissions().getAllowlist().getTools()).contains("writeFile");

        send("POST", "/api/permission/reset", "{}");
        assertThat(cfg.getConfig().getPermissions().getAllowlist().getTools()).isEmpty();
    }

    @Test
    void tasks_createListStatusDelete(@TempDir Path dir) throws Exception {
        TaskManager tm = new TaskManager(new FileSystemTaskRepository(dir.resolve("tasks")));
        startWith(ctx(null, tm, null));

        HttpResponse<String> created = send("POST", "/api/tasks", "{\"title\":\"Write report\",\"description\":\"x\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("Write report").contains("TODO");

        assertThat(send("GET", "/api/tasks", null).body()).contains("Write report");

        String id = tm.getAllTasks().get(0).id();
        assertThat(send("PUT", "/api/tasks/" + id, "{\"status\":\"DONE\"}").body()).contains("DONE");
        assertThat(send("DELETE", "/api/tasks/" + id, null).statusCode()).isEqualTo(204);
        assertThat(tm.getAllTasks()).isEmpty();
    }

    @Test
    void models_listEmpty(@TempDir Path dir) throws Exception {
        ModelManager mm = new ModelManager(new ProtocolRegistry(), new JsonModelStore(dir.resolve("models.json")));
        startWith(ctx(null, null, mm));
        HttpResponse<String> r = send("GET", "/api/models", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("[]");
    }
}
