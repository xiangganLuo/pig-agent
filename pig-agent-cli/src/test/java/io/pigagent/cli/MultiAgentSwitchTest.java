package io.pigagent.cli;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.ollama.OllamaProtocol;
import io.pigagent.provider.openai.OpenAiProtocol;
import io.pigagent.provider.registry.ProtocolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-1 acceptance (deterministic, no network): two agents bound to different stored models,
 * switched via {@link AgentRegistry}, each resolve to their own {@code Model} and the holder
 * (the active view) tracks the switch. This proves the wiring end-to-end through the real
 * {@link ModelManager} / {@link ProtocolRegistry} / {@link AgentInstanceFactory}; the live
 * "reply actually comes from model X" check is a real-model IT to run with API keys.
 */
class MultiAgentSwitchTest {

    @TempDir
    Path workspace;

    @Test
    void twoAgents_withDifferentModels_switchGivesEachItsOwnModel() {
        // Arrange — real registry + store + manager
        ProtocolRegistry protocols = new ProtocolRegistry();
        protocols.register(new OpenAiProtocol());
        protocols.register(new OllamaProtocol());

        JsonModelStore store = new JsonModelStore(workspace.resolve("models.json"));
        StoredModel openai = store.save(StoredModel.create("openai", "dummy-key", null, "gpt-4o"));
        StoredModel ollama = store.save(
                StoredModel.create("ollama", null, "http://localhost:11434", "llama3.2"));
        store.setDefaultId(openai.id());
        ModelManager modelManager = new ModelManager(protocols, store);

        AgentInstanceFactory factory = new AgentInstanceFactory(
                spec -> modelManager.modelFor(spec.modelId()),
                spec -> new Toolkit(),
                spec -> List.of(),
                null);

        AgentInstance a = factory.create(AgentSpec.create("a", "Alpha").withModelId(openai.id()));
        AgentInstance b = factory.create(AgentSpec.create("b", "Beta").withModelId(ollama.id()));

        AgentHolder holder = new AgentHolder(a.agent());
        AgentRegistry registry = new AgentRegistry(holder);
        registry.register(a);
        registry.register(b);

        // Act + Assert — switching active updates the holder to that agent's own model
        registry.setActive("a");
        assertThat(holder.get().getModel()).isSameAs(a.agent().getModel());

        registry.setActive("b");
        assertThat(holder.get().getModel()).isSameAs(b.agent().getModel());

        // Each agent was built with its own, distinct model
        assertThat(a.agent().getModel()).isNotSameAs(b.agent().getModel());
    }
}
