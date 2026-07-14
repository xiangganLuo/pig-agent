package io.pigagent.core.agent;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentFactoryTest {

    @Test
    void create_withMaxIters_appliesLimitToAgent() {
        // Arrange — the shared-config factory carrying an explicit iteration cap
        AgentFactory factory = new AgentFactory(
                "A", "sp", null, List.of(), null, null, null, 33);

        // Act
        PigAgent agent = factory.create(mock(Model.class));

        // Assert
        assertThat(agent.getReactAgent().getMaxIters()).isEqualTo(33);
    }

    @Test
    void create_legacyConstructor_keepsAgentScopeDefault() {
        // Arrange — the legacy constructor (no maxIters) must delegate with maxIters=0
        AgentFactory factory = new AgentFactory("A", "sp", null, List.of(), null);
        int def = PigAgent.builder().model(mock(Model.class)).build().getReactAgent().getMaxIters();

        // Act
        PigAgent agent = factory.create(mock(Model.class));

        // Assert
        assertThat(agent.getReactAgent().getMaxIters()).isEqualTo(def);
    }
}
