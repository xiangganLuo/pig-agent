package io.pigagent.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.memory.LongTermMemory;
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
import io.pigagent.core.memory.CompositeLongTermMemory;
import io.pigagent.core.memory.EphemeralMemoryMiddleware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase 5b — proves {@link PigAgent} now wraps a {@link HarnessAgent} <em>vehicle</em> that
 * carries pig's toolkit + middlewares unchanged (extending the throwaway HarnessAgent spike into a
 * production-wiring regression), and that per-session conversation state still persists + restores
 * through the wrap via pig's shared {@code AgentStateStore} (the arbiter that native session
 * persistence being disabled leaves pig's store as the single, working persistence mechanism).
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

    /** A spy long-term memory recording whether the ephemeral-memory middleware fired. */
    static final class SpyMemory implements LongTermMemory {
        final AtomicInteger retrieveCalls = new AtomicInteger();
        @Override public Mono<Void> record(List<Msg> messages) { return Mono.empty(); }
        @Override public Mono<String> retrieve(Msg query) {
            retrieveCalls.incrementAndGet();
            return Mono.just("MEMTOKEN");
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
                .longTermMemory(new CompositeLongTermMemory(new SpyMemory(), true))
                .workspace(ws)
                .build();

        HarnessAgent vehicle = agent.getHarnessAgent();
        assertThat(vehicle).as("PigAgent wraps a HarnessAgent vehicle").isNotNull();
        // getReactAgent() is exactly the vehicle's delegate (state ops stay consistent with turns).
        ReActAgent delegate = agent.getReactAgent();
        assertThat(delegate).isSameAs(vehicle.getDelegate());
        // Pig's custom toolkit tool is visible on the delegate.
        assertThat(delegate.getToolkit().getToolNames()).contains(TOOL_NAME);
        // Pig's ephemeral-memory middleware is wired into the delegate's chain.
        assertThat(delegate.getMiddlewares()).anyMatch(m -> m instanceof EphemeralMemoryMiddleware);
        agent.close();
    }

    @Test
    void streamRunsThroughVehicle_andEphemeralMemoryMiddlewareFires(@TempDir Path ws) {
        SpyMemory memory = new SpyMemory();
        PigAgent agent = PigAgent.builder()
                .name("wrap").sysPrompt("sp").model(new TextOnlyModel())
                .longTermMemory(new CompositeLongTermMemory(memory, true))
                .workspace(ws)
                .build();

        List<AgentEvent> events = agent.stream(user("hi")).collectList().block();

        String text = events == null ? "" : events.stream()
                .filter(e -> e instanceof TextBlockDeltaEvent)
                .map(e -> ((TextBlockDeltaEvent) e).getDelta())
                .reduce("", (a, b) -> a + b);
        assertThat(text).contains("ok from vehicle");
        assertThat(memory.retrieveCalls.get())
                .as("the ephemeral-memory middleware ran on the vehicle turn").isGreaterThanOrEqualTo(1);
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
