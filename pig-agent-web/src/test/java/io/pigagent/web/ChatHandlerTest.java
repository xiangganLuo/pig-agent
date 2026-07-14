package io.pigagent.web;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@code POST /api/chat} streams the agent's {@link Event}s as SSE {@code data:} frames and
 * finishes with a {@code done} frame — using a mocked {@link AgentKernel} so no real model runs.
 */
class ChatHandlerTest {

    private WebConsole console;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.activeId()).thenReturn("default");
        Event reasoning = event(EventType.REASONING, "thinking…");
        Event answer = event(EventType.AGENT_RESULT, "hello world");
        when(kernel.chat(any(), any(Msg.class))).thenReturn(Flux.just(reasoning, answer));

        console = new WebConsole(WebContext.ofKernel(kernel), "127.0.0.1", 0);
        console.start();
        base = "http://127.0.0.1:" + console.boundPort();
    }

    @AfterEach
    void tearDown() {
        if (console != null) console.stop();
    }

    private static Event event(EventType type, String text) {
        Event e = mock(Event.class);
        Msg m = mock(Msg.class);
        when(m.getTextContent()).thenReturn(text);
        when(e.getType()).thenReturn(type);
        when(e.getMessage()).thenReturn(m);
        return e;
    }

    @Test
    void chat_streamsFramesThenDone() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        String body = r.body();
        assertThat(body).contains("\"type\":\"reasoning\"").contains("thinking");
        assertThat(body).contains("\"type\":\"answer\"").contains("hello world");
        assertThat(body).contains("\"type\":\"done\"");
    }

    @Test
    void chat_missingMessage_is400() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(400);
    }
}
