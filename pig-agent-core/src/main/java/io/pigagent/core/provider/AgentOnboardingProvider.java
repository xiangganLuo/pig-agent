package io.pigagent.core.provider;

import io.agentscope.core.model.Model;

import java.util.List;

/**
 * Represents an LLM provider that can be discovered during onboarding.
 * Each provider knows how to create a Model instance from credentials.
 */
public interface AgentOnboardingProvider {

    /** Unique provider identifier, e.g. "anthropic", "openai", "ollama". */
    String providerId();

    /** Human-readable display name. */
    String displayName();

    /** Description shown during onboarding. */
    String description();

    /** Required credential keys, e.g. ["ANTHROPIC_API_KEY"]. */
    List<String> requiredCredentialKeys();

    /** Default model name for this provider. */
    String defaultModelName();

    /** Create a Model instance from the given credentials. */
    Model createModel(ProviderCredentials credentials);

    /** Check if this provider is available (credentials present). */
    default boolean isAvailable() {
        return requiredCredentialKeys().stream()
                .allMatch(key -> System.getenv(key) != null && !System.getenv(key).isBlank());
    }

    /** Build a model using environment variables as credentials. */
    default Model createModelFromEnv() {
        var creds = new ProviderCredentials();
        for (String key : requiredCredentialKeys()) {
            String value = System.getenv(key);
            if (value != null) {
                creds = creds.put(key, value);
            }
        }
        return createModel(creds);
    }
}
