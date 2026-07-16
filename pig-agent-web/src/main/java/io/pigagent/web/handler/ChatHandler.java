package io.pigagent.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.pigagent.web.Http;
import io.pigagent.web.WebContext;
import io.pigagent.web.WebJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Streaming chat over SSE — the Web analogue of the REPL's chat turn, delegating straight to
 * {@code kernel.chat(agentId, msg)}. The typed {@link AgentEvent} stream (av2 Phase 4) is aggregated
 * the same way as the CLI renderer — answer text from {@code TextBlockDeltaEvent}, tool output from
 * {@code ToolResultEndEvent} (DENIED shown), reasoning from {@code ModelCallStartEvent} — and each is
 * pushed as a {@code data:} frame {@code {type,text}} (type = reasoning|tool|answer), then a final
 * {@code {type:"done"}}.
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

        Map<String, StringBuilder> toolResults = new LinkedHashMap<>();
        Disposable sub = ctx.agentKernel().chat(agentId, userMsg).subscribe(
                event -> onEvent(event, os, lock, toolResults, done),
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

    /** Map one typed {@link AgentEvent} to an SSE chat frame (mirrors the CLI renderer's aggregation). */
    private void onEvent(AgentEvent event, OutputStream os, Object lock,
                         Map<String, StringBuilder> toolResults, CountDownLatch done) {
        if (event instanceof TextBlockDeltaEvent d) {
            write(os, lock, json.chatFrame("answer", d.getDelta()), done);
        } else if (event instanceof ModelCallStartEvent || event instanceof ThinkingBlockStartEvent) {
            write(os, lock, json.chatFrame("reasoning", ""), done);
        } else if (event instanceof ToolResultTextDeltaEvent d) {
            toolResults.computeIfAbsent(key(d.getToolCallId()), k -> new StringBuilder()).append(d.getDelta());
        } else if (event instanceof ToolResultEndEvent end) {
            write(os, lock, json.chatFrame("tool", toolSummary(end, toolResults)), done);
        } else if (event instanceof AllToolsDeniedEvent) {
            write(os, lock, json.chatFrame("tool", "all tool calls denied by permission policy"), done);
        }
    }

    private static String key(String toolCallId) {
        return toolCallId == null ? "" : toolCallId;
    }

    private static String toolSummary(ToolResultEndEvent end, Map<String, StringBuilder> toolResults) {
        StringBuilder acc = toolResults.get(key(end.getToolCallId()));
        String text = acc == null ? "" : acc.toString();
        String name = end.getToolCallName() == null ? "tool" : end.getToolCallName();
        if (end.getState() == ToolResultState.DENIED) {
            return name + ": " + (text.isBlank() ? "denied by permission policy" : text);
        }
        return text.isBlank() ? name : name + ": " + text;
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
