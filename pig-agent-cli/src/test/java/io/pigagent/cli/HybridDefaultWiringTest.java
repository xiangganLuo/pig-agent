package io.pigagent.cli;

import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.memory.search.DeterministicEmbedder;
import io.pigagent.core.memory.search.Embedder;
import io.pigagent.core.memory.search.MemorySearchIndex;
import io.pigagent.model.ModelManager;
import io.pigagent.model.ModelStore;
import io.pigagent.model.StoredModel;
import io.pigagent.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M-B derive + hybrid assembly (capability {@code hybrid-memory-search}, Group-1 spike, offline).
 * Proves: an embedder present ⇒ hybrid activated (register pig {@code memory_search},
 * {@code vectorEnabled()} true); no embedder ⇒ BM25-only (do not register); explicit
 * {@code hybrid-enabled} overrides the derive; and {@code resolveEmbedder} feeds the derive
 * (blank id → null → BM25-only; configured id → non-null embedder). Fully offline — the
 * embedder is a {@link DeterministicEmbedder}/constructed client, never a live network call.
 */
class HybridDefaultWiringTest {

    @Test
    void autoWithEmbedderActivatesHybrid(@TempDir Path ws) {
        // Arrange — auto (hybrid-enabled unset), an embedder resolved
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getRootPath()).thenReturn(ws);
        PigAgentConfig.SearchConfig cfg = new PigAgentConfig.SearchConfig();
        Embedder embedder = new DeterministicEmbedder();

        // Act + Assert — derive says register; the index runs hybrid (vector layer on)
        assertThat(cfg.resolveHybrid(embedder != null)).isTrue();
        MemorySearchIndex idx =
                AgentBootstrap.buildMemorySearchIndex(cfg, workspace, ws.resolve("USER.md"), embedder);
        assertThat(idx.vectorEnabled()).isTrue();
    }

    @Test
    void autoWithoutEmbedderStaysBm25Only(@TempDir Path ws) {
        // Arrange — auto, no embedder (the zero-regression case)
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getRootPath()).thenReturn(ws);
        PigAgentConfig.SearchConfig cfg = new PigAgentConfig.SearchConfig();
        Embedder embedder = null;

        // Act + Assert — derive says do NOT register (native keyword); index is BM25-only
        assertThat(cfg.resolveHybrid(embedder != null)).isFalse();
        MemorySearchIndex idx =
                AgentBootstrap.buildMemorySearchIndex(cfg, workspace, ws.resolve("USER.md"), embedder);
        assertThat(idx.vectorEnabled()).isFalse();
    }

    @Test
    void explicitFalseOverridesEvenWithEmbedder() {
        // Arrange — escape hatch: force native even though an embedder is configured
        PigAgentConfig.SearchConfig cfg = new PigAgentConfig.SearchConfig();
        cfg.setHybridEnabled(Boolean.FALSE);

        // Assert — override wins: hybrid not enabled → native memory tools retained
        assertThat(cfg.resolveHybrid(true)).isFalse();
    }

    @Test
    void explicitTrueWithoutEmbedderDegradesToBm25(@TempDir Path ws) {
        // Arrange — force hybrid on with no embedder
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getRootPath()).thenReturn(ws);
        PigAgentConfig.SearchConfig cfg = new PigAgentConfig.SearchConfig();
        cfg.setHybridEnabled(Boolean.TRUE);

        // Act + Assert — registered, but the index gracefully degrades to BM25-only
        assertThat(cfg.resolveHybrid(false)).isTrue();
        MemorySearchIndex idx =
                AgentBootstrap.buildMemorySearchIndex(cfg, workspace, ws.resolve("USER.md"), null);
        assertThat(idx.vectorEnabled()).isFalse();
    }

    @Test
    void resolveEmbedderBlankIdYieldsNull() {
        // Arrange — no embedder-model-id configured
        ModelManager mm = new ModelManager(null, inMemoryStore());

        // Act + Assert — null → the derive sees "no embedder" → BM25-only (zero regression)
        assertThat(AgentBootstrap.resolveEmbedder("", mm)).isNull();
    }

    @Test
    void resolveEmbedderConfiguredIdYieldsEmbedder() {
        // Arrange — a configured (embedding) model id
        ModelManager mm = new ModelManager(null, inMemoryStore());

        // Act + Assert — resolves an embedder (offline construction, no network) → derive activates hybrid
        assertThat(AgentBootstrap.resolveEmbedder("emb-1", mm)).isNotNull();
    }

    /** Minimal in-memory {@link ModelStore} holding one model {@code emb-1} as the default. */
    private static ModelStore inMemoryStore() {
        StoredModel m = new StoredModel("emb-1", "openai", "sk-x", "https://api.example.com/v1", "emb-model");
        return new ModelStore() {
            private String defaultId = "emb-1";
            @Override public List<StoredModel> findAll() { return List.of(m); }
            @Override public Optional<StoredModel> findById(String id) {
                return "emb-1".equals(id) ? Optional.of(m) : Optional.empty();
            }
            @Override public StoredModel save(StoredModel model) { return model; }
            @Override public void deleteById(String id) { }
            @Override public String getDefaultId() { return defaultId; }
            @Override public void setDefaultId(String id) { this.defaultId = id; }
        };
    }
}
