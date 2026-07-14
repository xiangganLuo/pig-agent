package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Streaming chat over SSE — the Web analogue of the REPL's chat turn, delegating straight to
 * {@code kernel.chat(agentId, msg)}. Each agent {@link Event} is pushed as a {@code data:} frame
 * {@code {type,text}} (type = reasoning|tool|answer), then a final {@code {type:"done"}}.
 *
 * <p>{@code POST /api/chat} body {@code {message, agentId?}}. Uses POST (bodies can be long) so the
 * browser reads it with {@code fetch()} streaming, not {@code EventSource}. One turn holds one
 * server thread; {@code ReActAgent} is single-flight so concurrent turns would collide — acceptable
 * for a single-user local console.
 *
 * <p>First version: pure kernel adapter. It does NOT mirror the REPL's per-turn session hooks
 * (noteUserMessage → maybeCompress → saveCurrent); the streamed turn still lands in the active
 * agent's in-memory conversation (shared with the REPL) and is persisted on process shutdown.
 */
public final class ChatHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);

    private final WebContext ctx;
    private final WebJson json;

    public ChatHandler(WebContext ctx, WebJson json) {
        this.ctx = ctx;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) {
                Http.json(ex, 405, json.toJson(Map.of("error", "method not allowed")));
                return;
            }
            Map<String, Object> req = json.parse(Http.body(ex));
            String message = Http.str(req.get("message"));
            if (message.isBlank()) {
                Http.json(ex, 400, json.toJson(Map.of("error", "message is required")));
                return;
            }
            String agentId = Http.str(req.get("agentId"));
            if (agentId.isBlank()) {
                agentId = ctx.agentKernel().activeId();
            }
            stream(ex, agentId, message);
        } catch (Exception e) {
            log.warn("chat turn failed: {}", e.getMessage(), e);
            // If headers are already sent (streaming started) this best-effort JSON is ignored.
            try {
                Http.json(ex, 500, json.toJson(Map.of("error", String.valueOf(e.getMessage()))));
            } catch (Exception ignore) {
                // stream already committed
            }
        } finally {
            ex.close();
        }
    }

    private void stream(HttpExchange ex, String agentId, String message) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        Object lock = new Object();
        CountDownLatch done = new CountDownLatch(1);

        Msg userMsg = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(message).build()).build();

        Disposable sub = ctx.agentKernel().chat(agentId, userMsg).subscribe(
                event -> {
                    String type = frameType(event.getType());
                    if (type != null) {
                        write(os, lock, json.chatFrame(type, text(event)), done);
                    }
                },
                err -> {
                    write(os, lock, json.chatFrame("error", String.valueOf(err.getMessage())), done);
                    done.countDown();
                },
                () -> {
                    write(os, lock, json.chatFrame("done", ""), done);
                    done.countDown();
                });

        try {
            done.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            sub.dispose();
        }
    }

    private static String frameType(EventType type) {
        if (type == EventType.REASONING) return "reasoning";
        if (type == EventType.TOOL_RESULT) return "tool";
        if (type == EventType.AGENT_RESULT) return "answer";
        return null; // HINT / SUMMARY / ALL are not surfaced
    }

    private static String text(Event event) {
        return event.getMessage() == null ? "" : event.getMessage().getTextContent();
    }

    private void write(OutputStream os, Object lock, Map<String, Object> frame, CountDownLatch done) {
        try {
            synchronized (lock) {
                os.write(("data: " + json.toJson(frame) + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException e) {
            done.countDown(); // client disconnected
        }
    }
}
