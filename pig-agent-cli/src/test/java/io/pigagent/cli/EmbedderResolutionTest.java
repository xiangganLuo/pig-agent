package io.pigagent.cli;

import io.pigagent.core.memory.search.Embedder;
import io.pigagent.core.memory.search.OpenAiCompatibleEmbedder;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelKind;
import io.pigagent.model.ModelManager;
import io.pigagent.model.ModelStore;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.registry.ProtocolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code AgentBootstrap.resolveEmbedder} (capability {@code embedding-model-layer}, task 5):
 * resolution order = explicit config id → store default embedding model → {@code null} (BM25-only). The
 * kind is not required to be EMBEDDING (R5 backward-compat), and every unresolvable path degrades to
 * {@code null} rather than throwing. Building the embedder makes no network call, so no live round-trip.
 */
class EmbedderResolutionTest {

    private ModelManager manager(Path dir) {
        ModelStore store = new JsonModelStore(dir.resolve("models.json"));
        return new ModelManager(new ProtocolRegistry(), store);
    }

    @Test
    void nullModelManager_returnsNull() {
        assertThat(AgentBootstrap.resolveEmbedder("anything", null)).isNull();
    }

    @Test
    void blankConfigId_noStoreDefault_returnsNull_bm25Only(@TempDir Path dir) {
        // Arrange — a chat model only, no default embedding pointer (today's default state)
        ModelManager mm = manager(dir);
        mm.add(StoredModel.create("openai", "k", null, "gpt-4o"));

        // Act + Assert — zero regression: blank id + no embedding model → BM25-only
        assertThat(AgentBootstrap.resolveEmbedder("", mm)).isNull();
        assertThat(AgentBootstrap.resolveEmbedder(null, mm)).isNull();
    }

    @Test
    void blankConfigId_fallsBackToStoreDefaultEmbedding(@TempDir Path dir) {
        // Arrange
        ModelManager mm = manager(dir);
        StoredModel emb = mm.add(StoredModel.create("openai", "k", "https://api.x/v1",
                "text-embedding-3-small", ModelKind.EMBEDDING));
        mm.setDefaultEmbeddingModelId(emb.id());

        // Act
        Embedder embedder = AgentBootstrap.resolveEmbedder(null, mm);

        // Assert
        assertThat(embedder).isInstanceOf(OpenAiCompatibleEmbedder.class);
    }

    @Test
    void explicitConfigId_resolvesEmbeddingModel(@TempDir Path dir) {
        ModelManager mm = manager(dir);
        StoredModel emb = mm.add(StoredModel.create("openai", "k", "https://api.x/v1", "emb",
                ModelKind.EMBEDDING));

        assertThat(AgentBootstrap.resolveEmbedder(emb.id(), mm))
                .isInstanceOf(OpenAiCompatibleEmbedder.class);
    }

    @Test
    void explicitConfigId_pointingAtChatModel_stillResolves(@TempDir Path dir) {
        // R5 backward-compat: a legacy embedder-model-id may point at a chat model — kind not required
        ModelManager mm = manager(dir);
        StoredModel chat = mm.add(StoredModel.create("openai", "k", "https://api.x/v1", "gpt-4o"));

        assertThat(AgentBootstrap.resolveEmbedder(chat.id(), mm))
                .isInstanceOf(OpenAiCompatibleEmbedder.class);
    }

    @Test
    void danglingConfigId_returnsNull(@TempDir Path dir) {
        ModelManager mm = manager(dir);
        mm.add(StoredModel.create("openai", "k", null, "gpt-4o"));

        // an explicit id that resolves to nothing → BM25-only (no silent fallback to the default chat model)
        assertThat(AgentBootstrap.resolveEmbedder("does-not-exist", mm)).isNull();
    }
}
