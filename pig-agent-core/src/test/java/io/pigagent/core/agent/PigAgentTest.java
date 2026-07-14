package io.pigagent.core.agent;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PigAgentTest {

    @Test
    void maxIters_positive_isAppliedToReActAgent() {
        // Arrange — pick a limit distinct from AgentScope's own default
        int limit = defaultMaxIters() + 7;

        // Act
        PigAgent agent = PigAgent.builder().model(mock(Model.class)).maxIters(limit).build();

        // Assert
        assertThat(agent.getReactAgent().getMaxIters()).isEqualTo(limit);
    }

    @Test
    void maxIters_nonPositive_leavesAgentScopeDefault() {
        // Arrange
        int def = defaultMaxIters();

        // Act
        PigAgent zero = PigAgent.builder().model(mock(Model.class)).maxIters(0).build();
        PigAgent negative = PigAgent.builder().model(mock(Model.class)).maxIters(-5).build();

        // Assert — a non-positive value must not override the underlying default
        assertThat(zero.getReactAgent().getMaxIters()).isEqualTo(def);
        assertThat(negative.getReactAgent().getMaxIters()).isEqualTo(def);
    }

    /** The maxIters an unset builder yields — the AgentScope default we must not override at {@code <= 0}. */
    private static int defaultMaxIters() {
        return PigAgent.builder().model(mock(Model.class)).build().getReactAgent().getMaxIters();
    }
}
