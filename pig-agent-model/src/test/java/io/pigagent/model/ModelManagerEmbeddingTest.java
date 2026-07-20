package io.pigagent.model;

import io.pigagent.core.memory.search.Embedder;
import io.pigagent.provider.registry.ProtocolRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Offline coverage for the {@code /embeddings} connectivity test dispatch (capability
 * {@code embedding-model-layer}): an embedding {@link StoredModel} is probed through the injected
 * embedder seam (no network), success/failure map through the same bounded + credential-redacted
 * {@link ModelManager#runProbe} as the chat probe. The live {@code /embeddings} round-trip is a
 * {@code /ls:itest} concern, not here.
 */
class ModelManagerEmbeddingTest {

    private final ProtocolRegistry registry = new ProtocolRegistry();

    private ModelManager manager() {
        return new ModelManager(registry, mock(ModelStore.class));
    }

    private static StoredModel embeddingModel() {
        return StoredModel.create("openai", "sk-x", "https://api.x/v1", "text-embedding-3-small",
                ModelKind.EMBEDDING);
    }

    @Test
    void embeddingModel_success_mapsToSuccess() {
        // Arrange — a fake embedder returning a vector (no network)
        ModelManager mm = manager();
        mm.setEmbedderFactory(m -> (Embedder) text -> new float[]{0.1f, 0.2f});

        // Act
        ModelManager.TestResult r = mm.test(embeddingModel());

        // Assert
        assertThat(r.ok()).isTrue();
        assertThat(r.error()).isNull();
    }

    @Test
    void embeddingModel_failure_mapsToFriendlyRedactedReason() {
        // Arrange — the embedder throws with a credential-looking token in the message
        ModelManager mm = manager();
        mm.setEmbedderFactory(m -> (Embedder) text -> {
            throw new IllegalStateException("boom sk-ABCDEF1234567890abcdef");
        });

        // Act
        ModelManager.TestResult r = mm.test(embeddingModel());

        // Assert — not ok, and the raw secret never surfaces in the reason
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).doesNotContain("sk-ABCDEF1234567890abcdef");
    }

    @Test
    void embeddingModel_dispatchesToEmbedderFactory() {
        // Arrange — record whether the embedding path (factory) was taken
        AtomicBoolean factoryUsed = new AtomicBoolean(false);
        ModelManager mm = manager();
        mm.setEmbedderFactory(m -> {
            factoryUsed.set(true);
            return (Embedder) text -> new float[]{1f};
        });

        // Act
        mm.test(embeddingModel());

        // Assert — an embedding model is probed via the embedder seam, not the chat model builder
        assertThat(factoryUsed).isTrue();
    }

    @Test
    void embedderFactoryThrows_mapsToFailure_notException() {
        // Arrange — building the embedder itself fails
        ModelManager mm = manager();
        mm.setEmbedderFactory(m -> {
            throw new IllegalArgumentException("bad config");
        });

        // Act
        ModelManager.TestResult r = mm.test(embeddingModel());

        // Assert
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isNotBlank();
    }
}
