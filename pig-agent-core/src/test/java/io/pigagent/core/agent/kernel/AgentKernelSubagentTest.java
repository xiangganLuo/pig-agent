package io.pigagent.core.agent.kernel;

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
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.AgentSpecRepository;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code AgentKernel} subagent-online-switch façade: (1) tracking exposed subagents seen on the
 * stream ({@code noteSubagentExposed}/{@code listSubagents}/{@code subagentOutput}); (2) clearing them
 * on an agent switch (they belong to the previous agent's gateway); (3) routing a switched turn to the
 * active agent's exposed subagent via {@code chatWithSubagent}. Offline: pure tracking + a scripted
 * fake model for the real routing path.
 */
class AgentKernelSubagentTest {

    private AgentInstanceFactory dummyFactory() {
        return new AgentInstanceFactory(
                spec -> mock(Model.class), spec -> new Toolkit(), spec -> List.of(), null);
    }

    private AgentKernel kernelWith(AgentInstance active, Path dir) {
        AgentRegistry registry = new AgentRegistry(new AgentHolder(active.agent()));
        registry.register(active);
        return new AgentKernel(registry, new AgentSpecRepository(dir), dummyFactory(), null);
    }

    // ---- tracking ------------------------------------------------------------------------------

    @Test
    void note_list_and_lookupExposedSubagents(@TempDir Path dir) {
        AgentInstance def = new AgentInstance("default", AgentSpec.create("default", "D"), mock(PigAgent.class));
        AgentKernel kernel = kernelWith(def, dir);

        kernel.noteSubagentExposed("sub-1", "general-purpose", "helper");
        kernel.noteSubagentExposed("sub-2", "reviewer", null);
        kernel.noteSubagentExposed(null, "x", "ignored"); // null id ignored

        assertThat(kernel.listSubagents()).extracting(ExposedSubagent::id)
                .containsExactly("sub-1", "sub-2");
        assertThat(kernel.subagentOutput("sub-1")).get()
                .satisfies(s -> assertThat(s.display()).isEqualTo("helper"));
        assertThat(kernel.subagentOutput("sub-2")).get()
                .satisfies(s -> assertThat(s.display()).isEqualTo("reviewer")); // label null → type
        assertThat(kernel.subagentOutput("nope")).isEmpty();
    }

    @Test
    void exposedSubagents_clearedOnAgentSwitch(@TempDir Path dir) {
        AgentInstance def = new AgentInstance("default", AgentSpec.create("default", "D"), mock(PigAgent.class));
        AgentKernel kernel = kernelWith(def, dir);
        kernel.createAgent(AgentSpec.create("b", "Beta"));
        kernel.noteSubagentExposed("sub-1", "general-purpose", "helper");
        assertThat(kernel.listSubagents()).hasSize(1);

        boolean ok = kernel.useAgent("b");

        assertThat(ok).isTrue();
        assertThat(kernel.listSubagents())
                .as("exposed subagents belong to the previous agent's gateway → dropped on switch")
                .isEmpty();
    }

    // ---- routing (real gateway, scripted model) ------------------------------------------------

    @Test
    void chatWithSubagent_routesToActiveAgentsExposedSubagent(@TempDir Path dir, @TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("main").sysPrompt("sp").model(new ExposeModel())
                .toolkit(new Toolkit()).workspace(ws).maxIters(8).subagents(true).build();
        AgentInstance def = new AgentInstance("default", AgentSpec.create("default", "D"), agent);
        AgentKernel kernel = kernelWith(def, dir);

        // Expose a subagent by driving a normal chat turn through the kernel, capture its id.
        List<AgentEvent> events =
                kernel.chat("default", user("spawn a helper"), null).collectList().block();
        assertThat(events).isNotNull();
        String subId = events.stream()
                .filter(e -> e instanceof SubagentExposedEvent)
                .map(e -> ((SubagentExposedEvent) e).getSubagentId())
                .findFirst().orElse(null);
        assertThat(subId).as("kernel.chat surfaced an exposed subagent id").isNotBlank();

        // Route a follow-up directly to the exposed subagent via the façade.
        String childOut = text(kernel.chatWithSubagent(subId, user("CHILD_MSG")));
        assertThat(childOut).contains("CHILD_REPLY");
    }

    private static Msg user(String t) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(t).build()).build();
    }

    private static String text(Flux<AgentEvent> stream) {
        StringBuilder sb = new StringBuilder();
        List<AgentEvent> events = stream.collectList().block();
        if (events != null) {
            for (AgentEvent e : events) {
                if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) {
                    sb.append(d.getDelta());
                }
            }
        }
        return sb.toString();
    }

    /** Parent exposes a general-purpose child; the child replies on a direct switch. */
    static final class ExposeModel implements Model {
        @Override public String getModelName() { return "fake-expose"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            for (Msg m : messages) {
                if (m.getRole() == MsgRole.USER) {
                    for (ContentBlock b : m.getContent()) {
                        if (b instanceof TextBlock t && t.getText().contains("CHILD_MSG")) {
                            return Flux.just(txt("CHILD_REPLY"));
                        }
                    }
                }
            }
            for (Msg m : messages) {
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof ToolResultBlock) {
                        return Flux.just(txt("PARENT_DONE"));
                    }
                }
            }
            return Flux.just(ChatResponse.builder()
                    .content(List.of(ToolUseBlock.builder().id("c1").name("agent_spawn")
                            .input(Map.of("agent_id", "general-purpose", "expose_to_user", true))
                            .content("{\"agent_id\":\"general-purpose\",\"expose_to_user\":true}").build()))
                    .finishReason("tool_calls").build());
        }

        private static ChatResponse txt(String s) {
            return ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(s).build())).finishReason("stop").build();
        }
    }
}
