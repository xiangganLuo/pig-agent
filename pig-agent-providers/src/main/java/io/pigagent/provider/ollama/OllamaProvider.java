package io.pigagent.provider.ollama;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ProviderCredentials;
import java.util.List;

public final class OllamaProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "ollama"; }
    @Override public String displayName() { return "Ollama (Local)"; }
    @Override public String description() { return "Local self-hosted models via Ollama"; }
    @Override public List<String> requiredCredentialKeys() { return List.of(); }
    @Override public String defaultModelName() { return "llama3.2"; }
    @Override public boolean isAvailable() { return true; }

    @Override
    public Model createModel(ProviderCredentials credentials) {
        String baseUrl = credentials.get("OLLAMA_BASE_URL").orElse("http://localhost:11434");
        return OllamaChatModel.builder().baseUrl(baseUrl).modelName(defaultModelName()).build();
    }
}
