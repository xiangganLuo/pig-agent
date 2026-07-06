package io.pigagent.web;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.AgentSpecRepository;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Drives the console over real HTTP (loopback) against a real {@link AgentKernel}, exercising the
 * REST adapter end to end: list/create/get/use/delete + loopback-only bind + port release on stop.
 */
class WebConsoleTest {

    private AgentKernel kernel;
    private WebConsole console;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        AgentInstanceFactory factory = new AgentInstanceFactory(
                spec -> mock(Model.class), spec -> new Toolkit(), spec -> List.of(), null);
        AgentInstance def = factory.create(AgentSpec.create("default", "Default"));
        AgentRegistry registry = new AgentRegistry(new AgentHolder(def.agent()));
        registry.register(def);
        kernel = new AgentKernel(registry, new AgentSpecRepository(dir), factory, null);

        console = new WebConsole(kernel, "127.0.0.1", 0); // ephemeral port, loopback only
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
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, pub).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bindsToLoopbackOnly() {
        assertThat(console.host()).isEqualTo("127.0.0.1");
        assertThat(console.boundPort()).isGreaterThan(0);
    }

    @Test
    void listAgents_returnsDefault() throws Exception {
        HttpResponse<String> r = send("GET", "/api/agents", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("default");
    }

    @Test
    void createGetUseDelete_fullLifecycle() throws Exception {
        HttpResponse<String> created = send("POST", "/api/agents", "{\"id\":\"b\",\"name\":\"Beta\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("Beta");

        assertThat(send("GET", "/api/agents/b", null).statusCode()).isEqualTo(200);

        HttpResponse<String> used = send("POST", "/api/agents/b/use", null);
        assertThat(used.statusCode()).isEqualTo(200);
        assertThat(kernel.activeId()).isEqualTo("b");

        assertThat(send("DELETE", "/api/agents/b", null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/agents/b", null).statusCode()).isEqualTo(404);
    }

    @Test
    void createDuplicate_conflicts() throws Exception {
        send("POST", "/api/agents", "{\"id\":\"b\",\"name\":\"Beta\"}");
        HttpResponse<String> dup = send("POST", "/api/agents", "{\"id\":\"b\",\"name\":\"Beta2\"}");
        assertThat(dup.statusCode()).isEqualTo(409);
    }

    @Test
    void useUnknown_returns404() throws Exception {
        assertThat(send("POST", "/api/agents/nope/use", null).statusCode()).isEqualTo(404);
    }

    @Test
    void stop_releasesPort() throws Exception {
        int port = console.boundPort();
        console.stop();
        HttpClient c = HttpClient.newHttpClient();
        assertThatThrownBy(() -> c.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agents")).GET().build(),
                HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(ConnectException.class);
    }
}
