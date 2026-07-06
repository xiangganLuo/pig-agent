package io.pigagent.core.agent;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentRegistryTest {

    private AgentInstance instance(String id) {
        PigAgent agent = PigAgent.builder()
                .name(id).sysPrompt("s").model(mock(Model.class)).build();
        return new AgentInstance(id, AgentSpec.create(id, id), agent);
    }

    @Test
    void firstRegistered_becomesActive_andHolderPointsToIt() {
        // Arrange
        AgentInstance a = instance("a");
        AgentHolder holder = new AgentHolder(a.agent());
        AgentRegistry reg = new AgentRegistry(holder);

        // Act
        reg.register(a);

        // Assert
        assertThat(reg.activeId()).isEqualTo("a");
        assertThat(holder.get()).isSameAs(a.agent());
    }

    @Test
    void setActive_switchesHolderView() {
        // Arrange
        AgentInstance a = instance("a");
        AgentInstance b = instance("b");
        AgentHolder holder = new AgentHolder(a.agent());
        AgentRegistry reg = new AgentRegistry(holder);
        reg.register(a);
        reg.register(b);

        // Act
        boolean ok = reg.setActive("b");

        // Assert
        assertThat(ok).isTrue();
        assertThat(reg.activeId()).isEqualTo("b");
        assertThat(holder.get()).isSameAs(b.agent());
    }

    @Test
    void setActive_unknownId_returnsFalse_andKeepsCurrent() {
        // Arrange
        AgentInstance a = instance("a");
        AgentHolder holder = new AgentHolder(a.agent());
        AgentRegistry reg = new AgentRegistry(holder);
        reg.register(a);

        // Act + Assert
        assertThat(reg.setActive("nope")).isFalse();
        assertThat(reg.activeId()).isEqualTo("a");
        assertThat(holder.get()).isSameAs(a.agent());
    }

    @Test
    void remove_active_fallsBackToRemainingAndUpdatesHolder() {
        // Arrange
        AgentInstance a = instance("a");
        AgentInstance b = instance("b");
        AgentHolder holder = new AgentHolder(a.agent());
        AgentRegistry reg = new AgentRegistry(holder);
        reg.register(a);
        reg.register(b);
        reg.setActive("a");

        // Act
        reg.remove("a");

        // Assert
        assertThat(reg.contains("a")).isFalse();
        assertThat(reg.activeId()).isEqualTo("b");
        assertThat(holder.get()).isSameAs(b.agent());
    }

    @Test
    void list_returnsAllRegisteredInOrder() {
        // Arrange
        AgentInstance a = instance("a");
        AgentInstance b = instance("b");
        AgentRegistry reg = new AgentRegistry(new AgentHolder(a.agent()));
        reg.register(a);
        reg.register(b);

        // Assert
        assertThat(reg.list()).extracting(AgentInstance::id).containsExactly("a", "b");
    }
}
