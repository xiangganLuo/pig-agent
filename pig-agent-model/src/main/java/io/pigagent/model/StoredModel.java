package io.pigagent.model;

import java.util.UUID;

/**
 * One saved model configuration: its {@link ModelKind category}, which protocol, the API key, an
 * optional custom base URL, and the specific model name/version. Global (not bound to any session).
 * Immutable.
 *
 * <p>The {@code kind} discriminator (capability {@code embedding-model-layer}) makes an embedding model
 * a first-class stored category alongside chat models. The 5-arg convenience constructor and
 * {@link #create(String, String, String, String)} default to {@link ModelKind#CHAT}, so existing
 * {@code models.json} entries and call sites are byte-compatible.
 */
public record StoredModel(String id, String protocolId, String apiKey, String baseUrl,
                          String modelName, ModelKind kind) {

    /** Normalize a {@code null} kind to {@link ModelKind#CHAT} (e.g. an old JSON entry with no field). */
    public StoredModel {
        if (kind == null) {
            kind = ModelKind.CHAT;
        }
    }

    /** Backward-compatible 5-arg constructor: a chat model (kind = {@link ModelKind#CHAT}). */
    public StoredModel(String id, String protocolId, String apiKey, String baseUrl, String modelName) {
        this(id, protocolId, apiKey, baseUrl, modelName, ModelKind.CHAT);
    }

    /** Create a chat model with a fresh id. */
    public static StoredModel create(String protocolId, String apiKey, String baseUrl, String modelName) {
        return create(protocolId, apiKey, baseUrl, modelName, ModelKind.CHAT);
    }

    /** Create a model of the given kind with a fresh id. */
    public static StoredModel create(String protocolId, String apiKey, String baseUrl,
                                     String modelName, ModelKind kind) {
        return new StoredModel(UUID.randomUUID().toString().substring(0, 8),
                protocolId, apiKey, baseUrl, modelName, kind);
    }

    public StoredModel withApiKey(String newApiKey) {
        return new StoredModel(id, protocolId, newApiKey, baseUrl, modelName, kind);
    }

    public StoredModel withBaseUrl(String newBaseUrl) {
        return new StoredModel(id, protocolId, apiKey, newBaseUrl, modelName, kind);
    }

    public StoredModel withModelName(String newModelName) {
        return new StoredModel(id, protocolId, apiKey, baseUrl, newModelName, kind);
    }

    public StoredModel withKind(ModelKind newKind) {
        return new StoredModel(id, protocolId, apiKey, baseUrl, modelName, newKind);
    }

    /** True when this configuration is an embedding model. */
    public boolean isEmbedding() {
        return kind == ModelKind.EMBEDDING;
    }

    /** Short label for listings, e.g. "openai / gpt-4o". */
    public String label() {
        return protocolId + " / " + modelName;
    }
}
