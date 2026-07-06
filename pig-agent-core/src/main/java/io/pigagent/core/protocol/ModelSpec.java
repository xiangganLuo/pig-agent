package io.pigagent.core.protocol;

/**
 * A fully-resolved description of one model configuration, used by a {@link ModelProtocol}
 * to build a concrete AgentScope {@code Model}.
 *
 * <p>Carries everything a stored model config needs: the protocol id, the API key, an optional
 * custom base URL / endpoint, and the specific model name/version. {@code apiKey} may be
 * {@code null}/blank for protocols that need none (e.g. Ollama); {@code baseUrl} may be
 * {@code null}/blank to use the protocol's default endpoint.
 */
public record ModelSpec(String protocolId, String apiKey, String baseUrl, String modelName) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
