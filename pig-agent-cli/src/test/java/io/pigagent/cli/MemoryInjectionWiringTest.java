package io.pigagent.cli;

import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.memory.injection.MemoryInjection;
import io.pigagent.core.memory.injection.PinnedSource;
import io.pigagent.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiring coverage for {@code AgentBootstrap.buildMemoryInjection} (capability
 * {@code memory-retrieval-injection}, task 5.3): default off yields {@code null} (whole-file injection,
 * unchanged), and enabled yields an injection with enabled settings + a working BM25-only retriever
 * (no embedder-model-id → no ModelManager needed).
 */
class MemoryInjectionWiringTest {

    @Test
    void disabled_returnsNull(@TempDir Path ws) {
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getRootPath()).thenReturn(ws);
        PigAgentConfig.InjectionConfig cfg = new PigAgentConfig.InjectionConfig(); // enabled=false

        assertThat(AgentBootstrap.buildMemoryInjection(cfg, workspace, null)).isNull();
    }

    @Test
    void enabled_buildsInjectionWithSettingsAndWorkingRetriever(@TempDir Path ws) {
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getRootPath()).thenReturn(ws);
        PigAgentConfig.InjectionConfig cfg = new PigAgentConfig.InjectionConfig();
        cfg.setEnabled(true);
        cfg.setTopK(9);
        cfg.getPinned().setSource("head");
        cfg.getPinned().setHeading("Identity");
        cfg.getPinned().setMaxChars(500);

        MemoryInjection inj = AgentBootstrap.buildMemoryInjection(cfg, workspace, null);

        assertThat(inj).isNotNull();
        assertThat(inj.settings().enabled()).isTrue();
        assertThat(inj.settings().topK()).isEqualTo(9);
        assertThat(inj.settings().pinnedSource()).isEqualTo(PinnedSource.HEAD);
        assertThat(inj.settings().pinnedHeading()).isEqualTo("Identity");
        assertThat(inj.settings().pinnedMaxChars()).isEqualTo(500);
        // BM25-only retriever over an empty corpus: returns a (possibly empty) list, never throws.
        assertThat(inj.retriever().retrieve("anything", 5)).isNotNull();
    }
}
