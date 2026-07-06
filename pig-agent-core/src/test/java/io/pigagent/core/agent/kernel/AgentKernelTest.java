package io.pigagent.core.agent.kernel;

import io.agentscope.core.model.Model;
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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentKernelTest {

    private AgentInstanceFactory factory() {
        return new AgentInstanceFactory(
                spec -> mock(Model.class), spec -> new Toolkit(), spec -> List.of(), null);
    }

    private AgentKernel kernel(Path dir) {
        AgentInstanceFactory factory = factory();
        AgentInstance def = factory.create(AgentSpec.create("default", "Default"));
        AgentRegistry registry = new AgentRegistry(new AgentHolder(def.agent()));
        registry.register(def);
        AgentSpecRepository repo = new AgentSpecRepository(dir);
        return new AgentKernel(registry, repo, factory, null);
    }

    @Test
    void createAgent_persists_registers_andEmitsEvent(@TempDir Path dir) {
        AgentKernel kernel = kernel(dir);
        List<KernelEvent> events = new CopyOnWriteArrayList<>();
        kernel.subscribeEvents().subscribe(events::add);

        kernel.createAgent(AgentSpec.create("b", "Beta"));

        assertThat(kernel.getAgent("b")).isPresent();
        assertThat(new AgentSpecRepository(dir).findById("b")).isPresent(); // persisted
        assertThat(events).extracting(KernelEvent::type).contains(KernelEvent.Type.AGENT_CREATED);
    }

    @Test
    void useAgent_switchesActive_andEmits(@TempDir Path dir) {
        AgentKernel kernel = kernel(dir);
        kernel.createAgent(AgentSpec.create("b", "Beta"));
        List<KernelEvent> events = new CopyOnWriteArrayList<>();
        kernel.subscribeEvents().subscribe(events::add);

        boolean ok = kernel.useAgent("b");

        assertThat(ok).isTrue();
        assertThat(kernel.activeId()).isEqualTo("b");
        assertThat(events).extracting(KernelEvent::type).contains(KernelEvent.Type.AGENT_SWITCHED);
    }

    @Test
    void useAgent_unknown_returnsFalse(@TempDir Path dir) {
        assertThat(kernel(dir).useAgent("nope")).isFalse();
    }

    @Test
    void listAgents_delegatesToRegistry(@TempDir Path dir) {
        AgentKernel kernel = kernel(dir);
        kernel.createAgent(AgentSpec.create("b", "Beta"));
        assertThat(kernel.listAgents()).extracting(AgentInstance::id).contains("default", "b");
    }

    @Test
    void noteChannelChat_isFacadeVisible_asChannelTaggedEvent(@TempDir Path dir) {
        AgentKernel kernel = kernel(dir);
        List<KernelEvent> events = new CopyOnWriteArrayList<>();
        kernel.subscribeEvents().subscribe(events::add);

        kernel.noteChannelChat("telegram");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(KernelEvent.Type.CHAT_STARTED);
            assertThat(e.agentId()).isEqualTo("channel:telegram"); // separate-track marker
        });
    }

    @Test
    void noSubscriber_emissionsDoNotError(@TempDir Path dir) {
        AgentKernel kernel = kernel(dir);
        // No subscriber; creating agents (which emits) must not throw.
        kernel.createAgent(AgentSpec.create("b", "Beta"));
        assertThat(kernel.getAgent("b")).isPresent();
    }
}
