package io.pigagent.core.agent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * subagent-online-switch — the load-bearing SPIKE, run offline against real AgentScope 2.0 artifacts
 * (scripted fake models + {@code @TempDir}). It proves the two gate questions:
 *
 * <ol>
 *   <li><b>No regression (the critical risk):</b> a subagent-enabled agent eagerly binds the native
 *       gateway at build ({@code harness.gateway()} wires the {@code expose_to_user} bridge), yet the
 *       normal {@code stream(...)} chat turn still streams identically — the gateway is a lazy,
 *       SEPARATE object the direct stream never routes through, so no fair-queuing / routing-engine
 *       regression.</li>
 *   <li><b>End-to-end exposure + switch:</b> the parent spawns a {@code general-purpose} child with
 *       {@code expose_to_user=true}; a {@link SubagentExposedEvent} carrying a {@code subagentId} is
 *       emitted onto the parent's stream; then {@link PigAgent#streamSubagent} routes a follow-up
 *       message directly to that child (via the gateway) and the child answers.</li>
 * </ol>
 *
 * Real-model behavior (a live model actually deciding to expose + a live switch) is deferred to a
 * {@code *IT}; this offline PoC exercises the full wiring with scripted models.
 */
class SubagentExposeSpikeTest {

    private static final String GENERAL_PURPOSE = "general-purpose";
    private static final String CHILD_MARK = "CHILD_MSG";

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static ChatResponse text(String s) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(s).build())).finishReason("stop").build();
    }

    private static ChatResponse toolCall(String id, String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder()
                        .id(id).name(name).input(input).content(json(input)).build()))
                .finishReason("tool_calls").build();
    }

    private static String json(Map<String, Object> input) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static boolean userMessageContains(List<Msg> msgs, String needle) {
        for (Msg m : msgs) {
            if (m.getRole() == MsgRole.USER) {
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof TextBlock t && t.getText().contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasToolResult(List<Msg> msgs) {
        for (Msg m : msgs) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof ToolResultBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Always answers "ok" — used by the no-regression check (never spawns). */
    static final class TextModel implements Model {
        @Override public String getModelName() { return "fake-text"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(text("ok"));
        }
    }

    // ---- Gate 1: binding the gateway does NOT regress the interactive stream path ----------------

    @Test
    void subagentEnabledAgent_bindsGateway_and_directStreamStillWorks(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("main").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit())
                .workspace(ws)
                .subagents(true) // eagerly wires the expose_to_user gateway at build
                .build();

        // The gateway was eagerly initialized (the expose bridge is ready)…
        assertThat(agent.getHarnessAgent().gateway())
                .as("subagents-enabled build eagerly initializes the native gateway").isNotNull();

        // …yet the normal chat turn still streams identically (no fair-queue/routing regression).
        String out = collectText(agent.stream(user("hi")));
        assertThat(out).as("the direct stream path is unaffected by the bound gateway").contains("ok");

        // And a second turn is likewise unaffected (idempotent gateway, direct path bypasses it).
        assertThat(collectText(agent.stream(user("again")))).contains("ok");
        agent.close();
    }

    // ---- Gate 2: expose a subagent, capture its id, and switch into it via the gateway ----------

    /** Parent spawns general-purpose with expose_to_user=true; the child answers on a direct switch. */
    static final class ExposeScriptModel implements Model {
        @Override public String getModelName() { return "fake-expose"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (userMessageContains(messages, CHILD_MARK)) {
                return Flux.just(text("CHILD_REPLY from the exposed subagent"));
            }
            if (hasToolResult(messages)) {
                return Flux.just(text("PARENT_DONE"));
            }
            // First parent step: expose a general-purpose subagent (no task → just register + accept).
            return Flux.just(toolCall("call-spawn", "agent_spawn",
                    Map.of("agent_id", GENERAL_PURPOSE, "expose_to_user", true, "label", "helper")));
        }
    }

    @Test
    void exposedSubagentIsAddressable_viaStreamSubagent(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("main").sysPrompt("sp").model(new ExposeScriptModel())
                .toolkit(new Toolkit())
                .workspace(ws)
                .maxIters(8)
                .subagents(true)
                .build();

        // Run a parent turn; capture the SubagentExposedEvent it emits.
        List<AgentEvent> events = agent.stream(user("please spawn a helper")).collectList().block();
        assertThat(events).isNotNull();
        SubagentExposedEvent exposed = events.stream()
                .filter(e -> e instanceof SubagentExposedEvent)
                .map(e -> (SubagentExposedEvent) e)
                .findFirst().orElse(null);

        assertThat(exposed).as("expose_to_user=true emits a SubagentExposedEvent onto the parent stream")
                .isNotNull();
        assertThat(exposed.getSubagentId()).as("the event carries an addressable subagentId").isNotBlank();
        assertThat(exposed.getLabel()).isEqualTo("helper");

        // Now switch: talk to the exposed subagent DIRECTLY (bypassing the parent) via the gateway.
        String childOut = collectText(agent.streamSubagent(exposed.getSubagentId(), user(CHILD_MARK)));
        assertThat(childOut).as("the exposed subagent is reachable and answers the direct message")
                .contains("CHILD_REPLY");
        agent.close();
    }

    @Test
    void streamSubagent_unknownId_isErrorFlux_notException(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("main").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit())
                .workspace(ws)
                .subagents(true)
                .build();

        assertThat(agent.streamSubagent(null, user("x")).collectList())
                .satisfies(mono -> {
                    try {
                        mono.block();
                    } catch (RuntimeException expected) {
                        assertThat(expected).hasMessageContaining("subagentId");
                        return;
                    }
                    org.junit.jupiter.api.Assertions.fail("expected an error for a null subagentId");
                });
        agent.close();
    }

    private static String collectText(Flux<AgentEvent> stream) {
        StringBuilder sb = new StringBuilder();
        List<AgentEvent> events = stream.collectList().block();
        if (events == null) {
            return "";
        }
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) {
                sb.append(d.getDelta());
            }
        }
        return sb.toString();
    }
}
