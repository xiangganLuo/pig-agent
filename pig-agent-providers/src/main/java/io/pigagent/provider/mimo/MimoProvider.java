package io.pigagent.provider.mimo;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ModelSpec;

import java.util.List;

public final class MimoProvider implements AgentOnboardingProvider {
    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1/chat/completions";

    @Override public String providerId() { return "mimo"; }
    @Override public String displayName() { return "mimo"; }
    @Override public String description() { return "mimo series models by xiaomi"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("MIMO_API_KEY"); }
    @Override public String defaultModelName() { return "mimo-v2.5-pro"; }
    @Override public boolean supportsBaseUrl() { return true; }

    @Override
    public Model createModel(ModelSpec spec) {
        String baseUrl = spec.hasBaseUrl() ? spec.baseUrl() : DEFAULT_BASE_URL;
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        return OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }
}
