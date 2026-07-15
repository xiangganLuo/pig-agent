package io.pigagent.core.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentInstanceFactoryTest {

    @Test
    void create_buildsInstanceFromSpec() {
        // Arrange
        Model model = mock(Model.class);
        AgentInstanceFactory factory = new AgentInstanceFactory(
                spec -> model, spec -> new Toolkit(), spec -> List.of(), null);
        AgentSpec spec = AgentSpec.create("x", "X").withModelId("m1");

        // Act
        AgentInstance inst = factory.create(spec);

        // Assert
        assertThat(inst.id()).isEqualTo("x");
        assertThat(inst.spec()).isEqualTo(spec);
        assertThat(inst.agent()).isNotNull();
        assertThat(inst.agent().getModel()).isSameAs(model);
    }

    @Test
    void create_appliesSpecMaxIters() {
        // Arrange
        Model model = mock(Model.class);
        AgentInstanceFactory factory = new AgentInstanceFactory(
                spec -> model, spec -> new Toolkit(), spec -> List.of(), null);
        AgentSpec spec = AgentSpec.create("x", "X").withMaxIters(17);

        // Act
        AgentInstance inst = factory.create(spec);

        // Assert — the per-agent iteration cap comes from the spec
        assertThat(inst.agent().getReactAgent().getMaxIters()).isEqualTo(17);
    }

    @Test
    void create_resolvesModelPerAgent() {
        // Arrange — resolver returns a different model depending on the spec
        Model m1 = mock(Model.class);
        Model m2 = mock(Model.class);
        AgentInstanceFactory.ModelResolver resolver = spec -> "b".equals(spec.id()) ? m2 : m1;
        AgentInstanceFactory factory = new AgentInstanceFactory(
                resolver, spec -> new Toolkit(), spec -> List.of(), null);

        // Act + Assert
        assertThat(factory.create(AgentSpec.create("a", "A")).agent().getModel()).isSameAs(m1);
        assertThat(factory.create(AgentSpec.create("b", "B")).agent().getModel()).isSameAs(m2);
    }
}
