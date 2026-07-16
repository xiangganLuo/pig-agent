package io.pigagent.web;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
 * Verifies {@code POST /api/chat} streams the agent's typed {@link AgentEvent}s as SSE {@code data:}
 * frames and finishes with a {@code done} frame — using a mocked {@link AgentKernel} so no real model
 * runs. av2 Phase 4: reasoning frames come from {@link ModelCallStartEvent}, answer text from
 * {@link TextBlockDeltaEvent}.
 */
class ChatHandlerTest {

    private WebConsole console;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.activeId()).thenReturn("default");
        AgentEvent reasoning = new ModelCallStartEvent("fake-model");
        AgentEvent answer = new TextBlockDeltaEvent("r1", "b1", "hello world");
        when(kernel.chat(any(), any(Msg.class))).thenReturn(Flux.just(reasoning, answer));

        console = new WebConsole(WebContext.ofKernel(kernel), "127.0.0.1", 0);
        console.start();
        base = "http://127.0.0.1:" + console.boundPort();
    }

    @AfterEach
    void tearDown() {
        if (console != null) console.stop();
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
        assertThat(body).contains("\"type\":\"reasoning\"");
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
