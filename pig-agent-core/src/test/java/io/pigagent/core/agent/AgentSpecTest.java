package io.pigagent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSpecTest {

    @Test
    void withModelId_returnsNewInstance_originalUnchanged() {
        // Arrange
        AgentSpec original = AgentSpec.create("a1", "Agent One");

        // Act
        AgentSpec updated = original.withModelId("m2");

        // Assert
        assertThat(updated.modelId()).isEqualTo("m2");
        assertThat(original.modelId()).isNull();
        assertThat(updated).isNotSameAs(original);
    }

    @Test
    void create_appliesDefaults() {
        // Act
        AgentSpec spec = AgentSpec.create("a1", "Agent One");

        // Assert
        assertThat(spec.toolNames()).isEmpty();
        assertThat(spec.usesAllTools()).isTrue();
        assertThat(spec.permissionMode()).isNull();
        assertThat(spec.modelId()).isNull();
        assertThat(spec.maxIters()).isEqualTo(AgentSpec.DEFAULT_MAX_ITERS);
    }

    @Test
    void constructor_normalizesNullsAndNonPositiveMaxIters() {
        // Act
        AgentSpec spec = new AgentSpec("a1", "n", null, null, null, null, 0);

        // Assert
        assertThat(spec.sysPrompt()).isEmpty();
        assertThat(spec.toolNames()).isEmpty();
        assertThat(spec.maxIters()).isEqualTo(AgentSpec.DEFAULT_MAX_ITERS);
    }

    @Test
    void toolNames_isImmutable() {
        // Arrange
        AgentSpec spec = AgentSpec.create("a1", "n").withToolNames(List.of("readFile"));

        // Assert
        assertThat(spec.usesAllTools()).isFalse();
        assertThatThrownBy(() -> spec.toolNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
