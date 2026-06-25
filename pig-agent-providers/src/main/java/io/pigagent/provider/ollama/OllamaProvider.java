package io.pigagent.provider.ollama;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ModelSpec;
import java.util.List;

public final class OllamaProvider implements AgentOnboardingProvider {
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    @Override public String providerId() { return "ollama"; }
    @Override public String displayName() { return "Ollama (Local)"; }
    @Override public String description() { return "Local self-hosted models via Ollama"; }
    @Override public List<String> requiredCredentialKeys() { return List.of(); }
    @Override public String defaultModelName() { return "llama3.2"; }
    @Override public boolean isAvailable() { return true; }
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
