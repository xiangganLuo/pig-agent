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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@code /api/events} pushes {@link io.pigagent.core.agent.kernel.KernelEvent}s from
 * {@code subscribeEvents()} as SSE {@code data:} frames. A background emitter probes repeatedly so
 * the test is immune to the subscribe/emit ordering race in the hot multicast stream.
 */
class EventStreamHandlerTest {

    private AgentKernel kernel;
    private WebConsole console;
    private String base;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        AgentInstanceFactory factory = new AgentInstanceFactory(
                spec -> mock(Model.class), spec -> new Toolkit(), spec -> List.of(), null);
        AgentInstance def = factory.create(AgentSpec.create("default", "Default"));
        AgentRegistry registry = new AgentRegistry(new AgentHolder(def.agent()));
        registry.register(def);
        kernel = new AgentKernel(registry, new AgentSpecRepository(dir), factory, null);
        console = new WebConsole(WebContext.ofKernel(kernel), "127.0.0.1", 0);
        console.start();
        base = "http://127.0.0.1:" + console.boundPort();
    }

    @AfterEach
    void tearDown() {
        if (console != null) console.stop();
    }

    @Test
    void eventsEndpoint_streamsKernelEventsAsSse() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> resp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/events"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");

        // Keep emitting until the SSE reader observes one (covers the hot-stream subscribe race).
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread emitter = new Thread(() -> {
            while (!stop.get()) {
                kernel.noteChannelChat("probe");
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        emitter.setDaemon(true);
        emitter.start();

        String dataLine = null;
        Iterator<String> it = resp.body().iterator();
        int guard = 0;
        while (it.hasNext() && guard++ < 500) {
            String line = it.next();
            if (line.startsWith("data:") && line.contains("CHAT_STARTED")) {
                dataLine = line;
                break;
            }
        }
        stop.set(true);

        assertThat(dataLine).isNotNull();
        assertThat(dataLine).contains("channel:probe");
    }
}
