package io.pigagent.core.agent.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.AgentSpecRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the read-only tool-inventory façade ({@code tools-observability}, T3): {@link
 * AgentKernel#listTools()} returns what the injected provider yields, and defaults to an empty list
 * when no provider is wired (so a kernel built without observability wiring is unaffected).
 */
class AgentKernelToolInventoryTest {

    private AgentKernel kernel(Path dir) {
        AgentInstanceFactory factory = new AgentInstanceFactory(
                spec -> mock(Model.class), spec -> new Toolkit(), spec -> List.of(), null);
        AgentInstance def = factory.create(AgentSpec.create("default", "Default"));
        AgentRegistry registry = new AgentRegistry(new AgentHolder(def.agent()));
        registry.register(def);
        return new AgentKernel(registry, new AgentSpecRepository(dir), factory, null);
    }

    @Test
    void listTools_withoutProvider_isEmpty(@TempDir Path dir) {
        assertThat(kernel(dir).listTools()).isEmpty();
    }

    @Test
    void listTools_returnsProviderEntries(@TempDir Path dir) {
        // Arrange
        AgentKernel kernel = kernel(dir);
        ToolInventoryEntry entry = new ToolInventoryEntry(
                "readFile", "READ_ONLY", true, "", ToolInventoryEntry.DEFERRAL_NONE, 3, 42, 0.0);
        kernel.setToolInventoryProvider(() -> List.of(entry));

        // Act
        List<ToolInventoryEntry> inventory = kernel.listTools();

        // Assert
        assertThat(inventory).hasSize(1);
        ToolInventoryEntry got = inventory.get(0);
        assertThat(got.name()).isEqualTo("readFile");
        assertThat(got.risk()).isEqualTo("READ_ONLY");
        assertThat(got.available()).isTrue();
        assertThat(got.calls()).isEqualTo(3);
        assertThat(got.avgLatencyMillis()).isEqualTo(42);
    }

    @Test
    void listTools_providerReturningNull_degradesToEmpty(@TempDir Path dir) {
        AgentKernel kernel = kernel(dir);
        kernel.setToolInventoryProvider(() -> null);
        assertThat(kernel.listTools()).isEmpty();
    }
}
