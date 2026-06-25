package io.pigagent.provider.openai;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ModelSpec;
import java.util.List;

public final class OpenAiProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "openai"; }
    @Override public String displayName() { return "OpenAI (GPT)"; }
    @Override public String description() { return "GPT series models by OpenAI"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("OPENAI_API_KEY"); }
    @Override public String defaultModelName() { return "gpt-4o"; }
    @Override public boolean supportsBaseUrl() { return true; }

    @Override
    public Model createModel(ModelSpec spec) {
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        var builder = OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(modelName);
        if (spec.hasBaseUrl()) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }
}
