package io.pigagent.core.provider;

/**
 * A fully-resolved description of one model configuration, used by an
 * {@link AgentOnboardingProvider} to build a concrete AgentScope {@code Model}.
 *
 * <p>Unlike {@link ProviderCredentials} (env-var oriented), this carries everything a
 * stored model config needs: the API key, an optional custom base URL / endpoint, and the
 * specific model name/version. {@code apiKey} may be {@code null}/blank for providers that
 * need none (e.g. Ollama); {@code baseUrl} may be {@code null}/blank to use the provider's
 * default endpoint.
 */
public record ModelSpec(String providerId, String apiKey, String baseUrl, String modelName) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
