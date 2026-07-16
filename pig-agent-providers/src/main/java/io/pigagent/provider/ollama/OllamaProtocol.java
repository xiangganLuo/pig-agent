package io.pigagent.provider.ollama;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;

/** Local Ollama protocol (self-hosted, no API key). */
public final class OllamaProtocol implements ModelProtocol {
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    @Override public String protocolId() { return "ollama"; }
    @Override public String displayName() { return "Ollama (Local)"; }
    @Override public String description() { return "Local self-hosted models via Ollama"; }
    @Override public String defaultModelName() { return "llama3.2"; }
    @Override public boolean requiresApiKey() { return false; }
    @Override public boolean supportsBaseUrl() { return true; }

    @Override
    public Model createModel(ModelSpec spec) {
        String baseUrl = spec.hasBaseUrl() ? spec.baseUrl() : DEFAULT_BASE_URL;
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        return OllamaChatModel.builder().baseUrl(baseUrl).modelName(modelName).build();
    }
}
