package io.pigagent.provider.anthropic;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.AnthropicChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ModelSpec;
import java.util.List;

public final class AnthropicProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "anthropic"; }
    @Override public String displayName() { return "Anthropic (Claude)"; }
    @Override public String description() { return "Claude series models by Anthropic"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("ANTHROPIC_API_KEY"); }
    @Override public String defaultModelName() { return "claude-sonnet-4-6"; }
    @Override public boolean supportsBaseUrl() { return true; }

    @Override
    public Model createModel(ModelSpec spec) {
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        var builder = AnthropicChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(modelName);
        if (spec.hasBaseUrl()) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }
}
