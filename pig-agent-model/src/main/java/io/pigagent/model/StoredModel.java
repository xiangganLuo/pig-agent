package io.pigagent.model;

import java.util.UUID;

/**
 * One saved model configuration: which provider, the API key, an optional custom base URL,
 * and the specific model name/version. Global (not bound to any session). Immutable.
 */
public record StoredModel(String id, String providerId, String apiKey, String baseUrl, String modelName) {

    public static StoredModel create(String providerId, String apiKey, String baseUrl, String modelName) {
        return new StoredModel(UUID.randomUUID().toString().substring(0, 8),
                providerId, apiKey, baseUrl, modelName);
    }

    public StoredModel withApiKey(String newApiKey) {
        return new StoredModel(id, providerId, newApiKey, baseUrl, modelName);
    }

    public StoredModel withBaseUrl(String newBaseUrl) {
        return new StoredModel(id, providerId, apiKey, newBaseUrl, modelName);
    }

    public StoredModel withModelName(String newModelName) {
        return new StoredModel(id, providerId, apiKey, baseUrl, newModelName);
    }

    /** Short label for listings, e.g. "openai / gpt-4o". */
    public String label() {
        return providerId + " / " + modelName;
    }
}
