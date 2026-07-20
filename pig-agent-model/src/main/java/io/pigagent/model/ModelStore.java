package io.pigagent.model;

import java.util.List;
import java.util.Optional;

/** Persistence for the global set of model configurations and the default-model pointer. */
public interface ModelStore {

    List<StoredModel> findAll();

    Optional<StoredModel> findById(String id);

    StoredModel save(StoredModel model);

    void deleteById(String id);

    /** Id of the global default (chat) model, or {@code null} if none set. */
    String getDefaultId();

    void setDefaultId(String id);

    /**
     * Id of the default embedding model (capability {@code embedding-model-layer}), or {@code null}
     * when none is configured. Independent of the default chat model — the two pointers never
     * interfere. Default implementation returns {@code null} (no embedding model), so alternate
     * stores stay source-compatible.
     */
    default String getDefaultEmbeddingModelId() {
        return null;
    }

    /** Set (or clear, with {@code null}) the default embedding model pointer. */
    default void setDefaultEmbeddingModelId(String id) {
        // no-op default; file-backed stores persist it.
    }
}
