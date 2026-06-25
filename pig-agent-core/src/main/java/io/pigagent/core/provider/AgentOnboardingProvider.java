package io.pigagent.core.provider;

import io.agentscope.core.model.Model;

import java.util.List;

/**
 * Represents an LLM provider that can be discovered during onboarding.
 * Each provider knows how to build an AgentScope {@code Model} from a {@link ModelSpec}
 * (api key + optional base url + model name).
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

    /** Build a Model from a fully-resolved spec (api key, optional base url, model name). */
    Model createModel(ModelSpec spec);

    /** Whether a custom base URL / endpoint is meaningful for this provider. */
    default boolean supportsBaseUrl() {
        return false;
    }

    /** Whether this provider needs an API key (false for local providers like Ollama). */
    default boolean requiresApiKey() {
        return !requiredCredentialKeys().isEmpty();
    }

    /** Check if this provider is available via environment variables (legacy/fallback). */
    default boolean isAvailable() {
        return requiredCredentialKeys().stream()
                .allMatch(key -> System.getenv(key) != null && !System.getenv(key).isBlank());
    }

    /** Build a model using environment variables as the api key (legacy/fallback). */
    default Model createModelFromEnv() {
        String apiKey = requiredCredentialKeys().stream()
                .map(System::getenv)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
        return createModel(new ModelSpec(providerId(), apiKey, null, defaultModelName()));
    }
}
