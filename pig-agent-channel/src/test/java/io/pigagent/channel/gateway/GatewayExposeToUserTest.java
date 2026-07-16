package io.pigagent.channel.gateway;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.SubagentExposedEvent;
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
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Gateway enhancement (part 3) — the {@code expose_to_user} subagent→user Channel bridge, made
 * functional and offline-testable. A parent turn (driven through the native gateway's
 * {@code ChatUiChannel} — the documented enabler for the bridge) spawns a subagent with
 * {@code expose_to_user=true}; the native gateway emits a {@link SubagentExposedEvent} carrying a
 * {@code subagentId}; the client then talks directly to that subagent via
 * {@link GatewayChannelKernel#sendToSubagent}, bypassing the parent. Fully offline — a scripted fake
 * model + {@code @TempDir}, mirroring {@code SubagentDelegationTest}.
 */
class GatewayExposeToUserTest {

    private static final String GENERAL_PURPOSE = "general-purpose";
    private static final String SPAWN_TASK = "EXPOSE_TASK research the topic";
    private static final String FOLLOWUP = "FOLLOWUP narrow the scope";

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static String lastUserText(List<Msg> msgs) {
        String last = "";
        for (Msg m : msgs) {
            if (m.getRole() == MsgRole.USER) {
                StringBuilder sb = new StringBuilder();
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof TextBlock t) {
                        sb.append(t.getText());
                    }
                }
                last = sb.toString();
            }
        }
        return last;
    }

    private static boolean anyUserContains(List<Msg> msgs, String needle) {
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

    private static String textOf(Msg m) {
        StringBuilder sb = new StringBuilder();
        if (m != null) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof TextBlock t) {
                    sb.append(t.getText());
                }
            }
        }
        return sb.toString();
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

    /**
     * Parent spawns general-purpose with expose_to_user=true; child answers its task, then answers the
     * user's direct follow-up. Routing is by the last user message so it is deterministic regardless of
     * whether the exposed child's session accumulates history.
     */
    static final class ExposeScriptModel implements Model {
        @Override public String getModelName() { return "fake-expose"; }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions o) {
            String last = lastUserText(messages);
            if (last.contains(FOLLOWUP)) {
                return Flux.just(text("CHILD_FOLLOWUP handled"));
            }
            if (anyUserContains(messages, SPAWN_TASK)) {
                return Flux.just(text("CHILD_EXPOSED answered"));
            }
            if (hasToolResult(messages)) {
                return Flux.just(text("PARENT_DONE"));
            }
            return Flux.just(toolCall("call-spawn", "agent_spawn", Map.of(
                    "agent_id", GENERAL_PURPOSE, "task", SPAWN_TASK, "expose_to_user", true)));
        }
    }

    @Test
    void exposedSubagentEmitsEventAndIsDirectlyAddressable(@TempDir Path ws) {
        PigAgent pig = PigAgent.builder()
                .name("orchestrator").sysPrompt("sp").model(new ExposeScriptModel())
                .toolkit(new Toolkit())
                .workspace(ws)
                .maxIters(8)
                .subagents(true)
                .build();
        GatewayChannelKernel kernel = GatewayChannelKernel.forAgent(pig.getHarnessAgent());

        // Parent turn through the native gateway → spawns + exposes a subagent.
        List<AgentEvent> events = kernel
                .sendStream(SendOptions.userId("u1"), "spawn and expose a researcher")
                .collectList().block();

        // The gateway emitted a SubagentExposedEvent carrying a usable subagent id.
        assertThat(events).isNotNull();
        SubagentExposedEvent exposed = events.stream()
                .filter(e -> e instanceof SubagentExposedEvent)
                .map(e -> (SubagentExposedEvent) e)
                .findFirst().orElse(null);
        assertThat(exposed).as("expose_to_user=true emits a SubagentExposedEvent onto the stream").isNotNull();
        assertThat(exposed.getSubagentId()).as("the event carries a subagent handle").isNotBlank();

        // The client can now talk to the exposed subagent directly (bypassing the parent).
        Msg reply = kernel.sendToSubagent(exposed.getSubagentId(), FOLLOWUP).block();
        assertThat(textOf(reply)).as("the exposed subagent answered a direct follow-up")
                .contains("CHILD_FOLLOWUP");
        pig.close();
    }
}
