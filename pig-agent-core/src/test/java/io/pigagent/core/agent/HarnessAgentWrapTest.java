package io.pigagent.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.pigagent.core.memory.NativeMemoryContextMiddleware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase 5b + pa-memory-native — proves {@link PigAgent} wraps a {@link HarnessAgent} <em>vehicle</em>
 * that carries pig's toolkit + middlewares unchanged, that native long-term memory ({@code .memory(...)})
 * wires the pig {@link NativeMemoryContextMiddleware} into the delegate chain, and that per-session
 * conversation state persists + restores through the wrap via pig's shared {@code AgentStateStore}.
 */
class HarnessAgentWrapTest {

    private static final String TOOL_NAME = "wrapEcho";

    /** Fake model: emits a single assistant text block (text-only turn, no tool call). */
    static final class TextOnlyModel implements Model {
        @Override public String getModelName() { return "fake-text"; }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("ok from vehicle").build()))
                    .finishReason("stop").build());
        }
    }

    /** A pig-style custom tool (no params → no reflective arg-binding concern). */
    static final class WrapTools {
        @Tool(name = TOOL_NAME, description = "Echo a fixed token (wrap test).")
        public String wrapEcho() {
            return "echoed";
        }
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    @Test
    void wrapExposesHarnessVehicleWhoseDelegateCarriesPigToolkitAndMiddlewares(@TempDir Path ws) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WrapTools());

        PigAgent agent = PigAgent.builder()
                .name("wrap").sysPrompt("sp").model(new TextOnlyModel())
                .toolkit(toolkit)
                .memory(MemoryConfig.defaults())
                .workspace(ws)
                .build();

        HarnessAgent vehicle = agent.getHarnessAgent();
        assertThat(vehicle).as("PigAgent wraps a HarnessAgent vehicle").isNotNull();
        // getReactAgent() is exactly the vehicle's delegate (state ops stay consistent with turns).
        ReActAgent delegate = agent.getReactAgent();
        assertThat(delegate).isSameAs(vehicle.getDelegate());
        // Pig's custom toolkit tool is visible on the delegate.
        assertThat(delegate.getToolkit().getToolNames()).contains(TOOL_NAME);
        // pa-memory-native: pig's MEMORY.md system-prompt injector is wired into the delegate's chain.
        assertThat(delegate.getMiddlewares()).anyMatch(m -> m instanceof NativeMemoryContextMiddleware);
        agent.close();
    }

    @Test
    void streamRunsThroughVehicle_withNativeMemoryEnabled(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("wrap").sysPrompt("sp").model(new TextOnlyModel())
                .memory(MemoryConfig.defaults())
                .workspace(ws)
                .build();

        List<AgentEvent> events = agent.stream(user("hi")).collectList().block();

        String text = events == null ? "" : events.stream()
                .filter(e -> e instanceof TextBlockDeltaEvent)
                .map(e -> ((TextBlockDeltaEvent) e).getDelta())
                .reduce("", (a, b) -> a + b);
        assertThat(text).contains("ok from vehicle");
        agent.close();
    }

    @Test
    void memoryDisabledByDefault_noNativeMemoryMiddleware(@TempDir Path ws) {
        PigAgent agent = PigAgent.builder()
                .name("wrap").sysPrompt("sp").model(new TextOnlyModel())
                .workspace(ws)
                .build();

        assertThat(agent.getReactAgent().getMiddlewares())
                .as("no memory config → no MEMORY.md injector (byte-for-byte pre-feature behaviour)")
                .noneMatch(m -> m instanceof NativeMemoryContextMiddleware);
        agent.close();
    }

    @Test
    void perSessionStatePersistsAndRestoresThroughTheWrap(@TempDir Path root) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(root.resolve("state"));

        // First agent: run a turn bound to session "s1" (native store auto-saves the (pig,s1) slot).
        PigAgent agent1 = PigAgent.builder()
                .name("wrap").sysPrompt("sp").model(new TextOnlyModel())
                .stateStore(store).workspace(root)
                .build();
        agent1.stream(user("remember-alpha"), "s1").blockLast();
        agent1.saveTo("s1");
        agent1.close();

        // A freshly-built agent sharing the SAME store restores that session's conversation — proving
        // pig's AgentStateStore persistence survives the HarnessAgent wrap (session-log disabled).
        PigAgent agent2 = PigAgent.builder()
                .name("wrap").sysPrompt("sp").model(new TextOnlyModel())
                .stateStore(store).workspace(root)
                .build();

        assertThat(agent2.loadIfExists("s1")).as("the (pig, s1) slot exists in the shared store").isTrue();
        List<Msg> restored = new ArrayList<>(agent2.getMemory("s1").getMessages());
        assertThat(restored).isNotEmpty();
        boolean carriesUserTurn = restored.stream().anyMatch(m ->
                m.getRole() == MsgRole.USER && m.getTextContent() != null
                        && m.getTextContent().contains("remember-alpha"));
        assertThat(carriesUserTurn).as("the prior turn is restored through the wrap").isTrue();
        agent2.close();
    }
}
